package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingRevealTest {
    @Test
    fun `makes visible progress through a burst and catches up as more chunks arrive`() {
        val burst = "思".repeat(360)
        val reveal = ThinkingReveal()
        reveal.update(burst)
        val first = reveal.advance()
        assertTrue(first in 2..18)
        reveal.update(burst + "后续")
        assertTrue(reveal.advance() > first)
        repeat(30) { reveal.advance() }
        assertEquals(burst.length + 2, reveal.end)
        assertFalse(reveal.hasPending)
    }

    @Test
    fun `reveals Chinese emoji and combining marks as whole graphemes`() {
        val parts = listOf("思", "👩🏽‍💻", "🇨🇳", "e\u0301", "想")
        val text = parts.joinToString("")
        val reveal = ThinkingReveal()
        reveal.update(text)
        var visible = ""
        parts.forEach { part ->
            visible += part
            assertEquals(visible, text.substring(0, reveal.advance()))
        }
    }

    @Test
    fun `waits for the second half of a surrogate pair`() {
        val text = "甲😀乙"
        val reveal = ThinkingReveal()
        reveal.update(text.substring(0, 2))
        assertEquals(1, reveal.advance())
        assertFalse(reveal.hasPending)
        reveal.update(text)
        assertEquals(3, reveal.advance())
        assertEquals(4, reveal.advance())
    }

    @Test
    fun `continues from saved and complete positions`() {
        val reveal = ThinkingReveal(initialEnd = 2)
        reveal.update("思考过程")
        assertEquals(3, reveal.advance())
        assertEquals(4, reveal.skip())
        reveal.update("思考内容已调整")
        assertEquals(7, reveal.advance())
        assertFalse(reveal.hasPending)
    }
}
