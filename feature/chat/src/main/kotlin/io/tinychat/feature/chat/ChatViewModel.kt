package io.tinychat.feature.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.PickedFile
import io.tinychat.data.chat.ChatRepository
import io.tinychat.data.chat.LiveSink
import io.tinychat.data.chat.LiveStream
import io.tinychat.data.chat.RunOutcome
import io.tinychat.data.db.FragmentEntity
import io.tinychat.data.db.MessageWithFragments
import io.tinychat.data.settings.UserSettings
import io.tinychat.audio.PlaybackStopped
import io.tinychat.feature.chat.voice.VoiceController
import io.tinychat.feature.chat.voice.VoiceEvent
import io.tinychat.model.AttachmentState
import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.BranchNode
import io.tinychat.model.ErrorKind
import io.tinychat.model.FilePayload
import io.tinychat.model.KnownProviders
import io.tinychat.model.ModelInfo
import io.tinychat.model.Provider
import io.tinychat.model.ProviderKind
import io.tinychat.model.QuotaSnapshot
import io.tinychat.net.account.AccountClient
import io.tinychat.model.Usage
import io.tinychat.model.billing.CostCalculator
import io.tinychat.model.billing.PeakClock
import io.tinychat.model.billing.UsageSummary
import java.time.Instant
import io.tinychat.model.MessageStatus
import io.tinychat.model.RequestPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.model.SessionRow
import io.tinychat.model.SessionsUiState
import io.tinychat.model.ThinkPayload
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ToolOpenPayload
import io.tinychat.model.ToolSearchPayload
import io.tinychat.model.TtsVoice
import io.tinychat.model.versionPointer
import io.tinychat.net.speech.SpeechException
import io.tinychat.net.transport.ChatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 正文里的一处引用来源（M2-8）。同一 URL 只对应一条来源，编号复用 */
data class ChatSource(
    /** 角标编号，1 基，等于它在来源列表里的位置 */
    val index: Int,
    val title: String?,
    val url: String?,
    /** 站点域名（如 `sina.com.cn`），来自搜索结果的 site_name */
    val site: String,
    /** favicon；搜索结果里没有就用域名拼 DeepSeek 的图标 CDN */
    val iconUrl: String?,
    /** 网页发布日期，ISO-8601（UTC） */
    val publishedAt: String?,
    /** 网页摘要（搜索结果的 snippet，兜底用被引用的原句） */
    val snippet: String?,
) {
    /** `yyyy/MM/dd`，按本地时区（服务端给的是 UTC，直接截前 10 位会差一天） */
    val publishedDate: String?
        get() = publishedDateText(publishedAt)
}

/** 正文里的一处角标：位置 + 指向哪条来源 */
data class SourceAnchor(val anchor: Int, val index: Int)

