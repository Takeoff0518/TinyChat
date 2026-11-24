package io.tinychat.feature.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.model.SessionGroup
import io.tinychat.model.SessionRow
import io.tinychat.model.SessionSearchHit
import io.tinychat.model.SessionsUiState
import io.tinychat.model.groupSessions
import io.tinychat.ui.TcAlertDialog
import io.tinychat.ui.MenuAnchor
import io.tinychat.ui.TcAnchorMenu
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcMenuItem
import io.tinychat.ui.TcListRow
import io.tinychat.ui.TcSectionLabel
import kotlinx.coroutines.launch

/**
 * 会话抽屉（02 §12.3）：标题行（右侧多选）→ 搜索框 → 分组的会话列表 → 设置。
 *
 * 余额只在右侧「用量与费用」抽屉里出现（决策 52）：两处各显示一遍没有意义，
 * 右侧抽屉打开时会自动刷新，左抽屉那一行只是重复入口。
 *
 * 三种形态：列表态（默认）、搜索态（点搜索框整栏变搜索页）、多选态（底部换成置顶/删除）。
 */
@Composable
fun SessionsDrawerContent(
    state: SessionsUiState,
    currentSessionId: String?,
    isOpen: Boolean,
    onOpenSettings: () -> Unit,
    onPickSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onTogglePinSession: (String, Boolean) -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPickSearchResult: (String) -> Unit,
    onEnterSelection: () -> Unit,
    onExitSelection: () -> Unit,
    onToggleSelected: (String) -> Unit,
    onPinSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val palette = TcTheme.palette
    var renaming by remember { mutableStateOf<SessionRow?>(null) }
    var deleting by remember { mutableStateOf<SessionRow?>(null) }
    var confirmingBatchDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(palette.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TinyChat",
                style = TcTypography.titleLarge,
                color = palette.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!state.selectionMode && !state.searching) {
                TcIconButton(TcIcons.MultiSelect, stringResource(R.string.sessions_multi_select), onEnterSelection)
            }
        }

        when {
            state.searching -> SearchBar(
                query = state.query,
                onQueryChange = onSearchQueryChange,
                onClose = onCloseSearch,
            )
            state.selectionMode -> SelectionBar(onClose = onExitSelection)
            else -> SearchEntry(onClick = onOpenSearch)
        }

        if (state.searching) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SearchResults(
                    query = state.query,
                    results = state.results,
                    onPick = onPickSearchResult,
                )
            }
        } else {
            SessionListBody(
                state = state,
                currentSessionId = currentSessionId,
                isOpen = isOpen,
                modifier = Modifier.weight(1f),
                onPickSession = onPickSession,
                onRename = { renaming = it },
                onTogglePin = { row -> onTogglePinSession(row.id, !row.pinned) },
                onDelete = { deleting = it },
                onToggleSelected = onToggleSelected,
            )
        }

        if (state.selectionMode) {
            SelectionActions(
                enabled = state.selected.isNotEmpty(),
                onPin = onPinSelected,
                onDelete = { confirmingBatchDelete = true },
            )
        } else {
            Spacer(Modifier.height(4.dp))
            TcListRow(
                title = stringResource(R.string.sessions_settings),
                leading = TcIcons.Settings,
                onClick = onOpenSettings,
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (confirmingBatchDelete) {
        val selectedCount = state.selected.size
        TcAlertDialog(
            onDismissRequest = { confirmingBatchDelete = false },
            title = {
                Text(
                    pluralStringResource(R.plurals.sessions_delete_selected, selectedCount, selectedCount),
                    style = TcTypography.titleMedium,
                    color = palette.labelPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.sessions_remove_selected),
                    style = TcTypography.bodyMedium,
                    color = palette.labelSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingBatchDelete = false
                    onDeleteSelected()
                }) {
                    Text(stringResource(R.string.sessions_delete), style = TcTypography.bodyMedium, color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBatchDelete = false }) {
                    Text(stringResource(R.string.sessions_cancel), style = TcTypography.bodyMedium, color = palette.labelSecondary)
                }
            },
            containerColor = palette.bg,
        )
    }

    renaming?.let { row ->
        RenameDialog(
            initial = row.localizedTitle(),
            onDismiss = { renaming = null },
            onConfirm = { title ->
                renaming = null
                onRenameSession(row.id, title)
            },
        )
    }

    deleting?.let { row ->
        TcAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sessions_delete_chat), style = TcTypography.titleMedium, color = palette.labelPrimary) },
            text = {
                Text(
                    text = stringResource(R.string.sessions_remove_chat, row.localizedTitle()),
                    style = TcTypography.bodyMedium,
                    color = palette.labelSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDeleteSession(row.id)
                }) {
                    Text(stringResource(R.string.sessions_delete), style = TcTypography.bodyMedium, color = palette.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.sessions_cancel), style = TcTypography.bodyMedium, color = palette.labelSecondary)
                }
            },
            containerColor = palette.bg,
        )
    }
}

