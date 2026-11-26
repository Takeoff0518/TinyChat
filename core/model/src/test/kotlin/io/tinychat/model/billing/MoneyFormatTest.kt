package io.tinychat.model.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 紧凑 token 的位数规则（决策 86）。
 *
 * 这组用例钉三件事：K 级去掉尾随 `.0`、M 级永远保留一位小数、以及**进位不能留下 `1000K`**。
 * 最后那条是旧的抽屉格式器真出过的毛病（999,960 会印成 `1000K`）。
 */
class MoneyFormatTest {

    @Test
    fun `K级去掉尾随零点`() {
        assertEquals("0", Money.compactTokens(0))
        assertEquals("742", Money.compactTokens(742))
        assertEquals("999", Money.compactTokens(999))
        assertEquals("1K", Money.compactTokens(1_000))
        assertEquals("1.2K", Money.compactTokens(1_240))
        assertEquals("43.1K", Money.compactTokens(43_135))
        assertEquals("900K", Money.compactTokens(900_000))
        assertEquals("999.9K", Money.compactTokens(999_949))
    }

    @Test
    fun `K级四舍五入到一千整时进位成M`() {
        assertEquals("1.0M", Money.compactTokens(999_950))
        assertEquals("1.0M", Money.compactTokens(999_999))
    }

    @Test
    fun `M级保留一位小数`() {
        assertEquals("1.0M", Money.compactTokens(1_000_000))
        assertEquals("1.5M", Money.compactTokens(1_500_000))
        assertEquals("8.4M", Money.compactTokens(8_388_608))
        assertEquals("11.0M", Money.compactTokens(10_992_127))
        assertEquals("100.0M", Money.compactTokens(100_000_000))
    }

    @Test
    fun `负数按零处理`() {
        assertEquals("0", Money.compactTokens(-5))
        assertEquals("0", Money.compactCapacity(-5))
    }

    @Test
    fun `容量不带多余小数_窗口大小写成整数`() {
        assertEquals("64K", Money.compactCapacity(64_000))
        assertEquals("128K", Money.compactCapacity(128_000))
        assertEquals("200K", Money.compactCapacity(200_000))
        assertEquals("1M", Money.compactCapacity(1_000_000))
    }
}
