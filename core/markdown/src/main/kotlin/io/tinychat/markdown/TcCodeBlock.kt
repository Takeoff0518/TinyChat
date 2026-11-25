package io.tinychat.markdown

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import kotlinx.coroutines.delay

/** 还在流式输出：代码块不做折叠，否则长代码会在输出途中突然被折起来 */
internal val LocalMarkdownStreaming = staticCompositionLocalOf { false }

/** 超过这个行数才折叠；普通示例代码不受影响 */
internal const val CollapseAfterLines = 20

/** 折叠后的可见代码 + 是否提供了折叠入口（纯函数，便于单测） */
internal fun collapsedCode(
    code: String,
    expanded: Boolean,
    streaming: Boolean,
    collapseAfter: Int = CollapseAfterLines,
): Pair<String, Boolean> {
    val lines = code.lines()
    val collapsible = !streaming && lines.size > collapseAfter
    val visible = if (collapsible && !expanded) lines.take(collapseAfter).joinToString("\n") else code
    return visible to collapsible
}

/**
 * 代码块（M2-4）：`[语言] ... [复制]` 的顶部栏 + 等宽代码区 + 长文折叠。
 *
 * 语言名就是 ``` 后面那段原文（`json` / `cpp` / 空），为空时顶部只留复制按钮；
 * 顶部栏显示原文，着色用别名表映射后的语言（`kt`、`c++` 这类写法都认）。
 * 着色覆盖十二种常用语言，未识别的语言按纯文本渲染，见 [SyntaxLanguage]。
 */
@Composable
internal fun TcCodeBlock(code: String, language: String?, style: TextStyle) {
    val palette = TcTheme.palette
    val clipboard = LocalClipboardManager.current
    val streaming = LocalMarkdownStreaming.current

    var copied by remember { mutableStateOf(false) }
    var expanded by remember(code) { mutableStateOf(false) }
    val lineCount = remember(code) { code.lines().size }
    val (visibleCode, collapsible) = collapsedCode(code = code, expanded = expanded, streaming = streaming)

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language.orEmpty(),
                style = MarkdownCodeBannerStyle,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (copied) TcIcons.Check else TcIcons.Copy,
                    contentDescription = if (copied) stringResource(R.string.markdown_copied) else stringResource(R.string.markdown_copy_code),
                    tint = if (copied) palette.labelPrimary else palette.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) stringResource(R.string.markdown_copied) else stringResource(R.string.markdown_copy),
                    style = MarkdownCodeBannerStyle,
                    color = if (copied) palette.labelPrimary else palette.labelTertiary,
                )
            }
        }

        HorizontalDivider(color = palette.borderL2, thickness = Dp.Hairline)

        TcCodeText(code = visibleCode, language = SyntaxLanguage.fromFence(language), style = style)

        if (collapsible) {
            HorizontalDivider(color = palette.borderL2, thickness = Dp.Hairline)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (expanded) stringResource(R.string.markdown_collapse_code) else stringResource(R.string.markdown_expand_code, lineCount),
                    style = MarkdownCodeBannerStyle,
                    color = palette.labelSecondary,
                )
            }
        }
    }
}

/** 顶部栏文字：13sp，比正文小一档，不跟正文字号缩放联动 */
internal val MarkdownCodeBannerStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
