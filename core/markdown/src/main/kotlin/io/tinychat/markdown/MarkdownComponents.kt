package io.tinychat.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownText
import io.tinychat.designsystem.TcTheme
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

/**
 * Markdown 组件的官方化改造（M2-1 调优）。三件事：
 *
 * 1. **块间距自己控**：库给「每个顶层节点」前面插 `Spacer(padding.block)`，而 GFM 的 AST
 *    在每两个块之间夹着 **2 个 EOL 节点**，等于一次块间距被乘三（实测标题下方 54dp）。
 *    所以 `block` 置 0，改由各组件自己加内边距 —— 间距从此可预测、也不会随 AST 抖动；
 * 2. `blockQuote`：库把竖线和正文都画成正文色（黑），官方是**灰竖线 + 灰字**；
 * 3. `checkbox`：库默认只是画一段等宽文字 `[x]`，不是真的勾选框。
 */
@Composable
internal fun tcMarkdownComponents(): MarkdownComponents = markdownComponents(
    paragraph = { model ->
        TcParagraph(model)
    },
    orderedList = { model ->
        // 列表项里的段落不再额外加块间距，否则每项之间会空出 12dp
        CompositionLocalProvider(LocalBlockSpacing provides 0.dp) {
            MarkdownOrderedList(
                content = model.content,
                node = model.node,
                style = model.typography.ordered,
                depth = model.listDepth,
                markerModifier = listMarkerModifier,
            )
        }
    },
    unorderedList = { model ->
        CompositionLocalProvider(LocalBlockSpacing provides 0.dp) {
            MarkdownBulletList(
                content = model.content,
                node = model.node,
                style = model.typography.bullet,
                depth = model.listDepth,
                markerModifier = listMarkerModifier,
            )
        }
    },
    heading1 = { model -> TcHeading(model, model.typography.h1, MarkdownTokenTypes.ATX_CONTENT, top = 14.dp) },
    heading2 = { model -> TcHeading(model, model.typography.h2, MarkdownTokenTypes.ATX_CONTENT, top = 14.dp) },
    heading3 = { model -> TcHeading(model, model.typography.h3, MarkdownTokenTypes.ATX_CONTENT, top = 12.dp) },
    heading4 = { model -> TcHeading(model, model.typography.h4, MarkdownTokenTypes.ATX_CONTENT, top = 10.dp) },
    heading5 = { model -> TcHeading(model, model.typography.h5, MarkdownTokenTypes.ATX_CONTENT, top = 10.dp) },
    heading6 = { model -> TcHeading(model, model.typography.h6, MarkdownTokenTypes.ATX_CONTENT, top = 10.dp) },
    setextHeading1 = { model -> TcHeading(model, model.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT, top = 14.dp) },
    setextHeading2 = { model -> TcHeading(model, model.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT, top = 14.dp) },
    blockQuote = { model -> TcBlockQuote(model) },
    checkbox = { model -> TcCheckbox(model) },
    codeFence = { model ->
        MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
            when (language?.trim()?.lowercase()) {
                // ```math / ```latex：模型偶尔不用 $$ 包块公式，而用围栏
                "math", "latex", "tex" -> TcMath(
                    latex = code.trim(),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                // ```mermaid：图画在 TcMermaidBlock 里，图/码切换也归它管
                "mermaid", "mmd" -> TcMermaidBlock(code = code, style = style)
                else -> TcCodeBlock(code = code, language = language, style = style)
            }
        }
    },
    codeBlock = { model ->
        MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
            TcCodeBlock(code = code, language = language, style = style)
        }
    },
    table = { model ->
        Box(modifier = Modifier.padding(vertical = 8.dp)) {
            MarkdownTable(content = model.content, node = model.node, style = model.typography.table)
        }
    },
    horizontalRule = {
        MarkdownDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        )
    },
)

/**
 * 块与块之间的竖直间距；列表项内部改成 0（列表自己用 listItemTop/Bottom 控节奏）。
 */
internal val LocalBlockSpacing = staticCompositionLocalOf { 6.dp }

/** 列表符号与正文之间的空隙：比库自带的 `"• "` 后面的空格更紧凑可控 */
private val listMarkerModifier: RowScope.() -> Modifier = { Modifier.padding(end = 6.dp) }

/**
 * 段落：库默认只把文字交给 MarkdownParagraph；这里多一步 —— 把落在本段范围内的引用锚点
 * 以**内联占位符**贴到段末（`appendInlineContent` 不带 altText，插入的是占位符而不是数字）。
 */
