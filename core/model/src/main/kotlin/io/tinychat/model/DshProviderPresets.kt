package io.tinychat.model

/**
 * API-key routes from the DeepSeek Harness 0.2.1-rc.3 model catalog that use
 * protocols TinyChat supports. These templates are applied only when selected.
 */
internal object DshProviderPresets {
    private val kimiModels = listOf(
        model("kimi-k3", "Kimi K3", 1_048_576, 1_048_576, vision = true, reasoning = true),
    )

    private val miniMaxModels = listOf(
        model("MiniMax-M3", "MiniMax M3", 1_000_000, 512_000, vision = true),
    )

    private val glmModels = listOf(
        model("glm-5.3-flash", "GLM-5.3-Flash", 1_000_000, 131_072, vision = true, reasoning = true),
        model("glm-5.3", "GLM-5.3", 1_000_000, 131_072, reasoning = true),
    )

    private val qwenModels = listOf(
        model("qwen3.8-flash", "Qwen3.8 Flash", 1_000_000, 131_072, vision = true, reasoning = true),
        model("qwen3.8-max", "Qwen3.8 Max", 1_000_000, 131_072, vision = true, reasoning = true),
    )

    val entries = listOf(
        preset(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            listOf(
                model("openai/gpt-4.1-mini", "GPT-4.1 Mini", 1_047_576, 32_768, vision = true),
                model("openai/gpt-5-mini", "GPT-5 Mini", 400_000, 128_000, vision = true, reasoning = true),
            ),
        ),
        preset(
            "opencode", "OpenCode Zen", "https://opencode.ai/zen/v1",
            listOf(
                model("glm-5.3-flash", "GLM-5.3-Flash", 1_000_000, 131_072, vision = true, reasoning = true),
                model("minimax-m3", "MiniMax M3", 512_000, 128_000, vision = true, reasoning = true),
            ),
        ),
        preset(
            "anthropic", "Anthropic", "https://api.anthropic.com",
            listOf(
                model(
                    "claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000,
                    vision = true, documents = true, reasoning = true, thinking = ThinkingMode.BUDGET,
                ),
                model(
                    "claude-sonnet-4-6", "Claude Sonnet 4.6", 1_000_000, 128_000,
                    vision = true, documents = true,
                ),
            ),
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        ),
        preset("moonshotai-cn", "Kimi (CN)", "https://api.moonshot.cn/v1", kimiModels),
        preset("moonshotai", "Kimi (Global)", "https://api.moonshot.ai/v1", kimiModels),
        preset(
            "minimax-cn", "MiniMax (CN)", "https://api.minimaxi.com/anthropic", miniMaxModels,
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        ),
        preset(
            "minimax", "MiniMax (Global)", "https://api.minimax.io/anthropic", miniMaxModels,
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        ),
        preset("zai-coding-cn", "GLM Coding Plan (CN)", "https://open.bigmodel.cn/api/coding/paas/v4", glmModels),
        preset("zai", "Z.ai Coding Plan", "https://api.z.ai/api/coding/paas/v4", glmModels),
        preset(
            "qwen-token-plan-cn", "Qwen Token Plan (CN)",
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", qwenModels,
        ),
        preset(
            "qwen-token-plan", "Qwen Token Plan",
            "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1", qwenModels,
        ),
        preset(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            listOf(
                model("llama-3.3-70b-versatile", "Llama 3.3 70B", 131_072, 32_768),
                model(
                    "openai/gpt-oss-120b", "GPT OSS 120B", 131_072, 65_536,
                    reasoning = true, thinking = ThinkingMode.EFFORT,
                ),
            ),
        ),
    )

    private fun preset(
        id: String,
        name: String,
        baseUrl: String,
        models: List<ModelInfo>,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT,
    ): Provider = Provider(
        id = id,
        kind = ProviderKind.OPENAI_COMPATIBLE,
        displayName = name,
        baseUrl = baseUrl,
        models = models,
        protocol = protocol,
    )

    private fun model(
        id: String,
        name: String,
        context: Int,
        output: Int,
        vision: Boolean = false,
        documents: Boolean = false,
        reasoning: Boolean = false,
        thinking: ThinkingMode = ThinkingMode.NONE,
    ): ModelInfo = ModelInfo(
        id = id,
        displayName = name,
        contextWindow = context,
        maxOutputTokens = output,
        capabilities = Capabilities(
            imageInput = vision,
            fileUpload = documents,
            thinking = thinking != ThinkingMode.NONE,
            effort = thinking != ThinkingMode.NONE,
            reasoning = reasoning,
        ),
        thinking = thinking,
    )
}
