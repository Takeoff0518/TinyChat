package io.tinychat

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
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
import io.tinychat.feature.chat.ChatViewModel
import io.tinychat.feature.chat.UsageDrawerContent
import io.tinychat.feature.sessions.SessionsDrawerContent
import io.tinychat.feature.settings.AboutScreen
import io.tinychat.feature.settings.DiagnosticsItem
import io.tinychat.feature.settings.DiagnosticsScreen
import io.tinychat.feature.settings.DiagnosticReportScreen
import io.tinychat.feature.settings.DataManagementScreen
import io.tinychat.feature.settings.LicenseScreen
import io.tinychat.feature.settings.PromptEditorScreen
import io.tinychat.feature.settings.PromptSettingsScreen
import io.tinychat.feature.settings.ProviderEditorScreen
import io.tinychat.feature.settings.ProviderListScreen
import io.tinychat.feature.settings.SettingsScreen
import io.tinychat.feature.settings.WebSearchSettingsScreen
import io.tinychat.feature.settings.R as SettingsResources
import io.tinychat.model.KnownProviders
import io.tinychat.ui.SidePanel
import io.tinychat.ui.TcSideDrawers
import io.tinychat.ui.panelTargetOpen
import io.tinychat.ui.swipeToOpenPanel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val PROVIDER = "settings/provider"
    const val PROVIDER_EDIT = "settings/provider/edit"
    const val DATA = "settings/data"
    const val WEB_SEARCH = "settings/web-search"
    const val ABOUT = "settings/about"
    const val LICENSE = "settings/about/licenses"
    const val DIAGNOSTICS = "settings/about/diagnostics"
    const val DIAGNOSTIC_REPORT = "settings/about/diagnostics/report"
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
    val application = context.applicationContext as TinyChatApplication
    val container = application.container
    // 服务商目录与凭据（M8-6）：设置页、服务商页、模型选择器都读它
    val providers by container.providers.collectAsState()
    val keyedProviders by container.keyedProviders.collectAsState()
    val activeProviderId by container.activeProviderId.collectAsState()
    val webSearchProvider by container.webSearchProvider.collectAsState()
    val webSearchCredentials by container.webSearchCredentials.collectAsState()
    // 字体大小（M8-4）：只放大正文，不动顶栏/设置列表
    val fontScale by container.userSettings.fontScale.collectAsState(initial = 1f)
    val themeMode by container.userSettings.themeMode.collectAsState(initial = "system")
    val languageTag by container.userSettings.appLanguage.collectAsState(initial = "zh-CN")
    val userLocation by container.userSettings.promptLocation.collectAsState(initial = "")
    val providerSubtitle = when {
        keyedProviders.isEmpty() -> stringResource(R.string.prefs_home_unconfigured)
        else -> providers.firstOrNull { it.id == activeProviderId }?.let { provider ->
            if (provider.displayName == KnownProviders.deepSeek.displayName) {
                stringResource(SettingsResources.string.prefs_detail_provider_official)
            } else provider.displayName
        } ?: stringResource(R.string.prefs_home_provider_count, keyedProviders.size)
    }
    val chatViewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = application,
        factory = ChatViewModel.factory(
            repository = container.repository,
            apiKey = container.apiKey,
            providers = container.providers,
            keyedProviders = container.keyedProviders,
            activeProviderId = container.activeProviderId,
            attachmentStore = container.attachmentStore,
            voice = container.voiceController,
            settings = container.userSettings,
            account = container.account,
            quotaFor = { provider -> container.quotaFor(provider) },
            providerKeyFor = { id -> container.apiKeyFor(id) },
            webSearchProvider = container.webSearchProvider,
            webSearchCredentials = container.webSearchCredentials,
            keepGenerationAlive = application.generation::keepAlive,
        ),
    )

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var sidePanel by rememberSaveable { mutableStateOf(SidePanel.None) }
    val scope = rememberCoroutineScope()
    val closeDrawer = { sidePanel = SidePanel.None }

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    fun openDrawer(panel: SidePanel) {
        if (sidePanel == SidePanel.None) {
            dismissKeyboard()
            sidePanel = panel
        }
    }

    var panelProgress by remember { mutableFloatStateOf(sidePanel.openProgress) }
    var draggingPanel by remember { mutableStateOf(SidePanel.None) }
    val revealedPanel = if (draggingPanel != SidePanel.None) draggingPanel else sidePanel
    val drawerCoversChat by remember {
        derivedStateOf { sidePanel != SidePanel.None || draggingPanel != SidePanel.None || panelProgress != 0f }
    }

    LaunchedEffect(sidePanel, draggingPanel) {
        if (draggingPanel != SidePanel.None) return@LaunchedEffect
        val target = sidePanel.openProgress
        if (panelProgress == target) return@LaunchedEffect
        animate(
            initialValue = panelProgress,
            targetValue = target,
            animationSpec = tween(
                durationMillis = if (sidePanel == SidePanel.None) TcMotion.Base else TcMotion.Panel,
                easing = TcMotion.Standard,
            ),
        ) { value, _ -> panelProgress = value }
        panelProgress = target
    }

    val density = LocalDensity.current
    val preferredDrawerWidth = LocalConfiguration.current.screenWidthDp * 0.82f
    val sessionsWidth = preferredDrawerWidth.coerceAtMost(360f).dp
    val usageWidth = preferredDrawerWidth.coerceAtMost(320f).dp
    val sessionsWidthPx = with(density) { sessionsWidth.toPx() }.coerceAtLeast(1f)
    val usageWidthPx = with(density) { usageWidth.toPx() }.coerceAtLeast(1f)
    val startDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val panelForSwipe: (Boolean) -> SidePanel = { leftward ->
        if (leftward == (startDirection > 0f)) SidePanel.Usage else SidePanel.Sessions
    }

    // 三个入口（整页滑动、两个抽屉上各自的拖动）共用这一套：dx 向右为正。
    // 位移按抽屉宽度换算成进度，会话列表为正，用量与费用为负。
    fun onPanelDragStart(panel: SidePanel) {
        if (sidePanel == SidePanel.None) dismissKeyboard()
        draggingPanel = panel
    }
    fun onPanelDrag(panel: SidePanel, dx: Float) {
        val widthPx = if (panel == SidePanel.Sessions) sessionsWidthPx else usageWidthPx
        val progress = panelProgress + dx * startDirection / widthPx
        panelProgress = progress.coerceIn(minOf(0f, panel.openProgress), maxOf(0f, panel.openProgress))
    }
    fun onPanelDragEnd(panel: SidePanel, velocityDp: Float) {
        // 判定口径统一成「正 = 打开」：左抽屉向右是开，右抽屉向左是开
        val open = panelTargetOpen(
            panelProgress * panel.openProgress,
            velocityDp * startDirection * panel.openProgress,
            sidePanel == panel,
        )
        draggingPanel = SidePanel.None
        sidePanel = if (open) panel else SidePanel.None
    }
    // 右抽屉打开就刷一次用量，滑开的和点开的都走这里
    LaunchedEffect(sidePanel) {
        if (sidePanel == SidePanel.Usage) chatViewModel.refreshUsageNow()
    }

    // 崩溃兜底（M9-2）要知道「崩在哪、是不是正在生成」：只有正在生成时，
    // 崩溃页才说「当前正在生成的内容可能未保存」。
    LaunchedEffect(currentRoute) {
        CrashGuard.stage = when (currentRoute) {
            Routes.SETTINGS -> "设置"
            Routes.PROVIDER, Routes.PROVIDER_EDIT -> "服务商"
            Routes.DATA -> "数据管理"
            Routes.WEB_SEARCH -> "联网搜索"
            Routes.ABOUT, Routes.LICENSE -> "关于"
            Routes.DIAGNOSTICS, "${Routes.DIAGNOSTIC_REPORT}/{id}" -> "诊断信息"
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
            "${stringResource(R.string.prefs_home_official_prompt)} + ${promptPresets.firstOrNull { it.id == promptSelectedId }?.name.orEmpty()}"

        promptSelectedId != null -> promptPresets.firstOrNull { it.id == promptSelectedId }?.name.orEmpty()
        promptIncludeOfficial -> stringResource(R.string.prefs_home_official_prompt)
        else -> stringResource(R.string.prefs_home_no_prompt)
    }

    // 「字体大小」以前只喂给了 Markdown 正文，设置页、列表、输入框全都不动，看起来就像设置无效。
    // 改成在 LocalDensity 上乘 fontScale：所有 sp 字号（标题、列表、正文、代码块）一起缩放，
    // 同时把 LocalTcFontScale 钉回 1，避免 Markdown 被缩放两次。
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, fontScale) {
        Density(baseDensity.density, baseDensity.fontScale * fontScale)
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalTcFontScale provides 1f,
    ) {
        val chatIsActive = currentRoute == Routes.CHAT
        val settingsAreActive = currentRoute != null && !chatIsActive
        Box(Modifier.fillMaxSize().background(palette.bg).clipToBounds()) {
            BackHandler(enabled = chatIsActive && drawerCoversChat, onBack = closeDrawer)
            // 退回历史抽屉时直接露出已测量的聊天布局和列表。
            TcSideDrawers(
                sessionsWidth = sessionsWidth,
                usageWidth = usageWidth,
                progress = { panelProgress },
                revealed = revealedPanel,
                coversChat = drawerCoversChat,
                canDrag = chatIsActive && sidePanel != SidePanel.None,
                onDismiss = closeDrawer,
                onDragStart = { onPanelDragStart(sidePanel) },
                onDragDelta = { dx -> onPanelDrag(draggingPanel, dx) },
                onDragEnd = { velocityDp -> onPanelDragEnd(draggingPanel, velocityDp) },
                modifier = Modifier.then(
                    if (settingsAreActive) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                ).swipeToOpenPanel(
                    enabled = chatIsActive && sidePanel == SidePanel.None &&
                        (!drawerCoversChat || draggingPanel != SidePanel.None),
                    onDragStart = { leftward -> onPanelDragStart(panelForSwipe(leftward)) },
                    onDragDelta = { leftward, dx -> onPanelDrag(panelForSwipe(leftward), dx) },
                    onDragEnd = { leftward, velocityDp -> onPanelDragEnd(panelForSwipe(leftward), velocityDp) },
                ),
                sessions = {
                    SessionsDrawerContent(
                        state = chatViewModel.sessionsUi,
                        currentSessionId = chatViewModel.sessionId,
                        isOpen = sidePanel == SidePanel.Sessions,
                        onOpenSettings = {
                            dismissKeyboard()
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
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
                },
                usage = {
                    UsageDrawerContent(
                        summary = chatViewModel.state.usageSummary,
                        contextUsed = chatViewModel.state.contextUsed,
                        contextWindow = chatViewModel.state.contextWindow,
                        balanceText = chatViewModel.state.balanceText,
                        quota = chatViewModel.state.quota,
                        onDismiss = closeDrawer,
                    )
                },
            ) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    ChatScreen(
                        sessionId = chatViewModel.sessionId,
                        state = chatViewModel.state,
                        screenIsActive = chatIsActive && !drawerCoversChat,
                        hasApiKey = chatViewModel.state.hasApiKey,
                        autoSpeak = chatViewModel.state.autoSpeak,
                        onInputChange = chatViewModel::onInputChange,
                        onSend = chatViewModel::send,
                        onStop = chatViewModel::stop,
                        onSetThinking = chatViewModel::setThinking,
                        onSetExpertMode = chatViewModel::setExpertMode,
                        onToggleSearch = chatViewModel::toggleSearch,
                        onSelectModel = chatViewModel::selectModel,
                        onOpenDrawer = {
                            openDrawer(SidePanel.Sessions)
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
                        onOpenUsage = { openDrawer(SidePanel.Usage) },
                    )
                }
            }
            if (settingsAreActive) {
                // 手势返回预览期间，露出的聊天面板只绘制，不接下方的触摸。
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
            }
            PageNavHost(
                navController = navController,
                startDestination = Routes.CHAT,
            ) {
                composable(Routes.CHAT) {}
                page(Routes.SETTINGS, background = { TcTheme.palette.settingsBackground }) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProvider = { navController.navigate(Routes.PROVIDER) },
                        onOpenData = { navController.navigate(Routes.DATA) },
                        webSearchSubtitle = webSearchProvider.label,
                        onOpenWebSearch = { navController.navigate(Routes.WEB_SEARCH) },
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
                        themeMode = themeMode,
                        onSelectThemeMode = { scope.launch { container.userSettings.setThemeMode(it) } },
                        fontScale = fontScale,
                        onSelectFontScale = { scope.launch { container.userSettings.setFontScale(it) } },
                        location = userLocation,
                        onSelectLocation = { scope.launch { container.userSettings.setPromptLocation(it) } },
                        languageTag = languageTag,
                        onSelectLanguage = { scope.launch { container.userSettings.setAppLanguage(it) } },
                    )
                }
                page(Routes.PROVIDER) {
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
                page(Routes.PROVIDER_EDIT) {
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
                page(Routes.WEB_SEARCH) {
                    WebSearchSettingsScreen(
                        provider = webSearchProvider,
                        credentials = webSearchCredentials,
                        onBack = { navController.popBackStack() },
                        onSave = container::saveWebSearchSettings,
                    )
                }
                page(Routes.DATA) {
                    DataManagementScreen(
                        archive = container.archive,
                        onBack = { navController.popBackStack() },
                        // 导入是整包替换：当前打开的会话可能已经不存在，回到新对话
                        onImported = { chatViewModel.newChat() },
                        onClearAll = { chatViewModel.clearAllConversations() },
                    )
                }
                page(Routes.PROMPT) {
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
                page(Routes.PROMPT_EDIT) {
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
                page(Routes.ABOUT) {
                    AboutScreen(
                        versionName = versionName,
                        onBack = { navController.popBackStack() },
                        onOpenLicenses = { navController.navigate(Routes.LICENSE) },
                        onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    )
                }
                page(Routes.LICENSE) {
                    LicenseScreen(onBack = { navController.popBackStack() })
                }
                page(Routes.DIAGNOSTICS) {
                    // 报告的读写都在 app 侧（CrashReporter），页面只接数据与回调
                    val crashDir = remember { CrashGuard.crashDir(context) }
                    val stamp = remember(languageTag) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(languageTag)) }
                    val fallbackScene = stringResource(R.string.prefs_home_crash)
                    var diagnostics by remember { mutableStateOf(emptyList<DiagnosticsItem>()) }
                    val reload: () -> Unit = {
                        diagnostics = CrashReporter.list(crashDir).map { file ->
                            DiagnosticsItem(
                                id = file.name,
                                time = stamp.format(Date(file.lastModified())),
                                scene = CrashReporter.sceneOf(file) ?: fallbackScene,
                            )
                        }
                    }
                    DiagnosticsScreen(
                        items = diagnostics,
                        onBack = { navController.popBackStack() },
                        onRefresh = reload,
                        onOpen = { id -> navController.navigate("${Routes.DIAGNOSTIC_REPORT}/${Uri.encode(id)}") },
                        onClearAll = { CrashReporter.clear(crashDir) },
                    )
                }
                page("${Routes.DIAGNOSTIC_REPORT}/{id}") { entry ->
                    val id = entry.arguments?.getString("id")
                    val file = remember(id) {
                        CrashReporter.list(CrashGuard.crashDir(context)).firstOrNull { it.name == id }
                    }
                    val time = remember(file, languageTag) {
                        file?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.forLanguageTag(languageTag)).format(Date(it.lastModified())) }
                    }
                    DiagnosticReportScreen(
                        time = time,
                        body = remember(file) { file?.let(CrashReporter::read) },
                        onBack = { navController.popBackStack() },
                        onClear = {
                            file?.let(CrashReporter::delete)
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}

private fun NavGraphBuilder.page(
    route: String,
    background: @Composable () -> Color = { TcTheme.palette.bg },
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route) { entry ->
        Box(Modifier.fillMaxSize().background(background()).safeDrawingPadding()) {
            content(entry)
        }
    }
}
