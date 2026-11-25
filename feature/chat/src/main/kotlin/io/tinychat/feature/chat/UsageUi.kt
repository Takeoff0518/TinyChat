package io.tinychat.feature.chat

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.model.QuotaSnapshot
import io.tinychat.model.QuotaWindow
import io.tinychat.model.Usage
import io.tinychat.model.billing.Money
import io.tinychat.model.billing.UsageSummary
import io.tinychat.ui.TcIconButton

/** 说明文案（点标签展开）。集中在一处，做 i18n 时只改这里。 */
object UsageGlossary {

    const val CONTEXT = "context"
    const val CACHE_HIT = "cacheHit"
    const val COST = "cost"
    const val BALANCE = "balance"
    const val USAGE_TOTAL = "usageTotal"

    @Composable
    fun of(key: String): String = when (key) {
        CONTEXT ->
            stringResource(R.string.chat_how_much_of_the_model_s_context)
        CACHE_HIT ->
            stringResource(R.string.chat_the_provider_reused_part_of_the_previous)
        COST ->
            stringResource(R.string.chat_the_actual_charge_may_differ_from_this)
        BALANCE ->
            stringResource(R.string.chat_balance_reported_by_the_provider)
        USAGE_TOTAL ->
            stringResource(R.string.chat_total_usage_for_this_chat_including_edited)
        else -> ""
    }
}

/** 圆环：18dp / 描边 2dp，`<40%` 品牌色、`≥40%` 琥珀、`≥80%` 红。点开用量抽屉。 */
@Composable
fun ContextRing(
    percent: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = TcTheme.palette
    val color = when {
        percent >= 0.8f -> palette.danger
        percent >= 0.4f -> palette.warning
        else -> palette.brand
    }
    val openUsage = stringResource(R.string.chat_open_usage_and_costs)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .semantics { contentDescription = openUsage }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = palette.borderL2, radius = radius, center = center, style = Stroke(stroke))
            val sweep = 360f * percent.coerceIn(0f, 1f)
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 每轮回答下面那一行（M7-3）：`4,096 命中  9,078 未命中  1,686 输出  ￥0.0259` */
@Composable
fun UsageStatsLine(usage: Usage, costMicros: Long?) {
    val palette = TcTheme.palette
    val tokens = stringResource(
        R.string.chat_usage_stats_line,
        Money.tokens(usage.cacheHitTokens.toLong()),
        Money.tokens(usage.promptTokens.toLong()),
        Money.tokens(usage.completionTokens.toLong()),
    )
    val line = buildString {
        append(tokens)
        // 第三方服务商没有单价表（CostCalculator 返回 0）：只显示 token，不编一个 ￥0.0000
        if (costMicros != null && costMicros > 0) append("  ${Money.format(costMicros)}")
    }
    Text(
        text = line,
        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        color = palette.labelTertiary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * 「用量与费用」抽屉内容。
 *
 * 展示会话的上下文占比、Token 用量、预估费用及可用的账户资料。
 */
@Composable
fun UsageDrawerContent(
    summary: UsageSummary,
    contextUsed: Int,
    contextWindow: Int,
    balanceText: String?,
    /** 订阅额度（M8-6）：OpenCode Go 的三窗口；null = 不是订阅制或没取到 */
    quota: QuotaSnapshot? = null,
    onDismiss: () -> Unit,
) {
    val palette = TcTheme.palette
    var expanded by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(palette.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_usage_and_costs),
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                color = palette.labelPrimary,
            )
            Spacer(Modifier.weight(1f))
            TcIconButton(TcIcons.Close, stringResource(R.string.chat_close), onDismiss)
        }

        Spacer(Modifier.height(16.dp))

        // 上下文占比
        val percent = contextRatio(contextUsed, contextWindow)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExplainableLabel(stringResource(R.string.chat_context_used), UsageGlossary.CONTEXT, expanded) { expanded = it }
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (contextWindow > 0) "${(percent * 100).toInt()}%" else "—",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                color = palette.labelPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (contextWindow > 0) {
                    "~${compactTokens(contextUsed)} / ${compactTokens(contextWindow)}"
                } else {
                    "~${compactTokens(contextUsed)}"
                },
                style = TextStyle(fontSize = 12.sp),
                color = palette.labelTertiary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.inlineCodeBg),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            percent >= 0.8f -> palette.danger
                            percent >= 0.4f -> palette.warning
                            else -> palette.brand
                        },
                    ),
            )
        }
        if (expanded == UsageGlossary.CONTEXT) {
            GlossaryText(UsageGlossary.CONTEXT)
        }

        Spacer(Modifier.height(28.dp))
        ExplainableLabel(stringResource(R.string.chat_token_usage), UsageGlossary.USAGE_TOTAL, expanded) { expanded = it }
        if (expanded == UsageGlossary.USAGE_TOTAL) {
            GlossaryText(UsageGlossary.USAGE_TOTAL)
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                ExplainableLabel(stringResource(R.string.chat_estimated_cost), UsageGlossary.COST, expanded) { expanded = it }
                Text(
                    text = if (summary.costMicros == 0L) "--" else Money.format(summary.costMicros),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = palette.labelPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                ExplainableLabel(stringResource(R.string.chat_cache_hits), UsageGlossary.CACHE_HIT, expanded) { expanded = it }
                Text(
                    text = summary.cacheHitRate?.let { "%.1f%%".format(it * 100) } ?: "--",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = palette.labelPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (expanded == UsageGlossary.COST) GlossaryText(UsageGlossary.COST)
        if (expanded == UsageGlossary.CACHE_HIT) GlossaryText(UsageGlossary.CACHE_HIT)

        val hasData = !summary.isEmpty
        MetricBlock(stringResource(R.string.chat_uncached_input), summary.usage.promptTokens, hasData)
        MetricBlock(stringResource(R.string.chat_cache_reads), summary.usage.cacheHitTokens, hasData)
        MetricBlock(stringResource(R.string.chat_output), summary.usage.completionTokens, hasData)

        val hasAccount = quota != null || balanceText != null
        if (hasAccount) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.borderL2),
            )
            Spacer(Modifier.height(20.dp))

            ExplainableLabel(stringResource(R.string.chat_account), UsageGlossary.BALANCE, expanded) { expanded = it }
            Spacer(Modifier.height(12.dp))
            if (quota != null) {
                Text(
                    text = stringResource(R.string.chat_subscription_quota),
                    style = TextStyle(fontSize = 13.sp),
                    color = palette.labelSecondary,
                )
                quota.windows.forEach { window ->
                    QuotaRow(window)
                }
            } else {
                Text(
                    text = stringResource(R.string.chat_balance),
                    style = TextStyle(fontSize = 13.sp),
                    color = palette.labelSecondary,
                )
                Text(
                    text = balanceText ?: "--",
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = palette.labelPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (expanded == UsageGlossary.BALANCE) GlossaryText(UsageGlossary.BALANCE)
        }
    }
}

