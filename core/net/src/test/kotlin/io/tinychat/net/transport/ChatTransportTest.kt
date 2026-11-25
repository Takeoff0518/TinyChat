package io.tinychat.net.transport

import io.tinychat.model.ErrorKind
import io.tinychat.model.ProviderKind
import io.tinychat.net.protocol.PreparedRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.SocketTimeoutException

class ChatTransportTest {
    @Test
    fun `rapid frames and the final marker survive a slow collector`() = runBlocking {
        val expected = (1..180).map(Int::toString) + "[DONE]"
        val wire = expected.joinToString("") { "data: $it\n\n" }
        val transport = OkHttpChatTransport(OkHttpChatTransport.defaultClient().newBuilder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(wire.toResponseBody())
                .build()
        }.build())

        val frames = transport.stream(request(null)).onEach { delay(1) }.toList()
        assertEquals(expected, frames.map { it.data })
    }

    @Test
    fun `only OpenCode requests get more read time to receive the first response`() = runBlocking {
        val transport = OkHttpChatTransport(OkHttpChatTransport.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val received = "data: ${chain.readTimeoutMillis()},${chain.connectTimeoutMillis()}\n\n"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(received.toResponseBody())
                .build()
        }.build())

        assertEquals("300000,15000", transport.stream(request(ProviderKind.OPENCODE_GO)).toList().single().data)
        val customOpenCode = request(ProviderKind.OPENAI_COMPATIBLE).copy(
            url = "https://opencode.ai/inference/go/v1/chat/completions",
        )
        assertEquals("300000,15000", transport.stream(customOpenCode).toList().single().data)
        assertEquals("120000,15000", transport.stream(request(ProviderKind.OPENAI_COMPATIBLE)).toList().single().data)
        assertEquals("120000,15000", transport.stream(request(null)).toList().single().data)
    }

    @Test
    fun `OpenCode Go body timeouts report the actual read budget`() {
        val transport = OkHttpChatTransport(OkHttpChatTransport.defaultClient().newBuilder().addInterceptor { chain ->
            val responseSource = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = throw SocketTimeoutException()
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(object : ResponseBody() {
                    override fun source() = responseSource
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = -1L
                })
                .build()
        }.build())

        val error = assertThrows(ChatException::class.java) {
            runBlocking { transport.stream(request(ProviderKind.OPENCODE_GO)).toList() }
        }
        assertEquals(ErrorKind.INTERRUPTED, error.kind)
        assertEquals("请求超时：超过 300 秒没有收到数据", error.message)
    }

    private fun request(providerKind: ProviderKind?) = PreparedRequest(
        url = "https://unused.example/v1/chat/completions",
        headers = emptyMap(),
        bodyJson = "{}",
        providerKind = providerKind,
    )
}
