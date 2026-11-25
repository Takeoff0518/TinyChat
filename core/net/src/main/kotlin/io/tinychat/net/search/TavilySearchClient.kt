package io.tinychat.net.search

import io.tinychat.model.SearchResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class TavilySearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    suspend fun search(apiKey: String, query: String): List<SearchResult> {
        val body = buildJsonObject {
            put("query", query)
            put("search_depth", "basic")
            put("max_results", MAX_RESULTS)
        }
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $apiKey")
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
                            if (!it.isSuccessful) throw IOException("Tavily HTTP ${it.code}")
                            val payload = it.body?.string() ?: throw IOException("Tavily 返回为空")
                            json.decodeFromString<Payload>(payload).results
                                .mapNotNull { hit ->
                                    val url = hit.url.toHttpUrlOrNull() ?: return@mapNotNull null
                                    SearchResult(
                                        url = url.toString(),
                                        title = hit.title.take(200),
                                        snippet = hit.content?.trim()?.take(1_500),
                                        siteName = url.host.removePrefix("www."),
                                    )
                                }
                                .distinctBy { hit -> hit.url }
                                .take(MAX_RESULTS)
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    @Serializable
    private data class Payload(val results: List<Hit>)

    @Serializable
    private data class Hit(val url: String, val title: String = "", val content: String? = null)

    private companion object {
        const val MAX_RESULTS = 5
        val json = Json { ignoreUnknownKeys = true }
    }
}