/** 搜索入口：长得像输入框，点了整栏切搜索页。 */
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.selector)
            .clickable(onClick = onClick)
            .heightIn(min = 38.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TcIcons.Search, null, tint = palette.labelTertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.sessions_search), style = TcTypography.bodyMedium, color = palette.labelTertiary)
    }
}

/** 搜索页顶栏：返回 + 输入框。 */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val palette = TcTheme.palette
    val focusRequester = remember { FocusRequester() }
    // 进搜索页就聚焦并弹输入法：少一次点击
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TcIconButton(TcIcons.Back, stringResource(R.string.sessions_close_search), onClose)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.selector)
                .heightIn(min = 38.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TcIcons.Search, null, tint = palette.labelTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TcTypography.bodyMedium.copy(color = palette.labelPrimary),
                cursorBrush = SolidColor(palette.brand),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.sessions_search),
                                style = TcTypography.bodyMedium,
                                color = palette.labelTertiary,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(palette.labelTertiary)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TcIcons.Close, stringResource(R.string.sessions_clear_search), tint = palette.bg, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

/** 多选态顶栏：把搜索框换成「选择对话 + 关闭」。 */
@Composable
private fun SelectionBar(onClose: () -> Unit) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sessions_select_chats),
            style = TcTypography.bodyLarge,
            color = palette.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        TcIconButton(TcIcons.Close, stringResource(R.string.sessions_close_selection), onClose)
    }
}

/** 多选态底部：置顶 / 删除。 */
@Composable
private fun SelectionActions(enabled: Boolean, onPin: () -> Unit, onDelete: () -> Unit) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionAction(
            label = stringResource(R.string.sessions_pin),
            icon = TcIcons.Pin,
            enabled = enabled,
            tint = if (enabled) palette.labelPrimary else palette.labelTertiary,
            modifier = Modifier.weight(1f),
            onClick = onPin,
        )
        SelectionAction(
            label = stringResource(R.string.sessions_delete),
            icon = TcIcons.Trash,
            enabled = enabled,
            tint = if (enabled) palette.danger else palette.labelTertiary,
            modifier = Modifier.weight(1f),
            onClick = onDelete,
        )
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TcTypography.bodyMedium, color = tint)
    }
}

private sealed interface DrawerItem {
    data class Header(val group: SessionGroup) : DrawerItem
    data class Row(val session: SessionRow) : DrawerItem
}

private val SessionGroup.labelRes: Int
    get() = when (this) {
        SessionGroup.PINNED -> R.string.sessions_group_pinned
        SessionGroup.TODAY -> R.string.sessions_group_today
        SessionGroup.YESTERDAY -> R.string.sessions_group_yesterday
        SessionGroup.LAST_7_DAYS -> R.string.sessions_group_week
        SessionGroup.LAST_30_DAYS -> R.string.sessions_group_month
        SessionGroup.EARLIER -> R.string.sessions_group_earlier
    }