/** 消息里的一个附件（已入库）。图片用 `thumbPath` 画，文件用名字+大小画卡。 */
data class ChatAttachment(
    val id: String,
    val fileName: String,
    val size: Long,
    val isImage: Boolean,
    val width: Int?,
    val height: Int?,
    /** 缩略图绝对路径；导入的老数据没有原件时为 null */
    val thumbPath: String?,
    /** 原件本地路径；有原件但没缩略图时（早期导入的数据）可以退回来按需解码 */
    val localPath: String? = null,
    val state: AttachmentState,
) {
    val isMissing: Boolean get() = state == AttachmentState.MISSING
    val isFailed: Boolean get() = state == AttachmentState.FAILED

    /**
     * 只有**正在上传**才算"上传中"。
     * ⚠️ `LOCAL` 只是"原件在本机、还没传"——导入的历史附件永远停在这个状态，
     * 早期把 LOCAL 也算进来，结果导入的图片一直在转圈。
     */
    val isUploading: Boolean get() = state == AttachmentState.UPLOADING

    /** 画图时用的路径：优先缩略图，其次原件 */
    val imagePath: String? get() = thumbPath ?: localPath

    /** 「1.2 MB」这种，列表里只显示大小 */
    val sizeText: String get() = formatBytes(size)

    /** 文件卡第二行：「PDF 1.2 MB」（官方 FileCard：扩展名 + 大小） */
    val fileDescription: String
        get() = listOf(fileName.substringAfterLast('.', "").uppercase(), sizeText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/** 输入框里还没发送的附件（草稿）：只带 Uri 与元信息，原件等发送时才落盘 */
data class DraftAttachment(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val mime: String?,
    val isImage: Boolean,
    val width: Int?,
    val height: Int?,
    val thumbPath: String?,
) {
    val sizeText: String get() = formatBytes(size)
}

internal fun formatBytes(size: Long): String {
    if (size <= 0) return "未知大小"
    if (size < 1024) return "$size B"
    val kb = size / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}

/** 会话页的一条消息（已从碎片拼好）。 */
data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    /** 引用来源（正文内联角标 + 末尾来源胶囊共用这一份） */
    val sources: List<ChatSource>,
    /** 正文里的角标位置（同一来源可能被引用多次，每次一个位置） */
    val anchors: List<SourceAnchor>,
    /** 思维链时间轴；纯工具轮次也可能只有工具项 */
    val timeline: List<TimelineItem>,
    val thinkingSeconds: Double?,
    val isThinking: Boolean,
    val status: MessageStatus,
    /** 本轮提问带的附件（M4）；只有用户消息会有 */
    val attachments: List<ChatAttachment> = emptyList(),
    /** 同一条提问下的第几版回答 / 同一位置的第几次提问（1 基）；只有一版时为 1/1 */
    val versionIndex: Int = 1,
    val versionCount: Int = 1,
    /** 这条回答是停住/截断的，且没动用服务端工具 —— 可以「继续生成」 */
    val canContinue: Boolean = false,
    /**
     * 这条用户消息后面那条回答失败了（可手动重试）：值为失败回答的 messageId。
     * 界面上表现为气泡左侧的橙色重试圆钮。
     */
    val retryTargetId: Long? = null,
    /** 失败提示（灰字，右贴边），如「请检查网络后重试」 */
    val retryHint: String? = null,
    /** 这一轮的用量（M7）；用户消息为 null */
    val usage: Usage? = null,
    /** 这一轮的费用（微元），按落库时的时段算；没有 usage 为 null */
    val costMicros: Long? = null,
) {
    val hasThinking: Boolean get() = timeline.isNotEmpty()

    /** 附件上传失败：输入区下面给一行可点的重试 */
    val hasFailedAttachment: Boolean get() = attachments.any { it.isFailed }
}

/**
 * 上下文到这个占比就不再接受新提问（0.95 = 95%）。
 *
 * 为什么不等到 100%：分子是上一轮结束时的真实用量，这一条新消息还要再加进去，
 * 剩下的 5%（1M 窗口下是 50k）留给「刚好卡在边界上的那条消息」。真实用量由服务端报，
 * 客户端不估，所以这个阈值只用于拦「追加提问」这一个入口。
 */
private const val CONTEXT_BLOCK_RATIO = 0.95f

/** 上下文占比：分母是模型的窗口，拿不到窗口时按 1M 算 */
internal fun contextRatio(used: Int, window: Int): Float =
    if (window <= 0) 0f else (used.toFloat() / window).coerceIn(0f, 1f)

/** 是否已到上限（`send()` 拦截追加提问用；重新生成、编辑重发不看它） */
internal fun isContextFull(used: Int, window: Int): Boolean =
    contextRatio(used, window) >= CONTEXT_BLOCK_RATIO

/**
 * 库里的正文追上实时正文了吗——追上才允许界面切回读库。
 *
 * 生成刚结束那一刻库里还落后一个落库间隔，这时切过去会看到正文忽然变短、再长回来，
 * 所以判据是**长度**而不是「生成结束」（决策 74）。
 */
internal fun liveCaughtUp(stored: String?, live: String): Boolean =
    stored != null && stored.length >= live.length

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    /** 输入框里待发送的附件（M4-1/2） */
    val attachments: List<DraftAttachment> = emptyList(),
    val isStreaming: Boolean = false,
    /**
     * 正在实时出字的那条消息（决策 74）；`null` = 没有。
     *
     * 界面只在这条上读实时快照代替库里的正文，别的消息一律照旧读库。
     */
    val liveMessageId: Long? = null,
    /** 是否已配置 Key（M7-6：Key 改成运行时可变，UI 要跟着重算） */
    val hasApiKey: Boolean = false,
    val error: String? = null,
    /** 错误分类：决定文案与「去设置」这类动作 */
    val errorKind: ErrorKind? = null,
    val title: String? = null,
    /** 当前会话的服务商（M8-6）：会话创建时固定，切换模型不换服务商 */
    val providerId: String = KnownProviders.deepSeek.id,
    /** 已配置的服务商目录（服务商页增删后自动刷新） */
    val providers: List<Provider> = emptyList(),
    /** 哪些服务商已经存了 Key（用来判断「未配置」提示条） */
    val keyedProviders: Set<String> = emptySet(),
    val modelId: String = KnownProviders.flash.id,
    /**
     * 默认 `Flash 低`（对齐官方 App 默认设定）。
     *
     * 日常问答上更高强度换不来多少质量，却要多花时间与思考 token；需要深想时手动点底栏切。
     */
    val effort: ThinkingEffort = ThinkingEffort.LOW,
    val thinkingEnabled: Boolean = true,
    val searchEnabled: Boolean = false,
    /** 非空 = 正在编辑这条用户消息 */
    val editingMessageId: Long? = null,

    // ---- 语音（M5）----

    /** 语音输入模式：输入框变成居中的「按住说话」，右下角按钮变成键盘 */
    val voiceMode: Boolean = false,
    val voiceStatus: VoiceStatus = VoiceStatus.IDLE,
    /** 本次录音已经录了几秒（录音中才递增） */
    val voiceSeconds: Int = 0,
    /** 正在朗读哪条回答；null = 没在读 */
    val speakingMessageId: Long? = null,
    /** 顶栏喇叭开关（持久化）：开着就自动朗读每条新回答 */
    val autoSpeak: Boolean = false,
    /** 朗读音色（持久化，只有官方两个） */
    val ttsVoice: TtsVoice = TtsVoice.STELLA,
    /** 一次性提示（未识别到语音内容等）；UI 弹完调 clearHint */
    val hint: String? = null,

    // ---- 用量与费用（M7）----

    /** 会话用量合计（token + 费用），见 [UsageSummary] */
    val usageSummary: UsageSummary = UsageSummary(),
    /** 余额文案（形如 `￥12.34`）；null = 未取到 */
    val balanceText: String? = null,
    /** 订阅额度（OpenCode Go）；null = 不是订阅制或没取到 */
    val quota: QuotaSnapshot? = null,
    val balanceLoading: Boolean = false,
    /** 每轮统计条开关（设置里可关，默认开） */
    val showUsage: Boolean = true,
    /** 用户元信息（`latest_reminder`）开关（M7-5，默认开） */
    val reminderEnabled: Boolean = true,
) {
    /** 当前服务商；被删除后为 null */
    val currentProvider: Provider?
        get() = providers.firstOrNull { it.id == providerId }

    /** 会话指向的服务商被删了：历史可读，输入要禁用 */
    val providerMissing: Boolean
        get() = providers.isNotEmpty() && currentProvider == null

    /** 当前模型的目录信息；服务商被删时退化为不支持任何扩展 */
    private val currentModel: ModelInfo?
        get() = currentProvider?.model(modelId)

    /**
     * 当前模型能不能收图片（v4-pro 不支持，M4-6）。
     *
     * Anthropic 兼容链路还没有图片块实现（决策 53 只做了 OpenAI 兼容），
     * 所以那里即使能力位为真也不露附件入口——不给自己造一个点了不发货的按钮。
     */
    val canAttachImage: Boolean
        get() = currentModel?.capabilities?.imageInput == true &&
            currentProvider?.protocol != io.tinychat.model.ProviderProtocol.ANTHROPIC_MESSAGES

    /** 圆环分子：当前分支最后一条带 usage 的回答（决策 26，不做预估） */
    val contextUsed: Int
        get() = messages.lastOrNull { it.usage != null }?.usage?.contextUsed ?: 0

    /** 圆环分母 */
    val contextWindow: Int
        get() = currentModel?.contextWindow ?: 1_000_000

    val contextPercent: Float
        get() = contextRatio(contextUsed, contextWindow)

    /**
     * 上下文是否已到上限：到了就不让**追加新提问**（重新生成、编辑重发不受影响）。
     *
     * 分子用的是上一轮真实 usage，已经含那一轮的输入与输出，所以到 95% 时再发一条
     * 基本必然溢出——与其让它发出去被服务端拒掉，不如在本地就拦住。
     */
    val contextFull: Boolean
        get() = isContextFull(contextUsed, contextWindow)

    /** 能不能带文件 */
    val canAttachFile: Boolean
        get() = currentModel?.capabilities?.fileUpload == true

    val canAttach: Boolean get() = canAttachImage || canAttachFile

    /** 当前模型支不支持服务端联网（第三方一律不支持） */
    val supportsSearch: Boolean
        get() = currentModel?.capabilities?.webSearch == true

    /** 语音（ASR/TTS）只有官方端点：第三方不显示麦克风与朗读 */
    val supportsVoice: Boolean
        get() = currentModel?.capabilities?.tts == true

    /** 当前模型支不支持思考强度 */
    val supportsEffort: Boolean
        get() = currentModel?.capabilities?.effort == true
}

/**
 * 语音输入的三个状态，对应官方交互：按住（RECORDING）→ 松手识别（TRANSCRIBING）→ 直接发送。
 */
enum class VoiceStatus { IDLE, RECORDING, TRANSCRIBING }

