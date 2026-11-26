package io.tinychat.model.billing

import io.tinychat.model.Usage
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 价格与费用（M7-1）。
 *
 * 三条硬规矩：
 * 1. **钱只用 `Long` 微元**（1 元 = 1e6 微元）累加，绝不出现 `Double` —— 浮点会把
 *    ￥0.0123 算成 0.0122999，显示时再四舍五入就是两笔账。
 * 2. 单价按「元/百万 token」写进代码（与官方价目表同一口径），换算成微元/百万 token：
 *    `元 × 1e6`。0.02 元 → 20_000。
 * 3. 计费依据是**最后一个 `message_delta.usage`**（见 [Usage]），不累加中间值。
 */
data class TokenPrice(
    /** 未命中缓存的输入，微元/百万 token */
    val inputMissPerMillion: Long,
    /** 命中缓存的输入 */
    val inputHitPerMillion: Long,
    val outputPerMillion: Long,
)

data class ModelPricing(
    val idle: TokenPrice,
    val peak: TokenPrice,
)

data class Pricing(
    /** 价格表生效日期（ISO），便于日后对账；远端覆盖留到 v1.1 */
    val effectiveFrom: String,
    /** true = 限时价，UI 不该当长期承诺 */
    val limitedOffer: Boolean,
    /** 每次搜索/抓取/图片搜索的费用（微元）：0.005 元 */
    val toolCallMicros: Long,
    val models: Map<String, ModelPricing>,
) {
    fun forModel(modelId: String): ModelPricing? = models[modelId]
}

/** 2026-11 官方价目表（元/百万 token，空闲 / 高峰两档）。 */
object DefaultPricing {

    private fun price(hitIdle: Double, missIdle: Double, outIdle: Double) = ModelPricing(
        idle = TokenPrice(
            inputMissPerMillion = (missIdle * 1_000_000).toLong(),
            inputHitPerMillion = (hitIdle * 1_000_000).toLong(),
            outputPerMillion = (outIdle * 1_000_000).toLong(),
        ),
        // 高峰 = 空闲单价翻倍（官方两档就是 2×）
        peak = TokenPrice(
            inputMissPerMillion = (missIdle * 2 * 1_000_000).toLong(),
            inputHitPerMillion = (hitIdle * 2 * 1_000_000).toLong(),
            outputPerMillion = (outIdle * 2 * 1_000_000).toLong(),
        ),
    )

    val value = Pricing(
        effectiveFrom = "2026-11-01",
        limitedOffer = true,
        toolCallMicros = 5_000,
        models = mapOf(
            "deepseek-flash" to price(hitIdle = 0.02, missIdle = 1.0, outIdle = 4.0),
            "deepseek-v4-pro" to price(hitIdle = 0.15, missIdle = 4.5, outIdle = 13.5),
        ),
    )
}

/**
 * 峰谷判定（北京时间工作日 9:00–12:00、14:00–18:00 为高峰）。
 *
 * 法定节假日**近似忽略**（官方没有公开的调休表），UI 文案只说「按工作日时段估算」。
 */
object PeakClock {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    fun isPeak(now: ZonedDateTime = ZonedDateTime.now(ZONE)): Boolean {
        val day = now.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        val morning = !time.isBefore(MORNING_START) && time.isBefore(MORNING_END)
        val afternoon = !time.isBefore(AFTERNOON_START) && time.isBefore(AFTERNOON_END)
        return morning || afternoon
    }

    private val MORNING_START: LocalTime = LocalTime.of(9, 0)
    private val MORNING_END: LocalTime = LocalTime.NOON
    private val AFTERNOON_START: LocalTime = LocalTime.of(14, 0)
    private val AFTERNOON_END: LocalTime = LocalTime.of(18, 0)
}

/** 一次请求的费用。没有价格表的模型返回 0（不猜）。 */
object CostCalculator {

    fun micros(
        usage: Usage,
        model: String,
        peak: Boolean,
        pricing: Pricing = DefaultPricing.value,
    ): Long {
        val modelPricing = pricing.forModel(model) ?: return 0
        val unit = if (peak) modelPricing.peak else modelPricing.idle
        var total = 0L
        total += usage.promptTokens.toLong() * unit.inputMissPerMillion / PER_MILLION
        total += usage.cacheHitTokens.toLong() * unit.inputHitPerMillion / PER_MILLION
        total += usage.completionTokens.toLong() * unit.outputPerMillion / PER_MILLION
        val tools = usage.searchCalls + usage.fetchCalls + usage.imageSearchCalls
        total += tools.toLong() * pricing.toolCallMicros
        return total
    }

    private const val PER_MILLION = 1_000_000L
}

/** 金额格式化：微元 → `￥0.0259` / `￥10.32` / `￥0.0021`。 */
object Money {

    /**
     * 最多四位小数，**去掉尾随零但至少保留两位**：
     * `340_000` → `￥0.34`；`13_160` → `￥0.0132`（四舍五入到四位，别把便宜算成免费）。
     */
    fun format(micros: Long): String {
        val negative = micros < 0
        val abs = kotlin.math.abs(micros)
        // 1e-4 元 = 100 微元；四舍五入到四位小数
        val scaled = (abs + 50) / 100
        val yuan = scaled / 10_000
        val fraction = (scaled % 10_000).toString().padStart(4, '0').trimEnd('0')
        val tail = when {
            fraction.isEmpty() -> "00"
            fraction.length == 1 -> "${fraction}0"
            else -> fraction
        }
        return (if (negative) "-￥" else "￥") + "$yuan.$tail"
    }

    /** 千分位 token：`1919810` → `1,919,810`。 */
    fun tokens(count: Long): String = "%,d".format(count)

    /**
     * 紧凑 token（决策 86）：`742` / `900K` / `8.4M` / `11.0M`。
     *
     * 两级的小数位规则不一样，这是故意的：K 级最大只到 999.9，整数段自己就够读（`900K`），
     * 再补一位小数只是变长；M 级是给人看量级的，少掉那一位反而像估出来的（`11M`）。
     * 进位在同一处判断——999,960 不能印成 `1000K`，要进位成 `1.0M`。
     */
    fun compactTokens(count: Long): String {
        val v = count.coerceAtLeast(0)
        if (v < 1_000) return v.toString()
        if (v < 1_000_000) {
            val tenths = (v + 50) / 100
            if (tenths < 10_000) return dropTenthZero(tenths) + "K"
        }
        // 顶层不设 B：一轮十亿 token 不现实，真到了也照样按 M 显示
        return oneDecimal((v + 50_000) / 100_000) + "M"
    }

    /**
     * 紧凑容量（上下文窗口这类「上限」）：`64K` / `200K` / `1M`，**不带多余的小数**。
     *
     * 和 [compactTokens] 分开是因为语义不同：窗口大小是几个写死的整数（1M、200K），
     * 写成 `1.0M` 只会让人以为还量了一次。
     */
    fun compactCapacity(count: Long): String {
        val v = count.coerceAtLeast(0)
        if (v < 1_000) return v.toString()
        if (v < 1_000_000) {
            val tenths = (v + 50) / 100
            if (tenths < 10_000) return dropTenthZero(tenths) + "K"
        }
        return dropTenthZero((v + 50_000) / 100_000) + "M"
    }

    /** `9000` → `900`；`431` → `43.1` */
    private fun dropTenthZero(tenths: Long): String =
        if (tenths % 10 == 0L) (tenths / 10).toString() else oneDecimal(tenths)

    /** `110` → `11.0`；`84` → `8.4` */
    private fun oneDecimal(tenths: Long): String = "${tenths / 10}.${tenths % 10}"
}
