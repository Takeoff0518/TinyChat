package io.tinychat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.tinychat.data.archive.ChatArchive
import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.AttachmentUploader
import io.tinychat.data.chat.ChatRepository
import io.tinychat.data.db.DatabaseFactory
import io.tinychat.data.prompt.PromptPresetStore
import io.tinychat.data.provider.ProviderStore
import io.tinychat.data.settings.KeystoreSecretStore
import io.tinychat.data.settings.UserSettings
import io.tinychat.feature.chat.voice.VoiceController
import io.tinychat.model.DiscoveredModel
import io.tinychat.model.Provider
import io.tinychat.model.ProviderCheck
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderMeta
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.QuotaSnapshot
import io.tinychat.model.SearchResult
import io.tinychat.model.WebSearchCredentials
import io.tinychat.model.WebSearchProvider
import io.tinychat.model.prompt.PromptMeta
import io.tinychat.net.account.AccountClient
import io.tinychat.net.account.KeyCheck
import io.tinychat.net.account.QuotaClient
import io.tinychat.net.search.AnySearchClient
import io.tinychat.net.search.DeepSeekWebSearchClient
import io.tinychat.net.search.TavilySearchClient
import io.tinychat.net.transport.OkHttpChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * 手写依赖容器（决策 21）。
 *
 * 凭据（M7-6 / M8-6）：每家服务商一把 Key，存 Keystore 加密后的 DataStore，
 * 通过 [apiKeyFor] 交给聊天仓库按会话的服务商取用；[apiKey] 只保留 DeepSeek 官方那一把，
 * 供语音与余额这类官方专属接口使用。
 */
class AppContainer(context: Context) {

    private val app = context.applicationContext

    /** 只用于凭据读写这类一次性后台任务，跟随进程生命周期。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database = DatabaseFactory.create(app)

    private val transport = OkHttpChatTransport()
    private val tavily = TavilySearchClient()
    private val anySearch = AnySearchClient()
    private val deepSeekSearch = DeepSeekWebSearchClient()

    /** 发请求前的连通性预检：离线直接判失败，不用等连接超时 */
    private val isOnline: () -> Boolean = {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** 附件落盘（M4）：选图/选文件后先把元信息与缩略图准备好 */
    val attachmentStore = AttachmentStore(app)

    /** 导入/导出（M6）：v3 兼容包 */
    val archive = ChatArchive(app, database)

    /** 本地设置（M5 起用它）：自动朗读、朗读音色、服务商与模型选择 */
    val userSettings = UserSettings(app)

    /** 语音（M5）：录音、频谱、ASR、TTS */
    val voiceController = VoiceController(app)

    /** 账户接口（M7/M8-6）：验 Key、查余额、拉模型目录 */
    val account = AccountClient()

    /** 订阅额度（M8-6）：OpenCode Go 的三窗口 usage */
    private val quotaClient = QuotaClient()

    /** 系统提示词（决策 45）：开关 + 用户预设，给新会话拼快照 */
    val promptStore = PromptPresetStore.create(
        database = database,
        selected = userSettings.promptSelectedId,
        selectPreset = { userSettings.setPromptSelectedId(it) },
        includeOfficial = userSettings.promptIncludeOfficial,
        setIncludeOfficial = { userSettings.setPromptIncludeOfficial(it) },
        migrated = userSettings.promptMigrated,
        markMigrated = { userSettings.markPromptMigrated() },
    )

    /** 服务商配置（M8-6）：列表、增删改、校验状态 */
    val providerStore = ProviderStore.create(database)

    /** 用户元信息开关的同步快照（仓库在 IO 线程按需读，不阻塞） */
    private val reminderEnabled = MutableStateFlow(true)

    /** 提示词变量的同步快照（所在地/语言），同样按需读 */
    private val promptMeta = MutableStateFlow(PromptMeta())

    private val _apiKey = MutableStateFlow("")

    /** DeepSeek 官方那一把 Key；空串 = 未配置。语音与余额用它。 */
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _keyedProviders = MutableStateFlow<Set<String>>(emptySet())

    /** 哪些服务商已经存了 Key；服务商目录或凭据变化时刷新，UI 用它判断「未配置」 */
    val keyedProviders: StateFlow<Set<String>> = _keyedProviders.asStateFlow()

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())

    /** 服务商目录（DB 为准）：列表页、模型选择器都读它 */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _activeProviderId = MutableStateFlow(DEEPSEEK_ID)

    /** 新会话默认服务商；聊天页选完模型后写回 */
    val activeProviderId: StateFlow<String> = _activeProviderId.asStateFlow()

    /** Keystore 加密存储（M7-6）；按服务商 id 读写。 */
    private val secrets = KeystoreSecretStore(app)

