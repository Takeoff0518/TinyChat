package io.tinychat.net.protocol

import io.tinychat.model.Capabilities
import io.tinychat.model.KnownProviders
import io.tinychat.model.PromptMode
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderMeta
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ThinkingMode
import io.tinychat.net.context.ContextRebuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8-1：OpenAI Chat Completions 的请求体与头。
 *
 * 只做文本链路：没有 tools / safety_check / title，标题本地生成。
 */
class OpenAiChatProtocolTest {

    private fun command(
        model: String = "gpt-4o-mini",
        systemPrompt: String? = "你是 TinyChat 的助手。",
        systemMode: PromptMode = PromptMode.REPLACE,
        capabilities: Capabilities = Capabilities(
            webSearch = false,
            thinking = true,
            effort = true,
            serverSideTitle = false,
        ),
        thinkingEnabled: Boolean = true,
        effort: ThinkingEffort? = ThinkingEffort.HIGH,
        thinking: ThinkingMode = ThinkingMode.EFFORT,
        withReminder: Boolean = true,
    ) = ChatCommand(
        capabilities = capabilities,
        model = model,
        systemPrompt = systemPrompt,
        systemMode = systemMode,
        thinkingEnabled = thinkingEnabled,
        effort = effort,
        thinking = thinking,
        messages = buildList {
            if (withReminder) add(WireMessage.text(WireMessage.ROLE_REMINDER, "2026-11-17,星期一,浙江,App,中文"))
            add(WireMessage.text(WireMessage.ROLE_USER, "你好"))
            add(WireMessage.text(WireMessage.ROLE_ASSISTANT, "你好，有什么可以帮你？"))
            add(WireMessage.text(WireMessage.ROLE_USER, "写一句诗"))
        },
    )

    @Test
    fun `地址归一三种写法`() {
        assertEquals("https://x/v1/chat/completions", OpenAiChatProtocol.endpoint("https://x"))
        assertEquals("https://x/v1/chat/completions", OpenAiChatProtocol.endpoint("https://x/v1"))
        assertEquals("https://x/v1/chat/completions", OpenAiChatProtocol.endpoint("https://x/v1/"))
        assertEquals("https://x/gw/v1/chat/completions", OpenAiChatProtocol.endpoint("https://x/gw"))
        assertEquals("https://x/gw/v4/chat/completions", OpenAiChatProtocol.endpoint("https://x/gw/v4/"))
    }

