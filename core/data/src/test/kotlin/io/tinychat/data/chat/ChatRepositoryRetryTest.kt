package io.tinychat.data.chat

import androidx.test.core.app.ApplicationProvider
import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.AttachmentUploader
import io.tinychat.data.db.DatabaseFactory
import io.tinychat.data.db.MessageEntity
import io.tinychat.data.db.TinyChatDatabase
import io.tinychat.data.db.toEntity
import io.tinychat.data.provider.ProviderStore
import io.tinychat.model.Fragment
import io.tinychat.model.KnownProviders
import io.tinychat.model.MessageStatus
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.net.SseEvent
import io.tinychat.net.protocol.PreparedRequest
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 断网失败后的重试（决策 84）。
 *
 * 1.1.1 之前重试是「新挂一个兄弟」：库里变成 `[空失败, 成功]`，界面显示 `‹ 2/2 ›`，
 * 而空的那个渲染不出来——切过去是一片空白、切不回来，指针还卡在隐形节点上。
 * 现在空失败回答被下一次生成**接管**，不占版本位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryRetryTest {

    private lateinit var db: TinyChatDatabase
    private lateinit var providerStore: ProviderStore
    private lateinit var attachmentStore: AttachmentStore

    /** 同一个假传输既能离线失败也能在线成功，由测试翻 [online] */
    private var online = false

    private class OkTransport : ChatTransport {
        var calls = 0
            private set

        override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
            calls++
            emit(SseEvent(null, """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":null}]}"""))
            emit(SseEvent(null, """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""))
            emit(SseEvent(null, "[DONE]"))
        }
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = DatabaseFactory.createInMemory(context)
        attachmentStore = AttachmentStore(context)
        providerStore = ProviderStore.create(db)
        providerStore.ensureSeeded()
        providerStore.save(
            KnownProviders.genericOpenAiCompatible(
                id = "acme",
                displayName = "Acme",
                baseUrl = "https://acme.example/v1",
                modelId = "m1",
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun repository(transport: ChatTransport) = ChatRepository(
        db = db,
        transport = transport,
        clock = { 1_700_000_000_000L },
        isOnline = { online },
        attachmentStore = attachmentStore,
        uploader = AttachmentUploader(),
        providerStore = providerStore,
        apiKeyFor = { "k" },
    )

    private fun newSession(repository: ChatRepository) = runBlocking {
        repository.createSession(providerId = "acme", model = "m1", thinkingEnabled = false, effort = null)
    }

    private fun assistants(sessionId: String) = runBlocking {
        db.messageDao().withFragments(sessionId).filter { it.message.role == Role.ASSISTANT }
    }

    @Test
    fun `断网失败的重试复用同一条回答，不新增版本`() = runBlocking {
        val transport = OkTransport()
        val repository = repository(transport)
        val sessionId = newSession(repository)

        val first = repository.send(sessionId, "断网时发一句")
        assertEquals(MessageStatus.FAILED, first.status)
        assertEquals("离线预检不该发请求", 0, transport.calls)
        assertEquals("失败那轮只有提问 + 一条空回答", 2, db.messageDao().withFragments(sessionId).size)

        online = true
        val retried = repository.regenerate(sessionId, first.messageId)

        assertEquals(MessageStatus.FINISHED, retried.status)
        assertEquals("重试要接管失败那条，而不是再挂一个兄弟", first.messageId, retried.messageId)

        val rows = db.messageDao().withFragments(sessionId)
        assertEquals("库里仍然只有一轮问答，版本数是 1", 2, rows.size)
        val assistant = rows.single { it.message.role == Role.ASSISTANT }
        assertEquals(first.messageId, assistant.message.id)
        assertEquals(MessageStatus.FINISHED, assistant.message.status)
        assertNull("接管后失败原因要清掉，否则界面上还挂着橙色重试钮", assistant.message.errorKind)
        assertTrue("新的正文要落在这条上", assistant.orderedFragments.isNotEmpty())
        assertEquals(
            "分支指针也落回这条",
            first.messageId,
            db.sessionDao().byId(sessionId)!!.currentMessageId,
        )
    }

    @Test
    fun `断网重试再次失败也不会攒出版本`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)

        val first = repository.send(sessionId, "断网时发一句")
        val again = repository.regenerate(sessionId, first.messageId)
        val third = repository.regenerate(sessionId, again.messageId)

        assertEquals(MessageStatus.FAILED, third.status)
        assertEquals(first.messageId, again.messageId)
        assertEquals(first.messageId, third.messageId)
        assertEquals(1, assistants(sessionId).size)
    }

    @Test
    fun `想过一段再断的回答算真版本，重试不覆盖`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)

        val first = repository.send(sessionId, "断网时发一句")
        // 造一条「吐了一半再断」的失败回答：有正文碎片，不是空壳
        db.fragmentDao().insert(
            Fragment(messageId = first.messageId, ord = 0, payload = ResponsePayload("说到一半")).toEntity(),
        )

        online = true
        val retried = repository.regenerate(sessionId, first.messageId)

        assertTrue("有内容的失败版本要保留，重试另起一条", retried.messageId != first.messageId)
        assertEquals(2, assistants(sessionId).size)
        assertEquals(
            "旧版本还能切回去看那半截",
            "说到一半",
            db.fragmentDao().byMessage(first.messageId)
                .mapNotNull { io.tinychat.model.FragmentPayloadCodec.decode(it.payload) as? ResponsePayload }
                .joinToString("") { it.content },
        )
    }

    @Test
    fun `进程被杀留下的空 INCOMPLETE 也一样被接管`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)

        val first = repository.send(sessionId, "断网时发一句")
        // 模拟启动自愈：进程被杀时这条还是 WIP，重启后归为 INCOMPLETE
        db.messageDao().setStatus(first.messageId, MessageStatus.INCOMPLETE)

        online = true
        val retried = repository.regenerate(sessionId, first.messageId)

        assertEquals(MessageStatus.FINISHED, retried.status)
        assertEquals(first.messageId, retried.messageId)
        assertEquals(1, assistants(sessionId).size)
    }

    @Test
    fun `拒答不是空壳：重试另起一版，也不被自愈清掉`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)

        val first = repository.send(sessionId, "问一句")
        // 模拟拒答：落成 CONTENT_FILTER，零碎片（模型什么都没说）
        db.messageDao().setStatus(first.messageId, MessageStatus.CONTENT_FILTER)
        db.messageDao().setError(first.messageId, null, null)

        online = true
        val next = repository.regenerate(sessionId, first.messageId)

        assertTrue("拒答是结果不是空壳，重发要另起一版", next.messageId != first.messageId)
        assertEquals(2, assistants(sessionId).size)
        assertEquals(
            "拒答那一版要能切回去看，不能被自愈删掉",
            0,
            repository.healEmptyVersions(),
        )
        assertNotNull(db.messageDao().byId(first.messageId))
    }

    @Test
    fun `启动自愈清掉有真回答的空失败版本，并把指针挪出来`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)
        val failed = repository.send(sessionId, "断网时发一句")
        val userId = db.messageDao().withFragments(sessionId)
            .single { it.message.role == Role.USER }.message.id

        // 复刻 1.1.1 之前攒出来的脏数据：[空失败, 成功]，指针卡在空的那个上
        val finishedId = db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                parentId = userId,
                role = Role.ASSISTANT,
                status = MessageStatus.FINISHED,
                insertedAt = 2,
                model = "m1",
                providerId = "acme",
            ),
        )
        db.fragmentDao().insert(
            Fragment(messageId = finishedId, ord = 0, payload = ResponsePayload("真正的回答")).toEntity(),
        )
        db.sessionDao().setCurrentMessage(sessionId, failed.messageId, 3)

        assertEquals(1, repository.healEmptyVersions())

        assertNull("空壳要被清掉", db.messageDao().byId(failed.messageId))
        assertNotNull(db.messageDao().byId(finishedId))
        assertEquals(
            "指针不能留在被删掉的隐形节点上",
            finishedId,
            db.sessionDao().byId(sessionId)!!.currentMessageId,
        )
    }

    @Test
    fun `唯一的孩子是空失败回答时自愈不删`() = runBlocking {
        val repository = repository(OkTransport())
        val sessionId = newSession(repository)
        val failed = repository.send(sessionId, "断网时发一句")

        assertEquals("那是占位，删了就没法重试了", 0, repository.healEmptyVersions())
        assertNotNull(db.messageDao().byId(failed.messageId))
        assertEquals(failed.messageId, db.sessionDao().byId(sessionId)!!.currentMessageId)
    }
}