@Composable
private fun SessionRow.localizedTitle(): String =
    if (title.isNullOrBlank()) stringResource(R.string.sessions_new_chat) else displayTitle

@Composable
private fun SessionListBody(
    state: SessionsUiState,
    currentSessionId: String?,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    onPickSession: (String) -> Unit,
    onRename: (SessionRow) -> Unit,
    onTogglePin: (SessionRow) -> Unit,
    onDelete: (SessionRow) -> Unit,
    onToggleSelected: (String) -> Unit,
) {
    val palette = TcTheme.palette
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 长按菜单挂在整行上方（决策 44）。状态提到列表这一层，是因为「列表一动就关菜单」
    // 只能在知道滚动状态的地方做；每行各留一份状态的话，滚走的那一行没人负责关。
    var menuRow by remember { mutableStateOf<SessionRow?>(null) }
    var menuBounds by remember { mutableStateOf<IntRect?>(null) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) menuRow = null
    }
    LaunchedEffect(state.selectionMode) {
        if (state.selectionMode) menuRow = null
    }
    LaunchedEffect(isOpen) {
        if (!isOpen) menuRow = null
    }

    val items = remember(state.rows) {
        buildList {
            groupSessions(state.rows, System.currentTimeMillis()).forEach { section ->
                add(DrawerItem.Header(section.group))
                section.rows.forEach { add(DrawerItem.Row(it)) }
            }
        }
    }

    // 第一次打开时定位当前会话；从设置返回时沿用抽屉原来的滚动位置。
    var positionedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(isOpen, currentSessionId, items) {
        if (!isOpen) {
            positionedSessionId = null
            return@LaunchedEffect
        }
        if (currentSessionId == null || positionedSessionId == currentSessionId) return@LaunchedEffect
        val index = items.indexOfFirst { it is DrawerItem.Row && it.session.id == currentSessionId }
        // 落到分组标题上：直接滚到会话行会把「今天/昨天」的标题顶出屏幕
        if (index >= 0) {
            listState.scrollToItem((index - 1).coerceAtLeast(0))
            positionedSessionId = currentSessionId
        }
    }

    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.sessions_empty),
            style = TcTypography.bodyMedium,
            color = palette.labelTertiary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp),
        )
        return
    }

    LazyColumn(state = listState, modifier = modifier) {
        items(
            items = items,
            key = { item ->
                when (item) {
                    is DrawerItem.Header -> "h-${item.group.name}"
                    is DrawerItem.Row -> item.session.id
                }
            },
        ) { item ->
            when (item) {
                is DrawerItem.Header -> TcSectionLabel(stringResource(item.group.labelRes))
                is DrawerItem.Row -> SessionItem(
                    row = item.session,
                    selected = item.session.id == currentSessionId,
                    selectionMode = state.selectionMode,
                    checked = item.session.id in state.selected,
                    highlighted = menuRow?.id == item.session.id,
                    onClick = {
                        if (state.selectionMode) {
                            onToggleSelected(item.session.id)
                        } else {
                            onPickSession(item.session.id)
                        }
                    },
                    onMenuRequest = { bounds ->
                        menuBounds = bounds
                        menuRow = item.session
                    },
                )
            }
        }
    }

    // 已移除行尾的「⋯」：官方 App 没有这个按钮，长按整行才是一级入口。
    // 位置对齐整行中心、从行上方弹出，手指按在行中间时菜单就在正上方。
    val target = menuRow
    val bounds = menuBounds
    if (target != null && bounds != null) {
        TcAnchorMenu(
            visible = true,
            anchor = MenuAnchor.Row(bounds),
            onDismiss = { menuRow = null },
            items = listOf(
                TcMenuItem(stringResource(R.string.sessions_rename)) {
                    menuRow = null
                    onRename(target)
                },
                TcMenuItem(stringResource(if (target.pinned) R.string.sessions_unpin else R.string.sessions_pin)) {
                    menuRow = null
                    onTogglePin(target)
                    // 置顶后滚到顶部，否则被列表位置锚定顶到屏幕外，看着像没生效
                    scope.launch { listState.animateScrollToItem(0) }
                },
                TcMenuItem(stringResource(R.string.sessions_delete), danger = true) {
                    menuRow = null
                    onDelete(target)
                },
            ),
        )
    }
}

