package io.tinychat.model

/**
 * 联网搜索的服务商。
 *
 * AnySearch 的公开接口（`POST /v1/search`）不要求鉴权，官方示例里连 `Authorization` 都没有；
 * Tavily 必须带自己的 Key；DeepSeek 可以复用聊天服务里的官方 Key。
 */
enum class WebSearchProvider(val wire: String, val label: String) {
    DEEPSEEK("deepseek", "DeepSeek"),
    TAVILY("tavily", "Tavily"),
    ANYSEARCH("anysearch", "AnySearch"),
    ;

    /** 这一家是否必须由用户自己填 Key（AnySearch 免 Key，DeepSeek 可复用官方 Key）。 */
    val needsOwnKey: Boolean get() = this == TAVILY

    /** DeepSeek 的内置搜索：只有官方协议且模型声明支持时才有。 */
    fun usesBuiltInSearch(protocol: ProviderProtocol?, modelSupportsSearch: Boolean): Boolean =
        this == DEEPSEEK && protocol == ProviderProtocol.DEEPSEEK_MESSAGES && modelSupportsSearch

    companion object {
        fun fromWire(value: String?): WebSearchProvider = entries.firstOrNull { it.wire == value } ?: DEEPSEEK
    }
}

data class WebSearchCredentials(
    val savedKeys: Set<WebSearchProvider> = emptySet(),
    val canReuseDeepSeekKey: Boolean = false,
) {
    fun hasKey(provider: WebSearchProvider): Boolean = when {
        provider == WebSearchProvider.ANYSEARCH -> true
        provider in savedKeys -> true
        provider == WebSearchProvider.DEEPSEEK && canReuseDeepSeekKey -> true
        else -> false
    }
}