    private val webSearchSettingsLock = Mutex()
    val webSearchProvider = userSettings.webSearchProvider
        .stateIn(scope, SharingStarted.Eagerly, WebSearchProvider.DEEPSEEK)

    private val savedWebSearchKeys = combine(WebSearchProvider.entries.map { provider ->
        secrets.observeHas(provider.secretId)
            .catch { emit(false) }
            .map { present -> provider.takeIf { present } }
    }) { entries -> entries.filterNotNull().toSet() }

    val webSearchCredentials = combine(savedWebSearchKeys, providers, keyedProviders) { keys, catalog, keyed ->
        WebSearchCredentials(
            savedKeys = keys,
            canReuseDeepSeekKey = catalog.any { it.id in keyed && it.usesOfficialDeepSeekEndpoint() },
        )
    }.stateIn(scope, SharingStarted.Eagerly, WebSearchCredentials())

    init {
        scope.launch {
            userSettings.reminderEnabled.collect { reminderEnabled.value = it }
        }
        scope.launch {
            combine(userSettings.promptLocation, userSettings.promptLanguage) { location, language ->
                PromptMeta(location = location, language = language)
            }.collect { promptMeta.value = it }
        }
        scope.launch {
            // v1 的内置预设与组合方式迁到 v2 的「开关 + 用户预设」；失败不拦启动
            runCatching { promptStore.migrateIfNeeded() }
        }
        scope.launch {
            // M7-6 的单 Key 迁到 deepseek 名下（M8-6）
            runCatching { secrets.migrateLegacy(DEEPSEEK_ID) }
            // 只在**第一次启动**播种（决策 48）：任何服务商都能删，删空之后不要再塞回来一个没要的
            if (!userSettings.providersSeeded.first()) {
                runCatching { providerStore.ensureSeeded() }
                runCatching { userSettings.markProvidersSeeded() }
            }
            // 仅本机联调便利：把构建期注入的 Key 播种一次。正式版这里不该有内容。
            val seeded = BuildConfig.DEV_API_KEY
            if (seeded.isNotBlank() && providerStore.byId(DEEPSEEK_ID) != null &&
                runCatching { secrets.read(DEEPSEEK_ID) }.getOrNull().isNullOrBlank()
            ) {
                runCatching { secrets.write(DEEPSEEK_ID, seeded) }
            }
            refreshCredentials()
        }
        scope.launch {
            providerStore.observe().collect { list ->
                _providers.value = list
                refreshCredentials()
            }
        }
        scope.launch {
            userSettings.activeProviderId.collect { _activeProviderId.value = it }
        }
    }

    /**
     * 凭据快照：哪些服务商有 Key，以及「官方那一把」（语音与余额用）。
     *
     * 官方可以有多个实例（决策 48），取第一个配了 Key 的当语音/余额的入口；
     * 会话自己的服务商是官方时，聊天页会优先用那家的 Key。
     */
    private suspend fun refreshCredentials() {
        val providers = providerStore.all()
        val keyed = providers
            .mapNotNull { provider -> apiKeyFor(provider.id)?.takeIf { it.isNotBlank() }?.let { provider.id } }
            .toSet()
        _keyedProviders.value = keyed
        _apiKey.value = providers
            .firstOrNull { it.kind == ProviderKind.DEEPSEEK_OFFICIAL && it.id in keyed }
            ?.let { apiKeyFor(it.id) }
            .orEmpty()
    }

    suspend fun apiKeyFor(providerId: String): String? =
        runCatching { secrets.read(providerId) }.getOrNull()

    /** 保存某家的 Key；传空白等价于清除。 */
    suspend fun saveProviderKey(providerId: String, key: String) {
        secrets.write(providerId, key.trim())
        refreshCredentials()
    }

    suspend fun clearProviderKey(providerId: String) {
        secrets.clear(providerId)
        refreshCredentials()
    }

    suspend fun saveWebSearchSettings(provider: WebSearchProvider, keyChanges: Map<WebSearchProvider, String>) {
        webSearchSettingsLock.withLock {
            if (provider.needsOwnKey) {
                val hasKey = keyChanges[provider]?.isNotBlank() ?: secrets.has(provider.secretId)
                require(hasKey) { "需要配置 ${provider.label} API Key" }
            }
            keyChanges.filterValues { it.isNotBlank() }.forEach { (option, key) ->
                secrets.write(option.secretId, key.trim())
            }
            userSettings.setWebSearchProvider(provider)
            keyChanges.filterValues { it.isBlank() }.keys.forEach { secrets.clear(it.secretId) }
        }
    }