@Composable
private fun SessionItem(
    row: SessionRow,
    selected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    /** 这一行的菜单正开着：底色标出来，用户才知道菜单属于哪一条 */
    highlighted: Boolean,
    onClick: () -> Unit,
    onMenuRequest: (IntRect) -> Unit,
) {
    val palette = TcTheme.palette
    val haptics = LocalHapticFeedback.current
    val chatActionsLabel = stringResource(R.string.sessions_chat_actions)
    var bounds by remember { mutableStateOf(IntRect.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if ((selected && !selectionMode) || highlighted) palette.selector else Color.Transparent,
            )
            .onGloballyPositioned {
                val rect = it.boundsInWindow()
                bounds = IntRect(
                    left = rect.left.toInt(),
                    top = rect.top.toInt(),
                    right = rect.right.toInt(),
                    bottom = rect.bottom.toInt(),
                )
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (!selectionMode) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMenuRequest(bounds)
                    }
                },
            )
            .semantics {
                // 行尾的「⋯」删掉之后，读屏用户没有别的入口，这里补一个等价动作
                if (!selectionMode) {
                    customActions = listOf(
                        CustomAccessibilityAction(chatActionsLabel) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMenuRequest(bounds)
                            true
                        },
                    )
                }
            }
            .heightIn(min = 44.dp)
            .padding(start = if (selectionMode) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = checked)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = row.localizedTitle(),
            style = TcTypography.bodyLarge,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 手绘复选框：勾是 TcIcons.Check，未选是描边方框。 */
@Composable
private fun Checkbox(checked: Boolean) {
    val palette = TcTheme.palette
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) palette.brand else Color.Transparent)
            .then(if (checked) Modifier else Modifier.border(1.dp, palette.labelTertiary, RoundedCornerShape(5.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(TcIcons.Check, null, tint = palette.brandOn, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SearchResults(
    query: String,
    results: List<SessionSearchHit>,
    onPick: (String) -> Unit,
) {
    val palette = TcTheme.palette

    if (query.isBlank()) return
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.sessions_no_results),
            style = TcTypography.bodyMedium,
            color = palette.labelTertiary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp),
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(results, key = { it.sessionId }) { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(hit.sessionId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (hit.untitled) stringResource(R.string.sessions_new_chat) else hit.title,
                    style = TcTypography.bodyLarge,
                    color = palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hit.matchedInTitle) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = snippetText(hit, palette.labelPrimary, palette.labelTertiary),
                        style = TcTypography.bodySmall,
                        color = palette.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 正文片段：命中词用主色（黑/白），其余用灰，官方就是这个观感。 */
private fun snippetText(hit: SessionSearchHit, matchColor: Color, restColor: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = restColor)) { append(hit.before) }
        withStyle(SpanStyle(color = matchColor)) { append(hit.match) }
        withStyle(SpanStyle(color = restColor)) { append(hit.after) }
    }

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val palette = TcTheme.palette
    var text by remember { mutableStateOf(initial) }

    TcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_rename_chat), style = TcTypography.titleMedium, color = palette.labelPrimary) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TcTypography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.sessions_save), style = TcTypography.bodyMedium, color = palette.brand)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sessions_cancel), style = TcTypography.bodyMedium, color = palette.labelSecondary)
            }
        },
        containerColor = palette.bg,
    )
}
