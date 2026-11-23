package io.tinychat.feature.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import io.tinychat.audio.SpectrumAnalyser
import io.tinychat.data.chat.LiveStream
import io.tinychat.designsystem.BubbleShape
import io.tinychat.designsystem.TcFavicon
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcLogoMark
import io.tinychat.designsystem.TcMotion
import io.tinychat.designsystem.TcPalette
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.feature.chat.share.ShareCapture
import io.tinychat.feature.chat.share.ShareCard
import io.tinychat.feature.chat.share.SharePreviewDialog
import io.tinychat.feature.chat.share.ShareProgressDialog
import io.tinychat.feature.chat.share.ShareSheet
import io.tinychat.feature.chat.share.copyText
import io.tinychat.feature.chat.share.shareImages
import io.tinychat.feature.chat.share.shareText
import io.tinychat.markdown.CitationAnchor
import io.tinychat.markdown.CitationBubbleShape
import io.tinychat.markdown.TcMarkdown
import io.tinychat.model.AttachmentState
import io.tinychat.model.ErrorKind
import io.tinychat.model.MessageStatus
import io.tinychat.model.KnownProviders
import io.tinychat.model.Provider
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ThinkingMode
import io.tinychat.ui.MenuAnchor
import io.tinychat.ui.TcAnchorMenu
import io.tinychat.ui.TcButton
import io.tinychat.ui.TcButtonVariant
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcInlineHint
import io.tinychat.ui.TcMenuItem
import io.tinychat.ui.TcTopBar
import io.tinychat.ui.tcPressPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime

