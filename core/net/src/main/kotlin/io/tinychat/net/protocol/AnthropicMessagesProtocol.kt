package io.tinychat.net.protocol

import io.tinychat.model.PromptMode
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ThinkingMode
import io.tinychat.net.context.HistoryAttachment
import io.tinychat.net.context.documentTextBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 通用 Anthropic Messages 协议（第三方 `/v1/messages`）。
 *
 * 与官方 Messages 的差别：
 * - 路径是 `/v1/messages`（官方是 `/anthropic/v1/messages`）；
 * - 除 `x-api-key` 外要带 `anthropic-version`；
 * - 不发 DeepSeek 扩展 `tools` / `safety_check` / `title` / `output_config`；
 * - `APPEND` 模式不拼官方那句「该助手为 DeepSeek」——第三方服务商不该被冒名。
 */
object AnthropicMessagesProtocol {

    const val VERSION = "2023-06-01"
    private const val BASE64_MARKER = ";base64,"
    private const val PDF_TYPE = "application/pdf"
    private val imageMediaTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "API 地址不能为空" }
        return if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
    }

    fun build(
        baseUrl: String,
        apiKey: String,
        command: ChatCommand,
        extraHeaders: Map<String, String> = emptyMap(),
    ): PreparedRequest {
        require(command.model.isNotBlank()) { "model 不能为空" }
        require(command.messages.isNotEmpty()) { "messages 不能为空" }

        val maxTokens = command.maxOutputTokens.takeIf { it > 0 } ?: 8_192
        val budget = resolveBudget(command, maxTokens)
        val request = MessagesRequest(
            model = command.model,
            system = resolveSystem(command),
            thinking = budget?.let { ThinkingFlag("enabled", it) },
            outputConfig = null,
            maxTokens = maxTokens,
            tools = null,
            safetyCheck = null,
            title = null,
            messages = withReminders(command.messages),
            stream = true,
        )
        return PreparedRequest(
            url = endpoint(baseUrl),
            headers = buildMap {
                put("Content-Type", "application/json")
                put("x-api-key", apiKey)
                put("anthropic-version", VERSION)
                extraHeaders.forEach { (name, value) -> put(name, value) }
            },
            bodyJson = DeepSeekMessagesProtocol.bodyJson.encodeToString(MessagesRequest.serializer(), request),
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        )
    }

    internal fun acceptsAttachment(attachment: HistoryAttachment): Boolean =
        attachment.dataUri?.let { inlineMediaType(it, attachment.isImage) } != null ||
            (!attachment.isImage && !attachment.text.isNullOrBlank())

    internal fun attachmentBlock(attachment: HistoryAttachment): JsonObject? {
        attachment.documentTextBlock()?.let { return it }
        val uri = attachment.dataUri ?: return null
        val mediaType = inlineMediaType(uri, attachment.isImage) ?: return null
        return buildJsonObject {
            put("type", if (attachment.isImage) "image" else "document")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", mediaType)
                put("data", uri.substringAfter(BASE64_MARKER))
            })
            if (!attachment.isImage) attachment.fileName?.takeIf(String::isNotBlank)?.let { put("title", it) }
        }
    }

    private fun inlineMediaType(uri: String, isImage: Boolean): String? {
        if (!uri.startsWith("data:")) return null
        val marker = uri.indexOf(BASE64_MARKER)
        if (marker < 5 || marker + BASE64_MARKER.length >= uri.length) return null
        val type = uri.substring(5, marker)
        return type.takeIf { if (isImage) it in imageMediaTypes else it == PDF_TYPE }
    }

    /** Anthropic 只接受 user 和 assistant 角色：元信息并入接下来的用户消息。 */
    private fun withReminders(messages: List<WireMessage>): List<WireMessage> = buildList {
        var reminder: String? = null
        messages.forEach { message ->
            if (message.role == WireMessage.ROLE_REMINDER) {
                reminder = (message.content as? JsonPrimitive)?.contentOrNull
            } else {
                val prefix = reminder
                add(if (prefix.isNullOrBlank()) message else message.copy(content = withReminder(message.content, prefix)))
                reminder = null
            }
        }
    }

    private fun withReminder(content: JsonElement, reminder: String): JsonElement = when (content) {
        is JsonPrimitive -> JsonPrimitive(listOfNotNull(reminder, content.contentOrNull).joinToString("\n\n"))
        is JsonArray -> {
            val last = content.lastOrNull() as? JsonObject
            val lastIsText = (last?.get("type") as? JsonPrimitive)?.contentOrNull == "text"
            val text = (last?.get("text") as? JsonPrimitive)?.contentOrNull
            val reminderBlock = buildJsonObject {
                put("type", "text")
                put("text", if (lastIsText && !text.isNullOrBlank()) "$reminder\n\n$text" else reminder)
            }
            JsonArray((if (lastIsText) content.dropLast(1) else content) + reminderBlock)
        }
        else -> content
    }

    private fun resolveSystem(command: ChatCommand): String? {
        val snapshot = command.systemPrompt?.takeIf { it.isNotBlank() }
        return when (command.systemMode) {
            PromptMode.CLEAR -> null
            else -> snapshot
        }
    }

    /**
     * 思考预算：只有模型声明了「thinking 预算」且这一轮开着思考才发。
     * 三档映射 1024 / 4096 / 8192，并为答案留出 512 token。
     * 如果输出上限容不下最小思考预算，就省略思考配置。
     */
    private fun resolveBudget(command: ChatCommand, maxTokens: Int): Int? {
        if (!command.capabilities.effort || !command.thinkingEnabled) return null
        if (command.thinking != ThinkingMode.BUDGET) return null
        val ceiling = maxTokens - 512
        if (ceiling < 1_024) return null
        val desired = when (command.effort) {
            ThinkingEffort.LOW -> 1_024
            ThinkingEffort.HIGH -> 4_096
            ThinkingEffort.MAX -> 8_192
            null -> 4_096
        }
        return desired.coerceAtMost(ceiling)
    }
}
