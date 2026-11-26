package io.tinychat.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.parseMarkdown
import io.tinychat.designsystem.LocalTcFontScale
import org.intellij.markdown.ast.ASTNode

/**
 * 一条消息的正文，切成可以**单独渲染**的块（B-015 / 决策 83）。
 *
 * 为什么要切：一条 7413 字符的回答在真机上是 48 个块、18589 像素高，进屏幕那一帧
 * 要把 48 块全部组合并排版一遍——实测解析 25–30ms、组合 25–35ms、排版 28–43ms，
 * 合计 90–133ms，就是一记可见的停顿。切成块之后交给 `LazyColumn` 按需组合，
 * 只有视口附近的几块真正参与排版。
 *
 * 切分规则只有一条：**按 GFM 的顶层块切**（段落、标题、列表、代码块、表格……），
 * 块内部的换行与嵌套不拆。
 */
class MarkdownDocument internal constructor(
    /** 解析好的整篇状态：库的 `Markdown(state = …)` 靠它铺好一整套渲染环境 */
    internal val state: State,
    /** 归一化之后的全文；块里每个节点的起止偏移都相对它 */
    internal val content: String,
    internal val blocks: List<ASTNode>,
    /** 引用角标，偏移已经平移到 [content] 的坐标系 */
    internal val citations: List<CitationAnchor>,
) {
    /** 正文一共切成了几块——上层用它决定列表要铺几行 */
    val blockCount: Int get() = blocks.size
}

/** 解析入口；解析结果按正文内容缓存，滚回来不再重解析 */
fun markdownDocument(raw: String, citations: List<CitationAnchor>): MarkdownDocument {
    val normalized = normalizeInlineMathDelimiters(raw)
    val content = stabilizeSetextMarkers(normalized.text).trimEnd('\n')
    val parsed = parseBlocksCached(content)
    val mapped = if (citations.isEmpty()) {
        citations
    } else {
        citations.map { CitationAnchor(normalized.mapOffset(it.offset), it.index) }
    }
    return MarkdownDocument(parsed.state, content, parsed.blocks, mapped)
}

private class ParsedBlocks(val state: State, val blocks: List<ASTNode>)

private fun parseBlocksCached(content: String): ParsedBlocks =
    MarkdownBlockCache.get(content) ?: run {
        val state = parseMarkdown(content)
        val blocks = (state as? State.Success)?.node?.children?.withoutEol().orEmpty()
        ParsedBlocks(state, blocks).also { MarkdownBlockCache.put(content, it) }
    }

/**
 * 渲染 [document] 里的第 [index] 块。
 *
 * 环境仍走库自己的 `Markdown(state = …)`——它会铺好字体、颜色、间距、扩展样式那一整套
 * `LocalMarkdown*`（少 provide 一个就是运行时崩溃），我们只在 `success` 里把这一块的节点
 * 交给渲染，别的块既不组合也不排版。解析走上面的缓存，不会因为按块渲染而重解析。
 */
@Composable
fun TcMarkdownBlock(
    document: MarkdownDocument,
    index: Int,
    citedSources: Int,
    onCitationClick: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val block = document.blocks.getOrNull(index) ?: return
    val inlineContent = citationInlineContent(document.citations, citedSources, onCitationClick)
    val withInlineCode = remember(document.content) { document.content.contains('`') }
    CompositionLocalProvider(
        LocalBulletListHandler provides TcBulletHandler,
        LocalMarkdownStreaming provides false,
        LocalCitationAnchors provides document.citations,
        LocalCitationCount provides citedSources,
    ) {
        Markdown(
            state = document.state,
            colors = tcMarkdownColors(),
            typography = tcMarkdownTypography(LocalTcFontScale.current),
            padding = tcMarkdownPadding(),
            dimens = tcMarkdownDimens(),
            modifier = modifier.fillMaxWidth(),
            components = tcMarkdownComponents(),
            inlineContent = inlineContent,
            extendedSpans = inlineCodeSpans(withInlineCode),
            animations = markdownAnimations { this },
            success = { _, components, mdModifier ->
                RenderBlocks(listOf(block), document.content, components, mdModifier)
            },
        )
    }
}

/** 块列表的 LRU：滚回来直接命中，省掉 25–30ms 的解析 */
private object MarkdownBlockCache {
    private const val MAX = 64

    private val entries = object : LinkedHashMap<String, ParsedBlocks>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedBlocks>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun get(key: String): ParsedBlocks? = entries[key]

    @Synchronized
    fun put(key: String, value: ParsedBlocks) {
        entries[key] = value
    }
}
