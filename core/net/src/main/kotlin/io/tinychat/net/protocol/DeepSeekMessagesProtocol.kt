package io.tinychat.net.protocol

import io.tinychat.model.Capabilities
import io.tinychat.model.PromptMode
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ThinkingMode
import io.tinychat.model.prompt.PromptSeeds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 一次请求的输入。**只依赖领域模型**，不碰 OkHttp，方便 golden JSON 快照测试。
 *
 * 字段顺序与 03 §6.1 的原始报文一致，序列化出来的 JSON 可逐字段对照。
 */
@Serializable
data class MessagesRequest(
    val model: String,
    /** 仅 replace/append 时出现；clear 或空串则整个字段省略 */
    val system: String? = null,
    val thinking: ThinkingFlag? = null,
    @SerialName("output_config") val outputConfig: OutputConfig? = null,
    /** Anthropic Messages 必填；DeepSeek 不传（其默认值够用） */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<ToolDecl>? = null,
    @SerialName("safety_check") val safetyCheck: SafetyCheck? = null,
    val title: TitleFlag? = null,
    val messages: List<WireMessage>,
    /** 恒为 true，且**不给默认值**：encodeDefaults=false 会把默认值省掉，stream 漏发就直接变非流式 */
    val stream: Boolean,
)

@Serializable
data class ThinkingFlag(
    val type: String,
    /** Anthropic `thinking.budget_tokens`；DeepSeek 只发 `type`，默认 null 会被省略 */
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
) {
    companion object {
        val ENABLED = ThinkingFlag("enabled")
        val DISABLED = ThinkingFlag("disabled")
    }
}

@Serializable
data class OutputConfig(val effort: String)

@Serializable
data class ToolDecl(val type: String, val name: String) {
    companion object {
        val READ_URL = ToolDecl("deepseek_read_url", "deepseek_read_url")
        val WEB_SEARCH = ToolDecl("deepseek_web_search", "deepseek_web_search")
    }
}

@Serializable
data class SafetyCheck(val level: String) {
    companion object {
        val HIGH = SafetyCheck("high")
    }
}

@Serializable
data class TitleFlag(val enabled: Boolean)

/**
 * 消息元素。`content` 用 [JsonElement] 而不是 String：
 * 后续附件（图片/文件块）要发数组，现在就把形状留开，避免 M4 再改协议层。
 */
@Serializable
data class WireMessage(val role: String, val content: JsonElement) {

    /**
     * 这条消息里有没有附件块（`document` / `image`）。
     * 有就得带 `anthropic-beta: files-api-2025-04-14`，否则服务端不认 `file_id`。
     */
    val hasAttachmentBlocks: Boolean
        get() = (content as? kotlinx.serialization.json.JsonArray)?.any { element ->
            val type = ((element as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            type == "document" || type == "image"
        } == true

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_REMINDER = "latest_reminder"

        fun text(role: String, text: String): WireMessage =
            WireMessage(role, JsonPrimitive(text))

        fun blocks(role: String, blocks: List<JsonObject>): WireMessage =
            WireMessage(role, kotlinx.serialization.json.JsonArray(blocks))
    }
}

/** 构建结果：URL + 头 + 已序列化的体。传输层只负责发出去。 */
data class PreparedRequest(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String,
    /** 决定用哪个流解码器（`ChatStreamRunner` 据此选 assembler） */
    val protocol: ProviderProtocol = ProviderProtocol.DEEPSEEK_MESSAGES,
    /** 传输层据此应用服务商的网络等待策略。 */
    val providerKind: ProviderKind? = null,
)

/**
 * 请求参数。能力位来自 [Capabilities]，**协议层不判断模型名**（02 §4.3 结尾的原则）。
 */
data class ChatCommand(
    val capabilities: Capabilities,
    val model: String,
    val systemPrompt: String? = null,
    val systemMode: PromptMode = PromptMode.REPLACE,
    val thinkingEnabled: Boolean = true,
    val effort: ThinkingEffort? = null,
    /** 第三方思考参数形态（M8-1）；DeepSeek 忽略，它只认 capabilities.effort */
    val thinking: ThinkingMode = ThinkingMode.NONE,
    /** 输出上限；Anthropic Messages 必填。 */
    val maxOutputTokens: Int = 8_192,
    val searchEnabled: Boolean = false,
    /** 仅会话首轮为 true；拿到标题后永久关掉（02 §5.3） */
    val includeTitle: Boolean = false,
    val messages: List<WireMessage>,
    /** 本轮含文件/图片块 → 需要 anthropic-beta 头 */
    val hasFileBlocks: Boolean = false,
    /** 压缩上下文时限制摘要长度，即使协议允许省略输出上限。 */
    val enforceOutputLimit: Boolean = false,
)

/**
 * DeepSeek Messages 协议构建器（主链路）。
 *
 * 与 OpenAI 兼容链路的关键差别都集中在 build() 里：端点、`x-api-key`、
 * `thinking`/`output_config`/`tools`/`safety_check`/`title` 这些扩展字段。
 */
object DeepSeekMessagesProtocol {

