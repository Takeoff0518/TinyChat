package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 决策 80：思维链展开与否 = 默认值（思考中展开、结束后按设置）+ 用户手动翻过的那一下。
 *
 * 这一层是纯函数，界面只管把算好的值交给 `ThinkingBlock`：以前「思考中用一套反相集合、
 * 结束后用另一套」的写法就是标签与渲染分支对不上的根源。
 */
class ThinkingBlockStateTest {

    @Test
    fun `思考中默认展开`() {
        assertEquals(true, thinkingBlockExpanded(active = true, autoCollapse = true, override = null))
    }

    @Test
    fun `思考结束且开着自动折叠时收起`() {
        assertEquals(false, thinkingBlockExpanded(active = false, autoCollapse = true, override = null))
    }

    @Test
    fun `关掉自动折叠之后一直展开`() {
        assertEquals(true, thinkingBlockExpanded(active = true, autoCollapse = false, override = null))
        assertEquals(true, thinkingBlockExpanded(active = false, autoCollapse = false, override = null))
    }

    @Test
    fun `手动展开压过自动折叠`() {
        assertEquals(true, thinkingBlockExpanded(active = false, autoCollapse = true, override = true))
    }

    @Test
    fun `手动收起压过思考中的默认展开`() {
        assertEquals(false, thinkingBlockExpanded(active = true, autoCollapse = true, override = false))
        assertEquals(false, thinkingBlockExpanded(active = true, autoCollapse = false, override = false))
    }

    @Test
    fun `没翻过的条目不进任何一个集合`() {
        assertEquals(null, thinkingOverrideOf(opened = longArrayOf(), closed = longArrayOf(), id = 7L))
    }

    @Test
    fun `翻过的条目按方向读出展开或收起`() {
        assertEquals(true, thinkingOverrideOf(opened = longArrayOf(7L), closed = longArrayOf(), id = 7L))
        assertEquals(false, thinkingOverrideOf(opened = longArrayOf(), closed = longArrayOf(7L), id = 7L))
    }
}
