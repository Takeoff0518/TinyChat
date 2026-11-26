package io.tinychat.markdown

import org.intellij.markdown.MarkdownTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文切块（B-015 / 决策 83）：切点必须落在 GFM 的顶层块边界上，
 * 块内（列表项、代码块、表格）不许被拆开，否则一块渲染出来是半个表格。
 */
class MarkdownDocumentTest {

    private val sample = """
        ## Quicksort

        Quicksort is a divide-and-conquer algorithm.

        1. Choose a pivot.
        2. Partition the array.

        ```kotlin
        fun quicksort(a: IntArray) = a
        ```

        | 步骤 | 说明 |
        | --- | --- |
        | 1 | 选基准 |
    """.trimIndent()

    @Test
    fun `按顶层块切分，块数与时序稳定`() {
        val doc = markdownDocument(sample, emptyList())
        val types = doc.blocks.map { it.type.name }
        assertEquals(listOf("ATX_2", "PARAGRAPH", "ORDERED_LIST", "CODE_FENCE", "TABLE"), types)
    }

    @Test
    fun `不出现空行节点`() {
        val doc = markdownDocument(sample, emptyList())
        assertTrue(doc.blocks.none { it.type == MarkdownTokenTypes.EOL })
    }

    @Test
    fun `代码块与表格是完整的一块`() {
        val doc = markdownDocument(sample, emptyList())
        val fence = doc.blocks.single { it.type.name == "CODE_FENCE" }
        // 代码块里必须还能看到代码本体，说明没有被切成两半
        val text = doc.content.substring(fence.startOffset, fence.endOffset)
        assertTrue(text.contains("fun quicksort"))
        val table = doc.blocks.single { it.type.name == "TABLE" }
        assertTrue(doc.content.substring(table.startOffset, table.endOffset).contains("选基准"))
    }

    @Test
    fun `正文归一化后偏移仍对齐`() {
        val doc = markdownDocument("行内公式 \\(a^2+b^2\\) 结束。", emptyList())
        val paragraph = doc.blocks.single()
        val text = doc.content.substring(paragraph.startOffset, paragraph.endOffset)
        assertTrue(text.contains("a^2+b^2"))
    }

    @Test
    fun `相同正文命中缓存，返回同一份块列表`() {
        val first = markdownDocument(sample, emptyList())
        val second = markdownDocument(sample, emptyList())
        assertSame(first.blocks, second.blocks)
    }

    @Test
    fun `引用角标的偏移跟着归一化平移`() {
        // `\(x\)` 会被归一成 `$x$`，长度变化后角标偏移不能错位
        val raw = "前面 \\(x\\) 后面[1]"
        val doc = markdownDocument(raw, listOf(CitationAnchor(offset = raw.indexOf("[1]"), index = 1)))
        val anchor = doc.citations.single()
        assertTrue(doc.content.substring(anchor.offset).startsWith("[1]"))
    }
}