@Composable
private fun TcParagraph(model: MarkdownComponentModel) {
    // 块级公式 `$$…$$`：GFM 不认它，会把它当普通文字。这里在**组件层**切段渲染，
    // 绝不改写正文 —— 引用角标的偏移是对着原文算的，改一个字就全线错位。
    // 注意 allowParagraphText：模型经常写成「文字\n$$ … $$」而不空行，GFM 会把它整段当一个段落，
    // 所以段落内部也要能切开（只处理整段判断会把这类公式当源码糊在正文里）。
    val raw = model.node.getTextInNode(model.content).toString()
    val blocks = remember(raw) { findBlockMath(raw) }
    if (blocks.isNotEmpty()) {
        Column(modifier = Modifier.padding(vertical = LocalBlockSpacing.current)) {
            var cursor = 0
            blocks.forEach { block ->
                val before = raw.substring(cursor, block.start).trim()
                if (before.isNotEmpty()) ParagraphSlice(before, model)
                TcMath(latex = block.latex)
                cursor = block.end
            }
            val after = raw.substring(cursor).trim()
            if (after.isNotEmpty()) ParagraphSlice(after, model)
        }
        return
    }

    val anchors = LocalCitationAnchors.current
    val mine = anchorsIn(anchors, model.node.startOffset, model.node.endOffset)
    val citeCount = LocalCitationCount.current
    val settings = annotatorSettings()
    val plainBuilt = buildAnnotatedString {
        pushStyle(model.typography.paragraph.toSpanStyle())
        buildMarkdownAnnotatedString(
            content = model.content,
            node = model.node,
            annotatorSettings = settings,
        )
        pop()
    }
    // 第三方搜索：把模型写在正文里的 `[k]` 就地换成角标；原生搜索的锚点仍然贴在段尾
    val numbered = plainBuilt.withNumberedCitations(citeCount)
    val built = if (mine.isEmpty()) {
        numbered
    } else {
        buildAnnotatedString {
            append(numbered)
            mine.forEach { anchor -> appendInlineContent(CitationTagPrefix + anchor.index) }
        }
    }
    // 行内公式：流式中只吞 `$`（宽度稳定），消息结束后升级成 KaTeX 内联位图
    val formulas = remember(built) { findInlineMath(built.text) }
    if (formulas.isNotEmpty()) {
        InlineMathParagraph(
            built = built,
            formulas = formulas,
            streaming = LocalMarkdownStreaming.current,
            style = model.typography.paragraph,
            modifier = Modifier.padding(vertical = LocalBlockSpacing.current),
        )
        return
    }

    MarkdownText(
        content = built,
        node = model.node,
        modifier = Modifier.padding(vertical = LocalBlockSpacing.current),
        style = model.typography.paragraph,
    )
}

/** 块级公式段落里被切开的那截普通文字（不再走 AST，避免和原 node 偏移打架） */
@Composable
private fun ParagraphSlice(text: String, model: MarkdownComponentModel) {
    val payload = remember(text) {
        buildAnnotatedString {
            pushStyle(model.typography.paragraph.toSpanStyle())
            append(text)
            pop()
        }
    }
    Text(
        text = payload,
        style = model.typography.paragraph,
        // 同 InlineMathParagraph：不显式给颜色会回落到 LocalContentColor（暗色下是黑的）
        color = model.typography.paragraph.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
            ?: TcTheme.palette.labelPrimary,
    )
}

@Composable
private fun TcHeading(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType,
    top: Dp,
) {
    MarkdownText(
        content = model.content,
        node = model.node,
        style = style,
        modifier = Modifier
            .semantics { heading() }
            .padding(top = top, bottom = 6.dp),
        contentChildType = contentChildType,
    )
}

@Composable
private fun TcBlockQuote(model: MarkdownComponentModel) {
    val palette = TcTheme.palette
    val base = LocalMarkdownTypography.current
    // 引用里的正文、列表、标题一律走三级灰；代码块保持原样
    val muted = base.paragraph.copy(color = palette.labelTertiary)
    val nested = defaultTypographyWith(
        base = base,
        text = muted,
        heading = muted,
        table = muted,
    )
    CompositionLocalProvider(LocalMarkdownTypography provides nested) {
        MarkdownBlockQuote(model.content, model.node, style = muted)
    }
}

private fun defaultTypographyWith(
    base: com.mikepenz.markdown.model.MarkdownTypography,
    text: TextStyle,
    heading: TextStyle,
    table: TextStyle,
): com.mikepenz.markdown.model.MarkdownTypography = com.mikepenz.markdown.model.DefaultMarkdownTypography(
    h1 = heading, h2 = heading, h3 = heading, h4 = heading, h5 = heading, h6 = heading,
    text = text,
    code = base.code,
    inlineCode = base.inlineCode,
    quote = text,
    paragraph = text,
    ordered = text,
    bullet = text,
    list = text,
    textLink = base.textLink,
    table = table,
    alertTitle = text.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
)

@Composable
private fun TcCheckbox(model: MarkdownComponentModel) {
    val checked = model.node.getTextInNode(model.content).contains("[x]")
    val palette = TcTheme.palette
    val boxColor = if (checked) palette.brand else palette.labelTertiary
    val tickColor = palette.brandOn

    Box(modifier = Modifier.padding(top = 5.dp, end = 8.dp)) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val radius = CornerRadius(4.dp.toPx())
            val strokeWidth = 1.5.dp.toPx()
            if (checked) {
                drawRoundRect(color = boxColor, cornerRadius = radius)
                val tick = Path().apply {
                    moveTo(size.width * 0.26f, size.height * 0.52f)
                    lineTo(size.width * 0.43f, size.height * 0.69f)
                    lineTo(size.width * 0.75f, size.height * 0.33f)
                }
                drawPath(
                    path = tick,
                    color = tickColor,
                    style = Stroke(width = strokeWidth * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            } else {
                drawRoundRect(color = boxColor, cornerRadius = radius, style = Stroke(width = strokeWidth))
            }
        }
    }
}
