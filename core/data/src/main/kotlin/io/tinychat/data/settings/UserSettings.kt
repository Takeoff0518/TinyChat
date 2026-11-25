package io.tinychat.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.tinychat.model.KnownProviders
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.TtsVoice
import io.tinychat.model.WebSearchProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 设置库：整个 app 只有这一份，DataStore 不允许同一个文件建两个实例。 */
private val Context.settingsDataStore by preferencesDataStore(name = "user_settings")

data class ChatDefaults(
    val providerId: String,
    val modelId: String,
    val effort: ThinkingEffort,
    val thinkingEnabled: Boolean,
    val searchEnabled: Boolean,
)

/**
 * 本地设置（M5 起用它，M8 的外观/字号也走这里）。
 *
 * 键：朗读开关与音色、用量行开关、用户元信息开关、当前系统提示词、
 * 用户所在地与语言（喂 `{{location}}` / `{{language}}`）。
 *
 * ⚠️ 刻意**不**把 API Key 放这里：它单独一份加密 DataStore（`credentials`，M7-6）。
 */
class UserSettings internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val autoSpeak: Flow<Boolean> = store.data.map { it[AUTO_SPEAK] ?: false }

    val ttsVoice: Flow<TtsVoice> = store.data.map { voiceOf(it[TTS_VOICE]) }

    /** 每轮回答下方的用量行（M7-3）；**默认开** */
    val showUsage: Flow<Boolean> = store.data.map { it[SHOW_USAGE] ?: true }

    /** 用户元信息（`latest_reminder`）开关（M7-5）；默认开，与官方一致 */
    val reminderEnabled: Flow<Boolean> = store.data.map { it[REMINDER] ?: true }

    /**
     * 思维链在思考结束后自动收起（决策 80）；**默认开**，与官方一致。
     *
     * 关掉之后思维链一直平铺在回答上方，历史上的消息进会话也是展开的。
     */
    val thinkingAutoCollapse: Flow<Boolean> = store.data.map { it[THINKING_AUTO_COLLAPSE] ?: true }

    /**
     * 当前选中的系统提示词预设（决策 45）。**null = 不使用自定义内容**。
     * 旧版本这个键存的是内置 id（`default` / `none` / …），迁移时会被改写或清掉。
     */
    val promptSelectedId: Flow<String?> = store.data.map { prefs ->
        prefs[PROMPT_PRESET]?.takeIf { it.isNotBlank() }
    }

    /** 是否同时发送官方那句提示词；默认开 */
    val promptIncludeOfficial: Flow<Boolean> = store.data.map { it[PROMPT_INCLUDE_OFFICIAL] ?: true }

    /** 提示词表结构与含义的迁移是否已经跑过（一次性） */
    val promptMigrated: Flow<Boolean> = store.data.map { it[PROMPT_MIGRATED] ?: false }

    /**
     * 服务商目录是否已经播种过一次（决策 48）。
     *
     * 不再用「表里有没有 deepseek 这一行」判断：所有服务商都可以删除，
     * 用户删空之后重启不该被塞回一个他没要的 DeepSeek。
     */
    val providersSeeded: Flow<Boolean> = store.data.map { it[PROVIDERS_SEEDED] ?: false }

    /** 用户所在地，喂给 `{{location}}` 与 `latest_reminder`；空 = 不填 */
    val promptLocation: Flow<String> = store.data.map { it[PROMPT_LOCATION] ?: "" }

    /** 用户常用语言，喂给 `{{language}}` 与 `latest_reminder` */
    val promptLanguage: Flow<String> = store.data.map { it[PROMPT_LANGUAGE] ?: "中文" }
    val appLanguage: Flow<String> = store.data.map { it[APP_LANGUAGE] ?: "zh-CN" }

    /** 外观：`system` / `light` / `dark`（M8-4） */
    val themeMode: Flow<String> = store.data.map { it[THEME_MODE] ?: "system" }

    /** 字体大小：1.0–1.3，只作用于正文（M8-4） */
    val fontScale: Flow<Float> = store.data.map { it[FONT_SCALE] ?: 1f }

    /** 当前服务商（M8-6）：新会话默认用它，由聊天页的模型选择器写入 */
    val activeProviderId: Flow<String> = store.data.map { it[ACTIVE_PROVIDER] ?: DEFAULT_PROVIDER_ID }

    val webSearchProvider: Flow<WebSearchProvider> = store.data.map {
        WebSearchProvider.fromWire(it[WEB_SEARCH_PROVIDER])
    }

    /** 该服务商上次用的模型 id；没有就空串，由上层回落到目录第一个 */
    fun lastModel(providerId: String): Flow<String> =
        store.data.map { it[modelKey(providerId)] ?: "" }

    suspend fun setActiveProviderId(id: String) {
        store.edit { it[ACTIVE_PROVIDER] = id }
    }

    suspend fun setWebSearchProvider(provider: WebSearchProvider) {
        store.edit { it[WEB_SEARCH_PROVIDER] = provider.wire }
    }

    suspend fun setLastModel(providerId: String, model: String) {
        store.edit { it[modelKey(providerId)] = model }
    }

    /** 旧版仅在会话中记录思考和搜索；首次读取时迁到新对话的设置。 */
    suspend fun loadChatDefaults(legacy: suspend () -> ChatDefaults? = { null }): ChatDefaults {
        val current = store.data.first()
        if (current.hasChatDefaults()) return current.chatDefaults()

        val fromSession = legacy()
        return store.edit { prefs ->
            if (!prefs.hasChatDefaults()) prefs.writeChatDefaults(prefs.chatDefaults(fromSession))
        }.chatDefaults()
    }

    suspend fun setChatDefaults(defaults: ChatDefaults) {
        store.edit { it.writeChatDefaults(defaults) }
    }

    private fun Preferences.hasChatDefaults(): Boolean =
        this[CHAT_EFFORT] != null && this[CHAT_THINKING] != null && this[CHAT_SEARCH] != null

    private fun Preferences.chatDefaults(legacy: ChatDefaults? = null): ChatDefaults {
        val providerId = this[ACTIVE_PROVIDER] ?: legacy?.providerId ?: DEFAULT_PROVIDER_ID
        return ChatDefaults(
            providerId = providerId,
            modelId = this[modelKey(providerId)]?.takeIf { it.isNotBlank() }
                ?: legacy?.takeIf { it.providerId == providerId }?.modelId
                ?: if (providerId == DEFAULT_PROVIDER_ID) KnownProviders.flash.id else "",
            effort = ThinkingEffort.entries.firstOrNull { it.wire == this[CHAT_EFFORT] }
                ?: legacy?.effort ?: ThinkingEffort.LOW,
            thinkingEnabled = this[CHAT_THINKING] ?: legacy?.thinkingEnabled ?: true,
            searchEnabled = this[CHAT_SEARCH] ?: legacy?.searchEnabled ?: false,
        )
    }

    private fun MutablePreferences.writeChatDefaults(defaults: ChatDefaults) {
        this[ACTIVE_PROVIDER] = defaults.providerId
        this[modelKey(defaults.providerId)] = defaults.modelId
        this[CHAT_EFFORT] = defaults.effort.wire
        this[CHAT_THINKING] = defaults.thinkingEnabled
        this[CHAT_SEARCH] = defaults.searchEnabled
    }

    suspend fun setAutoSpeak(enabled: Boolean) {
        store.edit { it[AUTO_SPEAK] = enabled }
    }

    suspend fun setTtsVoice(voice: TtsVoice) {
        store.edit { it[TTS_VOICE] = voice.wire }
    }

    suspend fun setShowUsage(enabled: Boolean) {
        store.edit { it[SHOW_USAGE] = enabled }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        store.edit { it[REMINDER] = enabled }
    }

    suspend fun setThinkingAutoCollapse(enabled: Boolean) {
        store.edit { it[THINKING_AUTO_COLLAPSE] = enabled }
    }

    suspend fun setPromptSelectedId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(PROMPT_PRESET) else prefs[PROMPT_PRESET] = id
        }
    }

    suspend fun setPromptIncludeOfficial(enabled: Boolean) {
        store.edit { it[PROMPT_INCLUDE_OFFICIAL] = enabled }
    }

    suspend fun markPromptMigrated() {
        store.edit { it[PROMPT_MIGRATED] = true }
    }

    suspend fun markProvidersSeeded() {
        store.edit { it[PROVIDERS_SEEDED] = true }
    }

    suspend fun setPromptLocation(location: String) {
        store.edit { it[PROMPT_LOCATION] = location.trim() }
    }

    suspend fun setPromptLanguage(language: String) {
        store.edit { it[PROMPT_LANGUAGE] = language }
    }

    suspend fun setThemeMode(mode: String) {
        store.edit { it[THEME_MODE] = mode }
    }

    suspend fun setAppLanguage(languageTag: String) {
        store.edit { it[APP_LANGUAGE] = languageTag }
    }

    suspend fun setFontScale(scale: Float) {
        store.edit { it[FONT_SCALE] = scale.coerceIn(0.9f, 1.3f) }
    }

    companion object {
        private val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        private val TTS_VOICE = stringPreferencesKey("tts_voice")
        private val SHOW_USAGE = booleanPreferencesKey("show_usage")
        private val REMINDER = booleanPreferencesKey("reminder_enabled")
        private val THINKING_AUTO_COLLAPSE = booleanPreferencesKey("thinking_auto_collapse")
        private val PROMPT_PRESET = stringPreferencesKey("prompt_preset_id")
        private val PROMPT_INCLUDE_OFFICIAL = booleanPreferencesKey("prompt_include_official")
        private val PROMPT_MIGRATED = booleanPreferencesKey("prompt_migrated_v2")
        private val PROVIDERS_SEEDED = booleanPreferencesKey("providers_seeded_v1")
        private val PROMPT_LOCATION = stringPreferencesKey("prompt_location")
        private val PROMPT_LANGUAGE = stringPreferencesKey("prompt_language")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val APP_LANGUAGE = stringPreferencesKey("app_language")
        private val FONT_SCALE = floatPreferencesKey("font_scale")
        private val ACTIVE_PROVIDER = stringPreferencesKey("active_provider_id")
        private val CHAT_EFFORT = stringPreferencesKey("chat_effort")
        private val CHAT_THINKING = booleanPreferencesKey("chat_thinking_enabled")
        private val CHAT_SEARCH = booleanPreferencesKey("chat_search_enabled")
        private val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")

        private const val DEFAULT_PROVIDER_ID = "deepseek"

        private fun modelKey(providerId: String) = stringPreferencesKey("model.$providerId")

        /** 认不出的值一律退回默认音色（旧的/手改的文件不该把朗读弄挂）。 */
        internal fun voiceOf(raw: String?): TtsVoice =
            TtsVoice.entries.firstOrNull { it.wire == raw } ?: TtsVoice.STELLA
    }
}
