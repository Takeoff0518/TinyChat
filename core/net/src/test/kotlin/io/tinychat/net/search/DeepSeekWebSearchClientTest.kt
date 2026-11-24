package io.tinychat.net.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeepSeekWebSearchClientTest {
    @Test
    fun `uses the Harness search request and joins structured citations by URL`() = runBlocking {
        lateinit var request: Request
        val client = fakeClient(
            body = """{
                "content": [
                    {"type":"text","text":"Untrusted prose https://example.org/prose", "citations":[
                        {"url":"https://www.example.com/one","cited_text":"Excerpt from the source"},
                        {"url":"https://www.example.com/one","cited_text":"Second excerpt"}
                    ]},
                    {"type":"web_search_tool_result","content":[
                        {"type":"web_search_result","url":"https://www.example.com/one","title":"First title","page_age":"2026-11-23"},
                        {"type":"web_search_result","url":"javascript:void(0)","title":"Invalid URL"}
                    ]},
                    {"type":"web_search_tool_result","content":[
                        {"type":"web_search_result","url":"https://www.example.com/one","title":"Duplicate title"}
                    ]}
                ]
            }""".trimIndent(),
            onRequest = { request = it },
        )

        val results = DeepSeekWebSearchClient(client).search("separate-search-key", "今天的新闻")
        val body = Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        val tool = payload.getValue("tools").jsonArray.single().jsonObject
        val message = payload.getValue("messages").jsonArray.single().jsonObject
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", request.url.toString())
        assertEquals("separate-search-key", request.header("x-api-key"))
        assertEquals("Bearer separate-search-key", request.header("Authorization"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        assertEquals("deepseek-v4-flash", payload.getValue("model").jsonPrimitive.content)
        assertEquals(4096, payload.getValue("max_tokens").jsonPrimitive.int)
        assertFalse(payload.containsKey("stream"))
        assertEquals("web_search_20250305", tool.getValue("type").jsonPrimitive.content)
        assertEquals("web_search", tool.getValue("name").jsonPrimitive.content)
        assertEquals(5, tool.getValue("max_uses").jsonPrimitive.int)
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals(
            "Perform a web search for the query: 今天的新闻",
            message.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
        )
        val hit = results.single()
        assertEquals("First title", hit.title)
        assertEquals("Excerpt from the source", hit.snippet)
        assertEquals("example.com", hit.siteName)
        assertEquals("2026-11-23", hit.publishedAt)
    }

    @Test
    fun `rejects prose without a native search result block`() = runBlocking {
        val client = fakeClient("""{"content":[{"type":"text","text":"https://example.org/"}]}""")
        val result = runCatching { DeepSeekWebSearchClient(client).search("test-key", "新闻") }
        assertTrue(result.exceptionOrNull() is IOException)
    }

    private fun fakeClient(body: String, onRequest: (Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            onRequest(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }.build()
}
