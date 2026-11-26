package io.tinychat.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 菜单落点的 8 条边界（`06` §三）。
 * 这些用例对应真机会遇到的位置：贴顶、贴左右边、窄屏、上下都不够。
 */
class MenuPlacementTest {

    private val window = IntSize(1080, 2400)
    private val margin = 8
    private val gap = 20

    /** 触点：零尺寸矩形，x 就是手指所在 */
    private fun pointer(x: Int, y: Int) = IntRect(x, y, x, y)

    @Test
    fun `贴在触点上方的正中`() {
        val offset = menuOffset(pointer(540, 1200), IntSize(300, 132), window, gap, margin)
        assertEquals(IntOffset(390, 1048), offset)
    }

    @Test
    fun `以整行中心对齐并贴住行顶`() {
        val row = IntRect(16, 1000, 1064, 1044)
        val offset = menuOffset(row, IntSize(300, 140), window, gap = 8, margin = margin)
        assertEquals(IntOffset(390, 852), offset)
    }

    @Test
    fun `上方放不下就翻到下方`() {
        val offset = menuOffset(pointer(540, 100), IntSize(300, 132), window, gap, margin)
        assertEquals(IntOffset(390, 120), offset)
    }

    @Test
    fun `贴左边缘时往里推`() {
        val offset = menuOffset(pointer(50, 1200), IntSize(300, 132), window, gap, margin)
        assertEquals(8, offset.x)
    }

    @Test
    fun `贴右边缘时往里推`() {
        val offset = menuOffset(pointer(1030, 1200), IntSize(300, 132), window, gap, margin)
        assertEquals(772, offset.x)
    }

    @Test
    fun `上下都放不下时取上方并钳在边距上`() {
        val narrow = IntSize(320, 400)
        val offset = menuOffset(pointer(160, 200), IntSize(300, 360), narrow, gap, margin)
        assertEquals(IntOffset(10, 8), offset)
    }

    @Test
    fun `菜单比窗口还宽时仍然不越界`() {
        val narrow = IntSize(320, 400)
        val offset = menuOffset(pointer(160, 200), IntSize(400, 80), narrow, gap, margin)
        assertEquals(8, offset.x)
    }

    @Test
    fun `上下都放不下且下方空间更大时取下方`() {
        val narrow = IntSize(400, 300)
        val offset = menuOffset(pointer(200, 100), IntSize(300, 200), narrow, gap, margin)
        assertEquals(IntOffset(50, 92), offset)
    }

    @Test
    fun `贴左对齐时弹出层左边缘与锚点左边缘重合`() {
        val row = IntRect(48, 1000, 400, 1096)
        val offset = menuOffset(row, IntSize(720, 300), window, gap = 8, margin = margin, align = HorizontalAlign.Left)
        assertEquals(IntOffset(48, 692), offset)
    }

    @Test
    fun `贴左对齐且锚点贴屏幕边时往里推`() {
        val row = IntRect(2, 1000, 300, 1096)
        val offset = menuOffset(row, IntSize(720, 300), window, gap = 8, margin = margin, align = HorizontalAlign.Left)
        assertEquals(8, offset.x)
    }

    @Test
    fun `贴左对齐且锚点靠右时钳在右边界`() {
        val row = IntRect(900, 1000, 1060, 1096)
        val offset = menuOffset(row, IntSize(720, 300), window, gap = 8, margin = margin, align = HorizontalAlign.Left)
        assertEquals(352, offset.x)
    }
}
