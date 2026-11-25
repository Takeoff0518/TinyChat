package io.tinychat.data.chat

import androidx.test.core.app.ApplicationProvider
import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.AttachmentUploader
import io.tinychat.data.db.DatabaseFactory
import io.tinychat.data.db.TinyChatDatabase
import io.tinychat.data.prompt.PromptSnapshot
import io.tinychat.data.provider.ProviderStore
import io.tinychat.model.PromptMode
import io.tinychat.model.prompt.PromptMeta
import io.tinychat.model.prompt.PromptSeeds
import io.tinychat.net.SseEvent
import io.tinychat.net.protocol.PreparedRequest
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统提示词接线（M7.5）：
 * 1. 新会话把当前预设快照写进库；
 * 2. 发送时把变量**求值**成真实日期/地点；
 * 3. `CLEAR` 模式请求体里不该有 system。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryPromptTest {

    private lateinit var db: TinyChatDatabase
    private lateinit var transport: CapturingTransport
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var providerStore: ProviderStore

    private class CapturingTransport : ChatTransport {
        var request: PreparedRequest? = null

        override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
            this@CapturingTransport.request = request
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = DatabaseFactory.createInMemory(context)
        attachmentStore = AttachmentStore(context)
        transport = CapturingTransport()
        providerStore = ProviderStore.create(db)
        runBlocking { providerStore.ensureSeeded() }
    }

    @After
    fun tearDown() = db.close()

    private fun repository(
        snapshot: PromptSnapshot = PromptSnapshot(PromptSeeds.OFFICIAL_SYSTEM, PromptMode.REPLACE),
        meta: PromptMeta = PromptMeta(location = "浙江", language = "中文"),
    ) = ChatRepository(
        db = db,
        transport = transport,
        clock = { 1_700_000_000_000L },
        isOnline = { true },
        attachmentStore = attachmentStore,
        uploader = AttachmentUploader(),
        promptSnapshot = { snapshot },
        promptMeta = { meta },
        providerStore = providerStore,
        apiKeyFor = { "k" },
    )

    @Test
    fun `新会话快照写入会话，请求带上官方 system`() = runBlocking {
        val repo = repository()
        val id = repo.createSession()
        assertEquals(PromptSeeds.OFFICIAL_SYSTEM, db.sessionDao().byId(id)!!.systemPromptSnapshot)

        repo.send(id, "你好")
        val body = transport.request!!.bodyJson
        assertTrue(body, body.contains("\"system\":\"${PromptSeeds.OFFICIAL_SYSTEM}\""))
    }

    @Test
    fun `发送时变量被求值成真实内容`() = runBlocking {
        val repo = repository(
            snapshot = PromptSnapshot("今天是 {{date}}，我在 {{location}}。", PromptMode.REPLACE),
        )
        val id = repo.createSession()
        repo.send(id, "你好")

        val body = transport.request!!.bodyJson
        assertFalse("变量没有被求值：$body", body.contains("{{"))
        assertTrue(body, body.contains("我在 浙江"))
        // 变量求值不能污染库里存的原文（快照要能重复使用）
        assertEquals("今天是 {{date}}，我在 {{location}}。", db.sessionDao().byId(id)!!.systemPromptSnapshot)
    }

    @Test
    fun `清空模式不带 system 字段`() = runBlocking {
        val repo = repository(snapshot = PromptSnapshot(content = null, mode = PromptMode.CLEAR))
        val id = repo.createSession()
        assertNotNull(db.sessionDao().byId(id))
        repo.send(id, "你好")

        assertFalse(transport.request!!.bodyJson.contains("\"system\""))
    }
}