/**
 * 会话页 = 应用唯一主界面（决策 16：冷启动直达这里，没有启动页/引导页）。
 *
 * 布局对齐 `02 §12.1/§12.2/§13`。M1 正文仍是朴素 `Text`，Markdown 留给 M2。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    /** 生成中的实时快照（决策 74）：正在出字的那条直接读它，不等数据库 */
    live: StateFlow<LiveStream?>,
    hasApiKey: Boolean,
    autoSpeak: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleSearch: () -> Unit,
    onSelectModel: (String, String, ThinkingEffort) -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onToggleAutoSpeak: () -> Unit,
    onOpenProviderSettings: () -> Unit,
    onEditMessage: (ChatMessage) -> Unit,
    onCancelEdit: () -> Unit,
    onSwitchVersion: (Long, Int) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onContinue: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    /** 选完图片/文件（含拍照）后交给 ViewModel 读元信息 + 生成缩略图（M4-1/2） */
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    /** 附件上传失败后重试这一轮 */
    onRetryAttachment: (ChatMessage) -> Unit,
    /** 图片快捷提问：带着当前附件直接发送 */
    onQuickSend: (String) -> Unit,
    /** 频谱数据（录音时画竖条，帧循环里直接读，不参与重组） */
    spectrum: SpectrumAnalyser,
    /** 麦克风 ↔ 键盘切换 */
    onToggleVoiceMode: () -> Unit,
    /** 按下「按住说话」 */
    onVoicePress: () -> Unit,
    /** 松手；参数 true = 上滑取消（丢掉这段录音），false = 正常结束去识别 */
    onVoiceRelease: (Boolean) -> Unit,
    /** 操作栏「朗读」：同一条再点 = 停止 */
    onReadAloud: (ChatMessage) -> Unit,
    /** 语音相关的一次性提示（录音太短/没识别出内容/朗读失败）已弹过 */
    onClearHint: () -> Unit,
    /** 点输入区圆环：打开右侧用量抽屉（M7-2） */
    onOpenUsage: () -> Unit,
) {
    val palette = TcTheme.palette
    val context = LocalContext.current

    // —— 附件选择器（M4-1/2）——
    // 选图有三个落点，优先"能进系统相册"的那个（详见 ImagePick 的注释）：
    //   ① 系统照片选择器（13+）② ACTION_PICK 进相册 ③ DocumentsUI 兜底
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> onAddAttachments(uris) }
    val galleryPicker = rememberLauncherForActivityResult(
        contract = PickImagesFromGallery(),
    ) { uris -> onAddAttachments(uris) }
    val documentsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onAddAttachments(uris) }
    val pickImageSource = remember(context) {
        when {
            ImagePick.hasPhotoPicker(context) -> ImagePickSource.PHOTO_PICKER
            ImagePick.hasGallery(context) -> ImagePickSource.GALLERY
            else -> ImagePickSource.DOCUMENTS
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> onAddAttachments(uris) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (ok && uri != null) onAddAttachments(listOf(uri))
    }
    // 没有相机 App（或系统不允许查询到相机）就不必给「拍照」入口。
    // ⚠️ Android 11+ 需要 manifest 里的 <queries> 声明，否则这里恒为 null —— 真机上
    // 曾经因为漏了那段，导致「拍照」直接不出现。
    val canTakePhoto = remember(context) {
        runCatching {
            context.packageManager
                .queryIntentActivities(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    // —— 麦克风权限（M5）：Android 6+ 运行时权限，第一次进语音模式才要 ——
    val micPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onToggleVoiceMode()
        } else {
            Toast.makeText(context, "无法使用麦克风，请在系统设置里允许录音", Toast.LENGTH_SHORT).show()
        }
    }
    val onMicClick: () -> Unit = {
        when {
            state.voiceMode -> onToggleVoiceMode()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> onToggleVoiceMode()

            else -> runCatching { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    // 语音/朗读的一次性提示（未识别到语音内容、朗读失败……）
    LaunchedEffect(state.hint) {
        val hint = state.hint ?: return@LaunchedEffect
        Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
        onClearHint()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            leading = { TcIconButton(TcIcons.Menu, "会话列表", onOpenDrawer) },
            title = state.title,
            trailing = {
                // 顶栏播音键 = **自动朗读开关**；语音只有官方端点，第三方不显示
                if (state.supportsVoice) {
                    TcIconButton(
                        icon = TcIcons.Speak,
                        contentDescription = if (autoSpeak) "关闭自动朗读" else "开启自动朗读",
                        onClick = onToggleAutoSpeak,
                        tint = if (autoSpeak) palette.brand else palette.labelSecondary,
                    )
                }
                TcIconButton(TcIcons.PlusCircle, "新建对话", onNewChat)
            },
        )

        if (state.messages.isEmpty()) {
            Greeting(modifier = Modifier.weight(1f))
        } else {
            MessageList(
                state = state,
                live = live,
                showUsage = state.showUsage,
                modifier = Modifier.weight(1f),
                onEditMessage = onEditMessage,
                onSwitchVersion = onSwitchVersion,
                onRegenerate = onRegenerate,
                onContinue = onContinue,
                onRetry = onRetry,
                onRetryAttachment = onRetryAttachment,
                onReadAloud = onReadAloud,
            )
        }

        state.error?.let { message ->
            val action = if (state.errorKind == ErrorKind.HTTP_401) onOpenProviderSettings else null
            Text(
                text = if (action != null) "$message，点这里去设置" else message,
                style = TcTypography.bodySmall,
                color = palette.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (state.providerMissing) {
            TcInlineHint(
                text = "此会话的服务商已被移除，无法继续对话",
                onClick = onOpenProviderSettings,
            )
        } else if (!hasApiKey) {
            TcInlineHint(
                text = "尚未配置服务商，点击设置 API Key",
                onClick = onOpenProviderSettings,
            )
        }

        InputCard(
            state = state,
            onInputChange = onInputChange,
            onSend = onSend,
            onStop = onStop,
            onToggleThinking = onToggleThinking,
            onToggleSearch = onToggleSearch,
            onSelectModel = onSelectModel,
            onCancelEdit = onCancelEdit,
            onRemoveAttachment = onRemoveAttachment,
            // 选图/拍照/选文件交给系统选择器，回调统一走 onAddAttachments
            onPickImages = {
                runCatching {
                    when (pickImageSource) {
                        ImagePickSource.PHOTO_PICKER -> photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                        ImagePickSource.GALLERY -> galleryPicker.launch(Unit)
                        ImagePickSource.DOCUMENTS -> documentsPicker.launch(arrayOf("image/*"))
                    }
                }
            },
            onPickFiles = { runCatching { filePicker.launch(arrayOf("*/*")) } },
            canTakePhoto = canTakePhoto,
            onQuickSend = onQuickSend,
            onTakePhoto = {
                val uri = createCameraUri(context)
                if (uri != null) {
                    cameraUri = uri
                    runCatching { cameraPicker.launch(uri) }
                }
            },
            spectrum = spectrum,
            onMicClick = onMicClick,
            onVoicePress = onVoicePress,
            onVoiceRelease = onVoiceRelease,
            contextPercent = state.contextPercent,
            onOpenUsage = onOpenUsage,
        )

        if (state.messages.isNotEmpty()) {
            Text(
                text = "回答由 AI 生成，仅供参考",
                style = TcTypography.bodySmall,
                color = palette.labelTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 10.dp),
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    val palette = TcTheme.palette
    val hour = remember { LocalTime.now().hour }
    val greeting = when {
        hour < 12 -> "早上好"
        hour < 18 -> "下午好"
        else -> "晚上好"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 图标 + 问候语整块居中（决策 62）：只加图标不破版，文字位置不受影响。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TcLogoMark(size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = "$greeting，我能帮什么忙吗？",
                style = TcTypography.headlineSmall,
                color = palette.labelPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    live: StateFlow<LiveStream?>,
    /** 每轮统计条（M7-3，设置里可关） */
    showUsage: Boolean,
    modifier: Modifier = Modifier,
    onEditMessage: (ChatMessage) -> Unit,
    onSwitchVersion: (Long, Int) -> Unit,
    onRegenerate: (ChatMessage) -> Unit,
    onContinue: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onRetryAttachment: (ChatMessage) -> Unit,
    onReadAloud: (ChatMessage) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // 用户自己拖离底部就停止跟随，别把人拽回去；拖回底部后恢复。
    val following = rememberFollowLatest(listState)

    // 上下划消息区时收起输入法（点消息不算拖动，不会误触发）。
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
    }

    // 自动跟随：新增消息、正文变长、思考区展开、键盘弹起让视口变矮，都会重新布局，
    // 因此不再依赖 messages.size —— 流式输出期间也能一直贴着底部。
    // 已经在底部时不再调滚动：实时出字下这份布局信息每帧都变，无谓的 scrollBy 会把
    // 一帧里塞进几次测量。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            if (following.value && !listState.isScrollInProgress &&
                (!info.latestItemVisible() || info.residualToBottom() > 0.5f)
            ) {
                listState.jumpToBottom()
            }
        }
    }

    // 换了分支（重新生成 / 继续生成 / 新消息）就恢复跟随，否则新答案会停在屏幕外面
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) following.value = true
    }

    // 「回到底部」只在**用户自己划走**之后出现：跟随中的那一两帧也算「不在底部」，
    // 不加这个条件就会在生成时一闪一闪，还会盖住正在长出来的最后一行。
    val showToBottom by remember {
        derivedStateOf { shouldShowToBottom(following.value, listState.layoutInfo.isAtLatestBottom()) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            // 四边留白用 Modifier.padding 而不是 contentPadding：后者属于滚动内容，
            // 滚到底之后还剩 12dp 要再滚一次 —— 动画会分两段，末尾那一下看着像瞬移。
            // 放在外面，留白照旧，滚到底就是一段顺到底的位移。
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                val isLatest = message.id == state.messages.lastOrNull()?.id
                // 只有正在出字的那一条订阅实时快照，别的消息不跟着每帧重组
                val snapshot = if (message.id == state.liveMessageId) {
                    live.collectAsState().value
                } else {
                    null
                }
                val shownText = snapshot?.text ?: message.text
                MessageRow(
                    message = message,
                    showUsage = showUsage,
                    liveText = snapshot?.text,
                    liveThinking = snapshot?.thinking,
                    onTap = { onEditMessage(message) },
                    onCopyMessage = { clipboard.setText(AnnotatedString(shownText)) },
                    onSwitchVersion = { delta -> onSwitchVersion(message.id, delta) },
                    onRegenerate = if (!message.isUser && isLatest) ({ onRegenerate(message) }) else null,
                    onContinue = if (!message.isUser && isLatest && message.canContinue) ({ onContinue(message) }) else null,
                    onRetry = { onRetry(message) },
                    onRetryAttachment = { onRetryAttachment(message) },
                    isSpeaking = state.speakingMessageId == message.id,
                    canReadAloud = state.supportsVoice,
                    onReadAloud = { onReadAloud(message) },
                )
            }
        }

        if (showToBottom) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(40.dp)
                    .shadow(4.dp, CircleShape)
                    .background(TcTheme.palette.bg, CircleShape)
                    .clickable {
                        // 只滚，不动输入法：收起键盘会让视口在动画中途变大，
                        // 输入框还停在旧位置，看起来像画面撕裂。
                        following.value = true
                        scope.launch { listState.animateToBottom() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TcIcons.ChevronDown,
                    contentDescription = "回到底部",
                    tint = TcTheme.palette.labelSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 一条消息。
 * - 用户消息：**短按进入编辑重发**，**长按弹菜单**（复制 / 编辑消息）；
 * - AI 消息：长按弹菜单（复制这条回复 / 选择文本）。
 *
 * 菜单贴在触点正上方（`06` §二）：手指按哪儿，菜单就出现在那儿的正上方，
 * 不会盖住手指，也不会跑到画面左上角去。
 */
/**
 * 把正在长出来的思考接到时间轴末尾。
 *
 * 最后一项是文字就替换（库里那条只是同一段思考的旧前缀），是工具行就另起一段。
 * 「思考—搜索—再思考」这种轮次里，新段落开始时旧段落已经落库（工具块往返至少一秒，
 * 远大于一个落库间隔），所以这里按「替换最后一段」处理即可。
 */
private fun List<TimelineItem>.withLiveThinking(thinking: String?): List<TimelineItem> {
    if (thinking.isNullOrBlank()) return this
    return if (lastOrNull() is TimelineItem.Text) {
        dropLast(1) + TimelineItem.Text(thinking)
    } else {
        this + TimelineItem.Text(thinking)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    showUsage: Boolean,
    /** 非空 = 这条正在实时出字，用它代替库里的正文（决策 74） */
    liveText: String? = null,
    /** 非空 = 正在长出来的思考，贴到时间轴末尾 */
    liveThinking: String? = null,
    onTap: () -> Unit,
    onCopyMessage: () -> Unit,
    onSwitchVersion: (Int) -> Unit,
    onRegenerate: (() -> Unit)?,
    onContinue: (() -> Unit)?,
    onRetry: () -> Unit,
    onRetryAttachment: () -> Unit,
    /** 这条正在朗读（图标换成跳动的横条） */
    isSpeaking: Boolean,
    /** 当前服务商支持 TTS 才显示朗读（语音只有官方端点） */
    canReadAloud: Boolean = true,
    onReadAloud: () -> Unit,
) {
    val palette = TcTheme.palette
    val haptics = LocalHapticFeedback.current
    // 实时出字时用内存快照里的正文（继续生成时已经接上了原有内容），否则用库里的
    val bodyText = liveText ?: message.text

    // 长按菜单（决策 44）：记下按下的位置与所在节点的坐标，菜单出现在手指正上方。
    // 用户消息与 AI 消息各占一个分支，同一时刻只有一个节点在写这两个值。
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<MenuAnchor?>(null) }
    var pressPos by remember { mutableStateOf(Offset.Zero) }
    var pressCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun openMenuAtPress() {
        val coords = pressCoords
        if (coords == null || !coords.isAttached) return
        menuAnchor = MenuAnchor.Pointer((coords.positionInWindow() + pressPos).round())
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuOpen = true
    }

    if (message.isUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            // 附件（M4-4）：图片走网格、文件走卡片，都在气泡**上方**右贴边
            if (message.attachments.isNotEmpty()) {
                UserAttachments(message.attachments)
                if (message.text.isNotBlank()) Spacer(Modifier.height(6.dp))
            }
            if (message.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (message.retryTargetId != null) {
                        // 官方做法：失败那轮在气泡左侧挂一个橙色实心圆 + 白色重试图标，
                        // 圆钮与气泡**最后一行文字**对齐（气泡内边距 10dp，这里补回来）
                        Box(
                            modifier = Modifier
                                .padding(bottom = 10.dp, end = 8.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(palette.warning)
                                .clickable(onClick = onRetry),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = TcIcons.Regenerate,
                                contentDescription = "重试",
                                tint = palette.brandOn,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    Text(
                        text = message.text,
                        style = TcTypography.bodyLarge,
                        color = palette.labelPrimary,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .background(palette.bubble, BubbleShape)
                            .onGloballyPositioned { pressCoords = it }
                            .tcPressPosition { pressPos = it }
                            .combinedClickable(onClick = onTap, onLongClick = { openMenuAtPress() })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            if (message.hasFailedAttachment) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "文件上传失败，点击重试",
                    style = TcTypography.bodySmall,
                    color = palette.warning,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onRetryAttachment)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            message.retryHint?.let { hint ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = TcTypography.bodySmall,
                    color = palette.labelTertiary,
                )
            }
            if (message.versionCount > 1) {
                VersionSwitcher(message.versionIndex, message.versionCount, onSwitchVersion)
            }
        }

        menuAnchor?.let { anchor ->
            TcAnchorMenu(
                visible = menuOpen,
                anchor = anchor,
                onDismiss = { menuOpen = false },
                items = listOf(
                    TcMenuItem("复制") {
                        menuOpen = false
                        onCopyMessage()
                    },
                    TcMenuItem("编辑消息") {
                        menuOpen = false
                        onTap()
                    },
                ),
            )
        }
        return
    }

    var selectOpen by remember { mutableStateOf(false) }
    var sourcesOpen by remember { mutableStateOf(false) }
    // 思维链里的工具行（搜索 / 浏览）：点开是抽屉，不是原位展开
    var toolSheet by remember(message.id) { mutableStateOf<ToolStep?>(null) }
    // 分享（M8-2）：抽屉 →（生成长图时）进度 → 预览 → 系统分享面板
    var shareOpen by remember { mutableStateOf(false) }
    var shareBusy by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<ShareCapture.Output?>(null) }
    // 复制做一下确认：图标变对勾 1.5s，不做 Toast（官方也没弹东西）
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    // 三个底部面板各有一份状态。共用一个 rememberModalBottomSheetState 会让后开的面板
    // 继承前一个的展开进度，挂载时锚点已经算好，看起来就是「一打开就往上跳」。
    val selectSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sourcesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val toolSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 长按只挂在这段内容区（思考块、正文、来源胶囊）。操作栏不在里面——
            // 以前整条消息都能触发，长按「复制」图标也会弹菜单。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { pressCoords = it }
                    .tcPressPosition { pressPos = it }
                    .combinedClickable(onClick = {}, onLongClick = { openMenuAtPress() }),
            ) {
                val liveTimeline = message.timeline.withLiveThinking(liveThinking)
                if (liveTimeline.isNotEmpty()) {
                    ThinkingBlock(
                        timeline = liveTimeline,
                        seconds = message.thinkingSeconds,
                        inProgress = message.isThinking || liveThinking != null,
                        stopped = message.status == MessageStatus.INCOMPLETE && bodyText.isBlank(),
                        onOpenTool = { toolSheet = it },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (bodyText.isNotBlank()) {
                    // 正文走 Markdown（M2-1/2/3）：流式期间按渲染耗时节流出帧，禁止整篇重解析。
                    // 实时的正文来自内存快照，库里的那条只是生成结束后的接管者。
                    TcMarkdown(
                        content = bodyText,
                        streaming = message.status == MessageStatus.WIP || liveText != null,
                        citations = message.anchors.map { CitationAnchor(it.anchor, it.index) },
                        onCitationClick = { sourcesOpen = true },
                    )
                    if (message.sources.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SourceCapsule(
                            sources = message.sources,
                            onClick = { sourcesOpen = true },
                        )
                    }
                }
            }

            // 操作栏（M2-9）按官方版式固定两行：
            //   第一行：复制/朗读/分享靠左贴边 …… 重新生成靠右贴边
            //   第二行：版本切换靠左贴边 …… 继续生成靠右贴边
            // 可见性规则抽到 resolveFooterState()，单测覆盖（手动停止也要给全三件）
            val footer = resolveFooterState(
                status = message.status,
                hasText = bodyText.isNotBlank(),
                versionCount = message.versionCount,
                isLatest = onRegenerate != null,
                canContinue = onContinue != null,
            )

            if (footer.visible) {
                Spacer(Modifier.height(4.dp))
            }
            if (footer.showFirstRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (footer.showActions) {
                        ActionIcon(
                            icon = if (copied) TcIcons.Check else TcIcons.Copy,
                            description = if (copied) "已复制" else "复制",
                            tint = if (copied) palette.labelSecondary else palette.labelTertiary,
                            onClick = {
                                onCopyMessage()
                                copied = true
                            },
                        )
                        // 朗读（M5-4）：点了开始读本条，朗读中图标变成跳动的横条，再点停止
                        if (canReadAloud) {
                            if (isSpeaking) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(onClick = onReadAloud),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ReadingBars(
                                        modifier = Modifier.size(width = 14.dp, height = 12.dp),
                                        tint = palette.brand,
                                    )
                                }
                            } else {
                                ActionIcon(
                                    icon = TcIcons.Speak,
                                    description = "朗读",
                                    onClick = onReadAloud,
                                )
                            }
                        }
                        ActionIcon(
                            icon = TcIcons.Share,
                            description = "分享",
                            onClick = { shareOpen = true },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    onRegenerate?.takeIf { footer.showRegenerate }?.let { regenerate ->
                        ActionIcon(
                            icon = TcIcons.Regenerate,
                            description = "重新生成",
                            onClick = regenerate,
                        )
                    }
                }
            }
            if (footer.showSecondRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (message.versionCount > 1) {
                        VersionSwitcher(message.versionIndex, message.versionCount, onSwitchVersion)
                    }
                    Spacer(Modifier.weight(1f))
                    if (onContinue != null) {
                        TcButton(
                            text = "继续生成",
                            onClick = onContinue,
                            variant = TcButtonVariant.Outline,
                            compact = true,
                        )
                    }
                }
            }
            if (showUsage && message.usage != null) {
                UsageStatsLine(message.usage, message.costMicros)
            }
        }

        menuAnchor?.let { anchor ->
            TcAnchorMenu(
                visible = menuOpen,
                anchor = anchor,
                onDismiss = { menuOpen = false },
                items = listOf(
                    TcMenuItem("复制") {
                        menuOpen = false
                        onCopyMessage()
                    },
                    TcMenuItem("选择文本") {
                        menuOpen = false
                        selectOpen = true
                    },
                ),
            )
        }
    }

    if (selectOpen) {
        ModalBottomSheet(
            onDismissRequest = { selectOpen = false },
            sheetState = selectSheetState,
            containerColor = palette.bg,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "选择文本",
                    style = TcTypography.titleMedium,
                    color = palette.labelPrimary,
                )
                Spacer(Modifier.height(10.dp))
                // 正文必须自己滚：面板高度有上限，长回答不加滚动容器就是直接裁掉，
                // 手指往上划时纵向拖拽全被面板的拖拽手势接管，于是「划不动、松手往上跳」。
                SelectionContainer {
                    Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = message.text,
                            style = TcTypography.bodyLarge,
                            color = palette.labelPrimary,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (sourcesOpen) {
        SourceSheet(
            sources = message.sources,
            sheetState = sourcesSheetState,
            onDismiss = { sourcesOpen = false },
        )
    }

    toolSheet?.let { step ->
        ToolResultSheet(
            step = step,
            sheetState = toolSheetState,
            onDismiss = { toolSheet = null },
        )
    }

    if (shareOpen) {
        ShareSheet(
            sheetState = shareSheetState,
            onDismiss = { shareOpen = false },
            onGenerateImage = {
                shareOpen = false
                shareBusy = true
                scope.launch {
                    val result = runCatching {
                        ShareCapture.render(context, context.cacheDir, message.text) { width ->
                            ShareCard(text = message.text, width = width)
                        }
                    }
                    shareBusy = false
                    result.onSuccess { shareResult = it }
                        .onFailure { throwable ->
                            android.util.Log.w("ShareCapture", "生成长图失败", throwable)
                            Toast.makeText(context, "生成长图失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                }
            },
            onCopyText = {
                shareOpen = false
                copyText(context, message.text)
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            onShareText = {
                shareOpen = false
                shareText(context, message.text)
            },
        )
    }

    if (shareBusy) {
        ShareProgressDialog()
    }

    shareResult?.let { output ->
        SharePreviewDialog(
            output = output,
            onShare = {
                shareImages(context, output.files)
                shareResult = null
            },
            onDismiss = { shareResult = null },
        )
    }
}

/**
 * 来源页：标题居中，每行 = 圆形 favicon + 站点 + 灰色日期 + 可选编号角标，
 * 下面两行灰色摘要（超出省略），点击用浏览器打开源网页。
 *
 * 两个入口共用这一份：正文末尾胶囊（**带**编号角标）、思维链里的工具行（**不带**角标）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    sources: List<ChatSource>,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
) {
    ResultSheet(title = "搜索结果", sheetState = sheetState, onDismiss = onDismiss) {
        sources.forEach { source ->
            ResultRow(
                site = source.site,
                iconUrl = source.iconUrl,
                publishedDate = source.publishedDate,
                detail = source.snippet?.takeIf { it.isNotBlank() } ?: source.title,
                index = source.index,
                url = source.url,
            )
        }
    }
}

/** 思维链工具行的抽屉：`搜索结果` / `已浏览搜索结果`，**不标编号角标** */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolResultSheet(
    step: ToolStep,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
) {
    ResultSheet(title = step.panelTitle, sheetState = sheetState, onDismiss = onDismiss) {
        step.entries.forEach { entry ->
            ResultRow(
                site = entry.site.ifBlank { entry.title.orEmpty() },
                iconUrl = entry.iconUrl,
                publishedDate = entry.publishedDate,
                detail = entry.detail,
                index = null,
                url = entry.url,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultSheet(
    title: String,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = TcTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bg,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = title,
                style = TcTypography.titleMedium,
                color = palette.labelPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/**
 * 抽屉里的一行结果。
 *
 * `index` 为 null 时不画右侧编号气泡——工具行抽屉不标角标，胶囊抽屉标。
 */
@Composable
private fun ResultRow(
    site: String,
    iconUrl: String?,
    publishedDate: String?,
    detail: String?,
    index: Int?,
    url: String?,
) {
    val palette = TcTheme.palette
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (url != null) {
                    Modifier.clickable { runCatching { uriHandler.openUri(url) } }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左侧一组（图标+站名+日期）吃掉剩余宽度，右侧角标才能真正贴右边
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TcFavicon(
                    url = iconUrl,
                    fallbackLetter = site,
                    size = 18,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = site,
                    style = TcTypography.bodySmall,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                publishedDate?.let { date ->
                    Text(
                        text = date,
                        style = TcTypography.labelSmall,
                        color = palette.labelTertiary,
                    )
                }
            }
            if (index != null) {
                Spacer(Modifier.width(8.dp))
                // 与正文角标同一个气泡形状，右贴边
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 16.dp)
                        .clip(CitationBubbleShape)
                        .background(palette.inlineCodeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = index.toString(),
                        style = TextStyle(
                            fontSize = 10.sp,
                            lineHeight = 10.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        color = palette.labelSecondary,
                    )
                }
            }
        }
        detail?.takeIf { it.isNotBlank() }?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                style = TcTypography.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
                color = palette.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 正文末尾的来源聚合胶囊：favicon 堆叠（同站去重、最多 3 个）+ `N 个网页` */
@Composable
private fun SourceCapsule(
    sources: List<ChatSource>,
    onClick: () -> Unit,
) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.dp, palette.borderL2, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sources.distinctBy { it.site }.take(3).forEach { source ->
            TcFavicon(
                url = source.iconUrl,
                fallbackLetter = source.site,
                size = 20,
            )
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${sources.size} 个网页",
            style = TcTypography.bodySmall,
            color = palette.labelSecondary,
        )
    }
}

/**
 * 录音频谱行（M5-2）：2dp 竖条 + 3dp 间距，根数按可用宽度自适应（同 chat-demo）。
 *
 * 性能：每帧只让 **draw 阶段**失效（在 Canvas 的绘制 lambda 里读一下帧号），
 * 不重组、不分配数组——柱高来自 `SpectrumAnalyser` 复用的 FloatArray。
 */
@Composable
private fun SpectrumRow(
    spectrum: SpectrumAnalyser,
    modifier: Modifier = Modifier,
    seconds: Int = 0,
) {
    val palette = TcTheme.palette
    val frame = remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            frame.intValue++
        }
    }

    Row(
        modifier = modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            // 手机宽度算下来 60 根左右，与 chat-demo 一致
            val bars = ((maxWidth.value + BAR_GAP_DP) / (BAR_WIDTH_DP + BAR_GAP_DP))
                .toInt()
                .coerceIn(4, SpectrumAnalyser.MAX_BANDS)
            LaunchedEffect(bars) { spectrum.resize(bars) }

            Canvas(modifier = Modifier.fillMaxSize()) {
                frame.intValue // 读帧号：只让绘制阶段失效，不触发重组
                val levels = spectrum.levels
                val barWidth = BAR_WIDTH_DP.dp.toPx()
                val gap = BAR_GAP_DP.dp.toPx()
                val minHeight = BAR_MIN_DP.dp.toPx()
                val maxHeight = BAR_MAX_DP.dp.toPx()
                val centerY = size.height / 2f
                val count = minOf(levels.size, ((size.width + gap) / (barWidth + gap)).toInt())
                for (index in 0 until count) {
                    val level = levels[index].coerceIn(0f, 1f)
                    val height = minHeight + (maxHeight - minHeight) * level
                    drawRoundRect(
                        color = palette.brand,
                        topLeft = Offset(index * (barWidth + gap), centerY - height / 2f),
                        size = Size(barWidth, height),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    )
                }
            }
        }
        if (seconds > 0) {
            Text(
                text = "${seconds}s",
                style = TcTypography.labelMedium,
                color = palette.labelTertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 语音模式的输入区：居中「按住说话」，按下即录、松手即识别并发送；
 * **按住时上滑超过阈值 = 取消**（早期微信的交互）。
 *
 * ⚠️ 手势**不能**以录音状态为 key：状态一变就会重启 `pointerInput`，
 * 正在按住的这次手势会被取消（现象是"一按下马上就收掉"）。所以 key 固定为 Unit，
 * 状态靠 `rememberUpdatedState` 取最新值。
 */
@Composable
private fun VoiceHoldArea(
    status: VoiceStatus,
    onPress: () -> Unit,
    onRelease: (cancelled: Boolean) -> Unit,
) {
    val palette = TcTheme.palette
    val idle by rememberUpdatedState(status == VoiceStatus.IDLE)
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    var cancelArmed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                val threshold = CANCEL_SLIDE_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!idle) return@awaitEachGesture
                    press()
                    var cancelled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUp()) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            // 主要往上滑才算取消：横向滑动不误判
                            val armed = dy < -threshold && dy < -kotlin.math.abs(dx)
                            if (armed != cancelled) {
                                cancelled = armed
                                cancelArmed = armed
                            }
                            change.consume()
                        }
                    } finally {
                        // 手势被系统打断也要收尾，否则录音线程会一直录下去
                        cancelArmed = false
                        release(cancelled)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    status == VoiceStatus.TRANSCRIBING -> "识别中…"
                    cancelArmed -> "松开取消"
                    status == VoiceStatus.RECORDING -> "松开发送"
                    else -> "按住说话"
                },
                style = TcTypography.bodyLarge,
                color = when {
                    cancelArmed -> palette.danger
                    status == VoiceStatus.RECORDING -> palette.brand
                    else -> palette.labelTertiary
                },
            )
            if (status == VoiceStatus.RECORDING && !cancelArmed) {
                Text(
                    text = "上滑取消",
                    style = TcTypography.labelMedium,
                    color = palette.labelTertiary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private const val CANCEL_SLIDE_DP = 56

private const val BAR_WIDTH_DP = 2
private const val BAR_GAP_DP = 3
private const val BAR_MIN_DP = 5
private const val BAR_MAX_DP = 28

/**
 * 朗读中替代播放三角的三根跳动横条（chat-demo 的 `ReadingBars`）。
 *
 * 纯装饰：不反映音量，只是"正在出声"的指示，所以用固定相位错开的循环动画。
 */
@Composable
private fun ReadingBars(
    modifier: Modifier = Modifier,
    tint: Color,
) {
    val transition = rememberInfiniteTransition(label = "reading-bars")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val height by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = TcMotion.Ambient,
                        delayMillis = index * TcMotion.Quick,
                        easing = TcMotion.Standard,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar-$index",
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(tint),
            )
        }
    }
}

/**
 * 操作栏里的单个灰图标按钮（M2-9）。
 * 40dp 触达、18dp 图形、无底色 —— 相邻两个按钮的图形间距正好 22dp，和官方一致。
 */
@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = TcTheme.palette.labelTertiary,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * 版本切换 `‹ 2/3 ›`（M1-8）。到头那一侧箭头置灰，点了也不动（不循环）。
 */
@Composable
private fun VersionSwitcher(index: Int, count: Int, onSwitch: (Int) -> Unit) {
    val palette = TcTheme.palette
    val canPrev = index > 1
    val canNext = index < count

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = canPrev) { onSwitch(-1) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TcIcons.Back,
                contentDescription = "上一版",
                tint = if (canPrev) palette.labelSecondary else palette.labelTertiary.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = "$index / $count",
            style = TcTypography.bodyMedium,
            color = palette.labelSecondary,
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(enabled = canNext) { onSwitch(1) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TcIcons.ChevronRight,
                contentDescription = "下一版",
                tint = if (canNext) palette.labelSecondary else palette.labelTertiary.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 思维链折叠块（§13）：折叠 `已思考（用时 N 秒）›`；展开为左侧竖线 + 圆点时间轴，
 * 中间穿插工具步骤卡片。
 */
@Composable
private fun ThinkingBlock(
    timeline: List<TimelineItem>,
    seconds: Double?,
    inProgress: Boolean,
    stopped: Boolean,
    onOpenTool: (ToolStep) -> Unit,
) {
    val palette = TcTheme.palette
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(inProgress) { expanded = inProgress }

    val label = when {
        inProgress -> "正在思考"
        // 思考阶段被掐断：不假装「已思考完」，直接标已停止
        stopped -> "已停止"
        else -> {
            val secs = seconds?.let { it.toInt().coerceAtLeast(1) }
            if (secs != null) "已思考（用时 $secs 秒）" else "已思考"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TcIcons.Think,
                contentDescription = null,
                tint = if (inProgress) palette.brand else palette.labelSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 文案切换（正在思考 → 已思考（用时 N 秒））用淡入淡出，别硬跳
            Crossfade(
                targetState = label,
                animationSpec = tween(TcMotion.Base, easing = TcMotion.Standard),
                label = "thinking-label",
            ) { text ->
                Text(text = text, style = TcTypography.bodyMedium, color = palette.labelSecondary)
            }
            Spacer(Modifier.width(4.dp))
            // 一个图标转 90°：收起是 ›，展开是 ˅（官方就是这个手感，别用两张图硬切）
            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(TcMotion.Base, easing = TcMotion.Standard),
                label = "thinking-chevron",
            )
            Icon(
                imageVector = TcIcons.ChevronRight,
                contentDescription = if (expanded) "收起思维链" else "展开思维链",
                tint = palette.labelTertiary,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(chevronRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(TcMotion.Panel, easing = TcMotion.Standard),
                expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = tween(TcMotion.Fade, easing = TcMotion.Standard)),
            exit = shrinkVertically(
                animationSpec = tween(TcMotion.Base, easing = TcMotion.Decelerate),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = tween(TcMotion.Quick, easing = TcMotion.Decelerate)),
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .height(IntrinsicSize.Min),
            ) {
                // 左侧 2dp 竖线
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(palette.borderL2, RoundedCornerShape(1.dp)),
                )
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    timeline.forEach { item ->
                        when (item) {
                            is TimelineItem.Text -> TimelineText(item.content)
                            is TimelineItem.Tool -> ToolCard(item.step, onOpenTool)
                        }
                    }
                }
            }
        }
    }
}

/** 时间轴上的一段思考：每行一个圆点 */
@Composable
private fun TimelineText(content: String) {
    val palette = TcTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content.split('\n').filter { it.isNotBlank() }.forEach { line ->
            Row {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(4.dp)
                        .background(palette.labelTertiary, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line.trim(),
                    style = TcTypography.bodyMedium,
                    color = palette.labelSecondary,
                )
            }
        }
    }
}

/**
 * 工具步骤行：`搜索到 N 个网页` / `已浏览 N 个页面` + 站点图标堆叠 + `›`。
 *
 * 点击**不在原位展开**，而是打开底部抽屉（官方形态）：原位展开会把 29 条结果
 * 压成一行行站名，既挤又没信息量。失败或没有结果时整行不可点。
 */
@Composable
private fun ToolCard(step: ToolStep, onOpen: (ToolStep) -> Unit) {
    val palette = TcTheme.palette

    val icon = when (step.kind) {
        ToolKind.SEARCH -> TcIcons.Search
        ToolKind.OPEN -> TcIcons.Globe
        ToolKind.FIND -> TcIcons.Search
    }

    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (step.canOpen) {
                    Modifier.clickable(onClickLabel = "打开${step.panelTitle}") { onOpen(step) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.labelSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = step.title,
            style = TcTypography.bodyMedium,
            color = palette.labelSecondary,
        )
        if (step.canOpen) {
            Spacer(Modifier.width(6.dp))
            SiteStack(step.entries)
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = TcIcons.ChevronRight,
                contentDescription = null,
                tint = palette.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 图标堆叠最多几个（参考实现 `SiteThumbnails` 是 4 个） */
private const val STACK_LIMIT = 4

/**
 * 站点图标堆叠：真 favicon，同站去重，最多 4 个。
 *
 * 每个图标外面套一圈背景色（页底 `bg`）再叠压：没有这圈描边时，后一个不透明的图标
 * 会直接切掉前一个的边缘，看起来像被裁坏；有描边之后是「压着站一排」。
 */
@Composable
private fun SiteStack(entries: List<ToolEntry>) {
    val palette = TcTheme.palette
    val shown = entries
        .distinctBy { it.site.ifBlank { it.url ?: it.title.orEmpty() } }
        .take(STACK_LIMIT)

    Row {
        shown.forEachIndexed { index, entry ->
            Box(
                modifier = Modifier
                    // 负 padding 会抛异常，叠压只能用 offset
                    .offset(x = if (index == 0) 0.dp else (-6).dp)
                    .size(18.dp)
                    .background(palette.bg, CircleShape)
                    .padding(1.dp),
            ) {
                TcFavicon(
                    url = entry.iconUrl,
                    fallbackLetter = entry.site.ifBlank { entry.title.orEmpty() },
                    size = 16,
                )
            }
        }
    }
}

@Composable
private fun InputCard(
    contextPercent: Float,
    onOpenUsage: () -> Unit,
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleThinking: () -> Unit,
    onToggleSearch: () -> Unit,
    onSelectModel: (String, String, ThinkingEffort) -> Unit,
    onCancelEdit: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    canTakePhoto: Boolean,
    onTakePhoto: () -> Unit,
    /** 图片快捷提问：点一下就用这句话 + 当前附件直接发出去 */
    onQuickSend: (String) -> Unit,
    /** 录音频谱（录音时画竖条） */
    spectrum: SpectrumAnalyser,
    /** 右下角麦克风 / 键盘：进/出语音模式（含权限申请） */
    onMicClick: () -> Unit,
    /** 按住「按住说话」开始录音 */
    onVoicePress: () -> Unit,
    /** 松手：停录并识别（cancelled = 上滑取消，丢掉这段） */
    onVoiceRelease: (Boolean) -> Unit,
) {
    val palette = TcTheme.palette
    val dark = isSystemInDarkTheme()
    // 深色下卡片与背景同色会糊在一起，用 bgLayer2 抬一层
    val cardColor = if (dark) palette.bgLayer2 else palette.bg
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var attachSheet by remember { mutableStateOf(false) }
    var previewImage by remember { mutableStateOf<DraftAttachment?>(null) }

    /** 取消编辑：清空由 ViewModel 做，这里负责「失焦 + 收起输入法」 */
    val cancelEdit: () -> Unit = {
        onCancelEdit()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }

    // 输入框内容由外部（state.input）驱动：从外面写进来时（例如点消息进入编辑）
    // 光标要落在**末尾**，否则会停在开头还要手动挪。
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(state.input, TextRange(state.input.length)))
    }
    LaunchedEffect(state.input) {
        if (fieldValue.text != state.input) {
            fieldValue = TextFieldValue(state.input, TextRange(state.input.length))
        }
    }

    // 点消息进入编辑态时，输入框要直接拿到焦点并弹输入法（否则还要再点一下）
    LaunchedEffect(state.editingMessageId) {
        if (state.editingMessageId != null) {
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .shadow(3.dp, RoundedCornerShape(24.dp))
            .background(cardColor, RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    ) {
        // 编辑态提示条
        if (state.editingMessageId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "正在编辑消息：",
                    style = TcTypography.bodySmall,
                    color = palette.brand,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "取消",
                    style = TcTypography.bodySmall,
                    color = palette.labelSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        // 取消要清空 + 失焦 + 收起输入法（只清空的话，键盘会留在原地）
                        .clickable(onClick = cancelEdit)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        // 附件预览行（M4-1）：选中后立刻能删，发送时一起上传
        if (state.attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.attachments.forEach { draft ->
                    DraftChip(
                        draft = draft,
                        onRemove = { onRemoveAttachment(draft.id) },
                        onOpen = { if (draft.isImage) previewImage = draft },
                    )
                }
            }
        }

        // 图片快捷提问（官方同款）：只要有图片草稿就出一排可横滑的圆角按钮，
        // 点一下就用这句话 + 全部附件直接发出去，省掉一次输入。
        if (state.attachments.any { it.isImage } &&
            state.editingMessageId == null &&
            !state.isStreaming
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageQuickPrompts.forEach { prompt ->
                    QuickPromptChip(text = prompt, onClick = { onQuickSend(prompt) })
                }
            }
        }

        // 录音频谱行（M5-2）：只在录音时出现，占输入框上方一行
        if (state.voiceStatus == VoiceStatus.RECORDING) {
            SpectrumRow(
                spectrum = spectrum,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                seconds = state.voiceSeconds,
            )
        }

        if (state.voiceMode) {
            // 语音模式：输入框位置整块变成居中的「按住说话」（官方交互，不弹输入法）
            VoiceHoldArea(
                status = state.voiceStatus,
                onPress = onVoicePress,
                onRelease = onVoiceRelease,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
            ) {
                if (state.input.isEmpty()) {
                    Text(
                        text = "发消息或按住说话",
                        style = TcTypography.bodyLarge,
                        color = palette.labelTertiary,
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        fieldValue = newValue
                        onInputChange(newValue.text)
                    },
                    textStyle = TcTypography.bodyLarge.copy(color = palette.labelPrimary),
                    cursorBrush = SolidColor(palette.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocus),
                    maxLines = 8,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左组（模型 pill + 搜索）占剩余宽度；pill 按需取宽，名字太长就省略，
            // 右侧三个圆钮的空间先保住——以前 pill 没有上限，长模型名会把发送键挤出屏幕
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelEffortPill(
                    providers = state.providers,
                    keyedProviders = state.keyedProviders,
                    providerId = state.providerId,
                    modelId = state.modelId,
                    effort = state.effort,
                    thinkingEnabled = state.thinkingEnabled,
                    onToggleThinking = onToggleThinking,
                    onSelect = onSelectModel,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                SearchPill(enabled = state.searchEnabled, onClick = onToggleSearch)
            }
            Spacer(Modifier.width(8.dp))
            // 上下文圆环（M7-2）：回形针左侧，点开右侧用量抽屉
            ContextRing(percent = contextPercent, onClick = onOpenUsage)
            Spacer(Modifier.width(2.dp))
            // 编辑态不给加附件（编辑只改文字；进入编辑时草稿已清空）
            if (state.editingMessageId == null && state.canAttach) {
                CircleIcon(TcIcons.Attach, "附件", palette.labelSecondary) { attachSheet = true }
                Spacer(Modifier.width(4.dp))
            }
            when {
                state.isStreaming -> CircleIcon(TcIcons.Stop, "停止生成", palette.brand, filled = true, onClick = onStop)
                state.voiceMode -> when (state.voiceStatus) {
                    // 识别中：转圈，点一下取消
                    VoiceStatus.TRANSCRIBING -> CircleIcon(
                        TcIcons.Keyboard,
                        "取消识别并回到键盘输入",
                        palette.labelSecondary,
                        onClick = onMicClick,
                    )
                    // 语音模式：键盘图标（再点回文字输入）
                    else -> CircleIcon(TcIcons.Keyboard, "切换到键盘输入", palette.labelSecondary, onClick = onMicClick)
                }
                state.input.isNotBlank() || state.attachments.isNotEmpty() ->
                    CircleIcon(TcIcons.Send, "发送", palette.brand, filled = true, onClick = onSend)
                else -> if (state.supportsVoice) {
                    CircleIcon(TcIcons.Mic, "语音输入", palette.labelSecondary, onClick = onMicClick)
                }
            }
        }
    }

    // 附件来源选择（M4-1/2）：相册 / 拍照 / 文件
    if (attachSheet) {
        AttachmentSheet(
            showCamera = canTakePhoto,
            showFiles = state.canAttachFile,
            onDismiss = { attachSheet = false },
            onImages = {
                attachSheet = false
                onPickImages()
            },
            onCamera = {
                attachSheet = false
                onTakePhoto()
            },
            onFiles = {
                attachSheet = false
                onPickFiles()
            },
        )
    }

    // 点缩略图放大看（草稿阶段的图直接按缩略图画，原件还没落盘）
    previewImage?.let { draft ->
        AttachmentPreviewDialog(
            thumbPath = draft.thumbPath,
            title = draft.fileName,
            aspectRatio = draft.width?.takeIf { it > 0 }?.let { w ->
                draft.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h }
            },
            onDismiss = { previewImage = null },
        )
    }
}

/** 模型与强度 pill：显示模型名 + 强度（决策 20：不加中点），点开半屏选择器（M8-6 按服务商分组） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelEffortPill(
    providers: List<Provider>,
    keyedProviders: Set<String>,
    providerId: String,
    modelId: String,
    effort: ThinkingEffort,
    thinkingEnabled: Boolean,
    onToggleThinking: () -> Unit,
    onSelect: (String, String, ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TcTheme.palette
    var sheetOpen by remember { mutableStateOf(false) }
    val current = providers.firstOrNull { it.id == providerId }
    val currentModel = current?.model(modelId)
    val label = when {
        current == null -> "已移除"
        else -> currentModel?.let { it.displayName.ifBlank { it.id } }?.shortName() ?: current.displayName
    }

    Row(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(palette.brand.copy(alpha = 0.10f))
            .clickable { sheetOpen = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // 强度只在「这一轮真的会带思考参数」时显示。关掉思考、或模型本来就不支持强度，
            // 都只是「没有参数」，不该拿一个 Off 冒充第四档（决策 50）。
            text = if (thinkingEnabled && currentModel?.capabilities?.effort == true) {
                "$label ${effort.displayName}"
            } else {
                label
            },
            style = TcTypography.bodySmall,
            color = palette.brand,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.bg,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "模型与强度",
                    style = TcTypography.titleMedium,
                    color = palette.labelPrimary,
                )
                Spacer(Modifier.height(10.dp))

                providers.filter { it.models.isNotEmpty() }.forEach { provider ->
                    val sameProvider = provider.id == providerId
                    val keyed = provider.id in keyedProviders
                    Text(
                        text = provider.displayName + if (!keyed) "  未配置密钥" else "",
                        style = TcTypography.labelMedium,
                        color = if (keyed) palette.labelSecondary else palette.labelTertiary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    provider.models.forEach { model ->
                        // 会话中途也能换家（决策 51）：只要这家配了密钥就可选
                        val enabled = keyed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = enabled) {
                                    sheetOpen = false
                                    val targetEffort = if (model.capabilities.effort) effort else ThinkingEffort.LOW
                                    onSelect(provider.id, model.id, targetEffort)
                                }
                                .padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = model.displayName.ifBlank { model.id }.shortName(),
                                style = TcTypography.bodyLarge,
                                color = if (enabled) palette.labelPrimary else palette.labelTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = when {
                                    sameProvider && model.id == modelId -> "当前"
                                    !keyed -> "未配置"
                                    else -> ""
                                },
                                style = TcTypography.bodySmall,
                                color = if (sameProvider && model.id == modelId) palette.brand else palette.labelTertiary,
                            )
                        }
                    }
                }

                // 思考开关（决策 50）：以前这个动作在界面上没有入口，`onToggleThinking` 是个没人调用的死参数。
                // 判据不是「模型声明了支持思考」，而是「模型确实会发思考参数或返回思考」——
                // 老配置里有 `thinking=EFFORT` 但能力位 `thinking=false` 的模型，也要能关。
                if (currentModel != null &&
                    (currentModel.capabilities.thinking || currentModel.thinking != ThinkingMode.NONE)
                ) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("思考", style = TcTypography.bodyMedium, color = palette.labelPrimary)
                            Text(
                                text = if (thinkingEnabled) "回答前先想一遍" else "直接回答，不产生思考过程",
                                style = TcTypography.labelMedium,
                                color = palette.labelTertiary,
                            )
                        }
                        Switch(
                            checked = thinkingEnabled,
                            onCheckedChange = { onToggleThinking() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = palette.brand,
                                checkedThumbColor = palette.brandOn,
                            ),
                        )
                    }
                }

                if (currentModel?.capabilities?.effort == true && thinkingEnabled) {
                    Spacer(Modifier.height(14.dp))
                    Text("思考强度", style = TcTypography.labelMedium, color = palette.labelSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        ThinkingEffort.entries.forEach { option ->
                            EffortChip(
                                text = option.displayName,
                                selected = option == effort,
                                onClick = {
                                    sheetOpen = false
                                    onSelect(providerId, modelId, option)
                                },
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** 模型显示名可能很长（`zai-org/GLM-5.3-Flash`），pill 只留短名 */
private fun String.shortName(): String = when {
    startsWith("DeepSeek V4.1 ") -> removePrefix("DeepSeek V4.1 ")
    startsWith("DeepSeek ") -> removePrefix("DeepSeek ")
    else -> substringAfterLast('/').substringAfterLast(':').ifBlank { this }
}

@Composable
private fun EffortChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = TcTheme.palette
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(if (selected) palette.brand else palette.bubble)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TcTypography.bodySmall,
            color = if (selected) palette.brandOn else palette.labelSecondary,
        )
    }
}

/** 搜索 pill：只显示图标。开 = 品牌色，关 = 灰（§12.1） */
@Composable
private fun SearchPill(enabled: Boolean, onClick: () -> Unit) {
    val palette = TcTheme.palette
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(if (enabled) palette.brand.copy(alpha = 0.10f) else palette.bubble)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TcIcons.Globe,
            contentDescription = if (enabled) "关闭智能搜索" else "开启智能搜索",
            tint = if (enabled) palette.brand else palette.labelSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CircleIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = TcTheme.palette
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (filled) palette.brand else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (filled) palette.brandOn else tint,
            modifier = Modifier.size(if (filled) 18.dp else 22.dp),
        )
    }
}

// ——————————————————————————————————————————————————————————————
// 附件（M4）
// ——————————————————————————————————————————————————————————————

/**
 * 消息里的附件（M4-4），版式对齐 chat-demo 的官方实现
 * （`UserMessage.module.css` + `MessageFile.module.css`）：
 *
 * - 一列 `flex-wrap`、右贴边、间距 10dp；
 * - 图片是**正方形缩略图**：只有图片时 120dp，与文档混排时缩到 64dp；
 * - 圆角 16dp、1dp 描边、`object-fit: cover`；上传中压 30% 黑蒙层 + 转圈，失败描红边 + 重试图标。
 *
 * 注意：不是「1 张 1:1 / 2 张 2 列 / 3 张 3 列」的九宫格 —— 那是照截图猜的，
 * 参考实现用的是固定尺寸缩略图自动折行（2026-11-19 按 chat-demo 更正）。
 */
@Composable
private fun UserAttachments(attachments: List<ChatAttachment>) {
    val palette = TcTheme.palette
    val hasDocuments = attachments.any { !it.isImage }
    val size = if (hasDocuments) 64.dp else 120.dp
    val gap = 10.dp
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    val images = attachments.filter { it.isImage }

    // ⚠️ 必须给 BoxWithConstraints 指定 contentAlignment：里面的 Column 是 wrap-content，
    // Column(horizontalAlignment = End) 只对**它自己的宽度**生效，靠不满整行。
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        val perRow = (((maxWidth - gap) / (size + gap)).toInt()).coerceAtLeast(1)
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            attachments.chunked(perRow).forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    rowItems.forEach { item ->
                        if (item.isImage) {
                            val indexInImages = images.indexOf(item)
                            AttachmentThumb(
                                attachment = item,
                                size = size,
                                onClick = { previewIndex = indexInImages },
                            )
                        } else {
                            FileAttachmentCard(attachment = item, palette = palette)
                        }
                    }
                }
            }
        }
    }

    previewIndex?.let { index ->
        val item = images.getOrNull(index) ?: return@let
        AttachmentPreviewDialog(
            thumbPath = item.imagePath,
            title = item.fileName,
            aspectRatio = item.width?.takeIf { it > 0 }?.let { w ->
                item.height?.takeIf { it > 0 }?.let { h -> w.toFloat() / h }
            },
            onDismiss = { previewIndex = null },
        )
    }
}

@Composable
private fun AttachmentThumb(attachment: ChatAttachment, size: Dp, onClick: () -> Unit) {
    val palette = TcTheme.palette
    val load = rememberBitmapLoad(attachment.imagePath, (size.value * 3).toInt())
    // 只有正在上传才转圈；LOCAL（导入的历史附件）表示原件在本机、无需再传
    val uploading = attachment.state == AttachmentState.UPLOADING
    val failed = attachment.state == AttachmentState.FAILED

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bubble)
            .border(
                width = 1.dp,
                color = if (failed) palette.danger else palette.bubble,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(enabled = attachment.imagePath != null && !uploading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            load.bitmap != null -> Image(
                bitmap = load.bitmap,
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 解码中：留空，别闪一个占位图标（会被误看成"图没了"）
            load.loading -> Unit
            else -> Icon(
                imageVector = if (attachment.isMissing) TcIcons.PhotoError else TcIcons.Photo,
                contentDescription = attachment.fileName,
                tint = palette.labelSecondary,
                modifier = Modifier.size(28.dp),
            )
        }

        // 上传中：30% 黑蒙层 + 转圈（官方同款）
        if (uploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
        } else if (failed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TcIcons.Regenerate,
                    contentDescription = "文件上传失败",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 消息内的文件卡：40dp 图标 + 名 + 「扩展名 大小」（官方 FileCard 版式） */
@Composable
private fun FileAttachmentCard(attachment: ChatAttachment, palette: TcPalette) {
    Row(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.bubble)
            .border(
                width = 1.dp,
                color = if (attachment.isFailed) palette.danger else palette.bubble,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.brand.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TcIcons.File, null, tint = palette.brand, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = attachment.fileName,
                style = TcTypography.bodySmall,
                color = palette.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    attachment.isMissing -> "原件未导入"
                    attachment.state == AttachmentState.UPLOADING -> "上传中"
                    attachment.isFailed -> "上传失败"
                    else -> attachment.fileDescription
                },
                style = TcTypography.bodySmall,
                color = if (attachment.isFailed || attachment.isMissing) {
                    palette.danger.takeIf { attachment.isFailed } ?: palette.labelTertiary
                } else {
                    palette.labelTertiary
                },
            )
        }
    }
}

/** 图片快捷提问（官方同款）：点一下直接带着图发出去 */
private val ImageQuickPrompts = listOf("这是什么", "分析一下", "怎么办", "解释一下")

@Composable
private fun QuickPromptChip(text: String, onClick: () -> Unit) {
    val palette = TcTheme.palette
    // 只描线、不填充（同「继续生成」那种轻量按钮）
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(100.dp))
            .border(1.dp, palette.borderL2, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = TcTypography.bodyMedium,
                color = palette.labelPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "→",
                style = TcTypography.bodyMedium,
                color = palette.labelTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 输入框上方的草稿缩略图（64dp，右上角删除） */
@Composable
private fun DraftChip(draft: DraftAttachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    val palette = TcTheme.palette
    val load = rememberBitmapLoad(draft.thumbPath, 192)
    Box(modifier = Modifier.size(56.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.bubble)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            if (draft.isImage && load.bitmap != null) {
                Image(
                    bitmap = load.bitmap,
                    contentDescription = draft.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(TcIcons.File, null, tint = palette.labelSecondary, modifier = Modifier.size(20.dp))
                    Text(
                        text = draft.fileName.substringAfterLast('.').uppercase().take(4),
                        style = TcTypography.bodySmall,
                        color = palette.labelTertiary,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(palette.labelSecondary)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TcIcons.Close,
                contentDescription = "移除附件",
                tint = palette.bg,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}

/** 附件来源面板（相册 / 拍照 / 文件）。第三方视觉只收图片，所以不显示「选择文件」 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    showCamera: Boolean,
    showFiles: Boolean,
    onDismiss: () -> Unit,
    onImages: () -> Unit,
    onCamera: () -> Unit,
    onFiles: () -> Unit,
) {
    val palette = TcTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.bg,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetAction(TcIcons.Photo, "从相册选择", onImages)
            if (showCamera) SheetAction(TcIcons.Camera, "拍照", onCamera)
            if (showFiles) SheetAction(TcIcons.Folder, "选择文件", onFiles)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.labelSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text = label, style = TcTypography.bodyLarge, color = palette.labelPrimary)
    }
}

/**
 * 放大看一张图（草稿与消息共用）。黑框就是最终承载图片的容器。
 *
 * ⚠️ 两个坑：
 * 1. 「还在解码」和「真的没有」必须分开：解码是异步的，第一帧必然是空，
 *    早先把空当成"图没了"，于是先闪一句「图片已不在本机」再出图；
 * 2. 容器**第一帧就要有最终尺寸**（按附件宽高算好的 `aspectRatio`）：
 *    否则加载态是被内容撑出来的"又宽又矮的黑条"，出图后盒子突然变高、
 *    弹窗重新居中，看起来就是"图先偏上、再往上跳一下"。
 */
@Composable
private fun AttachmentPreviewDialog(
    thumbPath: String?,
    title: String,
    aspectRatio: Float?,
    onDismiss: () -> Unit,
) {
    if (thumbPath == null) return
    val load = rememberBitmapLoad(thumbPath, 1080)
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (aspectRatio != null && aspectRatio > 0f) {
                        Modifier.aspectRatio(aspectRatio)
                    } else {
                        // 老数据可能没带宽高：至少别把盒子压成一条
                        Modifier.heightIn(min = 160.dp)
                    },
                )
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            when {
                load.bitmap != null -> Image(
                    bitmap = load.bitmap,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                // 还在解码：同一个盒子里转圈，尺寸不变
                load.loading -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                // 解码真的失败了（文件被删/损坏）才说这一句
                else -> Text(
                    text = "图片已不在本机",
                    style = TcTypography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

/** 一次异步解码的三种状态：还在读 / 读出来了 / 读不出来 */
private data class BitmapLoad(val bitmap: ImageBitmap? = null, val loading: Boolean = true)

@Composable
private fun rememberBitmapLoad(path: String?, targetPx: Int): BitmapLoad {
    val state by produceState(BitmapLoad(), path, targetPx) {
        value = BitmapLoad(loading = true)
        val bitmap = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { decodeSampled(path, targetPx) }.getOrNull()
        }
        value = BitmapLoad(bitmap = bitmap, loading = false)
    }
    return state
}

/** 按目标像素降采样解码，避免把 4000px 的原图整张读进内存 */
private fun decodeSampled(path: String, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

/** 按目标像素降采样解码，避免把 4000px 的原图整张读进内存 */
@Composable
private fun rememberAttachmentBitmap(path: String?, targetPx: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path, targetPx) {
        value = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { decodeSampled(path, targetPx) }.getOrNull()
            }
        }
    }
    return bitmap
}

/** 拍照的落点：cache/camera/ 下，用 FileProvider 授权给相机 App */
private fun createCameraUri(context: Context): Uri? {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
