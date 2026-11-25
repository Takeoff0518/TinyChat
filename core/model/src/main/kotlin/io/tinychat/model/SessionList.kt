package io.tinychat.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 抽屉里的一行会话。与 `SessionEntity` 解耦，方便单测分组与标题兜底。
 */
data class SessionRow(
    val id: String,
    val title: String?,
    val pinned: Boolean,
    val updatedAt: Long,
) {
    /** 新会话还没起标题时显示「新对话」（对齐 02 §12.3） */
    val displayTitle: String get() = title?.trim()?.takeIf { it.isNotEmpty() } ?: "新对话"
}

/** 分组顺序即枚举顺序：置顶 → 今天 → 昨天 → 7 天内 → 30 天内 → 更早。 */
enum class SessionGroup(val label: String) {
    PINNED("置顶"),
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7_DAYS("7 天内"),
    LAST_30_DAYS("30 天内"),
    EARLIER("更早"),
}

data class SessionSection(val group: SessionGroup, val rows: List<SessionRow>)

/**
 * 抽屉会话搜索的一条命中。
 *
 * 正文截成「前段 + 命中词 + 后段」三段：命中那段用主色（黑/白），其余用灰，
 * 一行里既看得到上下文，也能一眼看到命中位置。
 */
data class SessionSearchHit(
    val sessionId: String,
    val title: String,
    val before: String,
    val match: String,
    val after: String,
    /** true = 命中在标题里（正文没有可展示的上下文行） */
    val matchedInTitle: Boolean = false,
    val untitled: Boolean = false,
) {
    val snippet: String get() = before + match + after
}

/**
 * 抽屉的整块状态：列表数据 + 搜索页 + 多选。
 *
 * 放在 core:model 是因为 `feature:chat` 的状态机持有它、`feature:sessions` 只负责渲染，
 * 两边都需要这个类型（不引入 feature 之间的依赖）。
 */
data class SessionsUiState(
    val rows: List<SessionRow> = emptyList(),
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val searching: Boolean = false,
    val query: String = "",
    val results: List<SessionSearchHit> = emptyList(),
)

/**
 * 从消息正文里抽一条命中片段（大小写不敏感，只取第一处）。
 *
 * @param radius 命中词两侧各保留多少字符
 * @return 正文里没有命中时返回 null（只在标题里命中的情况由调用方另行处理）
 */
fun buildSearchHit(
    sessionId: String,
    title: String?,
    content: String,
    query: String,
    radius: Int = 24,
): SessionSearchHit? {
    val needle = query.trim()
    if (needle.isEmpty()) return null

    // 折行会改下标，统一在展平后的文本里定位
    val flat = content.replace('\n', ' ').replace('\r', ' ')
    val index = flat.indexOf(needle, ignoreCase = true)
    if (index < 0) return null

    val start = (index - radius).coerceAtLeast(0)
    val end = (index + needle.length + radius).coerceAtMost(flat.length)

    val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
    return SessionSearchHit(
        sessionId = sessionId,
        title = normalizedTitle ?: "新对话",
        before = (if (start > 0) "…" else "") + flat.substring(start, index).trimStart(),
        match = flat.substring(index, index + needle.length),
        after = flat.substring(index + needle.length, end).trimEnd() + if (end < flat.length) "…" else "",
        untitled = normalizedTitle == null,
    )
}


/**
 * 按 02 §12.3 分组。
 *
 * 置顶组不分时间，全部排在最上面；其余按 `updated_at` 落在哪个自然日归组。
 * 空组不出现（不显示只有标题没有内容的空分组）。
 */
fun groupSessions(
    rows: List<SessionRow>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SessionSection> {
    if (rows.isEmpty()) return emptyList()

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pinned = rows.filter { it.pinned }
    val rest = rows.filterNot { it.pinned }

    val buckets = linkedMapOf<SessionGroup, MutableList<SessionRow>>()
    rest.forEach { row ->
        buckets.getOrPut(groupOf(row.updatedAt, today, zone)) { mutableListOf() } += row
    }

    return buildList {
        if (pinned.isNotEmpty()) add(SessionSection(SessionGroup.PINNED, pinned))
        SessionGroup.entries
            .filter { it != SessionGroup.PINNED }
            .forEach { group ->
                buckets[group]?.let { add(SessionSection(group, it)) }
            }
    }
}

private fun groupOf(updatedAt: Long, today: LocalDate, zone: ZoneId): SessionGroup {
    val date = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        // 时钟漂移导致的「未来时间」按今天算，不单列怪分组
        days <= 0L -> SessionGroup.TODAY
        days == 1L -> SessionGroup.YESTERDAY
        days < 7L -> SessionGroup.LAST_7_DAYS
        days < 30L -> SessionGroup.LAST_30_DAYS
        else -> SessionGroup.EARLIER
    }
}
