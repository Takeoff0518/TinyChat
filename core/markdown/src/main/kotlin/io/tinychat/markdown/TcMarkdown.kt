package io.tinychat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.tinychat.designsystem.LocalTcFontScale
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.model.BulletHandler
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/** 列表符号：库默认是 `"• "`（带一个空格），宽度随字号变化，和正文的空隙不好控 */
private val TcBulletHandler = BulletHandler { _, _, _, _, _ -> "•" }

/**
 * 重建解析状态后要一次性补回的长度，落在安全的字符边界上。
 *
 * 目标位置如果正好把一个代理对劈成两半，退回一个 UTF-16 单元——半个 emoji
 * 交给解析器会渲染成方块，而且下一帧又会被改写，看起来就是闪一下。
 */
private fun safePrefix(target: String, wanted: Int): Int {
    val capped = wanted.coerceIn(0, target.length)
    if (capped == 0 || capped == target.length) return capped
    return if (target[capped - 1].isHighSurrogate()) capped - 1 else capped
}

/**
 * 助手正文的 Markdown 渲染（M2-1 / M2-2）。
 *
 * **硬性约定（docs/04 §3.4 实测结论，别改）**：
 * 1. 只用 `rememberStreamingMarkdownState()` + 逐片 `append()`；
 *    `Markdown(String)` 与「逐帧换 state」每帧整篇重解析，实测 42–61 次高度回缩 / 2042dp 闪屏；
 * 2. 追加前必须过 [FrameMerger] 的节流（按渲染耗时决定下一帧何时可以出）；
 * 3. 内容被整体替换（版本切换、重新生成）时才重建解析状态，重建走 `key(generation)`。
 *
 * **2026-11-18 性能实测（别自作聪明改回去）**：曾经把「已结束的消息」改成
 * `parseMarkdown()` + `Markdown(state = …)` 的**一次性渲染**并加 LRU 缓存，本意是省掉重复解析，
 * 结果同包对照反而更差：Janky 20–35% / 50th 21–25ms，而保持流式通道是 4–9% / 11–13ms。
 * 原因两条：一次性渲染把整棵 AST 的组合压进正在滚动的帧；缓存上的同步锁还会被后台预热长时间占住。
 * 所以**所有消息（含已结束的）都走流式通道**，让内容经 StateFlow 错开一帧出现。
 *
 * @param content 到目前位置的全部正文（不是增量切片）。
 * @param streaming 仍在接收正文。完成后会快速显示剩余文字，历史消息直接渲染。
 */
