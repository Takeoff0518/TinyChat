package io.tinychat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 决策 74：生成结束后从「内存实时正文」交棒给「库里的正文」。
 *
 * 交棒早了会看到正文忽然变短、再长回来（库里最多落后一个落库间隔），
 * 所以判据是长度追上，而不是「生成结束」。
 */
class LiveHandoffTest {

    @Test
    fun `库里还落后时不交棒`() {
        assertEquals(false, liveCaughtUp(stored = "前半", live = "前半段正文"))
    }

    @Test
    fun `库里长度追上才交棒`() {
        assertEquals(true, liveCaughtUp(stored = "前半段正文", live = "前半段正文"))
    }

    @Test
    fun `正文还没开始时不交棒`() {
        // 决策 80：思考阶段两边正文都是空串，0 >= 0 会恒真，每来一次落库就把实时快照撤掉；
        // 界面那会儿正靠快照画思维链，撤掉之后口径在实时与落后一截之间来回切，重放就是这么来的
        assertEquals(false, liveCaughtUp(stored = "", live = ""))
        assertEquals(false, liveCaughtUp(stored = null, live = ""))
    }

    @Test
    fun `库里更长时也交棒`() {
        // 拒答这类会在收尾时删掉已下发的正文：只要不短于实时内容就可以切过去
        assertEquals(true, liveCaughtUp(stored = "前半段正文（多出来）", live = "前半段正文"))
    }

    @Test
    fun `库里还没有这条消息时不交棒`() {
        assertEquals(false, liveCaughtUp(stored = null, live = "前半段正文"))
    }
}
