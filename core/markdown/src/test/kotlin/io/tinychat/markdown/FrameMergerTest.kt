package io.tinychat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMergerTest {
    private var now = 0L

    private fun merger() = FrameMerger(clockMs = { now })

    @Test
    fun `首次追加不被节流`() {
        val merger = merger()
        assertEquals(0L, merger.millisUntilNextUpdate())
    }

    @Test
    fun `按渲染耗时的两倍推迟下一帧`() {
        val merger = merger()
        now = 1_000L
        merger.recordCost(100L)

        assertEquals(200L, merger.millisUntilNextUpdate())

        now = 1_100L
        assertEquals(100L, merger.millisUntilNextUpdate())

        now = 1_200L
        assertEquals(0L, merger.millisUntilNextUpdate())
    }

    @Test
    fun `最长等待封顶两秒`() {
        val merger = merger()
        now = 0L
        // 解析 1.5s 的极端情况：×2 = 3s，但封顶 2s
        merger.recordCost(1_500L)

        assertEquals(2_000L, merger.millisUntilNextUpdate())
    }

    @Test
    fun `零耗时也允许立刻出下一帧`() {
        val merger = merger()
        now = 500L
        merger.recordCost(0L)

        assertEquals(0L, merger.millisUntilNextUpdate())
    }

    @Test
    fun `负耗时不会把时钟往回调`() {
        val merger = merger()
        now = 500L
        merger.recordCost(-1_000L)

        assertEquals(0L, merger.millisUntilNextUpdate())
    }

    @Test
    fun `连续记录以最后一次为准`() {
        val merger = merger()
        now = 0L
        merger.recordCost(300L)
        now = 100L
        merger.recordCost(50L)

        // 100 + 100 = 200，而不是 0 + 600
        assertEquals(100L, merger.millisUntilNextUpdate())
    }
}