/**
 * 会话页状态机。发送/停止、底栏模型与强度都在这里收口。
 * 停止 = `job.cancel()`，取消会一路传到 OkHttp 的 `call.cancel()`。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val apiKey: StateFlow<String>,
    /** 服务商目录与可用 Key（M8-6）：模型选择器与「未配置」判断都读它 */
    private val providers: StateFlow<List<Provider>>,
    private val keyedProviders: StateFlow<Set<String>>,
    private val activeProviderId: StateFlow<String>,
    /** 该服务商上次用的模型；新会话默认用它 */
    private val lastModelFor: suspend (String) -> String,
    /** 附件落盘（M4）：选完文件后先读元信息 + 生成缩略图 */
    private val attachmentStore: AttachmentStore,
    /** 语音（M5）：录音、频谱、ASR、TTS 播放 */
    private val voice: VoiceController,
    /** 自动朗读 / 朗读音色的持久化 */
    private val settings: UserSettings,
    /** 账户接口（M7-4）：余额查询 */
    private val account: AccountClient,
    /** 订阅额度（M8-6）：OpenCode Go 走它，其他服务商返回 null */
    private val quotaFor: suspend (Provider) -> QuotaSnapshot?,
    /** 按服务商取 Key（决策 48）：官方可以有多个实例，语音与余额要用会话自己那一家 */
    private val providerKeyFor: suspend (String) -> String? = { null },
) : ViewModel() {

    var state by mutableStateOf(ChatUiState())
        private set

    /** 抽屉整块状态：列表 + 搜索页 + 多选 */
    var sessionsUi by mutableStateOf(SessionsUiState())
        private set

    var sessionId: String? by mutableStateOf(null)
        private set

    /**
     * 生成中的实时快照（决策 74）。界面只订阅一次、每帧取最新值，
     * 拼出来的正文不经过数据库，所以出字的节奏跟着帧走，不跟着落库走。
     */
    private val _live = MutableStateFlow<LiveStream?>(null)
    val live: StateFlow<LiveStream?> = _live.asStateFlow()

    /** 交给仓库的接收端：第一条快照就把「正在出字的那条」记到状态里 */
    private val liveSink = LiveSink { snapshot ->
        if (state.liveMessageId != snapshot.messageId) {
            state = state.copy(liveMessageId = snapshot.messageId)
        }
        _live.value = snapshot
    }

    private var job: Job? = null
    private var observing: Job? = null
    private var searchJob: Job? = null
    private var speakingJob: Job? = null
    private var transcribeJob: Job? = null
    private var voiceTicker: Job? = null
    private var voiceStartedAt = 0L
    private var usageJob: Job? = null
    private var balanceJob: Job? = null
    private var balanceAt = 0L

    /** 本轮生成结束后要不要自动朗读（开关是全局的，但只在"自己发起的这一轮"才读）。 */
    private var autoSpeakPending = false

    /** 当前 Key（Key 页可随时换，所以每次调用都取最新值）。 */
    private val apiKeyValue: String get() = apiKey.value

    /**
     * 语音（ASR/TTS）只走官方端点：优先用**当前会话那一家**的 Key（可能有第二个官方实例），
     * 取不到再退回全局官方 Key。
     */
    private suspend fun voiceKey(): String =
        providerKeyFor(state.providerId)?.takeIf { it.isNotBlank() } ?: apiKeyValue


    /** 频谱数据（Canvas 直接在帧循环里读，不参与重组）。 */
    val spectrum get() = voice.spectrum

    init {
        viewModelScope.launch {
            repository.recoverInterrupted()
            // 冷启动**不**恢复上次会话（2026-11-18 定）：直接停在空白新对话，
            // 历史都在抽屉里。之前读 updated_at 最大的那条自动进去，用户明确不要。
        }
        viewModelScope.launch {
            repository.observeSessions().collect { rows ->
                sessionsUi = sessionsUi.copy(
                    rows = rows.map { SessionRow(it.id, it.title, it.pinned, it.updatedAt) },
                )
            }
        }
        viewModelScope.launch {
            providers.collect { list ->
                state = state.copy(
                    providers = list,
                    hasApiKey = state.providerId in state.keyedProviders,
                )
            }
        }
        viewModelScope.launch {
            keyedProviders.collect { keyed ->
                state = state.copy(
                    keyedProviders = keyed,
                    hasApiKey = state.providerId in keyed,
                )
            }
        }
        viewModelScope.launch {
            settings.autoSpeak.distinctUntilChanged().collect { enabled ->
                state = state.copy(autoSpeak = enabled)
            }
        }
        viewModelScope.launch {
            settings.ttsVoice.distinctUntilChanged().collect { voice ->
                state = state.copy(ttsVoice = voice)
            }
        }
        viewModelScope.launch {
            settings.showUsage.distinctUntilChanged().collect { show ->
                state = state.copy(showUsage = show)
            }
        }
        viewModelScope.launch {
            settings.reminderEnabled.distinctUntilChanged().collect { enabled ->
                state = state.copy(reminderEnabled = enabled)
            }
        }
        viewModelScope.launch {
            voice.events.collect { event ->
                when (event) {
                    VoiceEvent.RecordingLimitReached -> {
                        if (state.voiceStatus == VoiceStatus.RECORDING) {
                            finishVoiceRecording()
                            state = state.copy(hint = "已达到单次录音时长上限")
                        }
                    }

                    VoiceEvent.PlaybackFinished -> {
                        // 主动停止时我们自己也清过，这里只管"真播完了"
                        if (speakingJob?.isActive != true) state = state.copy(speakingMessageId = null)
                    }

                    // 来电/别人抢焦点：AudioTrack 已自行暂停，按钮保持"朗读中"，用户可再点停止
                    VoiceEvent.PlaybackPaused -> Unit

                    VoiceEvent.PlaybackNoisy -> stopSpeaking()
                }
            }
        }
    }

    /** 抽屉里点一条会话 */
    fun openSession(id: String) {
        if (id == sessionId) return
        job?.cancel()
        stopSpeaking()
        autoSpeakPending = false
        sessionId = id
        clearLive()
        state = state.copy(
            isStreaming = false,
            error = null,
            editingMessageId = null,
            input = "",
        )
        observe(id)
        // 会话保存了自己的模型/强度/开关，切过来要跟着变（一次性读，不跟本地切换打架）
        viewModelScope.launch {
            val row = repository.session(id) ?: return@launch
            state = state.copy(
                providerId = row.providerId,
                modelId = row.model,
                effort = row.effort ?: ThinkingEffort.LOW,
                thinkingEnabled = row.thinkingEnabled,
                searchEnabled = row.searchEnabled,
                title = row.title,
                hasApiKey = row.providerId in state.keyedProviders,
                // 换了服务商，账户区（￥ 余额 / 订阅额度）必须重取，不能沿用上一家的数字
                balanceText = null,
                quota = null,
            )
        }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch { repository.renameSession(id, title) }
    }

    fun togglePinnedSession(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setSessionPinned(id, pinned) }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.deleteSession(id)
            if (id == sessionId) newChat()
        }
    }

    // —— 抽屉：搜索 ——

    fun openSearch() {
        sessionsUi = sessionsUi.copy(searching = true, query = "", results = emptyList())
    }

    fun closeSearch() {
        searchJob?.cancel()
        sessionsUi = sessionsUi.copy(searching = false, query = "", results = emptyList())
    }

    /** 搜索输入：防抖 150ms，避免每个字都打一次库 */
    fun onSearchQueryChange(query: String) {
        sessionsUi = sessionsUi.copy(query = query, results = if (query.isBlank()) emptyList() else sessionsUi.results)
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(150)
            val hits = repository.searchSessions(query)
            sessionsUi = sessionsUi.copy(results = hits)
        }
    }

    /** 搜索结果点一条：切到那个会话，并让侧栏定位过去 */
    fun pickSearchResult(id: String) {
        closeSearch()
        openSession(id)
    }

    // —— 抽屉：多选 ——

    fun enterSelection() {
        sessionsUi = sessionsUi.copy(selectionMode = true, selected = emptySet())
    }

    fun exitSelection() {
        sessionsUi = sessionsUi.copy(selectionMode = false, selected = emptySet())
    }

    fun toggleSelected(id: String) {
        val selected = sessionsUi.selected
        sessionsUi = sessionsUi.copy(
            selected = if (id in selected) selected - id else selected + id,
        )
    }

    fun pinSelected() {
        val ids = sessionsUi.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.setSessionPinned(it, true) }
            exitSelection()
        }
    }

    fun deleteSelected() {
        val ids = sessionsUi.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.deleteSession(it) }
            if (sessionId in ids) newChat()
            exitSelection()
        }
    }

    fun onInputChange(text: String) {
        state = state.copy(input = text)
    }

    // —— 附件（M4-1/2）——

    /**
     * 选择器回来：读元信息 + 生成缩略图（在 IO 线程，图片要解码）。
     * 同一张图重复选中就跳过，避免出现两个一模一样的缩略图。
     */
    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existing = state.attachments.map { it.uri }.toSet()
        val fresh = uris.filterNot { it in existing }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                fresh.mapNotNull { uri ->
                    runCatching { attachmentStore.inspect(uri) }.getOrNull()
                }
            }
            if (picked.isEmpty()) {
                state = state.copy(error = "打不开选中的文件")
                return@launch
            }
            val oversized = picked.filter { it.size > AttachmentStore.MAX_BYTES }
            val accepted = picked - oversized.toSet()
            state = state.copy(
                attachments = state.attachments + accepted.map { it.toDraft() },
                error = if (oversized.isNotEmpty()) "附件超过 100MiB，已跳过" else state.error,
            )
        }
    }

    fun removeAttachment(id: String) {
        val removed = state.attachments.firstOrNull { it.id == id } ?: return
        runCatching { attachmentStore.delete(removed.id, null) }
        state = state.copy(attachments = state.attachments.filterNot { it.id == id })
    }

    private fun PickedFile.toDraft() = DraftAttachment(
        id = id,
        uri = uri,
        fileName = fileName,
        size = size,
        mime = mime,
        isImage = isImage,
        width = width,
        height = height,
        thumbPath = thumbPath,
    )

    /** 附件上传失败后点「重试」：重传这一轮的附件再重新请求，不新建提问 */
    fun retryAttachment(message: ChatMessage) {
        val id = sessionId ?: return
        if (state.isStreaming) return
        clearLive()
        state = state.copy(isStreaming = true, error = null, errorKind = null)
        job = viewModelScope.launch {
            try {
                state = state.withOutcomeError(repository.retryTurn(id, message.id, liveSink))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                state = state.copy(error = e.kind.userMessage(state.currentProvider?.displayName), errorKind = e.kind)
            } catch (e: Exception) {
                state = state.copy(error = "文件上传失败，请重试", errorKind = null)
            } finally {
                state = state.copy(isStreaming = false)
                // 生成结束：库里追上就交棒，追不上就让实时快照继续顶着（等下一批落库）
                finishLive()
            }
        }
    }

    /**
     * 图片快捷提问（官方同款）：把这句话当作用户输入直接发出去，附件一起带上。
     *
     * 已经是"点一下就走"，所以**覆盖**输入框里还没发的内容（官方也是这个行为）。
     */
    fun quickSend(text: String) {
        if (state.isStreaming) return
        if (text.isBlank()) return
        state = state.copy(input = text)
        send()
    }

    /**
     * 发送当前输入。
     *
     * 发送前有一串本地预检（服务商被删、没 Key、上下文到上限），全部在**清空输入框之前**
     * 返回：拦下来的内容原样留在输入区，用户能改、能复制、能带去新对话。
     */
    fun send() {
        val text = state.input.trim()
        val drafts = state.attachments
        // 只有附件没有文字也能发（真实 App 就是这样）
        if ((text.isEmpty() && drafts.isEmpty()) || state.isStreaming) return
        if (state.providerMissing) {
            state = state.copy(error = "当前会话使用的服务商已被移除，请新建会话", errorKind = null)
            return
        }
        if (!state.hasApiKey) {
            state = state.copy(error = "尚未配置当前服务商的 API Key", errorKind = ErrorKind.HTTP_401)
            return
        }
        // 附件能力跟着当前模型走：从带视觉的模型切到不带的，草稿里的图片不能悄悄发出去
        val unsupported = drafts.firstOrNull { draft ->
            if (draft.isImage) !state.canAttachImage else !state.canAttachFile
        }
        if (unsupported != null) {
            state = state.copy(
                error = if (unsupported.isImage) "当前模型不支持图片输入" else "当前模型不支持文件",
                errorKind = null,
            )
            return
        }

        val editingId = state.editingMessageId

        // 上下文到上限：只拦「追加新提问」。编辑重发（editingId != null）不拦——它是从更早的
        // 节点重建，输入只会变短或不变；重新生成、继续生成走的是别的入口，同样不会被拦。
        // 拦截点必须在清空输入框之前：被拦下来时文字和附件都留在原地，用户能复制到新对话。
        if (editingId == null && state.contextFull) {
            // 语音模式下输入框是藏起来的，识别出来的那句话会跟着一起看不见，所以退回去
            state = state.copy(
                error = ErrorKind.CONTEXT_LIMIT.userMessage,
                errorKind = ErrorKind.CONTEXT_LIMIT,
                voiceMode = false,
            )
            return
        }

        val sendingAttachments = drafts
        // 新问题开始 = 停止正在读的上一条（chat-demo 同款行为）
        stopSpeaking()
        autoSpeakPending = state.autoSpeak
        clearLive()
        state = state.copy(
            input = "",
            attachments = emptyList(),
            isStreaming = true,
            error = null,
            errorKind = null,
            editingMessageId = null,
        )
        job = viewModelScope.launch {
            try {
                val id = sessionId ?: repository.createSession(
                    providerId = state.providerId,
                    model = state.modelId,
                    thinkingEnabled = state.thinkingEnabled,
                    searchEnabled = state.searchEnabled,
                    effort = state.effort,
                ).also {
                    sessionId = it
                    observe(it)
                }

                persistSettings(id)
                val outcome = if (editingId != null) {
                    // 编辑重发：在新分支上跑，旧分支留库
                    repository.editMessage(id, editingId, text, liveSink)
                } else {
                    repository.send(
                        sessionId = id,
                        text = text,
                        attachments = sendingAttachments.map {
                            PickedFile(
                                id = it.id,
                                uri = it.uri,
                                fileName = it.fileName,
                                size = it.size,
                                mime = it.mime,
                                isImage = it.isImage,
                                width = it.width,
                                height = it.height,
                                thumbPath = it.thumbPath,
                            )
                        },
                        onLive = liveSink,
                    )
                }
                state = state.copy(
                    title = repository.session(id)?.title ?: outcome.title,
                ).withOutcomeError(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                state = state.copy(error = e.kind.userMessage(state.currentProvider?.displayName), errorKind = e.kind)
            } catch (e: Exception) {
                // 非协议错误（本地异常）：给一句能看懂的话，别再抛原始堆栈文案
                state = state.copy(error = "生成失败，请重试", errorKind = null)
            } finally {
                state = state.copy(isStreaming = false)
                // 生成结束：库里追上就交棒，追不上就让实时快照继续顶着（等下一批落库）
                finishLive()
            }
        }
    }

    /** 点一下用户消息：进入编辑重发 */
    fun startEdit(message: ChatMessage) {
        if (message.isUser.not() || state.isStreaming) return
        // 编辑重发只改文字；手上的草稿先清掉，免得用户以为会被一起发出去
        state.attachments.forEach { runCatching { attachmentStore.delete(it.id, null) } }
        state = state.copy(editingMessageId = message.id, input = message.text, attachments = emptyList())
    }

    fun cancelEdit() {
        state = state.copy(editingMessageId = null, input = "")
    }

    /** 切换版本（M1-8）：只在有兄弟分支时有效；指针换到那条分支的叶子上 */
    fun switchVersion(messageId: Long, delta: Int) {
        val id = sessionId ?: return
        if (state.isStreaming) return
        viewModelScope.launch { repository.switchVersion(id, messageId, delta) }
    }

    /** 重新生成（M1-8）：在当前这条回答下再生成一版，旧版留着可切回 */
    fun regenerate(message: ChatMessage) {
        val id = sessionId ?: return
        if (state.isStreaming || message.isUser) return

        stopSpeaking()
        autoSpeakPending = state.autoSpeak
        clearLive()
        state = state.copy(isStreaming = true, error = null)
        job = viewModelScope.launch {
            try {
                state = state.withOutcomeError(repository.regenerate(id, message.id, liveSink))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                state = state.copy(error = e.kind.userMessage(state.currentProvider?.displayName), errorKind = e.kind)
            } catch (e: Exception) {
                // 非协议错误（本地异常）：给一句能看懂的话，别再抛原始堆栈文案
                state = state.copy(error = "生成失败，请重试", errorKind = null)
            } finally {
                state = state.copy(isStreaming = false)
                // 生成结束：库里追上就交棒，追不上就让实时快照继续顶着（等下一批落库）
                finishLive()
            }
        }
    }

    /** 继续生成（M1-9）：把停住的回答接着写下去，内容原地追加 */
    fun continueGeneration(message: ChatMessage) {
        val id = sessionId ?: return
        if (state.isStreaming || message.isUser || !message.canContinue) return

        stopSpeaking()
        autoSpeakPending = state.autoSpeak
        clearLive()
        state = state.copy(isStreaming = true, error = null)
        job = viewModelScope.launch {
            try {
                state = state.withOutcomeError(repository.continueGeneration(id, message.id, liveSink))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                state = state.copy(error = e.kind.userMessage(state.currentProvider?.displayName), errorKind = e.kind)
            } catch (e: Exception) {
                // 非协议错误（本地异常）：给一句能看懂的话，别再抛原始堆栈文案
                state = state.copy(error = "生成失败，请重试", errorKind = null)
            } finally {
                state = state.copy(isStreaming = false)
                // 生成结束：库里追上就交棒，追不上就让实时快照继续顶着（等下一批落库）
                finishLive()
            }
        }
    }

    fun stop() {
        job?.cancel()
        state = state.copy(isStreaming = false)
    }

    /** M6-6：清空所有对话（含附件原件与缩略图），回到新对话 */
    fun clearAllConversations() {
        job?.cancel()
        viewModelScope.launch {
            repository.clearAllSessions()
            newChat()
        }
    }

    fun newChat() {
        job?.cancel()
        observing?.cancel()
        stopSpeaking()
        clearLive()
        autoSpeakPending = false
        sessionId = null
        // 草稿附件的缩略图要跟着清掉，别在磁盘上留垃圾
        state.attachments.forEach { runCatching { attachmentStore.delete(it.id, null) } }
        // 新对话回到「当前服务商 + 该家上次用的模型」：用户上次的选择要延续，
        // 但不能把上一会话的模型带到一个没有它的服务商下
        val providerId = activeProviderId.value
        val provider = state.providers.firstOrNull { it.id == providerId }
        state = ChatUiState(
            providerId = providerId,
            modelId = provider?.defaultModel?.id ?: state.modelId,
            effort = state.effort,
            thinkingEnabled = state.thinkingEnabled,
            searchEnabled = state.searchEnabled && provider?.defaultModel?.capabilities?.webSearch == true,
            // 这些都是全局状态，开新对话不能跟着重置（尤其是 hasApiKey：
            // 之前漏了它，开新对话后顶部会误报「尚未配置服务商」）
            autoSpeak = state.autoSpeak,
            ttsVoice = state.ttsVoice,
            hasApiKey = providerId in state.keyedProviders,
            keyedProviders = state.keyedProviders,
            providers = state.providers,
            showUsage = state.showUsage,
            reminderEnabled = state.reminderEnabled,
            balanceText = state.balanceText,
            quota = state.quota,
        )
        viewModelScope.launch {
            val last = lastModelFor(providerId).takeIf { it.isNotBlank() }
            val model = provider?.model(last ?: "") ?: return@launch
            state = state.copy(
                modelId = model.id,
                effort = state.effort,
                searchEnabled = state.searchEnabled && model.capabilities.webSearch,
                hasApiKey = providerId in state.keyedProviders,
            )
        }
    }

    fun toggleThinking() {
        state = state.copy(thinkingEnabled = !state.thinkingEnabled)
        persistStandalone()
    }

    fun toggleSearch() {
        // 打开搜索前先看能力位：第三方服务商没有服务端联网，不要假装能开
        if (!state.searchEnabled && !state.supportsSearch) {
            state = state.copy(hint = "当前模型不支持服务端联网搜索")
            return
        }
        state = state.copy(searchEnabled = !state.searchEnabled)
        persistStandalone()
    }

    /**
     * 底栏选模型：`providerId` 与模型一起进来，选择器按服务商分组。
     *
     * 决策 51：**会话中途也能换服务商**。原先拦着是怕思考签名与缓存串味，
     * 但历史思考本来就不回传，签名只用于「继续生成」，拦整个换家属于过度保守。
     * 换家时唯一的真实损失是历史里的图片与文件（各家的块形状不同，第三方链路只发文本），
     * 所以有附件历史时给一句一次性说明。
     */
    fun selectModel(providerId: String, modelId: String, effort: ThinkingEffort) {
        val switchingProvider = providerId != state.providerId
        val capabilities = state.providers.firstOrNull { it.id == providerId }?.model(modelId)?.capabilities
        val search = state.searchEnabled && (capabilities?.webSearch == true)
        val lostAttachments = switchingProvider && state.messages.any { it.attachments.isNotEmpty() }
        val providerName = state.providers.firstOrNull { it.id == providerId }?.displayName
        state = state.copy(
            providerId = providerId,
            modelId = modelId,
            effort = effort,
            searchEnabled = search,
            hasApiKey = providerId in state.keyedProviders,
            hint = if (lostAttachments) {
                "已换到 ${providerName ?: "新的服务商"}；历史里的图片与文件不会发给它"
            } else {
                state.hint
            },
        )
        persistStandalone()
        viewModelScope.launch {
            settings.setActiveProviderId(providerId)
            settings.setLastModel(providerId, modelId)
        }
    }

    /**
     * 结局里的错误：可重试（断网/限流/5xx）交给行内橙色圆钮 + 灰字，不弹底部错误条；
     * 其余（Key 无效、余额不足…）才走底部提示。
     */
    private fun ChatUiState.withOutcomeError(outcome: RunOutcome): ChatUiState {
        val error = outcome.error ?: return this
        if (error.kind.retryable) return this
        return copy(error = error.kind.userMessage(currentProvider?.displayName), errorKind = error.kind)
    }

    /** 点橙色圆钮：把失败的那一轮重发一次（不新建用户消息，不消耗修改次数） */
    fun retryFailed(message: ChatMessage) {
        val targetId = message.retryTargetId ?: return
        val id = sessionId ?: return
        if (state.isStreaming) return

        stopSpeaking()
        autoSpeakPending = state.autoSpeak
        clearLive()
        state = state.copy(isStreaming = true, error = null, errorKind = null)
        job = viewModelScope.launch {
            try {
                state = state.withOutcomeError(repository.regenerate(id, targetId, liveSink))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                state = state.copy(error = e.kind.userMessage(state.currentProvider?.displayName), errorKind = e.kind)
            } catch (e: Exception) {
                state = state.copy(error = "生成失败，请重试", errorKind = null)
            } finally {
                state = state.copy(isStreaming = false)
                // 生成结束：库里追上就交棒，追不上就让实时快照继续顶着（等下一批落库）
                finishLive()
            }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    private fun persistStandalone() {
        val id = sessionId ?: return
        viewModelScope.launch { persistSettings(id) }
    }

    private suspend fun persistSettings(id: String) {
        repository.updateSettings(
            sessionId = id,
            providerId = state.providerId,
            thinkingEnabled = state.thinkingEnabled,
            searchEnabled = state.searchEnabled,
            effort = state.effort,
            model = state.modelId,
        )
        settings.setActiveProviderId(state.providerId)
        settings.setLastModel(state.providerId, state.modelId)
    }

    // ---------------- 语音输入（M5） ----------------

    /**
     * 右下角按钮：文字模式点麦克风进语音模式；语音模式点键盘回文字模式。
     *
     * 官方交互：进入语音模式后输入框整块变成居中的「按住说话」，**不弹输入法**。
     */
    fun toggleVoiceMode() {
        if (state.isStreaming) return
        if (state.voiceMode) {
            exitVoiceMode()
        } else {
            state = state.copy(voiceMode = true, editingMessageId = null)
        }
    }

    fun exitVoiceMode() {
        if (state.voiceStatus == VoiceStatus.RECORDING) {
            voice.cancelRecording()
            stopVoiceTicker()
        }
        transcribeJob?.cancel()
        transcribeJob = null
        state = state.copy(voiceMode = false, voiceStatus = VoiceStatus.IDLE, voiceSeconds = 0)
    }

    /** 按下「按住说话」。 */
    fun startVoiceRecording() {
        if (!state.voiceMode || state.voiceStatus != VoiceStatus.IDLE || state.isStreaming) return
        try {
            voice.startRecording()
        } catch (error: SecurityException) {
            state = state.copy(voiceMode = false, hint = "无法使用麦克风，请检查权限后重试")
            return
        } catch (error: Exception) {
            state = state.copy(voiceMode = false, hint = "无法开始录音，请稍后重试")
            return
        }
        voiceStartedAt = System.currentTimeMillis()
        state = state.copy(voiceStatus = VoiceStatus.RECORDING, voiceSeconds = 0)
        startVoiceTicker()
    }

    /**
     * 松手：停录 → 识别 → **直接发送**（官方行为，不经过输入框）。
     *
     * 没录到 / 太短 / 没识别出内容都只弹提示，不发送、不改输入框。
     */
    fun finishVoiceRecording() {
        if (state.voiceStatus != VoiceStatus.RECORDING) return
        stopVoiceTicker()
        val audio = voice.stopRecording()
        if (audio == null || audio.durationMs < MIN_VOICE_MS) {
            state = state.copy(voiceStatus = VoiceStatus.IDLE, voiceSeconds = 0, hint = "未识别到语音内容")
            return
        }
        state = state.copy(voiceStatus = VoiceStatus.TRANSCRIBING, voiceSeconds = 0)
        transcribeJob = viewModelScope.launch {
            try {
                val text = voice.transcribe(voiceKey(), audio)
                if (text.isBlank()) {
                    state = state.copy(hint = "未识别到语音内容")
                } else {
                    quickSend(text)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SpeechException) {
                state = state.copy(hint = error.failure.userMessage)
            } catch (error: Exception) {
                state = state.copy(hint = "语音识别失败，请稍后再试")
            } finally {
                if (state.voiceStatus == VoiceStatus.TRANSCRIBING) {
                    state = state.copy(voiceStatus = VoiceStatus.IDLE)
                }
            }
        }
    }

    /** 识别中又点了键盘图标：丢弃这次结果。 */
    fun cancelVoiceTranscribe() {
        transcribeJob?.cancel()
        transcribeJob = null
        state = state.copy(voiceStatus = VoiceStatus.IDLE)
    }

    /** 按住说话时上滑取消：丢掉这段录音，什么都不发。 */
    fun cancelVoiceRecording() {
        if (state.voiceStatus != VoiceStatus.RECORDING) return
        stopVoiceTicker()
        voice.cancelRecording()
        state = state.copy(voiceStatus = VoiceStatus.IDLE, voiceSeconds = 0)
    }

    private fun startVoiceTicker() {
        voiceTicker?.cancel()
        voiceTicker = viewModelScope.launch {
            while (isActive && state.voiceStatus == VoiceStatus.RECORDING) {
                delay(500)
                if (state.voiceStatus == VoiceStatus.RECORDING) {
                    state = state.copy(
                        voiceSeconds = ((System.currentTimeMillis() - voiceStartedAt) / 1000).toInt(),
                    )
                }
            }
        }
    }

    private fun stopVoiceTicker() {
        voiceTicker?.cancel()
        voiceTicker = null
    }

    // ---------------- 朗读（M5-4） ----------------

    /** 操作栏「朗读」：同一条再点一次 = 停止。 */
    fun toggleSpeak(message: ChatMessage) {
        if (state.speakingMessageId == message.id) {
            stopSpeaking()
            return
        }
        if (message.isUser || message.text.isBlank()) return
        speak(message.id, message.text)
    }

    fun stopSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
        voice.stopPlayback()
        state = state.copy(speakingMessageId = null)
    }

    private fun speak(messageId: Long, text: String) {
        stopSpeaking()
        state = state.copy(speakingMessageId = messageId)
        val voiceId = state.ttsVoice
        speakingJob = viewModelScope.launch {
            try {
                voice.play(voiceKey(), text, voiceId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: PlaybackStopped) {
                // 用户主动停止：原本就是它想要的，不提示
            } catch (error: SpeechException) {
                state = state.copy(speakingMessageId = null, hint = error.failure.userMessage)
            } catch (error: Exception) {
                state = state.copy(speakingMessageId = null, hint = "朗读失败，请稍后再试")
            } finally {
                if (state.speakingMessageId == messageId) state = state.copy(speakingMessageId = null)
            }
        }
    }

    /** 顶栏喇叭开关；关掉时立刻停掉正在读的那条。 */
    fun setAutoSpeak(enabled: Boolean) {
        state = state.copy(autoSpeak = enabled)
        viewModelScope.launch { settings.setAutoSpeak(enabled) }
        if (!enabled) stopSpeaking()
    }

    fun setTtsVoice(voice: TtsVoice) {
        state = state.copy(ttsVoice = voice)
        viewModelScope.launch { settings.setTtsVoice(voice) }
    }

    /** 提示弹过了，清掉（否则同一句不会再弹）。 */
    fun clearHint() {
        state = state.copy(hint = null)
    }

    /** 本轮生成结束：开关开着就自动朗读最后一条回答（只读自己发起的那一轮）。 */
    private fun maybeAutoSpeak(messages: List<ChatMessage>) {
        if (!autoSpeakPending) return
        val last = messages.lastOrNull() ?: return
        if (last.isUser || last.status != MessageStatus.FINISHED || last.text.isBlank()) return
        autoSpeakPending = false
        speak(last.id, last.text)
    }

    /** 会话合计：流式期间跳过，落库稳定后再查（UI 只在抽屉里用） */
    private fun refreshUsage(id: String, force: Boolean = false) {
        if (!force && state.isStreaming) return
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val summary = runCatching { repository.usageSummary(id) }.getOrNull() ?: return@launch
            state = state.copy(usageSummary = summary)
        }
    }

    /** 抽屉打开时强制刷新一次（用户可能刚从别处回来） */
    /** 每轮统计条开关（M7-3） */
    fun setShowUsage(enabled: Boolean) {
        viewModelScope.launch { settings.setShowUsage(enabled) }
    }

    /** 用户元信息开关（M7-5） */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setReminderEnabled(enabled) }
    }

    fun refreshUsageNow() {
        sessionId?.let { refreshUsage(it, force = true) }
        refreshBalance()
    }

    /**
     * 账户信息（M7-4 / M8-6）：官方走 ￥ 余额，OpenCode Go 走三窗口订阅额度。
     * 60 秒内复用，失败静默（显示 --），不弹错。
     */
    fun refreshBalance(force: Boolean = false) {
        val provider = state.providers.firstOrNull { it.id == state.providerId }
        if (provider?.kind == ProviderKind.OPENCODE_GO) {
            // 订阅制没有 ￥ 余额：先把上一家的数字清掉，再拉三窗口额度
            state = state.copy(balanceText = null)
            refreshQuota(provider, force)
            return
        }
        if (provider != null && provider.kind != ProviderKind.DEEPSEEK_OFFICIAL) {
            // 通用第三方没有余额接口：不要拿 DeepSeek 的 Key 去查一个不属于它的账户
            state = state.copy(balanceText = null, quota = null)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - balanceAt < BALANCE_TTL_MS) return
        balanceJob?.cancel()
        balanceJob = viewModelScope.launch {
            // 多个官方实例时用**会话这一家**的 Key（决策 48），取不到才退回全局那一把
            val key = provider?.let { providerKeyFor(it.id) }?.takeIf { it.isNotBlank() } ?: apiKeyValue
            if (key.isBlank()) {
                state = state.copy(balanceText = null, quota = null)
                return@launch
            }
            state = state.copy(balanceLoading = true, quota = null)
            val text = runCatching { account.balance(key) }.getOrNull()
                ?.primary?.totalBalance
                ?.let { "￥$it" }
            state = state.copy(balanceText = text, balanceLoading = false)
            if (text != null) balanceAt = now
        }
    }

    /** 订阅额度：只对 OpenCode Go 生效；没配置等键就直接清空 */
    private fun refreshQuota(provider: Provider, force: Boolean) {
        val now = System.currentTimeMillis()
        val cached = state.quota
        if (!force && cached != null && now - cached.fetchedAt < BALANCE_TTL_MS) return
        balanceJob?.cancel()
        balanceJob = viewModelScope.launch {
            state = state.copy(balanceLoading = true, balanceText = null)
            val snapshot = runCatching { quotaFor(provider) }.getOrNull()
            state = state.copy(quota = snapshot, balanceLoading = false)
            if (snapshot != null) balanceAt = now
        }
    }

    // —— 实时出字（决策 74）——

    /** 开始新一轮之前把上一轮的快照清掉，免得新回答先闪一下旧字 */
    private fun clearLive() {
        _live.value = null
        if (state.liveMessageId != null) state = state.copy(liveMessageId = null)
    }

    /**
     * 交棒：库里这条消息已经有完整正文了，就把实时快照撤掉，界面改读库。
     *
     * 撤早了会闪一下（库里的正文最多落后一个落库间隔），所以判据是**长度追上**而不是
     * 「生成结束」——生成刚结束时库里还是上一秒的内容。
     */
    private fun finishLive() {
        val id = state.liveMessageId ?: return
        val snapshot = _live.value
        if (snapshot == null) {
            state = state.copy(liveMessageId = null)
            return
        }
        val stored = state.messages.firstOrNull { it.id == id }
        // 还在生成：只有库里的正文追上了才交棒，撤早了会闪一下短的
        val caughtUp = liveCaughtUp(stored?.text, snapshot.text)
        // 生成已经结束且这条消息落成了终态：库就是最终结果。
        // 拒答会主动删掉已下发的正文，长度永远追不上，只能按终态判。
        val settled = !state.isStreaming && (stored == null || stored.status != MessageStatus.WIP)
        if (caughtUp || settled) {
            _live.value = null
            state = state.copy(liveMessageId = null)
        }
    }

    private fun observe(id: String) {
        observing?.cancel()
        observing = viewModelScope.launch {
            combine(
                repository.observeMessages(id),
                repository.observeSession(id),
            ) { rows, session -> rows to session }
                .collect { (rows, session) ->
                    // 只渲染**当前分支**：从 current_message_id 往回走
                    val chain = chainOf(rows, session?.currentMessageId)
                    val branchNodes = rows.map {
                        BranchNode(it.message.id, it.message.parentId, it.message.insertedAt)
                    }
                    // 失败的那一轮：用户消息左侧要挂橙色重试圆钮，下面挂灰字提示
                    val failedAfterUser = HashMap<Long, MessageWithFragments>()
                    chain.forEachIndexed { index, entry ->
                        val next = chain.getOrNull(index + 1)?.message ?: return@forEachIndexed
                        val current = entry.message
                        val retryable = next.errorKind?.retryable == true
                        if (current.role == Role.USER && next.role == Role.ASSISTANT &&
                            next.parentId == current.id && next.status == MessageStatus.FAILED && retryable
                        ) {
                            failedAfterUser[current.id] = chain[index + 1]
                        }
                    }

                    state = state.copy(
                        messages = chain.mapNotNull { entry ->
                            val (index, count) = versionPointer(branchNodes, entry.message.id)
                            val failed = failedAfterUser[entry.message.id]
                            entry.toChatMessage()?.copy(
                                versionIndex = index,
                                versionCount = count,
                                retryTargetId = failed?.message?.id,
                                retryHint = failed?.message?.errorKind?.retryHint,
                            )
                        },
                        title = session?.title,
                    )
                    // 库里的正文追上了就交棒（决策 74）
                    finishLive()
                    // 会话合计（M7-2）：流式期间不查，落库稳定后再算
                    refreshUsage(id)
                    // 自动朗读：等这一轮真的落成 FINISHED 再读，别读半截
                    maybeAutoSpeak(state.messages)
                }
        }
    }

    private fun chainOf(rows: List<MessageWithFragments>, leafId: Long?): List<MessageWithFragments> {
        if (leafId == null) return emptyList()
        val byId = rows.associateBy { it.message.id }
        val path = ArrayDeque<MessageWithFragments>()
        var cursor: Long? = leafId
        val guard = HashSet<Long>()
        while (cursor != null && guard.add(cursor)) {
            val node = byId[cursor] ?: break
            path.addFirst(node)
            cursor = node.message.parentId
        }
        return path.toList()
    }

    /**
     * 用户消息的附件：优先读 `attachments` 表；表里没有时退回 `FILE` 碎片
     * （导入的老数据只有碎片、没有原件，标成 MISSING 才不会假装能预览）。
     */
    private fun MessageWithFragments.attachmentsOfMessage(
        fragments: List<FragmentEntity> = orderedFragments,
    ): List<ChatAttachment> {
        if (attachments.isNotEmpty()) {
            return attachments.map { entity ->
                ChatAttachment(
                    id = entity.id,
                    fileName = entity.fileName,
                    size = entity.fileSize,
                    isImage = entity.isImage,
                    width = entity.width,
                    height = entity.height,
                    thumbPath = attachmentStore.thumbFile(entity.id)
                        .takeIf { it.isFile }?.absolutePath,
                    localPath = entity.localPath,
                    state = entity.state,
                )
            }
        }
        return fragments
            .mapNotNull { FragmentPayloadCodec.decode(it.payload) as? FilePayload }
            .flatMap { it.files }
            .map { ref ->
                ChatAttachment(
                    id = ref.id,
                    fileName = ref.fileName,
                    size = ref.fileSize,
                    isImage = ref.isImage,
                    width = ref.width,
                    height = ref.height,
                    thumbPath = null,
                    state = AttachmentState.MISSING,
                )
            }
    }

    private fun MessageWithFragments.toChatMessage(): ChatMessage? {
        val fragments = orderedFragments
        val isUser = message.role == Role.USER

        if (isUser) {
            val request = fragments
                .filter { it.type == "REQUEST" }
                .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? RequestPayload)?.content }
                .joinToString("")
            val attachments = attachmentsOfMessage(fragments = fragments)
            // 只有图片/文件、没写字的那一轮也要显示（真实样本里就没有 REQUEST 碎片）
            if (request.isBlank() && attachments.isEmpty()) return null
            return ChatMessage(
                id = message.id,
                isUser = true,
                text = request,
                sources = emptyList(),
                anchors = emptyList(),
                timeline = emptyList(),
                thinkingSeconds = null,
                isThinking = false,
                status = message.status,
                attachments = attachments,
            )
        }

        val responses = fragments
            .filter { it.type == "RESPONSE" }
            .mapNotNull { FragmentPayloadCodec.decode(it.payload) as? ResponsePayload }

        val textBuilder = StringBuilder()
        val sources = mutableListOf<ChatSource>()
        val anchors = mutableListOf<SourceAnchor>()
        // 引用对象只有 url/title，站名、favicon、发布日期、摘要都在搜索结果里 → 按 url 关联
        val searchByUrl = fragments
            .mapNotNull { FragmentPayloadCodec.decode(it.payload) as? ToolSearchPayload }
            .flatMap { it.results }
            .associateBy { it.url }
        val openByUrl = fragments
            .mapNotNull { FragmentPayloadCodec.decode(it.payload) as? ToolOpenPayload }
            .associateBy { it.url }
        // 同一 URL 只列一条来源、正文里复用同一个编号（官方 App 就是这么做的）
        val indexByUrl = LinkedHashMap<String, Int>()

        responses.forEach { response ->
            textBuilder.append(response.content)
            response.citations?.forEach { element ->
                val obj = element.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                val title = obj["title"]?.jsonPrimitive?.contentOrNull
                // 锚点要落在「最后一个非空白字符之后」，否则会掉进段落之间的空行里，
                // 段落级渲染就贴不上角标了（实测：150 落在 71..148 与 166.. 之间）
                val anchor = textBuilder.trimmedEnd()
                val key = url ?: title.orEmpty()
                val index = indexByUrl.getOrPut(key) {
                    val search = url?.let { searchByUrl[it] }
                    val open = url?.let { openByUrl[it] }
                    val host = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                        ?.removePrefix("www.")
                    // 编号 = 来源在列表里的位置（1 基）
                    val newIndex = sources.size + 1
                    sources += ChatSource(
                        index = newIndex,
                        title = title ?: search?.title,
                        url = url,
                        site = search?.siteName ?: open?.siteName ?: host.orEmpty(),
                        iconUrl = search?.siteIcon
                            ?: open?.siteIcon
                            ?: host?.let { "https://cdn.deepseek.com/site-icons/$it" },
                        publishedAt = search?.publishedAt ?: open?.publishedAt,
                        snippet = search?.snippet ?: obj["cited_text"]?.jsonPrimitive?.contentOrNull,
                    )
                    newIndex
                }
                anchors += SourceAnchor(anchor = anchor, index = index)
            }
        }
        val text = textBuilder.toString()

        // v3 导出包里**没有** citations 字段（真样本 11643 个 RESPONSE 碎片里 0 个带），
        // 所以导入的历史没有正文角标；但搜索结果还在 TOOL_SEARCH 里，
        // 末尾的「N 个网页」胶囊按它补，否则历史联网对话看着像没搜过。
        if (sources.isEmpty() && searchByUrl.isNotEmpty()) {
            val seen = HashSet<String>()
            searchByUrl.values.forEach { result ->
                if (result.url.isBlank() || !seen.add(result.url)) return@forEach
                val host = runCatching { java.net.URI(result.url).host }.getOrNull()
                    ?.removePrefix("www.")
                sources += ChatSource(
                    index = sources.size + 1,
                    title = result.title,
                    url = result.url,
                    site = result.siteName ?: host.orEmpty(),
                    iconUrl = result.siteIcon
                        ?: host?.let { "https://cdn.deepseek.com/site-icons/$it" },
                    publishedAt = result.publishedAt,
                    snippet = result.snippet,
                )
            }
        }

        val timeline = buildTimeline(fragments.map { TimelineFragment(it.type, it.payload) })
        if (text.isBlank() && timeline.isEmpty()) return null

        val seconds = fragments
            .filter { it.type == "THINK" }
            .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? ThinkPayload)?.elapsedSecs }
            .takeIf { it.isNotEmpty() }
            ?.sum()

        // 「继续生成」靠 assistant 预填，必须有正文：思考阶段就被掐断的那种给「重新生成」；
        // OpenAI 兼容链路没有 prefix 预填充，第三方会话不显示这个动作
        val canContinue = message.status == MessageStatus.INCOMPLETE &&
            state.currentProvider?.protocol != io.tinychat.model.ProviderProtocol.OPENAI_CHAT &&
            timeline.none { it is TimelineItem.Tool } &&
            fragments.none { it.type == "TEMPLATE_RESPONSE" } &&
            text.isNotBlank()

        val rowUsage = Usage(
            promptTokens = message.promptTokens ?: 0,
            cacheHitTokens = message.cacheHitTokens ?: 0,
            completionTokens = message.completionTokens ?: 0,
            thinkingTokens = message.thinkingTokens ?: 0,
            safetyTokens = message.safetyTokens ?: 0,
            searchCalls = message.searchCalls ?: 0,
            fetchCalls = message.fetchCalls ?: 0,
            imageSearchCalls = message.imageSearchCalls ?: 0,
        ).takeUnless { it.isEmpty }

        return ChatMessage(
            id = message.id,
            isUser = false,
            text = text,
            sources = sources,
            anchors = anchors,
            timeline = timeline,
            thinkingSeconds = seconds,
            isThinking = message.status == MessageStatus.WIP,
            status = message.status,
            canContinue = canContinue,
            usage = rowUsage,
            // 优先用落库时定格的费用（决策 47）；老数据按旧口径现算，数字不会因为这次改动跳一下
            costMicros = message.costMicros ?: rowUsage?.let { usage -> legacyCostMicros(message, usage) },
        )
    }

    /**
     * 升级前落库的消息没有定格费用：按**这条消息自己的服务商**补算一次；
     * 老数据没有 `provider_id`，回落到会话当前服务商（与改动前的口径一致）。
     */
    private fun legacyCostMicros(message: io.tinychat.data.db.MessageEntity, usage: Usage): Long? {
        val providerId = message.providerId ?: state.providerId
        val provider = state.providers.firstOrNull { it.id == providerId } ?: return null
        if (provider.kind != ProviderKind.DEEPSEEK_OFFICIAL) return null
        val model = message.model ?: KnownProviders.flash.id
        if (io.tinychat.model.billing.DefaultPricing.value.forModel(model) == null) return null
        return CostCalculator.micros(
            usage = usage,
            model = model,
            peak = PeakClock.isPeak(
                Instant.ofEpochMilli(message.insertedAt).atZone(PeakClock.ZONE),
            ),
        )
    }

    /** 最后一个非空白字符之后的位置（不含行尾空白） */
