package io.tinychat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcTheme
import kotlin.math.abs

@Composable
fun TcSideDrawers(
    sessionsWidth: Dp,
    usageWidth: Dp,
    // 会话抽屉进度为正，用量抽屉进度为负。
    progress: () -> Float,
    revealed: SidePanel,
    coversChat: Boolean,
    canDrag: Boolean,
    onDismiss: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (dxPx: Float) -> Unit,
    onDragEnd: (velocityDp: Float) -> Unit,
    modifier: Modifier = Modifier,
    sessions: @Composable () -> Unit,
    usage: @Composable () -> Unit,
    chat: @Composable () -> Unit,
) {
    val palette = TcTheme.palette
    val density = LocalDensity.current.density
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val startDirection = if (leftToRight) 1f else -1f
    val updateDrag = rememberUpdatedState(onDragDelta)
    val drag = rememberDraggableState { updateDrag.value(it) }
    val startCorners = remember { RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) }
    val endCorners = remember { RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp) }

    Box(
        modifier.fillMaxSize().draggable(
            state = drag,
            orientation = Orientation.Horizontal,
            enabled = canDrag,
            onDragStarted = { onDragStart() },
            onDragStopped = { onDragEnd(it / density) },
        ),
    ) {
        DrawerPane(
            width = sessionsWidth,
            title = stringResource(R.string.side_drawer_history),
            atStart = true,
            revealed = revealed == SidePanel.Sessions,
            hasProgress = { progress() > 0f },
            modifier = Modifier.align(Alignment.CenterStart),
            content = sessions,
        )
        DrawerPane(
            width = usageWidth,
            title = stringResource(R.string.side_drawer_usage),
            atStart = false,
            revealed = revealed == SidePanel.Usage,
            hasProgress = { progress() < 0f },
            modifier = Modifier.align(Alignment.CenterEnd),
            content = usage,
        )
        // 只移动和缩放绘制层，列表、输入区和抽屉不跟着每帧重新测量。
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val signedProgress = progress()
                val fromStart = signedProgress >= 0f
                val fraction = abs(signedProgress)
                val width = if (fromStart) sessionsWidth else usageWidth
                val verticalScale = if (size.height > 0f) {
                    (1f - 40.dp.toPx() / size.height * fraction).coerceAtLeast(0.85f)
                } else 1f
                translationX = width.toPx() * signedProgress * startDirection
                scaleX = verticalScale
                scaleY = verticalScale
                transformOrigin = TransformOrigin(if (fromStart == leftToRight) 0f else 1f, 0.5f)
                shape = if (fromStart) startCorners else endCorners
                clip = fraction > 0f
                shadowElevation = 12.dp.toPx() * fraction
            }.background(palette.bg),
        ) {
            Box(
                Modifier.fillMaxSize().then(
                    if (coversChat) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier,
                ),
            ) {
                chat()
            }
            if (coversChat) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(palette.bg.copy(alpha = 0.62f * abs(progress())))
                    }.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(
                            when (revealed) {
                                SidePanel.Sessions -> R.string.side_drawer_close_history
                                SidePanel.Usage -> R.string.side_drawer_close_usage
                                SidePanel.None -> R.string.side_drawer_close
                            },
                        ),
                        onClick = onDismiss,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DrawerPane(
    width: Dp,
    title: String,
    atStart: Boolean,
    revealed: Boolean,
    hasProgress: () -> Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.width(width)
            .fillMaxHeight()
            // 抽屉收起后留在屏幕外，保留测量结果并避免遮挡另一侧的触摸区域。
            .offset {
                val direction = if (atStart) -1 else 1
                if (revealed || hasProgress()) IntOffset.Zero
                else IntOffset(width.roundToPx() * direction, 0)
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .then(
                if (revealed) Modifier.semantics { paneTitle = title }
                else Modifier.clearAndSetSemantics { hideFromAccessibility() },
            ),
    ) {
        content()
    }
}