    @Test
    fun `请求体只含文本字段`() {
        val prepared = OpenAiChatProtocol.build(
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-test",
            command = command(),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject

        assertEquals("gpt-4o-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, (body["stream_options"] as JsonObject)["include_usage"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("medium", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(body["tools"])
        assertNull(body["safety_check"])
        assertNull(body["title"])
        assertEquals(ProviderProtocol.OPENAI_CHAT, prepared.protocol)
    }

    @Test
    fun `声明不发送时不带 reasoning_effort`() {
        val prepared = OpenAiChatProtocol.build(
            baseUrl = "https://x/v1",
            apiKey = "k",
            command = command(thinking = ThinkingMode.NONE),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull("模型没声明思考参数就不该发", body["reasoning_effort"])
    }

    @Test
    fun `reminder 拼进用户消息正文且不进 system`() {
        val prepared = OpenAiChatProtocol.build("https://x/v1", "k", command())
        val messages = (Json.parseToJsonElement(prepared.bodyJson) as JsonObject)["messages"]!!.jsonArray

        val roles = messages.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertFalse("latest_reminder 不是 OpenAI 的合法角色", roles.contains("latest_reminder"))
        assertEquals(listOf("system", "user", "assistant", "user"), roles)

        // system 只有用户自己那段：日期放在这里等于每天把整段请求的开头改一次
        val system = messages.first().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("你是 TinyChat 的助手。", system)

        // 元信息挪到下一条用户消息的最前面，位置语义与官方链路一致
        val firstUser = messages[1].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("2026-11-17,星期一,浙江,App,中文\n\n你好", firstUser)
    }

    @Test
    fun `APPEND 不拼 DeepSeek 官方句`() {
        val prepared = OpenAiChatProtocol.build(
            "https://x/v1",
            "k",
            command(systemPrompt = "你是一只猫。", systemMode = PromptMode.APPEND, withReminder = false),
        )
        val messages = (Json.parseToJsonElement(prepared.bodyJson) as JsonObject)["messages"]!!.jsonArray
        val system = messages.first().jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("你是一只猫。", system)
        assertFalse(system.contains("DeepSeek"))
    }

    @Test
    fun `CLEAR 且没有 reminder 时不发 system`() {
        val prepared = OpenAiChatProtocol.build(
            "https://x/v1",
            "k",
            command(systemMode = PromptMode.CLEAR, withReminder = false),
        )
        val messages = (Json.parseToJsonElement(prepared.bodyJson) as JsonObject)["messages"]!!.jsonArray
        assertTrue(messages.none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
    }

    @Test
    fun `不支持强度时不发 reasoning_effort`() {
        val prepared = OpenAiChatProtocol.build(
            "https://x/v1",
            "k",
            command(capabilities = Capabilities(thinking = true, effort = false)),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `思考关掉时不发 reasoning_effort`() {
        val prepared = OpenAiChatProtocol.build("https://x/v1", "k", command(thinkingEnabled = false))
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `降级后不带 stream_options`() {
        val prepared = OpenAiChatProtocol.build("https://x/v1", "k", command(), includeUsage = false)
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull(body["stream_options"])
    }

    @Test
    fun `鉴权走 Bearer 并带额外头`() {
        val prepared = OpenAiChatProtocol.build(
            baseUrl = "https://x/v1",
            apiKey = "sk-test",
            command = command(),
            extraHeaders = mapOf("x-opencode-session" to "abc", "User-Agent" to "TinyChat/1.0"),
        )
        assertEquals("Bearer sk-test", prepared.headers["Authorization"])
        assertEquals("abc", prepared.headers["x-opencode-session"])
        assertEquals("TinyChat/1.0", prepared.headers["User-Agent"])
    }

    @Test
    fun `OpenCode Go 走 Zen 域并带会话头`() {
        val provider = KnownProviders.openCodeGo
        val prepared = ChatProtocols.build(
            provider = provider,
            apiKey = "go-key",
            command = command(model = "zai-org/GLM-5.3"),
            extraHeaders = ChatProtocols.extraHeaders(provider, "session-1"),
        )

        assertEquals("https://opencode.ai/zen/go/v1/chat/completions", prepared.url)
        assertEquals("Bearer go-key", prepared.headers["Authorization"])
        val marker = prepared.headers["x-opencode-session"]!!
        assertTrue(marker.length <= 64)
        assertEquals(marker, ChatProtocols.extraHeaders(provider, "session-1")["x-opencode-session"])
        assertTrue(prepared.headers["User-Agent"]!!.startsWith("TinyChat/"))
        assertEquals(ProviderKind.OPENCODE_GO, prepared.providerKind)
    }

    @Test
    fun `不支持 stream_options 的服务商不再发`() {
        val provider = KnownProviders.openCodeGo.copy(meta = mapOf(ProviderMeta.NO_STREAM_OPTIONS to "1"))
        val prepared = ChatProtocols.build(provider, "k", command())
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull(body["stream_options"])
    }

    @Test
    fun `会话头按会话稳定且不等于明文`() {
        val a = ChatProtocols.sessionMarker("session-1")
        val b = ChatProtocols.sessionMarker("session-2")
        assertEquals(a, ChatProtocols.sessionMarker("session-1"))
        assertTrue(a != b)
        assertFalse(a.contains("session-1"))
        assertEquals(64, a.length)
    }

    @Test
    fun `Anthropic 兼容协议不带官方扩展字段`() {
        val prepared = ChatProtocols.build(
            provider = KnownProviders.genericOpenAiCompatible(
                id = "acme",
                displayName = "Acme",
                baseUrl = "https://acme.example",
                modelId = "claude-x",
                protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            ),
            apiKey = "k",
            command = command(model = "claude-x"),
        )
        assertEquals("https://acme.example/v1/messages", prepared.url)
        assertEquals("k", prepared.headers["x-api-key"])
        assertEquals("2023-06-01", prepared.headers["anthropic-version"])

        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        assertNull(body["tools"])
        assertNull(body["thinking"])
        assertNull(body["title"])
        assertNull(body["safety_check"])
        // Anthropic Messages 必填 max_tokens；默认 8192
        assertEquals(8_192, body["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `Anthropic 思考预算映射成 budget_tokens`() {
        val prepared = ChatProtocols.build(
            provider = KnownProviders.genericOpenAiCompatible(
                id = "acme",
                displayName = "Acme",
                baseUrl = "https://acme.example",
                modelId = "claude-x",
                protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            ),
            apiKey = "k",
            command = command(
                model = "claude-x",
                effort = ThinkingEffort.HIGH,
                thinking = ThinkingMode.BUDGET,
            ),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        val thinking = body["thinking"] as JsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(4_096, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertTrue(
            "budget 必须小于 max_tokens",
            body["max_tokens"]!!.jsonPrimitive.int > 4_096,
        )
    }

    // —— 决策 53：第三方视觉（图片内联，只走 OpenAI 兼容） ——

    @Test
    fun `带图片的消息用内容数组，纯文本消息仍是字符串`() {
        val imageUri = "data:image/jpeg;base64,AAA"
        val messages = listOf(
            WireMessage.text(WireMessage.ROLE_USER, "看看这张图"),
            WireMessage.blocks(
                WireMessage.ROLE_USER,
                listOf(
                    ContextRebuilder.openAiImageBlock(imageUri),
                    buildJsonObject {
                        put("type", "text")
                        put("text", "这是什么")
                    },
                ),
            ),
        )
        val prepared = OpenAiChatProtocol.build(
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-test",
            command = command(withReminder = false).copy(messages = messages),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        // 第 0 条是 system，所以按 role 取用户消息
        val users = body["messages"]!!.jsonArray
            .filter { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }

        assertTrue("没有图片的那条仍然是字符串", users[0].jsonObject["content"] is JsonPrimitive)
        val content = users[1].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            imageUri,
            (content[0].jsonObject["image_url"] as JsonObject)["url"]!!.jsonPrimitive.content,
        )
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)    }

    @Test
    fun `带图片时元信息拼进 text 元素而不是丢掉`() {
        val messages = listOf(
            WireMessage.text(WireMessage.ROLE_REMINDER, "2026-11-17,星期一,浙江,App,中文"),
            WireMessage.blocks(
                WireMessage.ROLE_USER,
                listOf(ContextRebuilder.openAiImageBlock("data:image/jpeg;base64,AAA")),
            ),
        )
        val prepared = OpenAiChatProtocol.build(
            baseUrl = "https://gateway.example/v1",
            apiKey = "sk-test",
            command = command(withReminder = false).copy(messages = messages),
        )
        val body = Json.parseToJsonElement(prepared.bodyJson) as JsonObject
        val content = (body["messages"] as JsonArray)
            .first { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
            .jsonObject["content"]!!
            .jsonArray

        // 只有图、没有正文：仍要补一个 text 元素把元信息带上（图片在前、文本在后）
        assertEquals(2, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val text = content[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("2026-11-17"))
    }
}
