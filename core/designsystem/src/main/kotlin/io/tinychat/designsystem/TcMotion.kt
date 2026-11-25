package io.tinychat.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * 动效令牌（见 `02 §16`）。
 *
 * 数值全部照 `02 §16` 的规格取，不另造一套：抽屉 200ms、底部抽屉 240ms、思维链展开 200ms
 * 高度 + 150ms 淡入、消息插入 180ms、状态反馈 120ms。
 *
 * 规矩：新代码不许出现裸数字时长或裸曲线，一律取这里的值。节奏要改就改这一处——
 * 以前同样的 180ms 散在四个文件里，改一次要翻五处，改漏一处就开始各说各话。
 */
object TcMotion {

    /** 状态反馈与退场淡出：图标变勾、复制提示消失、菜单外的遮罩 */
    const val Quick = 120

    /** 内容淡入：跟着容器一起出现的文字，比容器本身晚一点点、快一点点 */
    const val Fade = 150

    /** 通用：操作栏出现、思维链标题切换 */
    const val Base = 180

    /** 布局移动与展开：左右抽屉、思维链高度 */
    const val Panel = 200

    /** 完整页面的进入和返回 */
    const val Page = 250

    /** 底部抽屉上滑 */
    const val Sheet = 240

    /** 长按菜单进场 */
    const val MenuIn = 120

    /** 长按菜单退场：比进场短一档，收得干脆一点 */
    const val MenuOut = 90

    /** 循环动画：朗读中的跳动竖条 */
    const val Ambient = 520

    /** 默认曲线，即 `02 §16` 的 `cubic-bezier(0.4, 0, 0.2, 1)` */
    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 退场曲线：减速收住。退场用 [Standard] 会显得拖沓 */
    val Decelerate: Easing = LinearOutSlowInEasing

    /** 需要一点「重量」的动作 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
