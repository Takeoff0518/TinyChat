package io.tinychat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.State
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
 * 块内部的换行与嵌套不拆——每个块自己就是一个完整的渲染单元，偏移量都相对 [content]。
 */
internal class MarkdownDocument(
    /** 归一化之后的全文；[blocks] 里每个节点的起止偏移都相对它 */
    val content: String,
    val blocks: List<ASTNode>,
    /** 引用角标，偏移已经平移到 [content] 的坐标系 */
    val citations: List<CitationAnchor>,
)

/** 解析入口；解析结果按正文内容缓存，滚回来不再重解析 */
internal fun markdownDocument(raw: String, citations: List<CitationAnchor>): MarkdownDocument {
    val normalized = normalizeInlineMathDelimiters(raw)
    val content = stabilizeSetextMarkers(normalized.text).trimEnd('\n')
    val blocks = parseBlocksCached(content)
    val mapped = if (citations.isEmpty()) {
        citations
    } else {
        citations.map { CitationAnchor(normalized.mapOffset(it.offset), it.index) }
    }
    return MarkdownDocument(content, blocks, mapped)
}

private fun parseBlocksCached(content: String): List<ASTNode> =
    MarkdownBlockCache.get(content) ?: run {
        val state = parseMarkdown(content)
        val blocks = (state as? State.Success)?.node?.children?.withoutEol().orEmpty()
        MarkdownBlockCache.put(content, blocks)
        blocks
    }

/**
 * 渲染 [document] 里的一个块。
 *
 * 每个块都是完整的渲染单元：行内公式、引用角标、行内代码画笔、列表符号都在这里就位，
 * 不依赖整条消息一起组合。调用方负责把块放进列表、并保证同一份 [document] 只解析一次。
 */
@Composable
internal fun TcMarkdownBlock(
    document: MarkdownDocument,
    block: ASTNode,
    components: MarkdownComponents,
    citedSources: Int,
    onCitationClick: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val inlineContent = citationInlineContent(document.citations, citedSources, onCitationClick)
    val withInlineCode = remember(document.content) { document.content.contains('`') }
    CompositionLocalProvider(
        LocalBulletListHandler provides TcBulletHandler,
        LocalMarkdownStreaming provides false,
        LocalCitationAnchors provides document.citations,
        LocalCitationCount provides citedSources,
    ) {
        Column(modifier = modifier) {
            MarkdownElement(block, components, document.content, includeSpacer = false)
        }
    }
}

/** 块列表的 LRU：滚回来直接命中，省掉 25–30ms 的解析 */
private object MarkdownBlockCache {
    private const val MAX = 64

    private val entries = object : LinkedHashMap<String, List<ASTNode>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ASTNode>>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun get(key: String): List<ASTNode>? = entries[key]

    @Synchronized
    fun put(key: String, value: List<ASTNode>) {
        entries[key] = value
    }
}
