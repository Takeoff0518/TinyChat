package io.tinychat.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.tinychat.designsystem.TcMotion
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 菜单挂在哪儿：触点（消息）或整行（会话条目）。坐标都是窗口像素。 */
sealed interface MenuAnchor {
    /** 贴在触点正上方，长按的位置就是它 */
    data class Pointer(val center: IntOffset) : MenuAnchor

    /** 贴在这一行正上方、水平居中 */
    data class Row(val bounds: IntRect) : MenuAnchor
}

data class TcMenuItem(
    val label: String,
    /** 危险动作（删除）用 `danger` 色 */
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** 触点与菜单底边的距离。指尖接触半径约 4.5 毫米，在 xxhdpi 上接近 17dp，留 20dp 才真的不挡字。 */
private val PointerGap = 20.dp

/** 行与菜单底边的距离：手指按在行中间，菜单在行上方，8dp 已经够开。 */
private val RowGap = 8.dp

/** 菜单离屏幕边缘的最小距离。 */
private val ScreenMargin = 8.dp

private val MenuShape = RoundedCornerShape(12.dp)

/**
 * 长按菜单（`06` §二 §三）。
 *
 * 位置由 [MenuAnchor] 决定，不用 Material 的 `DropdownMenu`：它的落点由内部 provider 钉死在锚点角落，
 * 做不出「贴在手指上方、水平居中对齐触点」。自绘 Popup 之后颜色也用回了自己的色板。
 */
@Composable
fun TcAnchorMenu(
    visible: Boolean,
    anchor: MenuAnchor,
    items: List<TcMenuItem>,
    onDismiss: () -> Unit,
) {
    if (!visible || items.isEmpty()) return

    val palette = TcTheme.palette
    val density = LocalDensity.current
    val gapPx = with(density) { (if (anchor is MenuAnchor.Pointer) PointerGap else RowGap).roundToPx() }
    val marginPx = with(density) { ScreenMargin.roundToPx() }
    val provider = remember(anchor, gapPx, marginPx) { TcMenuPositionProvider(anchor, gapPx, marginPx) }

    // 退场要做完整，就不能在点下去那一刻把 Popup 卸掉：先收起动画，动画走完再通知调用方。
    // 二者必须错开——Popup 一卸载，内容连同动画状态一起没了。
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    fun dismissAfterExit(then: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            // 多给一帧，否则状态切走时动画还差最后一帧，看起来像被切断
            delay(TcMotion.MenuOut + 16L)
            then()
        }
    }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { dismissAfterExit(onDismiss) },
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val progress by animateFloatAsState(
            targetValue = if (entered && !closing) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (closing) TcMotion.MenuOut else TcMotion.MenuIn,
                easing = if (closing) TcMotion.Decelerate else TcMotion.Standard,
            ),
            label = "anchor-menu",
        )
        Column(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = 0.96f + 0.04f * progress
                    alpha = progress
                }
                .shadow(8.dp, MenuShape)
                .clip(MenuShape)
                .background(palette.bgLayer2)
                // 宽度跟着最宽的一条文字走（三项等宽），上下限兜住极短与极长的文案。
                // 不这么写的话 fillMaxWidth 会把每条菜单撑到上限，两个字的「复制」也占满一条长条。
                .widthIn(min = 120.dp, max = 240.dp)
                .width(IntrinsicSize.Max)
                .padding(vertical = 4.dp),
        ) {
            items.forEach { item ->
                Text(
                    text = item.label,
                    style = TcTypography.bodyMedium,
                    color = if (item.danger) palette.danger else palette.labelPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dismissAfterExit(item.onClick) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private class TcMenuPositionProvider(
    private val anchor: MenuAnchor,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rect = when (anchor) {
            is MenuAnchor.Pointer -> IntRect(anchor.center.x, anchor.center.y, anchor.center.x, anchor.center.y)
            is MenuAnchor.Row -> anchor.bounds
        }
        return menuOffset(rect, popupContentSize, windowSize, gapPx, marginPx)
    }
}

/**
 * 只记下按下的位置，不消费事件——同一节点上的点击与长按检测照常工作。
 *
 * 用它而不是 `detectTapGestures` 的原因：后者会把按下事件消费掉，`combinedClickable` 就收不到长按了。
 */
fun Modifier.tcPressPosition(onDown: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown(down.position)
    }
}
