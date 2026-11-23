package io.tinychat

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.tinychat.crash.CrashGuard
import io.tinychat.crash.CrashReporter
import io.tinychat.data.db.PromptPresetEntity
import io.tinychat.designsystem.LocalTcFontScale
import io.tinychat.designsystem.TcMotion
import io.tinychat.designsystem.TcTheme
import io.tinychat.feature.chat.ChatScreen
import io.tinychat.feature.chat.UsageDrawer
import io.tinychat.feature.chat.ChatViewModel
import io.tinychat.feature.chat.usageDrawerWidth
import io.tinychat.feature.sessions.SessionsDrawerContent
import io.tinychat.feature.sessions.SessionsDrawerWidth
import io.tinychat.feature.settings.AboutScreen
import io.tinychat.feature.settings.DiagnosticsItem
import io.tinychat.feature.settings.DiagnosticsScreen
import io.tinychat.feature.settings.DataManagementScreen
import io.tinychat.feature.settings.LicenseScreen
import io.tinychat.feature.settings.PromptEditorScreen
import io.tinychat.feature.settings.PromptSettingsScreen
import io.tinychat.feature.settings.ProviderEditorScreen
import io.tinychat.feature.settings.ProviderListScreen
import io.tinychat.feature.settings.SettingsScreen
import io.tinychat.ui.SidePanel
import io.tinychat.ui.TcSideSheet
import io.tinychat.ui.panelTargetOpen
import io.tinychat.ui.swipeToOpenPanel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDER = "settings/provider"
    const val PROVIDER_EDIT = "settings/provider/edit"
    const val DATA = "settings/data"
    const val ABOUT = "settings/about"
    const val LICENSE = "settings/about/licenses"
    const val DIAGNOSTICS = "settings/about/diagnostics"
    const val PROMPT = "settings/prompt"
    const val PROMPT_EDIT = "settings/prompt/edit"
}

/**
 * 应用外壳：唯一主界面 = 会话页（决策 16）。
 *
 * M0 范围内 `hasApiKey` 恒为 false —— 用来验证「未配置 Key」时只出现行内提示条、
 * 不弹窗、不跳转、不阻断主界面。
 */
