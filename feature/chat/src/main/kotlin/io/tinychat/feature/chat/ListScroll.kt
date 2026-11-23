package io.tinychat.feature.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos

/**
 * 「到底」= 末尾条目的**底边**与视口底边（已扣除 `contentPadding`）对齐。
 *
 * 之前用 `animateScrollToItem(lastIndex)`：它只把条目**顶边**对齐视口顶边。
 * AI 回答普遍比一屏高，于是点完按钮停在这条消息的开头 —— 下面还差一大截，
 * 看起来「滚过了」其实还得再手动划一下。
 *
 * 注意不能用 `canScrollForward` 判断到底：底部 `contentPadding` 也算可滚动量，
 * 补完正文余量后它仍然是 true，据此再跳一次末条就会把位置打回正文顶部。
 */

/** 末条是否已经出现在视口里。 */
internal fun LazyListLayoutInfo.latestItemVisible(): Boolean {
    val last: LazyListItemInfo = visibleItemsInfo.lastOrNull() ?: return false
    return last.index >= totalItemsCount - 1
}

/**
 * 末条底边还差多少像素才贴住视口底边。
 *
 * 末条不在视口里、或已经贴住/超出时返回 `0`，调用方据此决定先跳过去还是补余量。
 */
internal fun LazyListLayoutInfo.residualToBottom(): Float {
    if (!latestItemVisible()) return 0f
    val last = visibleItemsInfo.last() ?: return 0f
    return (last.offset + last.size - viewportEndOffset).toFloat()
}

/** 是否已经停在「最新内容」的位置：末条可见且底边贴住视口底边。 */
internal fun LazyListLayoutInfo.isAtLatestBottom(): Boolean =
    latestItemVisible() && residualToBottom() <= 0.5f

/**
 * 「回到底部」圆钮的显示条件。
 *
 * **必须排除「正在跟随」**：跟随过程中每长出新字，在滚动补上的那一两帧里
 * `isAtLatestBottom` 都是 false，只看它会让按钮在生成时一闪一闪，还会盖住正在长的那一行。
 */
internal fun shouldShowToBottom(following: Boolean, atBottom: Boolean): Boolean =
    !following && !atBottom

/**
 * 带动画滚到底，供「滚动到最新」按钮使用。
 */
internal suspend fun LazyListState.animateToBottom() = scrollToLatest(animated = true)

/**
 * 无动画滚到底，供流式输出跟随使用：正文每长一截就贴一次底，
 * 用动画追会一直落在文字后面。
 */
internal suspend fun LazyListState.jumpToBottom() = scrollToLatest(animated = false)

private suspend fun LazyListState.scrollToLatest(animated: Boolean) {
    // 有次数上限：流式输出时条目高度一直在变，不做无界追赶。
    repeat(8) {
        // 「滚不动」才是终点：底部 contentPadding 也是可滚动量，必须把它一起滚出来。
        if (!canScrollForward) return
        val info = layoutInfo
        if (info.totalItemsCount == 0) return
        if (!info.latestItemVisible()) {
            // 末条还不在视口里（新消息刚插进来）：先跳过去，下一轮再补余量。
            val target = info.totalItemsCount - 1
            if (animated) animateScrollToItem(target) else scrollToItem(target)
            withFrameNanos { }
            return@repeat
        }
        val residual = info.residualToBottom()
        // 余量补完却还能滚，说明只剩底部留白：一次吃掉，scrollBy 会自己夹到底。
        val delta = if (residual > 0.5f) residual else info.viewportSize.height.toFloat()
        if (animated) animateScrollBy(delta) else scrollBy(delta)
    }
}

/**
 * 「是否跟随最新内容」。只由**用户自己**的拖动/甩动改变：程序滚动（按钮、流式贴底）
 * 不参与判断，否则一次抖动就会把自动跟随关掉。
 *
 * - 手指一按下就停止跟随，避免和用户抢滚动条；
 * - 甩动停下来后再结算一次：停在底部就恢复跟随，停在半路就保持不跟随。
 */
@Composable
internal fun rememberFollowLatest(listState: LazyListState): MutableState<Boolean> {
    val following = remember(listState) { mutableStateOf(true) }
    var dragging by remember(listState) { mutableStateOf(false) }
    var settleAfterDrag by remember(listState) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    dragging = true
                    settleAfterDrag = false
                    following.value = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    // 没有惯性时 isScrollInProgress 已经是 false，不会再来一次变化，这里直接结算。
                    if (listState.isScrollInProgress) {
                        settleAfterDrag = true
                    } else {
                        following.value = listState.layoutInfo.isAtLatestBottom()
                    }
                }
                else -> Unit
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !dragging && settleAfterDrag) {
                settleAfterDrag = false
                following.value = listState.layoutInfo.isAtLatestBottom()
            }
        }
    }

    return following
}
