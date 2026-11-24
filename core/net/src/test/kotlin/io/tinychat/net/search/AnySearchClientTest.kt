package io.tinychat.net.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AnySearchClientTest {
    @Test
    fun `sends the search key and normalizes the official response`() = runBlocking {
        lateinit var request: Request
        val client = clientReturning(
            200,
            """{
              "code": 0,
              "message": "success",
              "data": {
                "results": [
                  {"url":"https://www.example.com/a","title":"First","snippet":"Short summary","content":"Full text"},
                  {"url":"https://www.example.com/a","title":"Duplicate"},
                  {"url":"javascript:void(0)","title":"Invalid"},
                  {"title":"Missing URL"},
                  {"url":"https://second.example/b","title":"","content":"Text fallback"}
                ],
                "metadata": {"total_results":5}
              }
            }""".trimIndent(),
        ) { request = it }

        val hits = AnySearchClient(client).search(" test-search-key ", "今日新闻")
        val json = Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
        val payload = Json.parseToJsonElement(json).jsonObject

        assertEquals("POST", request.method)
        assertEquals("https://api.anysearch.com/v1/search", request.url.toString())
        assertEquals("Bearer test-search-key", request.header("Authorization"))
        assertEquals("今日新闻", payload["query"]!!.jsonPrimitive.content)
        assertEquals(5, payload["max_results"]!!.jsonPrimitive.int)
        assertEquals(listOf("First", "second.example"), hits.map { it.title })
        assertEquals(listOf("Short summary", "Text fallback"), hits.map { it.snippet })
        assertEquals(listOf("example.com", "second.example"), hits.map { it.siteName })
    }

    @Test
    fun `works without a key because the public endpoint needs no auth`() = runBlocking {
        lateinit var request: Request
        val client = clientReturning(
            200,
            """{"code":0,"data":{"results":[{"url":"https://example.com/x","title":"X"}]}}""",
        ) { request = it }

        val hits = AnySearchClient(client).search(null, "今日新闻")

        assertEquals(null, request.header("Authorization"))
        assertEquals(listOf("X"), hits.map { it.title })
    }

    @Test
    fun `HTTP quota errors omit server text that could contain credentials`() {
        val body = """{"code":-1,"message":"api_key=example-private-key"}"""
        assertEquals(
            "AnySearch 匿名搜索额度已用完，请配置 API Key（HTTP 402）",
            searchError(clientReturning(402, body), key = null).message,
        )
        assertEquals(
            "AnySearch 当前 API Key 的搜索额度已用完（HTTP 402）",
            searchError(clientReturning(402, body)).message,
        )
    }

    @Test
    fun `invalid key failure explains how to fix the authenticated search`() {
        val client = clientReturning(401, """{"code":-1,"error_code":"invalid_api_key"}""")
        assertEquals("AnySearch 请检查 API Key 是否有效（HTTP 401）", searchError(client).message)
    }

    @Test
    fun `search timeout and DNS failure report different reasons`() {
        val timeout = OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException() }.build()
        assertEquals("AnySearch 等待搜索结果超时，请稍后重试", searchError(timeout).message)
        val dnsFailure = OkHttpClient.Builder().addInterceptor { throw UnknownHostException() }.build()
        assertEquals("AnySearch 无法解析搜索服务地址，请检查网络或 DNS", searchError(dnsFailure).message)
    }

    @Test
    fun `nonzero API status is reported without returning results or server text`() {
        val client = clientReturning(200, """{"code":-1,"message":"server details","data":{"results":[]}}""")
        val error = searchError(client)
        assertEquals("AnySearch 返回错误（代码 -1）", error.message)
    }

    private fun searchError(client: OkHttpClient, key: String? = "test-search-key"): IOException =
        assertThrows(IOException::class.java) {
            runBlocking { AnySearchClient(client).search(key, "weather") }
        }

    private fun clientReturning(code: Int, body: String, capture: (Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            capture(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Test response")
                .body(body.toResponseBody())
                .build()
        }.build()
}