@Composable
fun TinyChatApp() {
    val palette = TcTheme.palette

    val context = LocalContext.current
    // 关于页/设置页显示安装包版本（M9）：不在代码里写死
    @Suppress("DEPRECATION")
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val container = remember { AppContainer(context.applicationContext) }
    // 服务商目录与凭据（M8-6）：设置页、服务商页、模型选择器都读它
    val providers by container.providers.collectAsState()
    val keyedProviders by container.keyedProviders.collectAsState()
    val activeProviderId by container.activeProviderId.collectAsState()
    // 字体大小（M8-4）：只放大正文，不动顶栏/设置列表
    val fontScale by container.userSettings.fontScale.collectAsState(initial = 1f)
    val language by container.userSettings.promptLanguage.collectAsState(initial = "中文")
    val themeMode by container.userSettings.themeMode.collectAsState(initial = "system")
    val userLocation by container.userSettings.promptLocation.collectAsState(initial = "")
    val providerSubtitle = when {
        keyedProviders.isEmpty() -> "未配置"
        else -> providers.firstOrNull { it.id == activeProviderId }?.displayName
            ?: "${keyedProviders.size} 个服务商"
    }
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            repository = container.repository,
            apiKey = container.apiKey,
            providers = container.providers,
            keyedProviders = container.keyedProviders,
            activeProviderId = container.activeProviderId,
            lastModelFor = { id -> container.lastModelFor(id) },
            attachmentStore = container.attachmentStore,
            voice = container.voiceController,
            settings = container.userSettings,
            account = container.account,
            quotaFor = { provider -> container.quotaFor(provider) },
            providerKeyFor = { id -> container.apiKeyFor(id) },
        ),
    )

    val navController = rememberNavController()
    // 两侧面板同一时刻只能有一个（P-028）：所有权收在一个变量里，互斥是结构性的。
    var sidePanel by remember { mutableStateOf(SidePanel.None) }
    val scope = rememberCoroutineScope()
    val closeDrawer = { sidePanel = SidePanel.None }

    // 两个侧栏都是自绘的（P-030 起左抽屉也自己画，不再用 M3 的 DrawerState）：
    // 拖拽期间进度由手指写，松手后由固定时长的标准缓动补完 —— 速度只用来判断开合，
    // 不参与动画，所以不存在「冲过终点再弹回来」那一下。
    var sessionsProgress by remember { mutableFloatStateOf(0f) }
    var usageProgress by remember { mutableFloatStateOf(0f) }
    var draggingPanel by remember { mutableStateOf(SidePanel.None) }

    // 松手后走完剩下的路：固定时长 + 标准缓动（两头慢、中间快），与松手速度无关
    fun settleSpec(open: Boolean) = tween<Float>(
        durationMillis = if (open) TcMotion.Panel else TcMotion.Base,
        easing = TcMotion.Standard,
    )

    LaunchedEffect(sidePanel, draggingPanel) {
        if (draggingPanel == SidePanel.Sessions) return@LaunchedEffect
        val target = if (sidePanel == SidePanel.Sessions) 1f else 0f
        if (sessionsProgress == target) return@LaunchedEffect
        animate(
            initialValue = sessionsProgress,
            targetValue = target,
            animationSpec = settleSpec(open = target > 0f),
        ) { value, _ -> sessionsProgress = value.coerceIn(0f, 1f) }
        sessionsProgress = target
    }
    LaunchedEffect(sidePanel, draggingPanel) {
        if (draggingPanel == SidePanel.Usage) return@LaunchedEffect
        val target = if (sidePanel == SidePanel.Usage) 1f else 0f
        if (usageProgress == target) return@LaunchedEffect
        animate(
            initialValue = usageProgress,
            targetValue = target,
            animationSpec = settleSpec(open = target > 0f),
        ) { value, _ -> usageProgress = value.coerceIn(0f, 1f) }
        usageProgress = target
    }

    val density = LocalDensity.current
    val sessionsWidthPx = with(density) { SessionsDrawerWidth.toPx() }
    val usageWidthPx = with(density) {
        usageDrawerWidth(LocalConfiguration.current.screenWidthDp).toPx()
    }

    // 三个入口（整页滑动、两个抽屉上各自的拖动）共用这一套：dx 向右为正。
    // 会话列表向右是打开，用量与费用向左是打开，位移按各自的宽度折算成 0..1 的进度。
    val onPanelDragStart: (SidePanel) -> Unit = { panel -> draggingPanel = panel }
    val onPanelDrag: (SidePanel, Float) -> Unit = { panel, dx ->
        if (panel == SidePanel.Sessions) {
            sessionsProgress = (sessionsProgress + dx / sessionsWidthPx).coerceIn(0f, 1f)
        } else {
            usageProgress = (usageProgress - dx / usageWidthPx).coerceIn(0f, 1f)
        }
    }
    val onPanelDragEnd: (SidePanel, Float) -> Unit = { panel, velocityDp ->
        val progress = if (panel == SidePanel.Sessions) sessionsProgress else usageProgress
        // 判定口径统一成「正 = 打开」：左抽屉向右是开，右抽屉向左是开
        val open = if (panel == SidePanel.Sessions) {
            panelTargetOpen(progress, velocityDp, sidePanel == SidePanel.Sessions)
        } else {
            panelTargetOpen(progress, -velocityDp, sidePanel == SidePanel.Usage)
        }
        draggingPanel = SidePanel.None
        sidePanel = if (open) panel else SidePanel.None
    }
    // 右抽屉打开就刷一次用量，滑开的和点开的都走这里
    LaunchedEffect(sidePanel) {
        if (sidePanel == SidePanel.Usage) chatViewModel.refreshUsageNow()
    }
    // 手势只挂在会话页：设置那几页也有整块内容，不该一滑就出面板
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // 崩溃兜底（M9-2）要知道「崩在哪、是不是正在生成」：只有正在生成时，
    // 崩溃页才说「当前正在生成的内容可能未保存」。
    LaunchedEffect(currentRoute) {
        CrashGuard.stage = when (currentRoute) {
            Routes.SETTINGS -> "设置"
            Routes.PROVIDER, Routes.PROVIDER_EDIT -> "服务商"
            Routes.DATA -> "数据管理"
            Routes.ABOUT, Routes.LICENSE -> "关于"
            Routes.PROMPT, Routes.PROMPT_EDIT -> "系统提示词"
            else -> "会话页"
        }
    }
    LaunchedEffect(chatViewModel.state.isStreaming) {
        CrashGuard.streaming = chatViewModel.state.isStreaming
    }

    // 系统提示词（决策 45）：开关 + 用户预设。进入页面时读库，改完后就地刷新
    var promptPresets by remember { mutableStateOf<List<PromptPresetEntity>>(emptyList()) }
    var promptSelectedId by remember { mutableStateOf<String?>(null) }
    var promptIncludeOfficial by remember { mutableStateOf(true) }
    var editingPromptId by remember { mutableStateOf<String?>(null) }
    var creatingPrompt by remember { mutableStateOf(false) }
    var promptReloadKey by remember { mutableStateOf(0) }
    // 服务商编辑（M8-6）：null = 新增
    var editingProviderId by remember { mutableStateOf<String?>(null) }
    var addingProvider by remember { mutableStateOf(false) }
    LaunchedEffect(promptReloadKey) {
        promptPresets = container.promptStore.all()
        val state = container.promptStore.state()
        promptSelectedId = state.selected?.id
        promptIncludeOfficial = state.includeOfficial
    }
    val promptName = when {
        promptSelectedId != null && promptIncludeOfficial ->
            "官方提示词 + ${promptPresets.firstOrNull { it.id == promptSelectedId }?.name.orEmpty()}"

        promptSelectedId != null -> promptPresets.firstOrNull { it.id == promptSelectedId }?.name.orEmpty()
        promptIncludeOfficial -> "官方提示词"
        else -> "不发送"
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // 整页横向滑动拉开侧栏（P-030）：左滑出用量与费用，右滑出会话列表。
                // 别把「正在拖」写进 enabled —— 拖到一半摘掉 pointerInput 会中断手势，进度卡住。
                .swipeToOpenPanel(
                    enabled = currentRoute == Routes.CHAT && sidePanel == SidePanel.None,
                    onDragStart = { leftward ->
                        onPanelDragStart(if (leftward) SidePanel.Usage else SidePanel.Sessions)
                    },
                    onDragDelta = { leftward, dx ->
                        onPanelDrag(if (leftward) SidePanel.Usage else SidePanel.Sessions, dx)
                    },
                    onDragEnd = { leftward, velocityDp ->
                        onPanelDragEnd(if (leftward) SidePanel.Usage else SidePanel.Sessions, velocityDp)
                    },
                ),
        ) {
            CompositionLocalProvider(LocalTcFontScale provides fontScale) {
                NavHost(navController = navController, startDestination = Routes.CHAT) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        state = chatViewModel.state,
                        live = chatViewModel.live,
                        hasApiKey = chatViewModel.state.hasApiKey,
                        autoSpeak = chatViewModel.state.autoSpeak,
                        onInputChange = chatViewModel::onInputChange,
                        onSend = chatViewModel::send,
                        onStop = chatViewModel::stop,
                        onToggleThinking = chatViewModel::toggleThinking,
                        onToggleSearch = chatViewModel::toggleSearch,
                        onSelectModel = chatViewModel::selectModel,
                        onOpenDrawer = {
                            if (sidePanel == SidePanel.None) sidePanel = SidePanel.Sessions
                            chatViewModel.refreshBalance()
                        },
                        onNewChat = chatViewModel::newChat,
                        onToggleAutoSpeak = {
                            chatViewModel.setAutoSpeak(!chatViewModel.state.autoSpeak)
                        },
                        onOpenProviderSettings = { navController.navigate(Routes.PROVIDER) },
                        onEditMessage = chatViewModel::startEdit,
                        onCancelEdit = chatViewModel::cancelEdit,
                        onSwitchVersion = chatViewModel::switchVersion,
                        onRegenerate = chatViewModel::regenerate,
                        onContinue = chatViewModel::continueGeneration,
                        onRetry = chatViewModel::retryFailed,
                        onAddAttachments = chatViewModel::addAttachments,
                        onRemoveAttachment = chatViewModel::removeAttachment,
                        onRetryAttachment = chatViewModel::retryAttachment,
                        onQuickSend = chatViewModel::quickSend,
                        spectrum = chatViewModel.spectrum,
                        onToggleVoiceMode = chatViewModel::toggleVoiceMode,
                        onVoicePress = chatViewModel::startVoiceRecording,
                        onVoiceRelease = { cancelled ->
                            if (cancelled) {
                                chatViewModel.cancelVoiceRecording()
                            } else {
                                chatViewModel.finishVoiceRecording()
                            }
                        },
                        onReadAloud = chatViewModel::toggleSpeak,
                        onClearHint = chatViewModel::clearHint,
                        onOpenUsage = {
                            if (sidePanel == SidePanel.None) sidePanel = SidePanel.Usage
                        },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProvider = { navController.navigate(Routes.PROVIDER) },
                        onOpenData = { navController.navigate(Routes.DATA) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                        providerSubtitle = providerSubtitle,
                        versionName = versionName,
                        promptName = promptName,
                        onOpenPrompt = { navController.navigate(Routes.PROMPT) },
                        ttsVoice = chatViewModel.state.ttsVoice,
                        onSelectTtsVoice = chatViewModel::setTtsVoice,
                        showUsage = chatViewModel.state.showUsage,
                        onToggleShowUsage = chatViewModel::setShowUsage,
                        reminderEnabled = chatViewModel.state.reminderEnabled,
                        onToggleReminder = chatViewModel::setReminderEnabled,
                        language = language,
                        onSelectLanguage = { scope.launch { container.userSettings.setPromptLanguage(it) } },
                        themeMode = themeMode,
                        onSelectThemeMode = { scope.launch { container.userSettings.setThemeMode(it) } },
                        fontScale = fontScale,
                        onSelectFontScale = { scope.launch { container.userSettings.setFontScale(it) } },
                        location = userLocation,
                        onSelectLocation = { scope.launch { container.userSettings.setPromptLocation(it) } },
                    )
                }
                composable(Routes.PROVIDER) {
                    ProviderListScreen(
                        providers = providers,
                        keyedProviders = keyedProviders,
                        onBack = { navController.popBackStack() },
                        onAdd = {
                            addingProvider = true
                            editingProviderId = null
                            navController.navigate(Routes.PROVIDER_EDIT)
                        },
                        onEdit = { provider ->
                            addingProvider = false
                            editingProviderId = provider.id
                            navController.navigate(Routes.PROVIDER_EDIT)
                        },
                        onDelete = { provider ->
                            scope.launch {
                                container.deleteProvider(provider.id)
                                if (provider.id == activeProviderId) {
                                    // 删掉的正是新会话的默认项：顺位选一个还有 Key 的，
                                    // 别让下一轮新对话停在「已移除」上（决策 48 起任何行都能删）
                                    val next = container.providers.value
                                        .firstOrNull { it.id in container.keyedProviders.value }
                                        ?: container.providers.value.firstOrNull()
                                    next?.let { container.userSettings.setActiveProviderId(it.id) }
                                }
                            }
                        },
                    )
                }
                composable(Routes.PROVIDER_EDIT) {
                    val provider = if (addingProvider) {
                        null
                    } else {
                        providers.firstOrNull { it.id == editingProviderId }
                    }
                    key(editingProviderId, addingProvider) {
                        ProviderEditorScreen(
                            provider = provider,
                            existing = providers,
                            hasKey = provider?.id?.let { it in keyedProviders } == true,
                            onBack = { navController.popBackStack() },
                            onSave = { edited, key ->
                                container.saveProvider(edited, key)
                                container.checkProvider(edited, key)
                            },
                            onCheck = { edited, key -> container.checkProvider(edited, key) },
                            onClearKey = { id -> container.clearProviderKey(id) },
                            onFetchModels = { edited, key -> container.fetchModels(edited, key) },
                        )
                    }
                }
                composable(Routes.DATA) {
                    DataManagementScreen(
                        archive = container.archive,
                        onBack = { navController.popBackStack() },
                        // 导入是整包替换：当前打开的会话可能已经不存在，回到新对话
                        onImported = { chatViewModel.newChat() },
                        onClearAll = { chatViewModel.clearAllConversations() },
                    )
                }
                composable(Routes.PROMPT) {
                    PromptSettingsScreen(
                        includeOfficial = promptIncludeOfficial,
                        presets = promptPresets,
                        selectedId = promptSelectedId,
                        onBack = { navController.popBackStack() },
                        onToggleOfficial = { enabled ->
                            scope.launch {
                                container.promptStore.setIncludeOfficial(enabled)
                                promptReloadKey++
                            }
                        },
                        onSelect = { id ->
                            scope.launch {
                                container.promptStore.select(id)
                                promptReloadKey++
                            }
                        },
                        onEdit = { id ->
                            editingPromptId = id
                            creatingPrompt = false
                            navController.navigate(Routes.PROMPT_EDIT)
                        },
                        onNew = {
                            editingPromptId = null
                            creatingPrompt = true
                            navController.navigate(Routes.PROMPT_EDIT)
                        },
                    )
                }
                composable(Routes.PROMPT_EDIT) {
                    val preset = promptPresets.firstOrNull { it.id == editingPromptId }
                    key(editingPromptId, creatingPrompt, promptReloadKey) {
                        PromptEditorScreen(
                            initialName = if (creatingPrompt) "" else preset?.name.orEmpty(),
                            initialContent = if (creatingPrompt) "" else preset?.content.orEmpty(),
                            isNew = creatingPrompt || preset == null,
                            canDelete = !creatingPrompt && preset != null,
                            existingNames = promptPresets.map { it.name },
                            onBack = { navController.popBackStack() },
                            onSave = { name, content, asNew ->
                                scope.launch {
                                    val id = container.promptStore.save(
                                        id = editingPromptId.takeIf { !asNew },
                                        name = name,
                                        content = content,
                                    )
                                    container.promptStore.select(id)
                                    promptReloadKey++
                                    navController.popBackStack()
                                }
                            },
                            onDelete = {
                                val id = editingPromptId ?: return@PromptEditorScreen
                                scope.launch {
                                    container.promptStore.delete(id)
                                    promptReloadKey++
                                    navController.popBackStack()
                                }
                            },
                        )
                    }
                }
                composable(Routes.ABOUT) {
                    AboutScreen(
                        versionName = versionName,
                        onBack = { navController.popBackStack() },
                        onOpenLicenses = { navController.navigate(Routes.LICENSE) },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    )
                }
                composable(Routes.LICENSE) {
                    LicenseScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DIAGNOSTICS) {
                    // 报告的读写都在 app 侧（CrashReporter），页面只接数据与回调
                    val crashDir = remember { CrashGuard.crashDir(context) }
                    val stamp = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA) }
                    var diagnostics by remember { mutableStateOf(emptyList<DiagnosticsItem>()) }
                    val reload: () -> Unit = {
                        diagnostics = CrashReporter.list(crashDir).map { file ->
                            DiagnosticsItem(
                                id = file.name,
                                time = stamp.format(Date(file.lastModified())),
                                scene = CrashReporter.sceneOf(file) ?: "应用崩溃",
                            )
                        }
                    }
                    DiagnosticsScreen(
                        items = diagnostics,
                        onBack = { navController.popBackStack() },
                        onRefresh = reload,
                        onRead = { id -> CrashReporter.read(File(crashDir, id)) },
                        onDelete = { id -> CrashReporter.delete(File(crashDir, id)) },
                        onClearAll = { CrashReporter.clear(crashDir) },
                    )
                }
                }
            }

            UsageDrawer(
                progress = usageProgress,
                summary = chatViewModel.state.usageSummary,
                contextUsed = chatViewModel.state.contextUsed,
                contextWindow = chatViewModel.state.contextWindow,
                balanceText = chatViewModel.state.balanceText,
                quota = chatViewModel.state.quota,
                onDismiss = { closeDrawer() },
                onDragStart = { onPanelDragStart(SidePanel.Usage) },
                onDragDelta = { dx -> onPanelDrag(SidePanel.Usage, dx) },
                onDragEnd = { velocityDp -> onPanelDragEnd(SidePanel.Usage, velocityDp) },
            )
        }

        // 会话抽屉：全屏高度（内容自带状态栏内边距），画在内容之后所以在最上层
        TcSideSheet(
            progress = sessionsProgress,
            fromStart = true,
            width = SessionsDrawerWidth,
            title = "会话列表",
            onDismiss = { closeDrawer() },
            onDragStart = { onPanelDragStart(SidePanel.Sessions) },
            onDragDelta = { dx -> onPanelDrag(SidePanel.Sessions, dx) },
            onDragEnd = { velocityDp -> onPanelDragEnd(SidePanel.Sessions, velocityDp) },
        ) {
            SessionsDrawerContent(
                state = chatViewModel.sessionsUi,
                currentSessionId = chatViewModel.sessionId,
                isOpen = sidePanel == SidePanel.Sessions,
                onOpenSettings = {
                    closeDrawer()
                    navController.navigate(Routes.SETTINGS)
                },
                onPickSession = { id ->
                    chatViewModel.openSession(id)
                    closeDrawer()
                },
                onRenameSession = chatViewModel::renameSession,
                onTogglePinSession = chatViewModel::togglePinnedSession,
                onDeleteSession = chatViewModel::deleteSession,
                onOpenSearch = chatViewModel::openSearch,
                onCloseSearch = chatViewModel::closeSearch,
                onSearchQueryChange = chatViewModel::onSearchQueryChange,
                onPickSearchResult = { id ->
                    chatViewModel.pickSearchResult(id)
                    closeDrawer()
                },
                onEnterSelection = chatViewModel::enterSelection,
                onExitSelection = chatViewModel::exitSelection,
                onToggleSelected = chatViewModel::toggleSelected,
                onPinSelected = chatViewModel::pinSelected,
                onDeleteSelected = chatViewModel::deleteSelected,
            )
        }
    }
}
