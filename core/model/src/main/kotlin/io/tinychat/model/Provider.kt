package io.tinychat.model

/** 服务商类型。OpenCode Go 是独立 kind：它有自己的会话头与额度接口，不能当通用兼容用。 */
enum class ProviderKind {
    DEEPSEEK_OFFICIAL,
    OPENCODE_GO,
    OPENAI_COMPATIBLE,
}

/**
 * 线上协议。**能力位决定发什么，协议决定往哪发**（02 §4.3 的原则）。
 *
 * - `DEEPSEEK_MESSAGES`：官方 `/anthropic/v1/messages`，带搜索/文件/标题等扩展字段；
 * - `ANTHROPIC_MESSAGES`：通用 Anthropic 兼容 `/v1/messages`，没有扩展字段；
 * - `OPENAI_CHAT`：`/v1/chat/completions`，降级链路（标题本地生成）。
 */
enum class ProviderProtocol(val wire: String) {
    DEEPSEEK_MESSAGES("deepseek_messages"),
    ANTHROPIC_MESSAGES("anthropic_messages"),
    OPENAI_CHAT("openai_chat"),
    ;

    companion object {
        fun fromWire(value: String?): ProviderProtocol =
            entries.firstOrNull { it.wire == value } ?: DEEPSEEK_MESSAGES
    }
}

/** 服务商最近一次校验的结果（列表页状态点用，落库）。 */
enum class ProviderCheck(val wire: String) {
    OK("ok"),
    FAILED("failed"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): ProviderCheck =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/**
 * 第三方模型的「思考」参数形态（M8-1 收尾）。
 *
 * 各家的字段不通用：OpenAI 系用 `reasoning_effort`，Anthropic 系用 `thinking.budget_tokens`，
 * 还有一堆私有参数。因此**按模型声明**，默认不发送——宁可没有，也不要发个被静默忽略的参数。
 */
enum class ThinkingMode(val wire: String, val title: String) {
    NONE("none", "不发送"),
    EFFORT("effort", "reasoning_effort"),
    BUDGET("budget", "thinking 预算"),
    ;

    companion object {
        fun fromWire(value: String?): ThinkingMode =
            entries.firstOrNull { it.wire == value } ?: NONE
    }
}

/**
 * 单个模型的能力矩阵。**全部由服务商+模型决定，不来自 UI 状态**。
 * 关键事实（docs/01 §4.2）：扩展能力（搜索/读链接/文件/图片/标题/安全）**只有 deepseek-flash 支持**，
 * deepseek-v4-pro 只支持思考与强度。
 */
@kotlinx.serialization.Serializable
data class Capabilities(
    val webSearch: Boolean = false,
    val readUrl: Boolean = false,
    val imageInput: Boolean = false,
    val fileUpload: Boolean = false,
    val serverSideTitle: Boolean = false,
    val safetyCheck: Boolean = false,
    val thinking: Boolean = true,
    val effort: Boolean = false,
    val tts: Boolean = false,
    /** 会下发 `reasoning_content`（OpenAI 风格思考）。只影响显示，不影响请求。 */
    val reasoning: Boolean = false,
) {
    /** 是否属于「扩展能力」模型：决定输入区是否露出搜索/附件入口。 */
    val supportsExtensions: Boolean get() = webSearch || readUrl || fileUpload || imageInput
}

@kotlinx.serialization.Serializable
data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val capabilities: Capabilities,
    /** 思考参数形态（M8-1）：默认不发送，自定义模型在服务商页里选 */
    val thinking: ThinkingMode = ThinkingMode.NONE,
)

/** Limits advertised by a provider. A missing field leaves the locally known limit untouched. */
data class DiscoveredModel(
    val id: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)

fun Provider.mergeDiscoveredModels(discovered: List<DiscoveredModel>): List<ModelInfo> {
    val existing = models.associateBy(ModelInfo::id)
    val remote = discovered.associateBy(DiscoveredModel::id)
    fun update(result: DiscoveredModel): ModelInfo {
        val saved = existing[result.id] ?: ModelInfo(
            id = result.id,
            displayName = result.id,
            contextWindow = 0,
            maxOutputTokens = 0,
            capabilities = Capabilities(thinking = false, reasoning = false),
        )
        return saved.copy(
            contextWindow = result.contextWindow ?: saved.contextWindow,
            maxOutputTokens = result.maxOutputTokens ?: saved.maxOutputTokens,
        )
    }

    if (kind != ProviderKind.OPENAI_COMPATIBLE) return remote.values.map(::update)
    return models.map { model -> remote[model.id]?.let(::update) ?: model } +
        remote.values.filter { it.id !in existing }.map(::update)
}

