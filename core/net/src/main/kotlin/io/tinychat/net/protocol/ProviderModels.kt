package io.tinychat.net.protocol

import io.tinychat.model.Provider
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderMeta
import io.tinychat.model.ProviderProtocol
import okhttp3.Request

/**
 * 模型目录探测地址（M8-6）。
 *
 * 各家的 `/models` 不在同一层：
 * - 官方 DeepSeek：`https://api.deepseek.com/models`（没有 `/v1`）；
 * - OpenAI 兼容：`{base}/models`（未指定版本时默认 `/v1`）；
 * - OpenCode Go：`zen/go/v1/models`（对话在 inference 域，目录在 zen 域）。
 */
object ProviderModels {
    private const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"
    private const val OPENROUTER_KEY = "https://openrouter.ai/api/v1/key"

    fun url(provider: Provider): String = when (provider.protocol) {
        ProviderProtocol.DEEPSEEK_MESSAGES ->
            provider.baseUrl.trimEnd('/') + "/models"

        ProviderProtocol.OPENAI_CHAT -> {
            val base = if (provider.kind == ProviderKind.OPENCODE_GO) {
                provider.meta[ProviderMeta.ZEN_BASE] ?: provider.baseUrl
            } else {
                provider.baseUrl
            }
            "${OpenAiChatProtocol.apiBase(base)}/models"
        }

        ProviderProtocol.ANTHROPIC_MESSAGES -> {
            val trimmed = provider.baseUrl.trimEnd('/')
            if (trimmed.endsWith("/v1")) "$trimmed/models" else "$trimmed/v1/models"
        }
    }

    /** OpenRouter 的公开模型目录不用于校验密钥。 */
    fun keyCheckRequest(provider: Provider, apiKey: String): Request =
        when (val endpoint = url(provider)) {
            OPENROUTER_MODELS -> request(OPENROUTER_KEY, apiKey, ProviderProtocol.OPENAI_CHAT)
            else -> request(endpoint, apiKey, provider.protocol)
        }

    fun listRequest(provider: Provider, apiKey: String): Request =
        request(url(provider), apiKey, provider.protocol)

    private fun request(url: String, apiKey: String, protocol: ProviderProtocol): Request {
        val builder = Request.Builder().url(url)
        if (protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
            builder.header("x-api-key", apiKey)
            builder.header("anthropic-version", AnthropicMessagesProtocol.VERSION)
        } else {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.get().build()
    }
}
