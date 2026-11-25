package io.tinychat.net.transport

import io.tinychat.model.ErrorKind
import io.tinychat.model.ProviderKind
import io.tinychat.net.SseEvent
import io.tinychat.net.SseReader
import io.tinychat.net.protocol.PreparedRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 聊天请求的异常。`kind` 直接复用 [ErrorKind]，M1-11 的错误映射与 UI 文案都从这里取值。
 */
class ChatException(
    val kind: ErrorKind,
    val httpStatus: Int? = null,
    message: String,
    cause: Throwable? = null,
    /** 429 时服务端给的 Retry-After（毫秒）；没有就由上层按默认退避 */
    val retryAfterMillis: Long? = null,
) : Exception(message, cause)

/** 传输层抽象：真实实现走 OkHttp，测试用假实现喂固件，上层不感知差别。 */
interface ChatTransport {
    fun stream(request: PreparedRequest): Flow<SseEvent>
}

/**
 * OkHttp 实现。
 *
 * - 默认连接超时 15s、读取空闲超时 120s；OpenCode Go 读取空闲超时 300s，以容纳慢首字；
 * - 取消：协程结束时 `call.cancel()`，这就是 M1-7「停止生成」的落点；
 * - 不挂任何日志拦截器：请求头里有 `x-api-key`，R9 要求「不写日志」。
 */
class OkHttpChatTransport(
    private val client: OkHttpClient = defaultClient(),
) : ChatTransport {
    // 共用连接池和调度器，只放宽 OpenCode Go 等待响应头和流数据的时间。
    private val openCodeClient by lazy {
        client.newBuilder()
            .readTimeout(maxOf(client.readTimeoutMillis.toLong(), OPENCODE_READ_TIMEOUT_MILLIS), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun stream(request: PreparedRequest): Flow<SseEvent> = callbackFlow {
        val builder = Request.Builder()
            .url(request.url)
            .post(request.bodyJson.toRequestBody(JSON))

        request.headers.forEach { (name, value) -> builder.header(name, value) }

        val httpRequest = builder.build()
        val url = httpRequest.url
        val isOpenCodeEndpoint = url.host == "opencode.ai" &&
            (url.encodedPath.startsWith("/inference/go/") || url.encodedPath.startsWith("/zen/"))
        val requestClient = if (request.providerKind == ProviderKind.OPENCODE_GO || isOpenCodeEndpoint) {
            openCodeClient
        } else {
            client
        }
        val call = requestClient.newCall(httpRequest)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 超时和「连不上」得说清楚：以前两种都只透出 OkHttp 的英文 "timeout"，
                // 用户只能猜是网络问题还是服务端不吐字。
                val message = when (e) {
                    is SocketTimeoutException -> "请求超时：连接或等待服务端响应超时"
                    is java.net.UnknownHostException ->
                        "域名解析失败，请检查网络或接口地址"
                    is java.net.ConnectException ->
                        "无法连接到服务器，请检查网络或接口地址"
                    else -> e.message ?: "网络请求失败"
                }
                close(ChatException(ErrorKind.NETWORK, message = message, cause = e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                        close(
                            ChatException(
                                kind = kindFor(resp.code, body),
                                httpStatus = resp.code,
                                message = body.ifBlank { "HTTP ${resp.code}" },
                                retryAfterMillis = retryAfterMillis(resp.header("Retry-After")),
                            ),
                        )
                        return
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        close(ChatException(ErrorKind.STREAM, resp.code, "响应体为空"))
                        return
                    }

                    try {
                        for (event in SseReader.read(source)) {
                            if (trySendBlocking(event).isFailure) break
                        }
                        close()
                    } catch (e: IOException) {
                        // 已经开始读流，报告读取超时，同时保留已经收到的内容。
                        val message = if (e is SocketTimeoutException) {
                            val seconds = TimeUnit.MILLISECONDS.toSeconds(requestClient.readTimeoutMillis.toLong())
                            "请求超时：超过 $seconds 秒没有收到数据"
                        } else {
                            e.message ?: "流被中断"
                        }
                        close(ChatException(ErrorKind.INTERRUPTED, resp.code, message, e))
                    } catch (e: Exception) {
                        close(ChatException(ErrorKind.STREAM, resp.code, e.message ?: "流解析失败", e))
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    /** `Retry-After` 只按秒解析；HTTP-date 形式与非法值一律忽略 */
    private fun retryAfterMillis(raw: String?): Long? {
        val seconds = raw?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1000).coerceIn(0, 60_000)
    }

    /**
     * HTTP 状态码 → 错误分类。
     *
     * 400 要再看一眼响应体：上下文超限也是 400，但它是「这段对话满了，开新的」，
     * 和「生成出错」完全是两回事，用户得知道下一步干什么。
     */
    private fun kindFor(code: Int, body: String): ErrorKind = when (code) {
        400 -> if (isContextOverflow(body)) ErrorKind.CONTEXT_LIMIT else ErrorKind.STREAM
        401 -> ErrorKind.HTTP_401
        402 -> ErrorKind.HTTP_402
        422 -> ErrorKind.HTTP_422
        429 -> ErrorKind.HTTP_429
        in 500..599 -> ErrorKind.HTTP_5XX
        else -> ErrorKind.STREAM
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private const val READ_TIMEOUT_SECONDS = 120L
        private const val OPENCODE_READ_TIMEOUT_MILLIS = 300_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 400 的响应体是不是「上下文超限」。
 *
 * 各家措辞不统一，只认这几个特征串（小写比较）。宁可漏判成「生成出错」，
 * 也不要把别的 400 误判成「对话满了」——后者会骗用户去开新对话。
 */
internal fun isContextOverflow(body: String): Boolean {
    val text = body.lowercase()
    return CONTEXT_OVERFLOW_MARKERS.any { it in text }
}

private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "context_length_exceeded",
    "maximum context length",
    "exceed context limit",
    "prompt is too long",
    "input is too long",
    "too many tokens",
)