data class Provider(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val models: List<ModelInfo>,
    val supportsBalance: Boolean = false,
    val supportsTts: Boolean = false,
    val protocol: ProviderProtocol = ProviderProtocol.DEEPSEEK_MESSAGES,
    /** 内置（目录）服务商：DeepSeek 不可删，其余可删 */
    val builtin: Boolean = false,
    val check: ProviderCheck = ProviderCheck.UNKNOWN,
    /** 校验失败的一句话原因，列表页不显示，编辑页用 */
    val checkMessage: String? = null,
    /** 服务商专属配置：OpenCode 的 Zen 地址、UA 等；不参与导出 */
    val meta: Map<String, String> = emptyMap(),
) {
    val defaultModel: ModelInfo? get() = models.firstOrNull()
    fun model(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    /** 当前服务商能做什么：取某个模型的能力位，模型不在目录里则按“都不支持”处理。 */
    fun capabilities(modelId: String): Capabilities =
        model(modelId)?.capabilities ?: Capabilities(thinking = false, reasoning = false)
}

/**
 * 思考强度（Messages 协议走 output_config.effort，OpenAI 兼容走 reasoning_effort）。
 *
 * 显示名用厂商宣传里的英文原词（P 阶段，决策 50）：用户对 `Low / High / Max` 的直觉，
 * 来自模型厂商一直以来的写法；「低 / 高 / 最大」是第二个说法，只会多一层翻译。
 * 报文取值仍按协议映射（OpenAI 兼容链路把 Max 落到 `high`）。
 */
enum class ThinkingEffort(val wire: String, val displayName: String) {
    LOW("low", "Low"),
    HIGH("high", "High"),
    MAX("max", "Max"),
}

/** TTS 音色：只用官方两个（决策 3）。 */
enum class TtsVoice(val wire: String, val displayName: String) {
    STELLA("stella", "Stella"),
    TIDE("tide", "Tide"),
}

/**
 * 内置服务商与模型。**这里是能力矩阵的唯一真值**，
 * UI 与网络层都从这里取，不允许在别处再写一遍 if (model == "...")。
 */
object KnownProviders {

    val flash: ModelInfo = ModelInfo(
        id = "deepseek-flash",
        displayName = "DeepSeek V4.1 Flash",
        contextWindow = 1_000_000,
        maxOutputTokens = 384_000,
        capabilities = Capabilities(
            webSearch = true,
            readUrl = true,
            imageInput = true,
            fileUpload = true,
            serverSideTitle = true,
            safetyCheck = true,
            thinking = true,
            effort = true,
            tts = true,
        ),
    )

    val v4Pro: ModelInfo = ModelInfo(
        id = "deepseek-v4-pro",
        displayName = "DeepSeek V4 Pro",
        contextWindow = 1_000_000,
        maxOutputTokens = 384_000,
        capabilities = Capabilities(
            webSearch = false,
            readUrl = false,
            imageInput = false,
            fileUpload = false,
            serverSideTitle = false,
            safetyCheck = false,
            thinking = true,
            effort = true,
            tts = true,
        ),
    )

    val deepSeek: Provider = Provider(
        id = "deepseek",
        kind = ProviderKind.DEEPSEEK_OFFICIAL,
        displayName = "DeepSeek 官方",
        baseUrl = "https://api.deepseek.com",
        models = listOf(flash, v4Pro),
        supportsBalance = true,
        supportsTts = true,
        protocol = ProviderProtocol.DEEPSEEK_MESSAGES,
        builtin = true,
    )

    /** OpenCode Go：独立 kind，模型由 `/models` 动态拉取，额度由 `/usage` 读三窗口。 */
    val openCodeGo: Provider = Provider(
        id = "opencode-go",
        kind = ProviderKind.OPENCODE_GO,
        displayName = "OpenCode Go",
        baseUrl = "https://opencode.ai/inference/go",
        models = emptyList(),
        protocol = ProviderProtocol.OPENAI_CHAT,
        meta = mapOf(ProviderMeta.ZEN_BASE to "https://opencode.ai/zen/go"),
    )

    /** 通用 OpenAI 兼容服务商：一律走降级链路，扩展能力全部关闭。 */
    fun genericOpenAiCompatible(
        id: String,
        displayName: String,
        baseUrl: String,
        modelId: String,
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_CHAT,
    ): Provider = Provider(
        id = id,
        kind = ProviderKind.OPENAI_COMPATIBLE,
        displayName = displayName,
        baseUrl = baseUrl,
        protocol = protocol,
        models = listOf(
            ModelInfo(
                id = modelId,
                displayName = modelId,
                contextWindow = 128_000,
                maxOutputTokens = 8_192,
                capabilities = Capabilities(
                    webSearch = false,
                    readUrl = false,
                    imageInput = false,
                    fileUpload = false,
                    serverSideTitle = false,
                    safetyCheck = false,
                    thinking = false,
                    effort = false,
                    tts = false,
                ),
            ),
        ),
    )

    /** 目录服务商：添加页「第三方模型提供商」下拉里的选项。 */
    val catalog: List<Provider> = listOf(deepSeek, openCodeGo) + DshProviderPresets.entries
}

/** `Provider.meta` 的已知键。 */
object ProviderMeta {
    /** OpenCode Go 的 Zen 地址：`/v1/models`、`/v1/usage` 都在这个域下 */
    const val ZEN_BASE = "zen_base"

    /** 请求头里的 User-Agent（OpenCode 要求用自己的产品名，不伪装） */
    const val USER_AGENT = "user_agent"

    /** 该服务商不支持 `stream_options`：一次降级后记住，后续不再发 */
    const val NO_STREAM_OPTIONS = "no_stream_options"

    /** 该服务商拒绝思考参数（400）：一次降级后记住，后续不再发 */
    const val NO_EFFORT = "no_effort"
}

/** 内置 UA：官方要求显式标识客户端，且**禁止**伪装成 OpenCode CLI。 */
const val TINYCHAT_USER_AGENT = "TinyChat/1.0 (Android)"
