package io.tinychat.data.chat

import android.content.Context
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
import io.tinychat.model.ErrorKind
import io.tinychat.model.MessageStatus
import io.tinychat.model.RequestPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.model.ThinkingEffort
import io.tinychat.net.SseEvent
import io.tinychat.net.protocol.PreparedRequest
import io.tinychat.net.transport.ChatException
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryCompressionTest {
    private lateinit var db: TinyChatDatabase
    private lateinit var repo: ChatRepository
    private lateinit var providers: ProviderStore
    private val requests = mutableListOf<PreparedRequest>()
    private var reply: suspend (PreparedRequest) -> List<SseEvent> = { request ->
        if (request.url.contains("source.example")) listOf(event("Compact project context")) else listOf(event("New answer"))
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = DatabaseFactory.createInMemory(context)
        providers = ProviderStore.create(db)
        providers.save(provider("source", 1_000_000, 4_096))
        providers.save(provider("target", 262_144, 8_192))
        repo = ChatRepository(
            db = db,
            transport = object : ChatTransport {
                override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
                    requests += request
                    reply(request).forEach { emit(it) }
                }
            },
            attachmentStore = AttachmentStore(context),
            uploader = AttachmentUploader(),
            providerStore = providers,
            apiKeyFor = { "$it-key" },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `oversized branch is summarized by outgoing model and old messages remain visible`() = runBlocking {
        val (session, anchor) = conversation(500_000)
        var compressing = false
        val estimate = switch(session) { compressing = true }

        assertTrue(compressing)
        assertNotNull(estimate)
        assertTrue(estimate!! < 262_144)
        assertEquals("target-model", db.sessionDao().byId(session)?.model)
        assertEquals("Compact project context", db.messageDao().byId(anchor)?.contextSummary)
        assertEquals(2, db.messageDao().countInSession(session))
        assertEquals("source-model", modelOf(requests.single()))
        assertEquals("Bearer source-key", requests.single().headers["Authorization"])
        assertEquals("4096", requestField(requests.single(), "max_tokens"))
        assertTrue(requests.single().bodyJson.contains("Original project requirement"))
        assertTrue(requests.single().bodyJson.contains("Original project answer"))

        repo.send(session, "Follow up")
        val next = requests.last()
        assertEquals("target-model", modelOf(next))
        assertEquals("Bearer target-key", next.headers["Authorization"])
        assertTrue(next.bodyJson.contains("Compact project context"))
        assertTrue(next.bodyJson.contains("Follow up"))
        assertFalse(next.bodyJson.contains("Original project requirement"))
        assertEquals(4, db.messageDao().countInSession(session))
    }

    @Test
    fun `below target limit switches without contacting either model`() = runBlocking {
        val (session, anchor) = conversation(30_000)
        assertNull(switch(session) { error("compression should not start") })
        assertTrue(requests.isEmpty())
        assertNull(db.messageDao().byId(anchor)?.contextSummary)
        assertEquals("target-model", db.sessionDao().byId(session)?.model)
    }

    @Test
    fun `truncated summary leaves settings and conversation unchanged`() = runBlocking {
        val (session, anchor) = conversation(500_000)
        reply = { listOf(event("Unfinished summary", "length")) }

        assertTrue(runCatching { switch(session) }.exceptionOrNull() is ChatException)
        assertEquals("source-model", db.sessionDao().byId(session)?.model)
        assertEquals(anchor, db.sessionDao().byId(session)?.currentMessageId)
        assertEquals(2, db.messageDao().countInSession(session))
        assertNull(db.messageDao().byId(anchor)?.contextSummary)
    }

    @Test
    fun `branch changed during compression prevents committing its stale result`() = runBlocking {
        val (session, anchor) = conversation(500_000)
        val question = db.messageDao().byId(anchor)!!.parentId
        reply = {
            db.sessionDao().setCurrentMessage(session, question, 3L)
            listOf(event("Stale summary"))
        }

        assertTrue(runCatching { switch(session) }.exceptionOrNull() is ChatException)
        assertEquals("source-model", db.sessionDao().byId(session)?.model)
        assertEquals(question, db.sessionDao().byId(session)?.currentMessageId)
        assertNull(db.messageDao().byId(anchor)?.contextSummary)
    }

    @Test
    fun `unfinished source summary retries with official DeepSeek and its own key`() = runBlocking {
        saveOfficial()
        val (session, anchor) = conversation(500_000)
        reply = { request ->
            if (request.url.contains("source.example")) listOf(event("Unfinished summary", "length"))
            else deepSeekEvents("DeepSeek compact context")
        }

        assertNotNull(switch(session))
        assertEquals(listOf("source-model", KnownProviders.flash.id), requests.map(::modelOf))
        assertEquals("Bearer source-key", requests.first().headers["Authorization"])
        assertEquals("deepseek-key", requests.last().headers["x-api-key"])
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", requests.last().url)
        assertTrue(requests.last().bodyJson.contains("Original project requirement"))
        assertEquals("DeepSeek compact context", db.messageDao().byId(anchor)?.contextSummary)
        assertEquals("target-model", db.sessionDao().byId(session)?.model)
    }

    @Test
    fun `deleted source goes straight to native official endpoint and skips modified DeepSeek profile`() = runBlocking {
        providers.save(KnownProviders.deepSeek.copy(id = "deepseek-proxy", baseUrl = "https://proxy.example"))
        saveOfficial()
        val (session, anchor) = conversation(500_000)
        providers.delete("source")
        reply = { deepSeekEvents("Recovered with DeepSeek") }

        assertNotNull(switch(session))
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", requests.single().url)
        assertEquals("Recovered with DeepSeek", db.messageDao().byId(anchor)?.contextSummary)
        assertEquals("target-model", db.sessionDao().byId(session)?.model)
    }

    @Test
    fun `source and native fallback fail without changing the selected model or conversation`() = runBlocking {
        saveOfficial()
        val (session, anchor) = conversation(500_000)
        reply = { throw ChatException(ErrorKind.HTTP_401, 401, "bad key") }

        assertTrue(runCatching { switch(session) }.exceptionOrNull() is ChatException)
        assertEquals(listOf("source-model", KnownProviders.flash.id), requests.map(::modelOf))
        assertEquals("source-model", db.sessionDao().byId(session)?.model)
        assertEquals(anchor, db.sessionDao().byId(session)?.currentMessageId)
        assertEquals(2, db.messageDao().countInSession(session))
        assertNull(db.messageDao().byId(anchor)?.contextSummary)
    }

    @Test
    fun `cancellation never invokes the DeepSeek fallback`() = runBlocking {
        saveOfficial()
        val (session, anchor) = conversation(500_000)
        reply = { throw kotlinx.coroutines.CancellationException("user left") }

        assertTrue(runCatching { switch(session) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(listOf("source-model"), requests.map(::modelOf))
        assertEquals("source-model", db.sessionDao().byId(session)?.model)
        assertNull(db.messageDao().byId(anchor)?.contextSummary)
    }

    private suspend fun saveOfficial() = providers.save(KnownProviders.deepSeek)

    private suspend fun conversation(tokens: Int): Pair<String, Long> {
        val session = repo.createSession(providerId = "source", model = "source-model")
        val question = db.messageDao().insert(MessageEntity(sessionId = session,
            role = Role.USER, status = MessageStatus.FINISHED, insertedAt = 1L))
        val answer = db.messageDao().insert(MessageEntity(sessionId = session, parentId = question,
            role = Role.ASSISTANT, status = MessageStatus.FINISHED, insertedAt = 2L,
            promptTokens = tokens - 10, completionTokens = 10))
        db.fragmentDao().insert(Fragment(messageId = question, ord = 0,
            payload = RequestPayload("Original project requirement")).toEntity())
        db.fragmentDao().insert(Fragment(messageId = answer, ord = 0,
            payload = ResponsePayload("Original project answer")).toEntity())
        db.sessionDao().setCurrentMessage(session, answer, 2L)
        return session to answer
    }

    private suspend fun switch(session: String, progress: () -> Unit = {}): Int? = repo.switchModel(
        session, "source", "source-model", "target", "target-model", ThinkingEffort.HIGH,
        thinkingEnabled = true, searchEnabled = false, onCompressing = progress,
    )

    private fun provider(id: String, contextLimit: Int, outputLimit: Int) =
        KnownProviders.genericOpenAiCompatible(id, id, "https://$id.example/v1", "$id-model").let { base ->
            base.copy(models = listOf(base.models.single().copy(contextWindow = contextLimit, maxOutputTokens = outputLimit)))
        }

    private fun modelOf(request: PreparedRequest): String = requestField(request, "model").orEmpty()

    private fun requestField(request: PreparedRequest, field: String): String? =
        Json.parseToJsonElement(request.bodyJson).jsonObject[field]?.jsonPrimitive?.content

    private fun event(text: String, finish: String = "stop") = SseEvent(null,
        """{"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":"$finish"}]}""",
    )

    private fun deepSeekEvents(text: String): List<SseEvent> = listOf(
        SseEvent(null, """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
        SseEvent(null, """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""),
        SseEvent(null, """{"type":"content_block_stop","index":0}"""),
        SseEvent(null, """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}"""),
        SseEvent(null, """{"type":"message_stop"}"""),
    )
}
