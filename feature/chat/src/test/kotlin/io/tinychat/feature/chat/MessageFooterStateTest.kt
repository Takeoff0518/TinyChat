package io.tinychat.feature.chat

import io.tinychat.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作栏可见性（M2-9）。官方版式两行，这组用例把「什么时候出什么」钉死：
 * 手动停止（INCOMPLETE + 有正文）必须给全第一行，只在正文为空时才退化成单独重新生成。
 */
class MessageFooterStateTest {

    private fun resolve(
        status: MessageStatus = MessageStatus.FINISHED,
        hasText: Boolean = true,
        versionCount: Int = 1,
        isLatest: Boolean = true,
        canContinue: Boolean = false,
    ) = resolveFooterState(status, hasText, versionCount, isLatest, canContinue)

    @Test
    fun `正常完成的最新回答 - 第一行四件齐全且不出第二行`() {
        val state = resolve()
        assertTrue(state.showActions)
        assertTrue(state.showRegenerate)
        assertFalse(state.showVersionSwitcher)
        assertFalse(state.showContinue)
        assertTrue(state.showFirstRow)
        assertFalse(state.showSecondRow)
    }

    @Test
    fun `手动停止且已有正文 - 复制朗读分享照给，并多出继续生成`() {
        val state = resolve(status = MessageStatus.INCOMPLETE, canContinue = true)
        assertTrue(state.showActions)
        assertTrue(state.showRegenerate)
        assertTrue(state.showContinue)
        assertTrue(state.showFirstRow)
        assertTrue(state.showSecondRow)
    }

    @Test
    fun `思考到一半被掐断 - 正文为空时只剩重新生成`() {
        val state = resolve(status = MessageStatus.INCOMPLETE, hasText = false, canContinue = false)
        assertFalse(state.showActions)
        assertTrue(state.showRegenerate)
        assertFalse(state.showSecondRow)
    }

    @Test
    fun `流式生成中 - 整栏不出，避免打断`() {
        val state = resolve(status = MessageStatus.WIP)
        assertFalse(state.visible)
    }

    @Test
    fun `失败与超时 - 整栏不出，交给正文下方的重试`() {
        assertFalse(resolve(status = MessageStatus.FAILED).visible)
        assertFalse(resolve(status = MessageStatus.TIMEOUT).visible)
    }

    @Test
    fun `拒答 - 有正文时照给左三件与重新生成，永远没有继续生成`() {
        val state = resolve(status = MessageStatus.CONTENT_FILTER)
        assertTrue("模型说的话可以复制、可以分享", state.showActions)
        assertTrue(state.showRegenerate)
        assertFalse("拒答没有可续的半句", state.showContinue)
    }

    @Test
    fun `拒答且一句话都没说 - 只留重新生成`() {
        val state = resolve(status = MessageStatus.CONTENT_FILTER, hasText = false)
        assertFalse(state.showActions)
        assertTrue(state.showRegenerate)
        assertFalse(state.showContinue)
    }

    @Test
    fun `历史回答 - 有左三件但没有重新生成与继续生成`() {
        val state = resolve(isLatest = false)
        assertTrue(state.showActions)
        assertFalse(state.showRegenerate)
        assertFalse(state.showContinue)
        assertTrue(state.showFirstRow)
        assertFalse(state.showSecondRow)
    }

    @Test
    fun `有兄弟版本 - 第二行左侧出版本切换，右侧继续生成`() {
        val state = resolve(versionCount = 2, canContinue = true)
        assertTrue(state.showVersionSwitcher)
        assertTrue(state.showContinue)
        assertTrue(state.showSecondRow)
    }

    @Test
    fun `历史回答多版本 - 版本切换可点，重新生成继续生成都不给`() {
        val state = resolve(versionCount = 3, isLatest = false, canContinue = true)
        assertTrue(state.showVersionSwitcher)
        assertFalse(state.showRegenerate)
        assertFalse(state.showContinue)
        assertEquals(true, state.showSecondRow)
    }

    @Test
    fun `只有重新生成时第一行也要出 - 否则右贴边图标会掉到第二行`() {
        val state = resolve(hasText = false)
        assertTrue(state.showFirstRow)
        assertFalse(state.showSecondRow)
    }
}
