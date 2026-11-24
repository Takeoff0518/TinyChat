package io.tinychat.data.chat

import androidx.test.core.app.ApplicationProvider
import io.tinychat.data.db.DatabaseFactory
import io.tinychat.data.db.MessageEntity
import io.tinychat.data.db.SessionEntity
import io.tinychat.data.db.TinyChatDatabase
import io.tinychat.model.ErrorKind
import io.tinychat.model.MessageStatus
import io.tinychat.model.Role
import io.tinychat.net.SseEvent
import io.tinychat.net.SseReader
import io.tinychat.net.StopReason
import io.tinychat.net.protocol.PreparedRequest
import io.tinychat.net.transport.ChatException
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M1-5：流式落库与状态流转。用假传输喂固件，不联网。
 *
 * 固件形状与 04 号报告一致，只保留必要帧以控制体积。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatStreamRunnerTest {

    private lateinit var db: TinyChatDatabase
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = DatabaseFactory.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private class FakeTransport(
        private val events: List<SseEvent>,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : ChatTransport {
        override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
            events.forEachIndexed { index, event ->
                if (index >= failAfter) throw CancellationException("用户停止")
                emit(event)
            }
        }
    }

    /**
     * 可数次的假传输：前 [failures].size 次调用直接抛对应错误，之后正常吐 [events]。
     * [failAfterOneEvent] 用来验证「收到内容后不许重试」。
     */
    private class ScriptedTransport(
        private val failures: List<ChatException> = emptyList(),
        private val events: List<SseEvent> = emptyList(),
        private val failAfterEventIndex: Int? = null,
        private val midStreamError: ChatException? = null,
    ) : ChatTransport {
        var calls = 0
            private set

        override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
            val call = calls++
            if (call < failures.size) throw failures[call]
            events.forEachIndexed { index, event ->
                emit(event)
                if (index == failAfterEventIndex) throw midStreamError!!
            }
        }
    }

    private fun sse(raw: String): List<SseEvent> = SseReader.read(Buffer().writeUtf8(raw)).toList()

    private fun request() = PreparedRequest(url = "", headers = emptyMap(), bodyJson = "{}")

    private suspend fun newAssistantMessage(): Long {
        db.sessionDao().upsert(
            SessionEntity(
                id = "s1",
                createdAt = now,
                updatedAt = now,
                providerId = "deepseek",
                model = "deepseek-flash",
            ),
        )
        return db.messageDao().insert(
            MessageEntity(
                sessionId = "s1",
                role = Role.ASSISTANT,
                status = MessageStatus.WIP,
                insertedAt = now,
            ),
        )
    }

    private fun runner() = ChatStreamRunner(
        messageDao = db.messageDao(),
        fragmentDao = db.fragmentDao(),
        // 固定时钟 + 关闭节流：测试不依赖真实时间
        clock = { now },
        flushIntervalMillis = 0,
        // 重试退避不真等
        sleep = {},
    )

    @Test
    fun `搜索流落库为 5 个碎片并标记 FINISHED`() = runBlocking {
        val id = newAssistantMessage()
        val outcome = runner().run(id, request(), FakeTransport(sse(SEARCH_STREAM)))

        assertEquals(MessageStatus.FINISHED, outcome.status)
        assertEquals(StopReason.END_TURN, outcome.stopReason)

        val stored = db.fragmentDao().byMessage(id)
        assertEquals(
            listOf("THINK", "TOOL_SEARCH", "RESPONSE"),
            stored.map { it.type },
        )
        assertTrue(stored.all { it.payload.contains("\"type\"") })

        val message = db.messageDao().byId(id)!!
        assertEquals(MessageStatus.FINISHED, message.status)
        assertEquals(9078, message.promptTokens)
        assertEquals(4096, message.cacheHitTokens)
        assertEquals(1686, message.completionTokens)
        // totalInput(13174) + output(1686)
        assertEquals(14860, message.accumulatedTokenUsage)
        assertEquals("DeepSeek V4.1 更新", outcome.title)
    }

    @Test
    fun `拒答落成 CONTENT_FILTER 并丢掉思考与正文`() = runBlocking {
        val id = newAssistantMessage()
        val outcome = runner().run(id, request(), FakeTransport(sse(REFUSAL_STREAM)))

        assertEquals(MessageStatus.CONTENT_FILTER, outcome.status)
        // 协议要求：拒答时已下发的 thinking/text 一律作废
        assertEquals(0, db.fragmentDao().byMessage(id).size)
        assertEquals(MessageStatus.CONTENT_FILTER, db.messageDao().byId(id)!!.status)
    }

    @Test
    fun `错误帧落成 FAILED 并记录错误`() = runBlocking {
        val id = newAssistantMessage()
        val outcome = runner().run(id, request(), FakeTransport(sse(ERROR_STREAM)))

        assertEquals(MessageStatus.FAILED, outcome.status)
        val message = db.messageDao().byId(id)!!
        assertEquals(MessageStatus.FAILED, message.status)
        assertEquals(ErrorKind.STREAM, message.errorKind)
        assertTrue(message.errorMessage!!.contains("failed to generate"))
    }

    @Test
    fun `用户停止时已生成内容保留 状态落 INCOMPLETE`() = runBlocking {
        val id = newAssistantMessage()

        val error = runCatching {
            runner().run(id, request(), FakeTransport(sse(SEARCH_STREAM), failAfter = 6))
        }.exceptionOrNull()

        assertTrue("取消要向上传播", error is CancellationException)
        assertEquals(MessageStatus.INCOMPLETE, db.messageDao().byId(id)!!.status)
        assertTrue("取消前的内容必须留住", db.fragmentDao().byMessage(id).isNotEmpty())
    }

    @Test
    fun `cancelled coroutine flushes buffered text and clears WIP`() = runBlocking {
        val id = newAssistantMessage()
        val buffered = CompletableDeferred<Unit>()
        val stream = object : ChatTransport {
            override fun stream(request: PreparedRequest): Flow<SseEvent> = flow {
                emit(SseEvent(null, """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}"""))
                emit(SseEvent(null, """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"buffered answer"}}"""))
                buffered.complete(Unit)
                awaitCancellation()
            }
        }
        val throttled = ChatStreamRunner(db.messageDao(), db.fragmentDao(), clock = { 0L })
        val work = launch { throttled.run(id, request(), stream) }
        withTimeout(5_000) { buffered.await() }
        assertTrue(db.fragmentDao().byMessage(id).isEmpty())

        work.cancelAndJoin()
        assertEquals(MessageStatus.INCOMPLETE, db.messageDao().byId(id)?.status)
        assertTrue(db.fragmentDao().byMessage(id).single().payload.contains("buffered answer"))
    }

    @Test
    fun `思考签名落到 thinking_signature 列`() = runBlocking {
        val id = newAssistantMessage()
        runner().run(id, request(), FakeTransport(sse(SEARCH_STREAM)))

        assertEquals("sig_abcdefghij", db.messageDao().byId(id)!!.thinkingSignature)
    }

    // —— M1-11 自动重试 ——

    @Test
    fun `5xx 在没收到任何帧前自动重试并成功`() = runBlocking {
        val id = newAssistantMessage()
        val transport = ScriptedTransport(
            failures = listOf(ChatException(ErrorKind.HTTP_5XX, 503, "boom")),
            events = sse(SEARCH_STREAM),
        )

        val outcome = runner().run(id, request(), transport)

        assertEquals(MessageStatus.FINISHED, outcome.status)
        assertEquals("应当重试一次", 2, transport.calls)
    }

    @Test
    fun `429 只补一次，退避后仍失败就落 FAILED`() = runBlocking {
        val id = newAssistantMessage()
        val transport = ScriptedTransport(
            failures = listOf(
                ChatException(ErrorKind.HTTP_429, 429, "slow down"),
                ChatException(ErrorKind.HTTP_429, 429, "slow down"),
                ChatException(ErrorKind.HTTP_429, 429, "slow down"),
            ),
            events = sse(SEARCH_STREAM),
        )

        val outcome = runner().run(id, request(), transport)

        assertEquals(MessageStatus.FAILED, outcome.status)
        assertEquals(ErrorKind.HTTP_429, outcome.error?.kind)
        assertEquals("429 最多补一次", 2, transport.calls)
    }

    @Test
    fun `收到内容之后不再自动重试`() = runBlocking {
        val id = newAssistantMessage()
        val transport = ScriptedTransport(
            events = sse(SEARCH_STREAM),
            failAfterEventIndex = 3,
            midStreamError = ChatException(ErrorKind.HTTP_5XX, 500, "mid-stream"),
        )

        val outcome = runner().run(id, request(), transport)

        assertEquals(MessageStatus.FAILED, outcome.status)
        // 铁律：出现过 content_block_start 就绝不重试，避免重复计费与内容错乱
        assertEquals(1, transport.calls)
        assertTrue("已下发内容要留住", db.fragmentDao().byMessage(id).isNotEmpty())
    }

    @Test
    fun `401 不重试，直接失败`() = runBlocking {
        val id = newAssistantMessage()
        val transport = ScriptedTransport(
            failures = listOf(ChatException(ErrorKind.HTTP_401, 401, "bad key")),
            events = sse(SEARCH_STREAM),
        )

        val outcome = runner().run(id, request(), transport)

        assertEquals(MessageStatus.FAILED, outcome.status)
        assertEquals(ErrorKind.HTTP_401, outcome.error?.kind)
        assertEquals(1, transport.calls)
    }

    private companion object {
        const val SEARCH_STREAM = """event: message_start
data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"deepseek-flash","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":40,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先查一下。"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig_abcdefghij"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"t1","name":"deepseek_web_search","input":{"action":"search","queries":["q1"]},"kind":"search"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: content_block_start
data: {"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"t1","content":[{"type":"web_search_result","title":"A","url":"https://a.example","snippet":"sa","site_name":"DeepSeek"}],"kind":"search"}}

event: content_block_stop
data: {"type":"content_block_stop","index":2}

event: content_block_start
data: {"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"主要更新："}}

event: content_block_delta
data: {"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"上下文 1M"}}

event: content_block_stop
data: {"type":"content_block_stop","index":3}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null,"title":"DeepSeek V4.1 更新"},"usage":{"input_tokens":9078,"cache_creation_input_tokens":0,"cache_read_input_tokens":4096,"output_tokens":1686,"server_tool_use":{"web_search_requests":1,"web_fetch_requests":0}}}

event: message_stop
data: {"type":"message_stop"}
"""

        const val REFUSAL_STREAM = """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"抱歉，这个我没法帮。"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"type":"refusal"}},"usage":{"input_tokens":31,"output_tokens":12}}

event: message_stop
data: {"type":"message_stop"}
"""

        const val ERROR_STREAM = """event: error
data: {"type":"error","error":{"type":"api_error","message":"The model failed to generate a complete response. Please retry."},"usage":{"input_tokens":10,"output_tokens":0}}
"""
    }
}
