package io.tinychat.net

import io.tinychat.model.Fragment
import io.tinychat.model.Usage

/**
 * 流式过程中给界面预览用的增量。
 *
 * 只有正文与思考两类：工具块、用量、标题都不参与「字往外冒」的观感。
 * **拼起来必须能还原落库的正文**——界面先按增量出字，生成结束后再由数据库接管；
 * 两边对不上，交棒那一帧就会跳字或回退。
 */
sealed interface LiveDelta {
    /** 新的一段思考开始：上一段已经结束，界面要把实时思考清空重来 */
    data object ThinkStart : LiveDelta

    /** 思考追加 */
    data class Think(val text: String) : LiveDelta

    /**
     * 这一段思考结束了（`content_block_stop`）。
     *
     * 界面要的不是「有没有实时快照」，而是「思考这会儿还在不在长」：标签从
     * `正在思考` 翻成 `已思考（用时 N 秒）`、图标转灰、默认折叠，都以这一项为准。
     * [elapsedSecs] 是协议侧记的耗时；OpenAI 兼容链路没有块边界，给 `null`。
     */
    data class ThinkStop(val elapsedSecs: Double? = null) : LiveDelta

    /** 正文追加 */
    data class Text(val text: String) : LiveDelta
}

/**
 * 流 → 碎片的归并器（M8-1 抽出的协议边界）。
 *
 * 两种协议（Messages / OpenAI Chat）产出的对外形状完全一致：
 * 碎片序列 + 用量 + 终止原因 + 错误 + 标题。上层的落库/状态机（`ChatStreamRunner`）不关心
 * 哪家协议，只消费这个接口。
 */
interface StreamAssembler {

    /** 协议给的终止原因；还在流中时为 `null` */
    val stopReason: StopReason?

    /** 服务端下发的会话标题（只有 Messages 协议会给）；没有就是 `null` */
    val title: String?

    val usage: Usage?

    /** 流中主动下发的错误（`error` 帧）；没有就是 `null` */
    val error: StreamError?

    /** 是否见到过协议层的「结束」标记（Messages: `message_stop`；OpenAI: `[DONE]`） */
    val sawMessageStop: Boolean

    /** Messages 协议的思考签名，续写要回传；OpenAI 链路恒 `null` */
    val thinkingSignature: String?

    fun onFrame(name: String?, data: String)

    /** 当前已归并出的碎片（按 ord 升序） */
    fun fragments(): List<Fragment>

    /**
     * 取走本帧产生的实时增量（界面逐帧出字用）。
     *
     * 每帧只发一次、发完即清。默认空实现：不关心实时预览的解码器不必实现。
     */
    fun drainLive(into: (LiveDelta) -> Unit) = Unit

    /** 流结束时把没配对的工具块落成 WIP（只有 Messages 协议用得到） */
    fun finishUnpairedTools()

    /** 本轮是否用过服务端工具（继续生成的前置条件，只有官方有） */
    fun usedServerTools(): Boolean
}
