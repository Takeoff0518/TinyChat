package io.tinychat.markdown

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography

/**
 * 块级公式的显示（M2-5）：`$$…$$`、`\[…\]`、```math 围栏都走这里。
 *
 * **行内公式不走这里** —— 它必须以「内联内容」的形式插进段落（见 [InlineMathParagraph]），
 * 单独一个 Composable 参与不了正文的行盒，也就无法对齐基线、无法撑开行高。
 *
 * 渲染交给 [TcRenderHost]（隐藏 WebView 出位图），这里只管摆放，规矩只有一条：
 * **位图有多少像素，屏幕上就画多大**，绝不缩放。于是
 * - 填不满整行 → 居中；
 * - 填满或超出整行 → 左边缘顶住、右侧溢出被裁掉，手指左右滑动查看（带惯性）。
 */
@Composable
internal fun TcMath(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 17.sp,
) {
    val palette = TcTheme.palette
    val density = LocalDensity.current
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        val available = maxWidth
        val widthPx = with(density) { available.toPx().toInt() }.coerceAtLeast(1)
        val fontPx = with(density) { fontSize.toPx().toInt() }.coerceAtLeast(8)
        // **一律按自然宽度出图**：WebView 那边把公式排成不换行的自然宽，再按墨迹裁成紧贴内容的
        // 位图。不做「先按容器宽画一遍、发现被切了再重画」—— 那样每次都要多渲染一轮（排版 + 光栅
        // 约 300ms），而且第一帧必然是被切断的残图，会闪一下。
        val request = remember(latex, fontPx, widthPx, palette.labelPrimary) {
            mathRequest(
                latex = latex,
                display = true,
                fontPx = fontPx,
                widthPx = widthPx,
                textColor = palette.labelPrimary,
                background = palette.bg,
                naturalWidth = true,
            )
        }
        var result by remember(request) { mutableStateOf(TcRenderHost.cached(request)) }
        LaunchedEffect(request) {
            if (result?.bitmap == null) result = TcRenderHost.render(context, request)
        }

        val bitmap = result?.bitmap
        when {
            bitmap != null -> {
                val imageWidth = with(density) { bitmap.width.toDp() }
                val imageHeight = with(density) { bitmap.height.toDp() }
                if (bitmap.width <= widthPx) {
                    // 填不满整行：居中
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TcMathImage(bitmap, imageWidth, imageHeight)
                    }
                } else {
                    // 填满或超出整行：左边缘顶住、右侧溢出裁掉，左右滑动查看。
                    // 用标准的 horizontalScroll（有惯性、有越界回弹），别自己手搓位移。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TcMathImage(bitmap, imageWidth, imageHeight)
                    }
                }
            }

            result?.error != null -> MathSource(latex, display = true, error = true)
            else -> MathPlaceholder()
        }
    }
}

/**
 * 把公式位图按「位图像素 = 布局像素」1:1 画出来。
 *
 * 两个坑都记在这里：
 * ① 必须用 `requiredSize` 而不是 `size` —— `size` 会被父约束压缩，压缩之后 `Image` 又会按
 *    `contentScale` 再「适配」一次，公式就被缩成小字（实测缩到过 0.57 倍）；
 * ② 不用 `Image` / `contentScale`，直接 `drawImage`。
 *
 * ⚠️ 它必须是一个**独立的 Composable**，在 `Box`/`Row` 的 content lambda 里调用。
 * 早先写成 `val image = Canvas(…)` 是错的：`Canvas` 会在赋值那一行**就地发射**，
 * 结果是外层容器的子项而不是 `Box`/`Row` 的子项 —— 于是横向滚动容器里什么都没有（拖不动），
 * 画布位置由祖先决定（居中、两边溢出屏幕）。这坑踩过两次。
 */
@Composable
private fun TcMathImage(bitmap: ImageBitmap, width: Dp, height: Dp) {
    Canvas(modifier = Modifier.requiredSize(width = width, height = height)) {
        drawImage(bitmap)
    }
}

private fun mathRequest(
    latex: String,
    display: Boolean,
    fontPx: Int,
    widthPx: Int,
    textColor: Color,
    background: Color,
    naturalWidth: Boolean = false,
) = TcRenderHost.Request(
    kind = TcRenderHost.Kind.MATH,
    source = latex,
    display = display,
    fontPx = fontPx,
    widthPx = widthPx,
    colorHex = textColor.toHex(),
    dark = background.luminance() < 0.5f,
    naturalWidth = naturalWidth,
)

/** 位图出来之前的兜底：等宽源码，宽度与行高都稳定，不会把下面顶来顶去 */
@Composable
private fun MathSource(latex: String, display: Boolean, error: Boolean) {
    val palette = TcTheme.palette
    Text(
        text = latex,
        fontFamily = FontFamily.Monospace,
        fontSize = if (display) 15.sp else 14.sp,
        color = if (error) palette.labelTertiary else palette.labelSecondary,
        modifier = Modifier
            .then(if (display) Modifier.fillMaxWidth() else Modifier)
            .padding(vertical = if (display) 4.dp else 0.dp),
        textAlign = if (display) TextAlign.Center else TextAlign.Start,
    )
}

/** 块级公式底板：固定高度，避免「先矮后高」把下方内容顶一下 */
@Composable
private fun MathPlaceholder() {
    val palette = TcTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(palette.inlineCodeBg.copy(alpha = 0.55f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.markdown_rendering_math),
            style = TcTypography.bodySmall,
            color = palette.labelTertiary,
        )
    }
}

private fun Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
