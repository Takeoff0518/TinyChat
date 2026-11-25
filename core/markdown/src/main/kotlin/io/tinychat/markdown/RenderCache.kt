package io.tinychat.markdown

import androidx.compose.ui.text.AnnotatedString

/**
 * 渲染层的小缓存（P-045，2026-11-25）。
 *
 * 为什么需要：消息是 `LazyColumn` 的一项，滚出屏幕就被回收，滚回来整条重新组合。
 * 真机实测一条 7413 字符的回答重新进屏要花约 153 毫秒（第 1 次布局 31、解析 10–23、
 * 块组合与排版 95、收尾 27），惯性滑动时就是一记可见的停顿。
 *
 * 这里只缓存**纯函数**的结果——同一段文字、同一套样式，算出来的东西永远一样：
 * 1. 段落的 `AnnotatedString`（引用角标、行内样式都在里面）；
 * 2. 行内公式渲出来的位图（走 WebView 桥，异步且贵）。
 *
 * 刻意不做的事（都有实测教训）：
 * - 不缓存整篇解析状态、也不改渲染路径（`TcMarkdown` 头部记着：一次性渲染反而更卡，
 *   因为把整棵 AST 的组合压进了正在滚动的帧）；
 * - 不在锁里做计算：先查、算完再放，重复计算一次也无所谓，绝不让后台预热把主线程堵住。
 *
 * 容量按「够覆盖一两屏消息」取，超过就淘汰最久没用的那条。
 */
internal object RenderCache {

    private const val PARAGRAPH_MAX = 256
    private const val MATH_MAX = 96

    private val paragraphs = object : LinkedHashMap<String, AnnotatedString>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnnotatedString>?): Boolean =
            size > PARAGRAPH_MAX
    }

    private val math = object : LinkedHashMap<String, Map<Int, InlineMathImage>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Int, InlineMathImage>>?): Boolean =
            size > MATH_MAX
    }

    @Synchronized
    fun paragraphOrNull(key: String): AnnotatedString? = paragraphs[key]

    @Synchronized
    fun putParagraph(key: String, value: AnnotatedString) {
        paragraphs[key] = value
    }

    @Synchronized
    fun mathOrNull(key: String): Map<Int, InlineMathImage>? = math[key]

    @Synchronized
    fun putMath(key: String, value: Map<Int, InlineMathImage>) {
        math[key] = value
    }
}
