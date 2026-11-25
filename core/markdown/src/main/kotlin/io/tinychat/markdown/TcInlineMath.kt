package io.tinychat.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcTheme

/**
 * 行内公式（M2-5 的第二段，A2 的「结束后升级」）。
 *
 * 流式期间：只吞掉 `$` 定界符，宽度稳定、零异步（见 [applyInlineMath]）；
 * 消息结束：把这段里所有公式**一次性**渲成位图，替换成内联内容 —— 只重排一次。
 * 之所以要等结束：内联内容的占位尺寸必须在排版前确定，而位图尺寸是异步才知道的；
 * 边流边换 = 每换一次重排一段，官方 App 长文卡顿就是这么来的。
 *
 * **对齐（M2-5 收尾的核心）**：占位框只占位图「基线以上」那一段，并且用
 * `AboveBaseline` 让这一段的下边正好落在正文基线上；位图 [Image] 用 `requiredSize`
 * 按原尺寸从占位框顶端往下画，超出的部分（分式的分母、下标、根号下沿）落进行距里。
 * 这样公式的**基线与正文基线是同一条**，不会整体偏高，也不会压到下一行。
 * 代价是内容会画出占位框，所以 `Text` 必须显式 `overflow = Visible`（默认会裁）。
 *
 * 任何一条公式渲染失败就整段退回源码文字，**不显示半张图**。
 */
@Composable
internal fun InlineMathParagraph(
    built: AnnotatedString,
    formulas: List<InlineMath>,
    streaming: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val palette = TcTheme.palette
    // 段落文字色：样式里带了就用它（引用块是三级灰），否则用正文色。
    // **必须显式传**：`Text` 不传颜色会回落到 `LocalContentColor`，而这里的外层是
    // Markdown 库的容器，暗色下拿到的是 Material 的默认（黑），表现为「字黑、公式白」。
    val textColor = style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
        ?: palette.labelPrimary
    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    var images by remember(formulas, streaming) { mutableStateOf<Map<Int, InlineMathImage>?>(null) }

    LaunchedEffect(formulas, streaming) {
        if (streaming || formulas.isEmpty()) {
            images = null
            return@LaunchedEffect
        }
        // 放大字号重排（见 InlineMathFontScale）：位图仍是 1:1 显示，不做位图缩放
        val fontPx = (with(density) { style.fontSize.toPx() } * InlineMathFontScale)
            .toInt()
            .coerceAtLeast(10)
        val rendered = LinkedHashMap<Int, InlineMathImage>()
        formulas.forEachIndexed { index, formula ->
            val request = TcRenderHost.Request(
                kind = TcRenderHost.Kind.MATH,
                source = formula.latex,
                display = false,
                fontPx = fontPx,
                widthPx = screenPx.coerceAtLeast(1),
                colorHex = textColor.toHex(),
                dark = palette.bg.luminance() < 0.5f,
            )
            val result = TcRenderHost.render(context, request)
            val bitmap = result.bitmap ?: run {
                images = null
                return@LaunchedEffect
            }
            rendered[index] = InlineMathImage(bitmap, result.widthPx, result.heightPx, result.baselinePx)
        }
        images = rendered
    }

    val ready = images
    if (ready == null || ready.size != formulas.size) {
        Text(
            text = remember(formulas) { built.applyInlineMath(formulas, replace = false) },
            style = style,
            color = textColor,
            modifier = modifier,
        )
        return
    }

    // 占位尺寸与行高都用 sp 数值（Compose 会再乘 fontScale），换算口径见 InlineMathLayout
    val fontScale = density.fontScale
    val pxPerSp = (density.density * fontScale).coerceAtLeast(0.01f)
    val metrics = remember(ready, pxPerSp) {
        ready.mapValues { (_, image) ->
            inlineMathMetrics(image.widthPx, image.heightPx, image.baselinePx, pxPerSp)
        }
    }
    val lineHeightSp = remember(metrics, style.lineHeight) {
        inlineMathLineHeight(
            baseLineHeightSp = style.lineHeight.value,
            metrics = metrics.values,
            gapSp = with(density) { InlineMathLineGap.toPx() } / pxPerSp,
        )
    }

    val content = remember(ready, metrics) {
        formulas.mapIndexed { index, formula ->
            val image = ready.getValue(index)
            val metric = metrics.getValue(index)
            inlineMathTag(index) to InlineTextContent(
                Placeholder(
                    width = metric.widthSp.sp,
                    height = metric.aboveBaselineSp.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                ),
            ) {
                // 占位框 = 基线以上那段；位图按自身尺寸从框顶往下画，下伸部分画进下行距
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = image.bitmap,
                        contentDescription = formula.latex,
                        modifier = Modifier.requiredSize(
                            width = with(density) { image.widthPx.toDp() },
                            height = with(density) { image.heightPx.toDp() },
                        ),
                    )
                }
            }
        }.toMap()
    }
    Text(
        text = remember(formulas) { built.applyInlineMath(formulas, replace = true) },
        style = style.copy(lineHeight = lineHeightSp.sp),
        color = textColor,
        inlineContent = content,
        // 位图会画出占位框（下伸），默认 overflow 会把它裁掉
        overflow = TextOverflow.Visible,
        modifier = modifier,
    )
}

/** 撑开行高时在公式上下各留的余量：太小会贴字，太大会让含公式的段落明显变松 */
private val InlineMathLineGap = 3.dp

internal data class InlineMathImage(
    val bitmap: ImageBitmap,
    val widthPx: Int,
    val heightPx: Int,
    /** 基线在位图中的 y（物理 px）；0 = 没量到，按「底边贴基线」退化成整图在基线之上 */
    val baselinePx: Int = 0,
)

private fun androidx.compose.ui.graphics.Color.toHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)