    const val MESSAGES_PATH = "/anthropic/v1/messages"
    const val FILES_BETA = "anthropic-beta: files-api-2025-04-14"

    /** 请求体序列化配置：省略 null，保证报文干净（与官方示例一致） */
    val bodyJson: Json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
    }

    fun build(
        baseUrl: String,
        apiKey: String,
        command: ChatCommand,
    ): PreparedRequest {
        require(command.model.isNotBlank()) { "model 不能为空" }
        require(command.messages.isNotEmpty()) { "messages 不能为空" }

        val request = MessagesRequest(
            model = command.model,
            system = resolveSystem(command),
            thinking = resolveThinking(command),
            outputConfig = resolveEffort(command),
            maxTokens = command.maxOutputTokens.takeIf { command.enforceOutputLimit && it > 0 },
            tools = resolveTools(command),
            safetyCheck = if (command.capabilities.safetyCheck) SafetyCheck.HIGH else null,
            title = if (command.capabilities.serverSideTitle && command.includeTitle) {
                TitleFlag(enabled = true)
            } else {
                null
            },
            messages = command.messages,
            stream = true,
        )
        return PreparedRequest(
            url = baseUrl.trimEnd('/') + MESSAGES_PATH,
            headers = buildMap {
                put("Content-Type", "application/json")
                put("x-api-key", apiKey)
                if (command.hasFileBlocks || command.messages.any { it.hasAttachmentBlocks }) {
                    put("anthropic-beta", "files-api-2025-04-14")
                }
            },
            bodyJson = bodyJson.encodeToString(MessagesRequest.serializer(), request),
        )
    }

    /**
     * system 的三种模式：
     * - `CLEAR` → 不发；
     * - `REPLACE` → 用快照；
     * - `APPEND` → 快照 + 官方默认句（chat-demo `store.ts:38`）。
     */
    private fun resolveSystem(command: ChatCommand): String? {
        val snapshot = command.systemPrompt?.takeIf { it.isNotBlank() }
        return when (command.systemMode) {
            PromptMode.CLEAR -> null
            PromptMode.REPLACE -> snapshot
            PromptMode.APPEND -> listOfNotNull(OFFICIAL_DEFAULT_SYSTEM, snapshot)
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }
        }
    }

    private fun resolveThinking(command: ChatCommand): ThinkingFlag? {
        if (!command.capabilities.thinking) return null
        return if (command.thinkingEnabled) ThinkingFlag.ENABLED else ThinkingFlag.DISABLED
    }

    /** effort 只在「支持强度 + 开着思考 + 有取值」时才有意义 */
    private fun resolveEffort(command: ChatCommand): OutputConfig? {
        val effort = command.effort ?: return null
        if (!command.capabilities.effort) return null
        if (!command.capabilities.thinking || !command.thinkingEnabled) return null
        return OutputConfig(effort.wire)
    }

    /**
     * 工具声明：`deepseek_read_url` 常开，`deepseek_web_search` 由开关控制（01 §4.2）。
     * 二者顺序与官方示例一致。
     */
    private fun resolveTools(command: ChatCommand): List<ToolDecl>? {
        val tools = buildList {
            if (command.capabilities.readUrl) add(ToolDecl.READ_URL)
            if (command.capabilities.webSearch && command.searchEnabled) add(ToolDecl.WEB_SEARCH)
        }
        return tools.ifEmpty { null }
    }

    /** 官方默认 system 句（一手证实：chat-demo `store.ts:38`）。正文真值在 [PromptSeeds]。 */
    const val OFFICIAL_DEFAULT_SYSTEM = PromptSeeds.OFFICIAL_SYSTEM

    /** 便于测试构造：把若干块拼成 user 消息 */
    fun userBlocks(vararg blocks: JsonObject): WireMessage =
        WireMessage.blocks(WireMessage.ROLE_USER, blocks.toList())

    /** 文件块（M4 用；形状先定死，避免协议层返工） */
    fun fileBlock(fileId: String): JsonObject = buildJsonObject {
        put("type", "document")
        put(
            "source",
            buildJsonObject {
                put("type", "file")
                put("file_id", fileId)
            },
        )
    }

    /** 图片块（M4 用） */
    fun imageBlock(fileId: String): JsonObject = buildJsonObject {
        put("type", "image")
        put(
            "source",
            buildJsonObject {
                put("type", "file")
                put("file_id", fileId)
            },
        )
    }
}