@Composable
fun TcMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    citations: List<CitationAnchor> = emptyList(),
    /** 第三方搜索的结果条数：正文里的 `[k]`（k ≤ 这个数）会被渲染成角标 */
    citedSources: Int = 0,
    onCitationClick: ((Int) -> Unit)? = null,
) {
    var generation by remember { mutableIntStateOf(0) }
    var rebuildPrefix by remember { mutableIntStateOf(0) }
    // 出字节奏跟着帧走：节流只按实测渲染耗时推迟，不设固定的最小间隔
    val merger = remember { FrameMerger() }
    // `\(…\)` → `$…$`：GFM 会把 `\(` 当成转义括号吃掉反斜杠，不归一的话模型给的
    // LaTeX 行内公式会被当普通文字渲染（真机上表现为「公式糊在正文里」）
    val normalized = remember(content) { normalizeInlineMathDelimiters(content) }
    // 「正文后紧跟 --- / ===」会被 GFM 当成 setext 标题，标记到达的瞬间上一行变标题 → 高度回缩。
    // 换成等长的 ***（长度不变，引用角标偏移不受影响），见 stabilizeSetextMarkers。
    val rendered = remember(normalized.text) { stabilizeSetextMarkers(normalized.text) }
    // 末尾连续的换行也先扣住不给解析器：`……现象。\n\n` 这种中间态会先渲染出一行空白，
    // 下一段文字到达时那行空白消失，高度就回缩一次（真机实测 -24dp）。
    // 扣住只影响「画面末尾的空行」，下一片文字到达时会连同换行一起补上。
    val target = remember(rendered) { rendered.trimEnd('\n') }
    // 归一化会缩短文本，引用角标的偏移要跟着平移
    val anchoredCitations = remember(citations, normalized) {
        if (citations.isEmpty()) {
            citations
        } else {
            citations.map { CitationAnchor(normalized.mapOffset(it.offset), it.index) }
        }
    }
    // 只有真含行内代码时才启用圆角画笔：没有反引号的正文可以省掉
    // extend() / onTextLayout / drawBehind 这一整条链路
    val withInlineCode = remember(target) { target.contains('`') }
    val inlineContent = citationInlineContent(anchoredCitations, citedSources, onCitationClick)

    key(generation) {
        val state = rememberStreamingMarkdownState()

        LaunchedEffect(state, target, streaming) {
            var cursor = state.content.length
            // 重建之后第一次进：先把上一次已经显示的那一段原样补回去，
            // 否则整篇会从头再长一遍（表现为闪一下）。
            if (rebuildPrefix > 0) {
                cursor = safePrefix(target, rebuildPrefix)
                rebuildPrefix = 0
                if (cursor > 0) state.append(target.substring(0, cursor))
            }

            if (cursor > target.length || !target.regionMatches(0, state.content, 0, cursor)) {
                // Markdown 归一化有时会改写已显示的前缀。重建后直接恢复已显示的长度。
                rebuildPrefix = cursor.coerceAtMost(target.length)
                generation += 1
                return@LaunchedEffect
            }
            if (cursor == target.length) return@LaunchedEffect

            // 逐帧追加：正文每帧都可能变长，这里只补「新到的那一段」，
            // 节流靠实测渲染耗时（FrameMerger），不靠固定定时器。
            while (cursor < target.length) {
                // 已结束的消息不节流：一次补完即可，没必要压着出
                if (streaming) {
                    val wait = merger.millisUntilNextUpdate()
                    if (wait > 0) {
                        delay(wait)
                        continue
                    }
                }
                val startMs = nowMs()
                state.append(target.substring(cursor))
                merger.recordCost(nowMs() - startMs)
                cursor = target.length
            }
        }

        CompositionLocalProvider(
            LocalBulletListHandler provides TcBulletHandler,
            LocalMarkdownStreaming provides streaming,
            LocalCitationAnchors provides anchoredCitations,
            LocalCitationCount provides citedSources,
        ) {
            Markdown(
                streamingMarkdownState = state,
                colors = tcMarkdownColors(),
                typography = tcMarkdownTypography(LocalTcFontScale.current),
                padding = tcMarkdownPadding(),
                dimens = tcMarkdownDimens(),
                modifier = modifier.fillMaxWidth(),
                components = tcMarkdownComponents(),
                inlineContent = inlineContent,
                // 行内代码的灰底由自己的画笔绘制：库自带画笔拿「行盒」当矩形，
                // 14sp 的字被 28sp 行高的灰条压住，首行与续行的裁切量还不一样。
                extendedSpans = inlineCodeSpans(withInlineCode),
                // 库默认给每段文字挂 animateContentSize：流式时高度动画与帧合并互相打架，
                // 表现为内容「慢慢长出来」并伴随回缩。消息流里必须关掉。
                animations = markdownAnimations { this },
                success = { snapshot, components, mdModifier ->
                    val blocks = remember(snapshot) {
                        buildList {
                            addAll(snapshot.stableAst)
                            addAll(snapshot.unstableAstTail)
                        }.withoutEol()
                    }
                    // 全文 toString 每帧只做一次，别写在会反复读的位置
                    val text = remember(snapshot) { state.content.toString() }
                    RenderBlocks(blocks, text, components, mdModifier)
                },
            )
        }
    }
}

/** 只有含反引号时才挂圆角画笔，省掉其余消息的整条链路 */
@Composable
private fun inlineCodeSpans(enabled: Boolean) = if (enabled) {
    markdownExtendedSpans {
        remember { ExtendedSpans(TcInlineCodePainter(fontSize = InlineCodeFontSize)) }
    }
} else {
    markdownExtendedSpans()
}

@Composable
private fun RenderBlocks(
    blocks: List<ASTNode>,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        for (node in blocks) {
            MarkdownElement(node, components, content, includeSpacer = false)
        }
    }
}

/** EOL 在 GFM 的 AST 里成对出现，渲染它们只会多出空 Spacer（还拖慢流式） */
private fun List<ASTNode>.withoutEol(): List<ASTNode> =
    if (any { it.type == MarkdownTokenTypes.EOL }) filter { it.type != MarkdownTokenTypes.EOL } else this

private fun nowMs(): Long = System.nanoTime() / 1_000_000
