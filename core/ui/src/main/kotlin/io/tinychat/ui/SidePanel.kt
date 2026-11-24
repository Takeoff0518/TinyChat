package io.tinychat.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * 屏幕两侧的面板（P-028）。同一时刻至多一个 —— 用一个变量表示，而不是两个布尔，
 * 互斥因此是结构性的，不依赖任何一段记得清理另一边的代码。
 *
 * 两个面板都是自绘的（P-030 起左抽屉也自己画）：拖拽 1:1 跟手，松手走固定时长的
 * 曲线，速度只参与「开还是关」的判定，不参与动画 —— 所以不会冲过终点再弹回来。
 *
 * - [Sessions] 起始侧会话抽屉；
 * - [Usage] 结束侧「用量与费用」。
 */
enum class SidePanel(val openProgress: Float) { None(0f), Sessions(1f), Usage(-1f) }

/** 朝打开方向走过多少算开：2%。左右两个侧栏都几乎「有位移就开」（P-031，对齐官方那种手感）。 */
const val PANEL_OPEN_FRACTION = 0.02f

/** 朝关闭方向走过多少算关：35%。关要更明确一点，免得手一抖就把抽屉收了（与底部抽屉同一口径）。 */
const val PANEL_CLOSE_FRACTION = 0.35f

/** 打开方向的甩动判定，dp/s：250 比底部抽屉松得多，轻轻一拨就开。 */
const val PANEL_OPEN_FLING_DP = 250f

/** 关闭方向的甩动判定，dp/s：800，与底部抽屉一致。 */
const val PANEL_CLOSE_FLING_DP = 800f

/** 横向必须明显大于纵向才接管，否则竖向翻聊天会被当成开面板。 */
private const val PANEL_SWIPE_RATIO = 1.5f

/**
 * 松手时该开还是该关（纯函数，可单测）。
 *
 * [progress] 是当前展开进度（0 收起、1 展开），[velocityDp] 是指向「打开」方向的速度
 * （正 = 打开，调用方负责把左右两个方向的符号统一），[currentlyOpen] 是松手前那一侧的状态。
 *
 * 两个方向的判据是**不对称**的：打开很松（2%，或者 250dp/s 的轻拨），关闭要明确
 * （35%，或者 800dp/s）—— 官方那种「手指轻轻一划、抽屉就整个出来」的观感来自前者，
 * 而后者保证不会因为手一抖就把已经打开的抽屉关掉。
 *
 * 这里只判断开合；开合**怎么走完**由固定时长曲线决定，与速度无关。
 */
fun panelTargetOpen(
    progress: Float,
    velocityDp: Float,
    currentlyOpen: Boolean,
    openFraction: Float = PANEL_OPEN_FRACTION,
    closeFraction: Float = PANEL_CLOSE_FRACTION,
    openFlingDp: Float = PANEL_OPEN_FLING_DP,
    closeFlingDp: Float = PANEL_CLOSE_FLING_DP,
): Boolean = when {
    velocityDp >= openFlingDp -> true
    velocityDp <= -closeFlingDp -> false
    currentlyOpen -> progress > 1f - closeFraction
    else -> progress >= openFraction
}

/**
 * 整页横向滑动拉开侧栏（P-028 / P-030）：向左拉出右侧面板，向右拉出左侧面板。
 *
 * 为什么不贴边缘起手：返回手势的触区就在两侧边缘（AOSP 原文为 inward swipe from either
 * edge），从那里起手会被系统当成返回 —— 聊天页是栈底，表现就是直接退出应用。所以起手
 * 位置不限，唯独边缘那一下归系统。
 *
 * 为什么这样不会抢竖向滚动：本检测器挂在内容层之上、在 Main 通道里排在子级之后，
 * 竖列表、横向滚动的代码块先消费，这里看到 `isConsumed` 就放弃。
 *
 * @param onDragDelta 相对上一次的水平位移，px（向左为负）
 * @param onDragEnd 松手时的横向速度，dp/s（向右为正）——已换算好，调用方不必再管密度
 */
@Composable
fun Modifier.swipeToOpenPanel(
    enabled: Boolean,
    onDragStart: (leftward: Boolean) -> Unit,
    onDragDelta: (leftward: Boolean, dxPx: Float) -> Unit,
    onDragEnd: (leftward: Boolean, velocityDp: Float) -> Unit,
): Modifier {
    if (!enabled) return this
    val startDrag by rememberUpdatedState(onDragStart)
    val updateDrag by rememberUpdatedState(onDragDelta)
    val endDrag by rememberUpdatedState(onDragEnd)
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            var lastX = startX
            var claimed = false
            var leftward = false
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed || !change.pressed) break

                if (!claimed) {
                    val dx = change.position.x - startX
                    if (abs(dx) < slop) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        continue
                    }
                    if (abs(dx) < abs(change.position.y - startY) * PANEL_SWIPE_RATIO) break
                    claimed = true
                    leftward = dx < 0f
                    startDrag(leftward)
                    lastX = change.position.x
                    change.consume()
                    continue
                }

                tracker.addPosition(change.uptimeMillis, change.position)
                updateDrag(leftward, change.position.x - lastX)
                lastX = change.position.x
                change.consume()
            }

            if (claimed) endDrag(leftward, tracker.calculateVelocity().x / density)
        }
    }
}