/**
 * 一个额度窗口：`5 小时  已用 11%` + 细进度条 + 重置时间。
 *
 * 进度条按**已用**画（走满 = 额度用光）；触顶变红，不整块灰显。
 */
@Composable
private fun QuotaRow(window: QuotaWindow) {
    val palette = TcTheme.palette
    val color = when {
        window.exhausted || window.usedPercent >= 90 -> palette.danger
        window.usedPercent >= 70 -> palette.warning
        else -> palette.brand
    }
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (window.key) {
                    "rolling" -> stringResource(R.string.chat_five_hours)
                    "weekly" -> stringResource(R.string.chat_week)
                    "monthly" -> stringResource(R.string.chat_month)
                    else -> window.label
                },
                style = TextStyle(fontSize = 13.sp),
                color = palette.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${window.usedPercent}%",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = color,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(palette.borderL2, RoundedCornerShape(2.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(window.usedPercent / 100f)
                    .height(4.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
        resetLabel(window.resetAt)?.let { text ->
            Text(
                text = text,
                style = TextStyle(fontSize = 11.sp),
                color = palette.labelTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 重置时间：只给相对说法（`3 小时后重置`），不暴露 ISO 串。单位为零时省略。 */
@Composable
private fun resetLabel(resetAt: String?): String? {
    if (resetAt.isNullOrBlank()) return null
    val instant = runCatching { java.time.Instant.parse(resetAt) }.getOrNull() ?: return null
    val minutes = java.time.Duration.between(java.time.Instant.now(), instant).toMinutes()
    if (minutes <= 0) return stringResource(R.string.chat_resets_soon)
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    val mins = minutes % 60
    return when {
        days > 0 && hours > 0 -> stringResource(R.string.chat_resets_in_d_h, days, hours)
        days > 0 -> stringResource(R.string.chat_resets_in_d, days)
        hours > 0 && mins > 0 -> stringResource(R.string.chat_resets_in_h_min, hours, mins)
        hours > 0 -> stringResource(R.string.chat_resets_in_h, hours)
        else -> stringResource(R.string.chat_resets_in_min, mins)
    }
}

/** 可点的灰色标签：点一下展开/收起说明。不加下划线、不加问号（决策 27）。 */
@Composable
private fun ExplainableLabel(
    text: String,
    key: String,
    expanded: String?,
    onToggle: (String?) -> Unit,
) {
    val palette = TcTheme.palette
    Text(
        text = text,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        color = palette.labelTertiary,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            onToggle(if (expanded == key) null else key)
        },
    )
}

@Composable
private fun GlossaryText(key: String) {
    val palette = TcTheme.palette
    Text(
        text = UsageGlossary.of(key),
        style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
        color = palette.labelSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ColumnScope.MetricBlock(label: String, value: Int, hasData: Boolean) {
    val palette = TcTheme.palette
    Spacer(Modifier.height(16.dp))
    Text(
        text = label,
        style = TextStyle(fontSize = 13.sp),
        color = palette.labelSecondary,
    )
    Text(
        text = if (!hasData) "--" else "${Money.tokens(value.toLong())} tok",
        style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        color = palette.labelPrimary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** `120000` → `120K`；`623400` → `623.4K`；`1000000` → `1M`。 */
internal fun compactTokens(value: Int): String {
    val v = value.coerceAtLeast(0)
    return when {
        v < 1_000 -> v.toString()
        v < 1_000_000 -> trimZero(v / 1_000.0) + "K"
        else -> trimZero(v / 1_000_000.0) + "M"
    }
}

private fun trimZero(value: Double): String {
    val text = "%.1f".format(value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
