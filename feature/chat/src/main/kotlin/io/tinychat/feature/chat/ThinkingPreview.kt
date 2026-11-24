package io.tinychat.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcMotion
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import kotlinx.coroutines.delay

private const val PREVIEW_LINES = 6
private const val PREVIEW_SCROLLBACK_LINES = 8
private const val PREVIEW_FRAME_MS = 32L

internal fun List<TimelineItem>.thinkingPreviewText(): String {
    val parts = filterIsInstance<TimelineItem.Text>()
    return parts.singleOrNull()?.content ?: parts.joinToString("\n") { it.content }
}

@Composable
internal fun rememberThinkingPreviewEnd(text: String, active: Boolean, streaming: Boolean): IntState {
    // Keep the cursor with the message when its preview is hidden or scrolled off screen.
    val end = rememberSaveable { mutableIntStateOf(if (streaming) 0 else text.length) }
    val currentText by rememberUpdatedState(text)
    LaunchedEffect(active, streaming) {
        if (!active) return@LaunchedEffect
        val reveal = ThinkingReveal(end.intValue)
        val motion = coroutineContext[MotionDurationScale]
        snapshotFlow { currentText to (motion?.scaleFactor ?: 1f) }.collect {
            do {
                val latest = currentText
                reveal.update(latest)
                if (streaming && motion?.scaleFactor != 0f) {
                    val advancing = reveal.hasPending
                    end.intValue = reveal.advance()
                    if (advancing) delay(PREVIEW_FRAME_MS)
                } else {
                    end.intValue = reveal.skip()
                }
            } while (reveal.hasPending || latest != currentText)
        }
    }
    return end
}

@Composable
internal fun ThinkingPreview(text: String, visibleEnd: IntState) {
    val offset = visibleEnd.intValue.coerceAtMost(text.length)
    val end = if (text.getOrNull(offset - 1)?.isHighSurrogate() == true) offset - 1 else offset
    if (end == 0) return

    val style = remember { TcTypography.bodyMedium.copy(lineBreak = LineBreak.Simple) }
    val color = TcTheme.palette.labelSecondary
    val measurer = rememberTextMeasurer(cacheSize = 0)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val window = remember(measurer, width, style) { ThinkingPreviewWindow(measurer, width, style) }
        val viewport = remember(text, end, window) { window.measure(text, end) }
        val scroll by animateFloatAsState(
            targetValue = viewport.scroll,
            animationSpec = tween(78, easing = TcMotion.Decelerate),
            label = "thinking lines",
        )
        Canvas(
            Modifier.fillMaxWidth()
                .height(with(density) { viewport.height.toDp() })
                .clipToBounds()
                .semantics { this.text = viewport.text },
        ) {
            val offsetY = (viewport.scroll - scroll).coerceIn(0f, viewport.previousLineHeight)
            drawText(viewport.layout, color = color, topLeft = Offset(0f, offsetY - viewport.top))
        }
    }
}

/** Reuse the wrapping of a previous line when discarding older text from the measured window. */
private class ThinkingPreviewWindow(
    private val measurer: TextMeasurer,
    private val width: Int,
    private val style: TextStyle,
) {
    private var anchor = 0
    private var anchorY = 0f
    private var previousText = ""
    private var previousEnd = 0

    fun measure(text: String, end: Int): ThinkingViewport {
        if (end < previousEnd || !text.regionMatches(anchor, previousText, anchor, previousEnd - anchor)) {
            anchor = 0
            anchorY = 0f
        }
        previousText = text
        previousEnd = end
        val tail = text.substring(anchor, end).trimEnd()
        val layout = measurer.measure(tail, style = style, constraints = Constraints.fixedWidth(width))
        val first = (layout.lineCount - PREVIEW_LINES).coerceAtLeast(0)
        val top = layout.getLineTop(first)
        val viewport = ThinkingViewport(
            layout = layout,
            top = top,
            height = layout.size.height - top,
            scroll = anchorY + top,
            previousLineHeight = if (first == 0) 0f else top - layout.getLineTop(first - 1),
            text = AnnotatedString(tail.substring(layout.getLineStart(first))),
        )

        if (first > PREVIEW_SCROLLBACK_LINES) {
            val keepFrom = first - PREVIEW_SCROLLBACK_LINES / 2
            anchor += layout.getLineStart(keepFrom)
            anchorY += layout.getLineTop(keepFrom)
        }
        return viewport
    }
}

private data class ThinkingViewport(
    val layout: TextLayoutResult,
    val top: Float,
    val height: Float,
    val scroll: Float,
    val previousLineHeight: Float,
    val text: AnnotatedString,
)

@Composable
internal fun ThinkingIcon(active: Boolean) {
    val palette = TcTheme.palette
    val pulse = if (active) {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(TcMotion.Ambient, easing = TcMotion.Standard), RepeatMode.Reverse),
            label = "thinking icon pulse",
        )
    } else null
    Icon(
        imageVector = TcIcons.Think,
        contentDescription = null,
        tint = if (active) palette.brand else palette.labelSecondary,
        modifier = Modifier.size(16.dp).graphicsLayer { alpha = pulse?.value ?: 1f },
    )
}
