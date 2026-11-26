package io.tinychat.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/** 水平方向怎么对锚点：菜单居中（正对触点/行），数据卡片贴左（与正文左边缘对齐）。 */
internal enum class HorizontalAlign { Center, Left }

/**
 * 菜单落点的纯计算：给定锚点矩形、菜单尺寸、窗口尺寸，算出菜单左上角的窗口坐标。
 *
 * 规则（`06` §二）：
 * - 水平：以锚点中心对齐，左右各留 [margin]，超界就往里推；
 * - 垂直：默认贴在锚点上方、留 [gap]；上方放不下就翻到下方；上下都放不下就取空间大的一侧，再钳进窗口。
 *
 * [align] 为 [HorizontalAlign.Left] 时改成「弹出层左边缘与锚点左边缘重合」：
 * 用在每轮用量明细卡上——那张卡是贴着正文左边缘的数据表，居中会看起来浮在句子中间（决策 86）。
 *
 * 抽成纯函数是为了能在 JVM 上把边界跑完，不必上真机拿手指试。
 */
internal fun menuOffset(
    anchor: IntRect,
    popup: IntSize,
    window: IntSize,
    gap: Int,
    margin: Int,
    align: HorizontalAlign = HorizontalAlign.Center,
): IntOffset {
    val maxX = (window.width - margin - popup.width).coerceAtLeast(margin)
    val x = when (align) {
        HorizontalAlign.Center -> anchor.center.x - popup.width / 2
        HorizontalAlign.Left -> anchor.left
    }.coerceIn(margin, maxX)

    val above = anchor.top - gap - popup.height
    val below = anchor.bottom + gap
    val maxY = (window.height - margin - popup.height).coerceAtLeast(margin)

    val y = when {
        above >= margin -> above
        below <= maxY -> below
        // 上下都放不下：比较两侧剩余空间，取大的那边
        anchor.top >= window.height - anchor.bottom -> margin
        else -> maxY
    }
    return IntOffset(x, y.coerceIn(margin, maxY))
}