    private suspend fun searchWeb(provider: WebSearchProvider, query: String): List<SearchResult> {
        val key = secrets.read(provider.secretId) ?: if (provider == WebSearchProvider.DEEPSEEK) {
            providers.value.firstNotNullOfOrNull { candidate ->
                if (candidate.usesOfficialDeepSeekEndpoint()) apiKeyFor(candidate.id) else null
            }
        } else null
        // AnySearch 的公开接口免鉴权：没填 Key 也能搜，不要把"没 Key"当成不可用。
        if (provider != WebSearchProvider.ANYSEARCH) {
            check(!key.isNullOrBlank()) { "尚未配置 ${provider.label} API Key" }
        }
        return when (provider) {
            WebSearchProvider.DEEPSEEK -> deepSeekSearch.search(key.orEmpty(), query)
            WebSearchProvider.TAVILY -> tavily.search(key.orEmpty(), query)
            WebSearchProvider.ANYSEARCH -> anySearch.search(key, query)
        }
    }

    /** 保存服务商配置与（可选的）新 Key；`newKey == null` 表示沿用已存密钥。 */
    suspend fun saveProvider(provider: Provider, newKey: String?) {
        if (newKey != null) saveProviderKey(provider.id, newKey)
        providerStore.save(provider)
        refreshCredentials()
    }

    suspend fun deleteProvider(providerId: String) {
        providerStore.delete(providerId)
        secrets.clear(providerId)
        refreshCredentials()
    }

    /**
     * 校验某个服务商：优先用传入的候选 Key（还没保存时），否则用已存的。
     * 结论会写回 `providers.check_status`，列表页的状态点直接读它。
     */
    suspend fun checkProvider(provider: Provider, candidateKey: String? = null): String? {
        val key = candidateKey?.trim()?.takeIf { it.isNotEmpty() } ?: apiKeyFor(provider.id)
        if (key.isNullOrBlank()) {
            providerStore.setCheck(provider.id, ProviderCheck.UNKNOWN, null, System.currentTimeMillis())
            return "尚未配置 API Key"
        }
        val message = when (val result = account.checkKey(provider, key)) {
            KeyCheck.Ok -> null
            KeyCheck.Invalid -> "Key 无效"
            is KeyCheck.Failed -> result.message
        }
        // 网络不通/服务端 5xx 不代表 Key 有问题：标成「未校验」，别把点染红
        val status = when {
            message == null -> ProviderCheck.OK
            message.startsWith("网络不通") || message.startsWith("服务端异常") -> ProviderCheck.UNKNOWN
            else -> ProviderCheck.FAILED
        }
        providerStore.setCheck(
            id = provider.id,
            check = status,
            message = message,
            at = System.currentTimeMillis(),
        )
        return message
    }

    /** 拉模型目录和服务端公布的限制。 */
    suspend fun fetchModels(provider: Provider, candidateKey: String? = null): List<DiscoveredModel> {
        val key = candidateKey?.trim()?.takeIf { it.isNotEmpty() } ?: apiKeyFor(provider.id)
            ?: throw IllegalStateException("请先填写 API Key")
        return account.models(provider, key)
    }

    /** 订阅额度（M8-6）：只有 OpenCode Go 有；失败返回 null，UI 隐藏整块。 */
    suspend fun quotaFor(provider: Provider): QuotaSnapshot? {
        if (provider.kind != ProviderKind.OPENCODE_GO) return null
        val key = apiKeyFor(provider.id)?.takeIf { it.isNotBlank() } ?: return null
        val zenBase = provider.meta[ProviderMeta.ZEN_BASE] ?: return null
        return runCatching { quotaClient.openCodeGo(zenBase, key) }.getOrNull()
    }

    val repository = ChatRepository(
        db = database,
        transport = transport,
        isOnline = isOnline,
        attachmentStore = attachmentStore,
        uploader = AttachmentUploader(),
        reminderEnabled = { reminderEnabled.value },
        promptSnapshot = { promptStore.snapshotForNewSession() },
        promptMeta = { promptMeta.value },
        providerStore = providerStore,
        apiKeyFor = { id -> apiKeyFor(id) },
        webSearchProvider = { userSettings.webSearchProvider.first() },
        webSearch = ::searchWeb,
    )

    private companion object {
        const val DEEPSEEK_ID = "deepseek"
    }
}

private val WebSearchProvider.secretId: String get() = "search.$wire"

private fun Provider.usesOfficialDeepSeekEndpoint(): Boolean {
    if (protocol != ProviderProtocol.DEEPSEEK_MESSAGES) return false
    val url = runCatching { URI(baseUrl) }.getOrNull() ?: return false
    return url.scheme.equals("https", ignoreCase = true) &&
        url.host.equals("api.deepseek.com", ignoreCase = true)
}
