package io.tinychat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberedCitationTest {
    @Test
    fun `单条标号切成 文本 角标 文本`() {
        val pieces = splitNumberedCitations("hallo[1]welt", count = 3)

        assertEquals(3, pieces.size)
        assertEquals(CitePiece.Plain(0, 5), pieces[0])
        assertEquals(CitePiece.Badge(1), pieces[1])
        assertEquals(CitePiece.Plain(8, 12), pieces[2])
    }

    @Test
    fun `连续标号和两位数字都认`() {
        val pieces = splitNumberedCitations("a[1][12]b", count = 12)

        assertEquals(listOf(CitePiece.Plain(0, 1), CitePiece.Badge(1), CitePiece.Badge(12), CitePiece.Plain(8, 9)), pieces)
    }

    @Test
    fun `越界编号保持原样`() {
        assertTrue(splitNumberedCitations("见[9]", count = 5).isEmpty())
        // 混着来：合法的替换，越界的留在文本里
        val pieces = splitNumberedCitations("[1] 与 [9]", count = 5)
        assertEquals(listOf(CitePiece.Badge(1), CitePiece.Plain(3, 9)), pieces)
    }

    @Test
    fun `半截标记不处理`() {
        assertTrue(splitNumberedCitations("接着 [", count = 3).isEmpty())
        assertTrue(splitNumberedCitations("接着 [1", count = 3).isEmpty())
    }

    @Test
    fun `没有来源时整段原样`() {
        assertTrue(splitNumberedCitations("带[1]的正文", count = 0).isEmpty())
    }
}
