package io.tinychat.feature.chat

import io.tinychat.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 每轮用量明细（决策 86）。
 *
 * 折叠行只给量级，四行精确值在点开之后；这里钉住顺序与口径，
 * 保证「本轮用量」永远等于另外三项之和——不然用户点开发现三个数加起来对不上，就没人信了。
 */
class UsageBreakdownTest {

    /** 用户给的样例：命中 10,928,384 + 未命中 43,135 + 输出 20,608 = 10,992,127。 */
    private val sample = Usage(
        promptTokens = 43_135,
        cacheHitTokens = 10_928_384,
        completionTokens = 20_608,
    )

    @Test
    fun `四行顺序固定_标签依次是本轮用量与三项明细`() {
        val rows = usageBreakdownRows(sample)
        assertEquals(4, rows.size)
        assertEquals(R.string.chat_usage_this_round, rows[0].labelRes)
        assertEquals(R.string.chat_cache_hits, rows[1].labelRes)
        assertEquals(R.string.chat_uncached_input, rows[2].labelRes)
        assertEquals(R.string.chat_output, rows[3].labelRes)
    }

    @Test
    fun `本轮用量等于后三项之和`() {
        val rows = usageBreakdownRows(sample)
        assertEquals(10_992_127L, rows[0].tokens)
        assertEquals(rows.drop(1).sumOf { it.tokens }, rows[0].tokens)
        assertEquals(10_928_384L, rows[1].tokens)
        assertEquals(43_135L, rows[2].tokens)
        assertEquals(20_608L, rows[3].tokens)
    }

    @Test
    fun `没有缓存的轮次也不出错`() {
        val rows = usageBreakdownRows(Usage(promptTokens = 1_200, completionTokens = 300))
        assertEquals(1_500L, rows[0].tokens)
        assertEquals(0L, rows[1].tokens)
    }
}
