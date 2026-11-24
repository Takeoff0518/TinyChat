package io.tinychat.net.search

import io.tinychat.model.KnownProviders
import io.tinychat.model.SearchResult
import io.tinychat.model.TINYCHAT_USER_AGENT
import io.tinychat.net.protocol.AnthropicMessagesProtocol
import io.tinychat.net.protocol.DeepSeekMessagesProtocol
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Runs DeepSeek's native search through a separate Messages request, as DeepSeek Harness does. */
class DeepSeekWebSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build(),
) {
    suspend fun search(apiKey: String, query: String): List<SearchResult> {
        require(apiKey.isNotBlank()) { "尚未配置 DeepSeek 搜索 API Key" }
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val body = buildJsonObject {
            put("model", SEARCH_MODEL)
            put("max_tokens", 4096)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "Perform a web search for the query: $query")
                        }
                    }
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                    put("max_uses", 5)
                }
            }
        }
        val request = Request.Builder()
            .url(KnownProviders.deepSeek.baseUrl + DeepSeekMessagesProtocol.MESSAGES_PATH)
            .header("x-api-key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("anthropic-version", AnthropicMessagesProtocol.VERSION)
            .header("Accept", "application/json")
            .header("User-Agent", TINYCHAT_USER_AGENT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    continuation.resumeWithException(exception)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw IOException("DeepSeek 联网搜索 HTTP ${it.code}")
                            val payload = it.body?.string() ?: throw IOException("DeepSeek 联网搜索返回为空")
                            parseResults(payload)
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun parseResults(payload: String): List<SearchResult> {
        val response = try {
            Json.parseToJsonElement(payload) as? JsonObject
        } catch (exception: SerializationException) {
            throw IOException("DeepSeek 联网搜索返回格式错误", exception)
        }
        val blocks = response?.objects("content").orEmpty()
        val resultBlocks = blocks.filter { it.text("type") == "web_search_tool_result" }
        val resultLists = resultBlocks.mapNotNull { it["content"] as? JsonArray }
        if (resultLists.isEmpty()) throw IOException("DeepSeek 未返回结构化联网搜索结果")

        val snippets = mutableMapOf<String, String>()
        blocks.filter { it.text("type") == "text" }.flatMap { it.objects("citations") }.forEach { citation ->
            val url = citation.text("url")
            val snippet = citation.text("cited_text")
            if (!url.isNullOrBlank() && !snippet.isNullOrBlank()) {
                snippets.putIfAbsent(url, snippet)
            }
        }
        return resultLists.asSequence()
            .flatMap { it.asSequence().filterIsInstance<JsonObject>() }
            .filter { it.text("type") == "web_search_result" }
            .mapNotNull { item ->
                val rawUrl = item.text("url") ?: return@mapNotNull null
                val url = rawUrl.toHttpUrlOrNull() ?: return@mapNotNull null
                SearchResult(
                    url = url.toString(),
                    title = item.text("title").orEmpty().trim().take(200),
                    snippet = snippets[rawUrl]?.trim()?.take(1_500),
                    publishedAt = item.text("page_age"),
                    siteName = url.host.removePrefix("www."),
                )
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
            .toList()
    }

    private fun JsonObject.objects(name: String): List<JsonObject> =
        (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val SEARCH_MODEL = "deepseek-v4-flash"
        const val MAX_RESULTS = 5
    }
}
