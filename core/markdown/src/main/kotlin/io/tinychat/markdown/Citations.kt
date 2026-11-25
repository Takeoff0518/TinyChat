package io.tinychat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownInlineContent
import io.tinychat.designsystem.TcTheme

/**
 * 正文里的一个引用锚点：在全部正文的第 [offset] 个字符处插一个上标角标，编号 [index]（1 基）。
 *
 * 为什么用「偏移量」而不是在正文里塞 `[citation:N]` 标记：
 * 服务端的引用走 `citations_delta`，**不写进正文**；但它必然紧跟在被引用的那段文字之后，
 * 且与该段文字属于同一个 RESPONSE 碎片，所以拼正文时按碎片末尾记下偏移就是精确位置，
 * 既不用改协议、也不用给导出格式加字段。
 *
 * 落位规则：**贴在所属段落的末尾**（角标表示「这一段的出处」）。真实数据里锚点几乎都落在
 * 段落边界上，按文本节点插会让它们整批丢失（实测过）。
 */
data class CitationAnchor(val offset: Int, val index: Int)

internal const val CitationTagPrefix = "tc_citation_"

/** 引用角标外形：三圆一直角（官方 `border-radius: 6px 6px 6px 0`），正文与来源页共用 */
val CitationBubbleShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomEndPercent = 50,
    bottomStartPercent = 0,
)

/** 当前正文的引用锚点，供段落组件取用 */
internal val LocalCitationAnchors = staticCompositionLocalOf<List<CitationAnchor>> { emptyList() }

/** 这一条消息可引用的搜索结果数量：第三方搜索让模型在正文里写 `[k]`，这里给上限 */
internal val LocalCitationCount = staticCompositionLocalOf { 0 }

/**
 * 把正文里合法的 `[k]` 就地换成角标。
 *
 * 换的是 `AnnotatedString` 里的字符片段，样式与后续 append 的锚点保持同一份，
 * 所以不会影响原生引用那套"段尾追加"的行为。
 */
internal fun AnnotatedString.withNumberedCitations(count: Int): AnnotatedString {
    val pieces = splitNumberedCitations(text, count)
    if (pieces.isEmpty()) return this
    val source = this
    return buildAnnotatedString {
        pieces.forEach { piece ->
            when (piece) {
                is CitePiece.Plain -> append(source.subSequence(piece.start, piece.end))
                is CitePiece.Badge -> appendInlineContent(CitationNumberPrefix + piece.index)
            }
        }
    }
}

/** 属于 [start, end) 这个段落的锚点（纯函数，便于单测） */
internal fun anchorsIn(anchors: List<CitationAnchor>, start: Int, end: Int): List<CitationAnchor> =
    if (anchors.isEmpty()) {
        emptyList()
    } else {
        anchors.filter { it.offset > start && it.offset <= end }
    }

/** 角标本体：10sp 数字、圆角、`InlineCodeBg` 底色，上标对齐 */
@Composable
internal fun citationInlineContent(
    anchors: List<CitationAnchor>,
    numberedCount: Int,
    onClick: ((Int) -> Unit)?,
): MarkdownInlineContent {
    if (anchors.isEmpty() && numberedCount <= 0) return markdownInlineContent()
    val palette = TcTheme.palette
    fun tag(index: Int) = InlineTextContent(
        Placeholder(
            width = 15.sp,
            height = 15.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        ),
    ) {
        Box(
            modifier = Modifier
                // 撑满占位符，否则底色只有数字那么大，看着像裸数字
                .fillMaxSize()
                // 官方样式是 `border-radius: 6px 6px 6px 0`：三个角圆、左下角是直角，
                // 看起来像一个左下角带尖的气泡
                .clip(CitationBubbleShape)
                .background(palette.inlineCodeBg)
                .then(if (onClick != null) Modifier.clickable { onClick(index) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                // 关掉字体内边距，数字才会在气泡里真正居中（默认会整体偏下）
                style = TextStyle(
                    fontSize = 9.5.sp,
                    lineHeight = 9.5.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                fontWeight = FontWeight.Medium,
                color = palette.labelSecondary,
            )
        }
    }
    val content = buildMap {
        anchors.forEach { put(CitationTagPrefix + it.index, tag(it.index)) }
        // 第三方搜索的结果：模型在正文里写 `[k]`，这里按编号注册角标
        if (numberedCount > 0) {
            for (index in 1..numberedCount) put(CitationNumberPrefix + index, tag(index))
        }
    }
    return markdownInlineContent(content)
}

/**
 * 模型在正文里写的 `[k]`（第三方搜索的引用）→ 角标。
 *
 * 只在**文本节点**上做：代码块、行内代码、公式都走别的组件，天然不会被误伤。
 * 编号必须在 `1..count` 之内，否则原样保留（宁可显示 `[9]`，也不要吞掉让句子缺字）。
 */
internal const val CitationNumberPrefix = "tc_citenum_"

private val NumberedCitation = Regex("""\[(\d{1,3})\]""")

internal sealed interface CitePiece {
    data class Plain(val start: Int, val end: Int) : CitePiece
    data class Badge(val index: Int) : CitePiece
}

/** 纯函数，便于单测：把文本切成「原样片段 / 角标」；没有可识别的编号时返回空 */
internal fun splitNumberedCitations(text: String, count: Int): List<CitePiece> {
    if (count <= 0 || '[' !in text) return emptyList()
    val pieces = mutableListOf<CitePiece>()
    var cursor = 0
    for (match in NumberedCitation.findAll(text)) {
        val index = match.groupValues[1].toIntOrNull() ?: continue
        if (index !in 1..count) continue
        if (match.range.first > cursor) pieces += CitePiece.Plain(cursor, match.range.first)
        pieces += CitePiece.Badge(index)
        cursor = match.range.last + 1
    }
    if (pieces.isEmpty()) return emptyList()
    if (cursor < text.length) pieces += CitePiece.Plain(cursor, text.length)
    return pieces
}
