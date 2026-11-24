package io.tinychat.markdown

/**
 * 流式渲染的**帧合并**节流器（docs/02 §M2-2）。
 *
 * 规则移植自 chat-demo 的 `useThrottleMarkdownRender`：
 * 一次渲染耗时 T 之后，至少要等 `T × 2` 才允许下一次更新，上限 [maxWaitMs]（2s）。
 * 流式内容在这段时间内继续到达也没关系 —— 调用方每次都只追加「当前全文」，
 * 中间那些帧被合并掉，等于「按渲染能力出帧」，而不是按 token 到达出帧。
 *
 * 为什么要按耗时而不是固定间隔：慢设备解析慢，固定 16ms 只会积压主线程；
 * 快设备解析快，也不该被人为拖慢。
 */
internal class FrameMerger(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxWaitMs: Long = 2_000,
    private val costFactor: Long = 2,
) {
    private var updatableAtMs = 0L

    /** 距离下次允许更新还有多少毫秒；0 表示现在就可以。 */
    fun millisUntilNextUpdate(): Long = (updatableAtMs - clockMs()).coerceAtLeast(0L)

    /** 记录一次渲染（解析 + 组装）的耗时，据此推迟下一次更新。 */
    fun recordCost(costMs: Long) {
        updatableAtMs = clockMs() + minOf(maxWaitMs, costMs.coerceAtLeast(0L) * costFactor)
    }
}
