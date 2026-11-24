package io.tinychat.net.protocol

import io.tinychat.model.Provider
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderMeta
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.TINYCHAT_USER_AGENT
import io.tinychat.net.MessageAssembler
import io.tinychat.net.OpenAiStreamAssembler
import io.tinychat.net.StreamAssembler
import java.security.MessageDigest

/**
 * 协议分发：把「服务商 + 命令」变成「可发送的请求」与「对应的流解码器」。
 *
 * 服务商专属的请求格式与地址在这里处理。
 */
object ChatProtocols {

    fun assembler(protocol: ProviderProtocol): StreamAssembler = when (protocol) {
        ProviderProtocol.OPENAI_CHAT -> OpenAiStreamAssembler()
        ProviderProtocol.DEEPSEEK_MESSAGES, ProviderProtocol.ANTHROPIC_MESSAGES -> MessageAssembler()
    }

    /** 该协议是否支持「继续生成」（Messages 的 `prefix` 预填充；OpenAI 没有等价物） */
    fun supportsContinuation(protocol: ProviderProtocol): Boolean =
        protocol != ProviderProtocol.OPENAI_CHAT

    /**
     * 构建请求。`extraHeaders` 由 [extraHeaders] 生成：OpenCode 的会话头必须与主请求同源，
     * 所以放在仓库里按 sessionId 派生，而不是在 UI 层随手拼。
     */
    fun build(
        provider: Provider,
        apiKey: String,
        command: ChatCommand,
        extraHeaders: Map<String, String> = emptyMap(),
    ): PreparedRequest = (when (provider.protocol) {
        ProviderProtocol.DEEPSEEK_MESSAGES ->
            DeepSeekMessagesProtocol.build(provider.baseUrl, apiKey, command)

        ProviderProtocol.ANTHROPIC_MESSAGES ->
            AnthropicMessagesProtocol.build(provider.baseUrl, apiKey, command, extraHeaders)

        ProviderProtocol.OPENAI_CHAT ->
            OpenAiChatProtocol.build(
                baseUrl = inferenceBase(provider),
                apiKey = apiKey,
                command = command,
                extraHeaders = extraHeaders,
                includeUsage = provider.meta[ProviderMeta.NO_STREAM_OPTIONS] != "1",
            )
    }).copy(providerKind = provider.kind)

    /** 已配置 Zen 地址的 OpenCode Go 会话继续走该兼容端点。 */
    private fun inferenceBase(provider: Provider): String =
        if (provider.kind == ProviderKind.OPENCODE_GO) {
            provider.meta[ProviderMeta.ZEN_BASE] ?: provider.baseUrl
        } else {
            provider.baseUrl
        }

    /**
     * 服务商要求的额外请求头。
     *
     * OpenCode Go：`x-opencode-session` 是硬要求（缺失 400 `MissingSessionID`），
     * 同一会话的所有派生请求必须同值；UA 必须是我们自己的，禁止伪装成 OpenCode CLI。
     */
    fun extraHeaders(provider: Provider, sessionId: String): Map<String, String> {
        if (provider.kind != ProviderKind.OPENCODE_GO) return emptyMap()
        return buildMap {
            put("x-opencode-session", sessionMarker(sessionId))
            put("User-Agent", provider.meta[ProviderMeta.USER_AGENT] ?: TINYCHAT_USER_AGENT)
        }
    }

    /** 按会话稳定的 ASCII 值，严格 ≤64 字符：sha256(sessionId) 的十六进制。 */
    fun sessionMarker(sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
