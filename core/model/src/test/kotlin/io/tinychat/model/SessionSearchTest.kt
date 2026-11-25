package io.tinychat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话搜索的片段抽取：命中词要能定位到，两侧保留上下文，换行展平成一行。
 */
class SessionSearchTest {

    @Test
    fun `正文命中给出上下文并标注省略号`() {
        val content = "咖啡最早出现在埃塞俄比亚，后来传到阿拉伯半岛，再进入欧洲市场，最后成为全球饮品。"

        val hit = buildSearchHit("s1", "咖啡史", content, "阿拉伯半岛", radius = 6)!!

        assertEquals("s1", hit.sessionId)
        assertEquals("咖啡史", hit.title)
        assertTrue(hit.before.startsWith("…"))
        assertTrue(hit.after.endsWith("…"))
        assertEquals("阿拉伯半岛", hit.match)
        assertTrue(hit.snippet.contains("阿拉伯半岛"))
    }

    @Test
    fun `命中在开头与结尾时不补省略号`() {
        val content = "开头就是关键词，结尾也是关键词"

        val head = buildSearchHit("s1", null, content, "开头", radius = 20)!!
        val tail = buildSearchHit("s1", null, content, "关键词", radius = 3)!!

        assertEquals("", head.before)
        assertEquals("开头", head.match)
        assertEquals("", tail.before.take(0))
        assertTrue(tail.after.endsWith("关键词").not())
    }

    @Test
    fun `大小写不敏感但保留原文写法`() {
        val hit = buildSearchHit("s1", null, "DeepSeek 的 API 很便宜", "deepseek")!!

        assertEquals("DeepSeek", hit.match)
    }

    @Test
    fun `换行被展平成空格`() {
        val hit = buildSearchHit("s1", null, "第一行\n第二行关键词结尾", "关键词")!!

        assertTrue(hit.snippet.contains("\n").not())
        assertEquals("关键词", hit.match)
    }

    @Test
    fun `没有命中或空查询返回 null`() {
        assertNull(buildSearchHit("s1", null, "完全无关的正文", "找不到"))
        assertNull(buildSearchHit("s1", null, "正文里有词", "   "))
    }

    @Test
    fun `标题为空时回落为新对话`() {
        val hit = buildSearchHit("s1", null, "正文关键词", "关键词")!!
        val named = buildSearchHit("s2", "新对话", "正文关键词", "关键词")!!

        assertEquals("新对话", hit.title)
        assertTrue(hit.untitled)
        assertFalse(named.untitled)
    }

    @Test
    fun `上下文按 radius 截断`() {
        val content = "a".repeat(50) + "目标" + "b".repeat(50)

        val hit = buildSearchHit("s1", null, content, "目标", radius = 4)!!

        assertTrue(hit.before.length <= 5)
        assertTrue(hit.after.length <= 5)
        assertEquals("目标", hit.match)
    }
}
