package io.tinychat.markdown

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme

/**
 * Mermaid 图表块（M2-6）：顶部栏（`mermaid` + 图/码切换）+ 图或源码。
 *
 * 流式期间先显示源码：图还没画完时反复重绘既慢又会闪，等收尾再切图。
 * 渲染失败落到错误态，并把源码摆出来（不能白屏，也不能把用户卡死在空白框里）。
 */
@Composable
internal fun TcMermaidBlock(code: String, style: TextStyle) {
    val palette = TcTheme.palette
    val streaming = LocalMarkdownStreaming.current
    var showCode by remember { mutableStateOf(streaming) }
    var error by remember(code) { mutableStateOf<String?>(null) }

    // 流式结束自动切到图：用户不用为了看一眼图去点一下
    LaunchedEffect(streaming) {
        if (!streaming) showCode = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.codeBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.inlineCodeBg)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "mermaid",
                style = MarkdownCodeBannerStyle,
                color = palette.labelSecondary,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showCode = !showCode }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (showCode) TcIcons.Browse else TcIcons.Data,
                    contentDescription = if (showCode) stringResource(R.string.markdown_show_diagram) else stringResource(R.string.markdown_show_code),
                    tint = palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (showCode) stringResource(R.string.markdown_show_diagram) else stringResource(R.string.markdown_show_code),
                    style = MarkdownCodeBannerStyle,
                    color = palette.labelTertiary,
                )
            }
        }

        HorizontalDivider(color = palette.borderL2, thickness = Dp.Hairline)

        if (showCode) {
            TcCodeText(code = code, language = SyntaxLanguage.MERMAID, style = style)
        } else {
            error?.let {
                val detail = when (it) {
                    "渲染超时" -> stringResource(R.string.markdown_render_timeout)
                    "渲染失败" -> stringResource(R.string.markdown_render_failure)
                    else -> it
                }
                Text(
                    text = stringResource(R.string.markdown_diagram_error_detail, detail.take(200)),
                    style = MarkdownCodeBannerStyle,
                    color = palette.labelTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                HorizontalDivider(color = palette.borderL2, thickness = Dp.Hairline)
                TcCodeText(code = code, language = SyntaxLanguage.MERMAID, style = style)
            } ?: MermaidImage(code = code, onError = { error = it })
        }
    }
}

/**
 * 图本体：一次性出位图，宽度铺满正文（超宽的图走横向滚动由上层限制在正文宽度内）。
 */
@Composable
private fun MermaidImage(code: String, onError: (String) -> Unit) {
    val palette = TcTheme.palette
    val density = LocalDensity.current
    val context = LocalContext.current
    val screenPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx().toInt() }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx().toInt() }.coerceAtLeast(320)
        val request = remember(code, widthPx, palette.bg) {
            TcRenderHost.Request(
                kind = TcRenderHost.Kind.MERMAID,
                source = code,
                fontPx = 14,
                widthPx = widthPx,
                colorHex = "#FFFFFF",
                dark = palette.bg.luminance() < 0.5f,
            )
        }
        var result by remember(request) { mutableStateOf(TcRenderHost.cached(request)) }
        LaunchedEffect(request) {
            if (result == null) result = TcRenderHost.render(context, request)
            result?.error?.let(onError)
        }

        val bitmap = result?.bitmap
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.markdown_mermaid_diagram),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    // 按位图长宽比占高：图是等比缩到正文宽的，不能写死 fillMaxWidth 再压扁
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = if (result?.error != null) stringResource(R.string.markdown_diagram_error) else stringResource(R.string.markdown_rendering_diagram),
                    style = MarkdownCodeBannerStyle,
                    color = palette.labelTertiary,
                )
            }
        }
        // screenPx 只用于崩溃保护（极端窄屏时也至少给 320px 视口）
        if (screenPx <= 0) Spacer(Modifier.height(0.dp))
    }
}
