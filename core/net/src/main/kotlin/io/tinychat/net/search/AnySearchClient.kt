package io.tinychat.net.search

import io.tinychat.model.SearchResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

class AnySearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    /**
     * AnySearch 的公开接口不要求鉴权（官方示例只有一个 `query`），所以 [apiKey] 可以为空：
     * 有 Key 就带上（配额与优先级归账号），没有就走游客额度，不该因为"没填 Key"整条搜索不可用。
     */
    suspend fun search(apiKey: String?, query: String): List<SearchResult> {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val body = buildJsonObject {
            put("query", query)
            put("max_results", MAX_RESULTS)
        }
        val builder = Request.Builder()
            .url("https://api.anysearch.com/v1/search")
            .header("Accept", "application/json")
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        key?.let { builder.header("Authorization", "Bearer $it") }
        val request = builder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use { readResults(it, key != null) } })
                }
            })
        }
    }

    private fun readResults(response: Response, authenticated: Boolean): List<SearchResult> {
        // AnySearch 的错误正文可能包含生成的凭据，只保留 HTTP 状态码。
        if (!response.isSuccessful) throw httpError(response.code, authenticated)
        val source = response.body?.source() ?: throw IOException("AnySearch 返回为空")
        val payload = try {
            if (source.request(MAX_RESPONSE_BYTES + 1)) null else source.readUtf8()
        } catch (error: IOException) {
            throw networkError(error)
        } ?: throw IOException("AnySearch 返回过大")
        val envelope = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw IOException("AnySearch 返回格式无法解析")

        val code = (envelope["code"] as? JsonPrimitive)?.intOrNull
            ?: throw IOException("AnySearch 返回缺少状态码")
        if (code != 0) throw IOException("AnySearch 返回错误（代码 $code）")
        val results = (envelope["data"] as? JsonObject)?.get("results") as? JsonArray
            ?: throw IOException("AnySearch 返回缺少搜索结果")

        return results.asSequence()
            .mapNotNull { hit ->
                val item = hit as? JsonObject ?: return@mapNotNull null
                val url = item.text("url")?.toHttpUrlOrNull() ?: return@mapNotNull null
                val host = url.host.removePrefix("www.")
                SearchResult(
                    url = url.toString(),
                    title = item.text("title")?.take(200) ?: host,
                    snippet = (item.text("snippet") ?: item.text("content"))?.take(1_500),
                    siteName = host,
                )
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun httpError(code: Int, authenticated: Boolean): IOException {
        val reason = when (code) {
            401, 403 -> if (authenticated) "请检查 API Key 是否有效" else "匿名访问被拒绝，请配置 API Key"
            402 -> if (authenticated) "当前 API Key 的搜索额度已用完" else "匿名搜索额度已用完，请配置 API Key"
            429 -> "请求过于频繁，请稍后重试"
            in 500..599 -> "搜索服务暂不可用，请稍后重试"
            else -> "搜索请求失败"
        }
        return IOException("AnySearch $reason（HTTP $code）")
    }

    private fun networkError(error: IOException): IOException {
        val reason = when (error) {
            is InterruptedIOException -> "等待搜索结果超时，请稍后重试"
            is UnknownHostException -> "无法解析搜索服务地址，请检查网络或 DNS"
            is SSLException -> "无法建立安全连接，请检查网络"
            else -> "无法连接搜索服务，请检查网络"
        }
        return IOException("AnySearch $reason", error)
    }

    private companion object {
        const val MAX_RESULTS = 5
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}