private fun StringBuilder.trimmedEnd(): Int {
    var i = length
    while (i > 0 && this[i - 1].isWhitespace()) i--
    return i
}

    companion object {
        /** 短于这个时长直接当"没说话"（省一次请求，也符合直觉：点一下就松手不算）。 */
        private const val MIN_VOICE_MS = 600L

        /** 余额 60 秒内复用 */
        private const val BALANCE_TTL_MS = 60_000L

        fun factory(
            repository: ChatRepository,
            apiKey: StateFlow<String>,
            providers: StateFlow<List<Provider>>,
            keyedProviders: StateFlow<Set<String>>,
            activeProviderId: StateFlow<String>,
            lastModelFor: suspend (String) -> String,
            attachmentStore: AttachmentStore,
            voice: VoiceController,
            settings: UserSettings,
            account: AccountClient,
            quotaFor: suspend (Provider) -> QuotaSnapshot?,
            providerKeyFor: suspend (String) -> String? = { null },
        ) = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = repository,
                    apiKey = apiKey,
                    providers = providers,
                    keyedProviders = keyedProviders,
                    activeProviderId = activeProviderId,
                    lastModelFor = lastModelFor,
                    attachmentStore = attachmentStore,
                    voice = voice,
                    settings = settings,
                    account = account,
                    quotaFor = quotaFor,
                    providerKeyFor = providerKeyFor,
                )
            }
        }
    }
}
