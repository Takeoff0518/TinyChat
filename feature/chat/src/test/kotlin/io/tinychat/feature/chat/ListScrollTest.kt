package io.tinychat.feature.chat

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「到底」的判定必须是**末条底边**贴住视口底边，而不是末条顶边贴住视口顶边。
 *
 * 回归来源：末条（AI 回答）比一屏高时，`animateScrollToItem(lastIndex)` 把它顶边对齐
 * 视口顶边就停了 —— 下面还差一大截，滚动按钮也不消失。
 */
class ListScrollTest {

    @Test
    fun `末条高于视口且顶边对齐时，还差整个溢出高度`() {
        val info = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 3,
            items = listOf(item(index = 2, offset = 0, size = 4000)),
        )

        assertEquals(2200f, info.residualToBottom(), 0.01f)
    }

    @Test
    fun `末条底边贴住视口底边时余量为零`() {
        val info = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 3,
            items = listOf(item(index = 2, offset = -1200, size = 3000)),
        )

        assertEquals(0f, info.residualToBottom(), 0.01f)
    }

    @Test
    fun `末条底边超出视口底边时只补超出部分`() {
        val info = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 3,
            items = listOf(item(index = 2, offset = -900, size = 3000)),
        )

        assertEquals(300f, info.residualToBottom(), 0.01f)
    }

    @Test
    fun `末条还没进入视口时不补余量，交给调用方先跳过去`() {
        val info = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 5,
            items = listOf(item(index = 1, offset = 0, size = 900)),
        )

        assertEquals(0f, info.residualToBottom(), 0.01f)
    }

    @Test
    fun `空列表余量为零`() {
        val info = layout(viewportEndOffset = 1800, totalItemsCount = 0, items = emptyList())

        assertEquals(0f, info.residualToBottom(), 0.01f)
    }

    @Test
    fun `末条底边贴住就算到底，底部留白不算`() {
        val atBottom = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 3,
            items = listOf(item(index = 2, offset = -1200, size = 3000)),
        )
        val paddingOnly = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 3,
            // 正文已经贴住底边，剩下的 12dp contentPadding 还能再滚一点点：这仍算到底。
            items = listOf(item(index = 2, offset = -1212, size = 3000)),
        )

        assertEquals(true, atBottom.isAtLatestBottom())
        assertEquals(true, paddingOnly.isAtLatestBottom())
    }

    @Test
    fun `用户翻到上面时不算到底`() {
        val info = layout(
            viewportEndOffset = 1800,
            totalItemsCount = 5,
            items = listOf(item(index = 1, offset = 0, size = 900)),
        )

        assertEquals(false, info.isAtLatestBottom())
    }

    // —— 决策 74：「回到底部」的显示条件 ——

    @Test
    fun `跟随中的那一两帧不显示按钮`() {
        // 正文刚长出来、滚动还没补上的那一帧：确实不在底部，但用户没走开，不该弹按钮
        assertEquals(false, shouldShowToBottom(following = true, atBottom = false))
    }

    @Test
    fun `用户自己划走之后才显示按钮`() {
        assertEquals(true, shouldShowToBottom(following = false, atBottom = false))
    }

    @Test
    fun `划回底部后按钮消失`() {
        assertEquals(false, shouldShowToBottom(following = false, atBottom = true))
    }

    private fun layout(
        viewportEndOffset: Int,
        totalItemsCount: Int,
        items: List<LazyListItemInfo>,
    ) = object : LazyListLayoutInfo {
        override val visibleItemsInfo: List<LazyListItemInfo> = items
        override val viewportStartOffset: Int = 0
        override val viewportEndOffset: Int = viewportEndOffset
        override val totalItemsCount: Int = totalItemsCount
        override val viewportSize: IntSize = IntSize(1080, viewportEndOffset)
        override val reverseLayout: Boolean = false
        override val orientation: Orientation = Orientation.Vertical
        override val afterContentPadding: Int = 0
        override val beforeContentPadding: Int = 0
        override val mainAxisItemSpacing: Int = 0
    }

    private fun item(index: Int, offset: Int, size: Int) = object : LazyListItemInfo {
        override val index: Int = index
        override val key: Any = index
        override val offset: Int = offset
        override val size: Int = size
        override val contentType: Any? = null
    }
}
