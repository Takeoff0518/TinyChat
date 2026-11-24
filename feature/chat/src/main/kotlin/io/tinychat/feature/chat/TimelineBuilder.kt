package io.tinychat.feature.chat

import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.SearchResult
import io.tinychat.model.ThinkPayload
import io.tinychat.model.ToolFindPayload
import io.tinychat.model.ToolOpenPayload
import io.tinychat.model.ToolSearchPayload
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 思维链里的一步：一段思考文字，或一次工具动作。 */
sealed interface TimelineItem {
    data class Text(val content: String) : TimelineItem
    data class Tool(val step: ToolStep) : TimelineItem
}

enum class ToolKind { SEARCH, OPEN, FIND }

/**
 * 工具行里的一条结果：搜索命中的网页，或边想边搜打开的页面。
 *
 * 为什么不是一串站名：图标堆叠要用真 favicon，抽屉里要站点、日期、摘要、可点跳转，
 * 这些都在碎片里（`site_icon` / `published_at` / `snippet`），只是渲染时没读出来。
 */
data class ToolEntry(
    val title: String?,
    val url: String?,
    /** 站名（如「新浪新闻」）；服务端没给时回落到域名 */
    val site: String,
    val iconUrl: String?,
    /** 网页发布日期，ISO-8601（UTC） */
    val publishedAt: String?,
    val snippet: String?,
) {
    val publishedDate: String? get() = publishedDateText(publishedAt)

    /** 抽屉行第二行：优先摘要，没有摘要才退回标题 */
    val detail: String?
        get() = snippet?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }
}

/**
 * 工具步骤（§13）：搜索 → `搜索到 N 个网页`；浏览 → `已浏览 N 个页面`；
 * 页内查找 → `查找关键词 "pattern"`。前两者点击打开底部抽屉，抽屉里不标编号角标。
 */
data class ToolStep(
    val kind: ToolKind,
    val title: String,
    val subtitle: String?,
    val entries: List<ToolEntry> = emptyList(),
    val failed: Boolean = false,
    val searchFailure: String? = null,
    val findPattern: String? = null,
) {
    val canOpen: Boolean get() = entries.isNotEmpty()

    /** 抽屉标题：搜索结果 / 已浏览搜索结果 */
    val panelTitle: String
        get() = when (kind) {
            ToolKind.SEARCH -> "搜索结果"
            ToolKind.OPEN -> "已浏览搜索结果"
            ToolKind.FIND -> "搜索结果"
        }
}

/** 时间轴输入：只取类型与 payload 文本，不依赖 Room 实体，便于单测。 */
internal data class TimelineFragment(val type: String, val payload: String)

/** 服务端工具结果的状态值（与 `MessageAssembler` 一致） */
private const val TOOL_STATUS_FAILED = "FAILED"

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/** 网页日期 `yyyy/MM/dd`，按本地时区（服务端给的是 UTC，直接截前 10 位会差一天） */
internal fun publishedDateText(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(dateFormatter)
    }.getOrNull()
}

/**
 * 把 THINK 与工具碎片按 ord 顺序排成时间轴。
 *
 * 结算规则（**顺序就是这三行的顺序**，错一处整段思考会跑到工具行下面）：
 * 1. 遇到工具行，先结清它**之前**已经攒下的浏览分组，再结清它之前攒下的思考文字；
 * 2. 连续的浏览合并成一行（官方就是这么做的），中间夹了思考就另起一行；
 * 3. 流结束时同样先浏览后文字——最后一段文字一定在最末。
 */
