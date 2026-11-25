package io.tinychat.net.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class TavilySearchClientTest {
    @Test
    fun `queries Tavily with its own key and keeps usable distinct results`() = runBlocking {
        lateinit var request: Request
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"results":[
                      {"url":"https://www.example.com/articles/1","title":"Example","content":"Text"},
                      {"url":"https://www.example.com/articles/1","title":"Duplicate"},
                      {"url":"javascript:void(0)","title":"Invalid"}
                    ]}""".trimIndent().toResponseBody(),
                )
                .build()
        }.build()

        val results = TavilySearchClient(client).search("test-search-key", "今天的新闻")
        val body = Buffer().apply { request.body!!.writeTo(this) }.readUtf8()

        assertEquals("https://api.tavily.com/search", request.url.toString())
        assertEquals("Bearer test-search-key", request.header("Authorization"))
        assertEquals("今天的新闻", Json.parseToJsonElement(body).jsonObject["query"]?.jsonPrimitive?.content)
        assertEquals("Example", results.single().title)
        assertEquals("Text", results.single().snippet)
        assertEquals("example.com", results.single().siteName)
    }
}
