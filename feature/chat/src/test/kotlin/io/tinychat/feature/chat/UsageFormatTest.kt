package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽屉里的紧凑数字。
 *
 * 两条规则不同：**用量**按决策 86 写成 `8.4M` / `11.0M`（M 级保留一位小数），
 * **窗口上限**写成 `1M` / `200K`（整数，不留 `.0`）。格式化本体在 `Money` 里，
 * 这里只钉住本模块两个包装函数确实接到了对应的那条规则上。
 */
class UsageFormatTest {

    @Test
    fun `用量紧凑格式_M级保留一位小数`() {
        assertEquals("0", compactTokens(0))
        assertEquals("999", compactTokens(999))
        assertEquals("1K", compactTokens(1_000))
        assertEquals("120K", compactTokens(120_000))
        assertEquals("623.4K", compactTokens(623_400))
        assertEquals("1.0M", compactTokens(1_000_000))
        assertEquals("1.2M", compactTokens(1_234_567))
    }

    @Test
    fun `容量紧凑格式_窗口大小写成整数`() {
        assertEquals("64K", compactCapacity(64_000))
        assertEquals("200K", compactCapacity(200_000))
        assertEquals("1M", compactCapacity(1_000_000))
    }
}