internal fun buildTimeline(fragments: List<TimelineFragment>): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()
    val buffer = StringBuilder()
    val opened = mutableListOf<OpenedPage>()

    fun flushText() {
        val content = buffer.toString().trim()
        if (content.isNotEmpty()) items += TimelineItem.Text(content)
        buffer.setLength(0)
    }

    fun flushOpened() {
        if (opened.isEmpty()) return
        val pages = opened.toList()
        opened.clear()
        items += TimelineItem.Tool(
            ToolStep(
                kind = ToolKind.OPEN,
                title = if (pages.all { it.failed }) {
                    "浏览页面失败"
                } else {
                    "已浏览 ${pages.size} 个页面"
                },
                subtitle = null,
                entries = pages.map { it.entry },
                failed = pages.all { it.failed },
            ),
        )
    }

    for (fragment in fragments) {
        when (fragment.type) {
            "THINK" -> {
                flushOpened()
                val payload = FragmentPayloadCodec.decode(fragment.payload) as? ThinkPayload ?: continue
                buffer.append(payload.content).append('\n')
            }

            "TOOL_SEARCH" -> {
                flushOpened()
                flushText()
                val payload = FragmentPayloadCodec.decode(fragment.payload) as? ToolSearchPayload ?: continue
                val failed = payload.status == TOOL_STATUS_FAILED
                val entries = payload.results.map { it.toEntry() }
                items += TimelineItem.Tool(
                    ToolStep(
                        kind = ToolKind.SEARCH,
                        title = when {
                            failed -> payload.reason?.takeIf { it.isNotBlank() }
                                ?.let { "联网搜索暂不可用：$it" }
                                ?: "联网搜索暂不可用"
                            entries.isEmpty() -> "未搜索到合适内容"
                            else -> "搜索到 ${entries.size} 个网页"
                        },
                        subtitle = payload.queries.joinToString(" ").takeIf { it.isNotBlank() },
                        entries = entries,
                        failed = failed,
                        searchFailure = payload.reason,
                    ),
                )
            }

            "TOOL_OPEN" -> {
                flushText()
                val payload = FragmentPayloadCodec.decode(fragment.payload) as? ToolOpenPayload ?: continue
                val entry = payload.toEntry()
                // 同一次浏览里重复打开同一个链接只算一页（参照实现按 url 去重）
                val duplicated = opened.any { it.entry.url != null && it.entry.url == entry.url }
                if (!duplicated) {
                    opened += OpenedPage(entry = entry, failed = payload.status == TOOL_STATUS_FAILED)
                }
            }

            "TOOL_FIND" -> {
                flushOpened()
                flushText()
                val payload = FragmentPayloadCodec.decode(fragment.payload) as? ToolFindPayload ?: continue
                val failed = payload.status == TOOL_STATUS_FAILED
                items += TimelineItem.Tool(
                    ToolStep(
                        kind = ToolKind.FIND,
                        title = if (failed) {
                            "未查找到相关内容"
                        } else {
                            "查找关键词 \"${payload.pattern}\""
                        },
                        subtitle = null,
                        failed = failed,
                        findPattern = payload.pattern,
                    ),
                )
            }
        }
    }

    flushOpened()
    flushText()
    return items
}

private data class OpenedPage(val entry: ToolEntry, val failed: Boolean)

private fun SearchResult.toEntry(): ToolEntry {
    val host = hostOf(url)
    return ToolEntry(
        title = title.takeIf { it.isNotBlank() },
        url = url.takeIf { it.isNotBlank() },
        site = siteName?.takeIf { it.isNotBlank() } ?: host.orEmpty(),
        iconUrl = siteIcon?.takeIf { it.isNotBlank() } ?: iconUrlFor(host),
        publishedAt = publishedAt,
        snippet = snippet?.takeIf { it.isNotBlank() },
    )
}

private fun ToolOpenPayload.toEntry(): ToolEntry {
    val host = hostOf(url)
    return ToolEntry(
        title = title.takeIf { it.isNotBlank() },
        url = url.takeIf { it.isNotBlank() },
        site = siteName?.takeIf { it.isNotBlank() } ?: host.orEmpty(),
        iconUrl = siteIcon?.takeIf { it.isNotBlank() } ?: iconUrlFor(host),
        publishedAt = publishedAt,
        snippet = snippet?.takeIf { it.isNotBlank() },
    )
}

/** favicon 兜底：和正文来源胶囊同一套规则，按域名拼官方图标 CDN */
private fun iconUrlFor(host: String?): String? =
    host?.let { "https://cdn.deepseek.com/site-icons/$it" }

private fun hostOf(url: String?): String? =
    url?.takeIf { it.isNotBlank() }
        ?.let { runCatching { URI(it).host }.getOrNull() }
        ?.removePrefix("www.")
