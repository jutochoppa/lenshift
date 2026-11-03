package dev.lenshift.app.ui.markdown

import dev.lenshift.app.ui.visual.AnswerVisuals
import dev.lenshift.app.ui.visual.GeneratedImageBlock
import dev.lenshift.app.ui.visual.VisualBlock
import dev.lenshift.app.visual.VisualKind
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent

/**
 * A deliberately small Markdown renderer.
 *
 * Why not a library: the answer surface needs streaming-friendly rendering, selection,
 * and code blocks with horizontal scroll. This renderer handles the subset models emit:
 *
 *   headings, paragraphs, nested bullet/numbered lists, fenced code, blockquotes, tables,
 *   horizontal rules, inline **bold** / *italic* / `code` / [links](…), and LaTeX math —
 *   `$…$`, `\(…\)` inline and `$$…$$`, `\[…\]`, bare `\begin{aligned}` blocks as display.
 *
 * Streaming behaviour: the caller re-invokes this with a growing string. Typeset formulas
 * are cached, and a display block whose closing delimiter has not arrived yet shows its
 * source until it is complete.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    /** False when an enclosing SelectionContainer already covers this text (a chat thread). */
    selectable: Boolean = true,
    /** Generated images for the answer's ```image blocks; null where images do not apply. */
    visuals: AnswerVisuals? = null
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    if (selectable) {
        SelectionContainer(modifier = modifier) { MarkdownBlocks(blocks, visuals) }
    } else {
        Box(modifier) { MarkdownBlocks(blocks, visuals) }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, visuals: AnswerVisuals?) {
    // Image blocks are numbered in order, matching ImageBlocks.find.
    var imageIndex = 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> RichText(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            else -> 18.sp
                        }
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )

                is MdBlock.Paragraph -> RichText(block.text, readingBodyStyle())

                is MdBlock.Bullet -> ListItem(
                    marker = if (block.level == 0) "•" else "◦",
                    level = block.level,
                    text = block.text
                )

                is MdBlock.Numbered -> ListItem(
                    marker = "${block.number}.",
                    level = block.level,
                    text = block.text
                )

                is MdBlock.Math -> DisplayMath(block.tex, block.complete)

                is MdBlock.Table -> MarkdownTable(block)

                is MdBlock.Code -> when (VisualKind.of(block.language)) {
                    VisualKind.GRAPH -> VisualBlock(block.code, threeD = false, complete = block.complete)
                    VisualKind.GRAPH3D -> VisualBlock(block.code, threeD = true, complete = block.complete)
                    VisualKind.IMAGE -> GeneratedImageBlock(block.code, block.complete, imageIndex++, visuals)
                    null -> CodeBlock(block.code, block.language)
                }

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                    RichText(
                        text = block.text,
                        style = readingBodyStyle().copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                MdBlock.Rule -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun ListItem(marker: String, level: Int, text: String) {
    val style = readingBodyStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (level * 22).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            marker,
            style = style,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 14.dp)
        )
        RichText(text, style)
    }
}

/**
 * Text with inline Markdown and typeset inline math. Formulas become inline content sized
 * to their bitmaps; their baseline is aligned with the surrounding text's baseline.
 */
@Composable
private fun RichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val linkColor = MaterialTheme.colorScheme.primary
    val fontSize = style.fontSize.orDefault(16.sp)
    val fontSizePx = with(density) { fontSize.toPx() }

    val (annotated, inlineContent) = remember(text, fontSizePx, textColor, linkColor, density) {
        val content = mutableMapOf<String, InlineTextContent>()
        val built = InlineMarkdown.build(text, linkColor) { span ->
            val size = fontSizePx * if (span.display) 1.2f else 1.08f
            val rendered = MathRenderer.render(
                context, span.tex, size, textColor.toArgb(), span.display,
                textCenterAboveBaselinePx = fontSizePx * 0.34f
            ) ?: return@build null
            val id = InlineMarkdown.MATH_PREFIX + content.size
            content[id] = InlineTextContent(
                Placeholder(
                    width = with(density) { rendered.widthPx.toSp() },
                    height = with(density) { rendered.heightPx.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Image(rendered.image, contentDescription = span.tex, modifier = Modifier.fillMaxSize())
            }
            id
        }
        built to content
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style,
        color = textColor,
        inlineContent = inlineContent
    )
}

/** A centred display formula; scrolls sideways when wider than the column. */
@Composable
private fun DisplayMath(tex: String, complete: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val style = readingBodyStyle()
    val color = style.color.takeOrElse { LocalContentColor.current }
    val sizePx = with(density) { style.fontSize.orDefault(16.sp).toPx() } * 1.22f

    val rendered = remember(tex, complete, sizePx, color) {
        if (complete) MathRenderer.render(context, tex, sizePx, color.toArgb(), true, null) else null
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minWidth = maxWidth
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .widthIn(min = minWidth)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (rendered != null) {
                    val annotated = remember(tex) {
                        buildAnnotatedString { appendInlineContent(DISPLAY_MATH_ID, "$$$tex$$") }
                    }
                    val content = remember(rendered, density) {
                        mapOf(
                            DISPLAY_MATH_ID to InlineTextContent(
                                Placeholder(
                                    width = with(density) { rendered.widthPx.toSp() },
                                    height = with(density) { rendered.heightPx.toSp() },
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                )
                            ) {
                                Image(rendered.image, contentDescription = tex, modifier = Modifier.fillMaxSize())
                            }
                        )
                    }
                    Text(annotated, inlineContent = content)
                } else {
                    // Still streaming, or not typesettable: show the source quietly.
                    Text(
                        text = tex,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val border = MaterialTheme.colorScheme.outlineVariant
    val body = readingBodyStyle().copy(fontSize = 15.sp, lineHeight = 22.sp)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fits = maxWidth / columns >= 110.dp
        val scroll = if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())
        Column(
            modifier = Modifier
                .then(scroll)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, border, RoundedCornerShape(14.dp))
        ) {
            val all = listOf(table.header) + table.rows
            all.forEachIndexed { r, row ->
                Row(
                    modifier = Modifier
                        .then(if (fits) Modifier.fillMaxWidth() else Modifier)
                        .height(IntrinsicSize.Min)
                        .background(
                            if (r == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                        )
                ) {
                    for (c in 0 until columns) {
                        val cell = Modifier
                            .then(if (fits) Modifier.weight(1f) else Modifier.width(150.dp))
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                        RichText(
                            text = row.getOrNull(c).orEmpty(),
                            style = if (r == 0) body.copy(fontWeight = FontWeight.SemiBold) else body,
                            modifier = cell
                        )
                    }
                }
                if (r < all.lastIndex) {
                    Box(
                        Modifier
                            .then(if (fits) Modifier.fillMaxWidth() else Modifier.width((150 * columns).dp))
                            .height(1.dp)
                            .background(border)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}

private const val DISPLAY_MATH_ID = "display-math"

private fun TextUnit.orDefault(fallback: TextUnit): TextUnit = if (this == TextUnit.Unspecified) fallback else this

// ======================================================================= parser

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String, val level: Int = 0) : MdBlock
    data class Numbered(val number: Int, val text: String, val level: Int = 0) : MdBlock
    /** A fenced block; [complete] is false while its closing fence is still streaming. */
    data class Code(val code: String, val language: String?, val complete: Boolean = true) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** A display formula; [complete] is false while its closing delimiter is still streaming. */
    data class Math(val tex: String, val complete: Boolean = true) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/**
 * Line-oriented Markdown block parser. Unclosed code fences and display formulas (common
 * mid-stream) run to the end of the text so the UI never flickers between block types.
 */
object MarkdownParser {

    private val mathEnvironments = setOf(
        "equation", "equation*", "align", "align*", "aligned", "alignat", "alignat*",
        "gather", "gather*", "gathered", "multline", "multline*", "split", "cases",
        "eqnarray", "matrix", "pmatrix", "bmatrix", "vmatrix", "Vmatrix", "array"
    )
    private val tableSeparator = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val bulletRegex = Regex("""^\s*[-*+]\s+""")
    private val numberedRegex = Regex("""^\s*\d+[.)]\s+""")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.lines()

        var i = 0
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.toString().trim())
                paragraph.setLength(0)
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trim()

            when {
                line.trimStart().startsWith("```") -> {
                    flushParagraph()
                    val language = trimmed.removePrefix("```").trim().ifBlank { null }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        code.appendLine(lines[i])
                        i++
                    }
                    val closed = i < lines.size
                    if (closed) i++
                    blocks += MdBlock.Code(code.toString().trimEnd(), language, closed)
                    continue
                }

                trimmed.startsWith("$$") || trimmed.startsWith("\\[") -> {
                    flushParagraph()
                    val close = if (trimmed.startsWith("$$")) "$$" else "\\]"
                    val afterOpen = trimmed.substring(2)
                    val sameLine = afterOpen.indexOf(close)
                    if (sameLine >= 0) {
                        val tex = afterOpen.substring(0, sameLine).trim()
                        if (tex.isNotEmpty()) blocks += MdBlock.Math(tex)
                        val rest = afterOpen.substring(sameLine + 2).trim()
                        if (rest.isNotEmpty()) paragraph.append(rest)
                        i++
                        continue
                    }
                    val tex = StringBuilder(afterOpen)
                    var rest = ""
                    var closed = false
                    i++
                    while (i < lines.size) {
                        val idx = lines[i].indexOf(close)
                        if (idx >= 0) {
                            tex.append('\n').append(lines[i].substring(0, idx))
                            rest = lines[i].substring(idx + 2).trim()
                            closed = true
                            i++
                            break
                        }
                        tex.append('\n').append(lines[i])
                        i++
                    }
                    if (tex.isNotBlank()) blocks += MdBlock.Math(tex.toString().trim(), closed)
                    if (rest.isNotEmpty()) paragraph.append(rest)
                    continue
                }

                trimmed.startsWith("\\begin{") && environmentOf(trimmed) in mathEnvironments -> {
                    flushParagraph()
                    val end = "\\end{${environmentOf(trimmed)}}"
                    val tex = StringBuilder()
                    var closed = false
                    while (i < lines.size) {
                        tex.append(lines[i]).append('\n')
                        val done = lines[i].contains(end)
                        i++
                        if (done) {
                            closed = true
                            break
                        }
                    }
                    blocks += MdBlock.Math(tex.toString().trim(), closed)
                    continue
                }

                trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1].trim()) -> {
                    flushParagraph()
                    val header = cells(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows += cells(lines[i].trim())
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                    continue
                }

                line.isBlank() -> flushParagraph()

                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                }

                trimmed.matches(Regex("^([-*_])(\\s*\\1){2,}$")) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }

                trimmed.startsWith("> ") || trimmed == ">" -> {
                    flushParagraph()
                    val quote = StringBuilder(trimmed.removePrefix(">").trim())
                    i++
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote.append(' ').append(lines[i].trimStart().removePrefix(">").trim())
                        i++
                    }
                    blocks += MdBlock.Quote(quote.toString().trim())
                    continue
                }

                bulletRegex.containsMatchIn(line) && bulletRegex.find(line)?.range?.first == 0 -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(line.replaceFirst(bulletRegex, ""), indentLevel(line))
                }

                numberedRegex.find(line)?.range?.first == 0 -> {
                    flushParagraph()
                    val number = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                    blocks += MdBlock.Numbered(number, line.replaceFirst(numberedRegex, ""), indentLevel(line))
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            i++
        }

        flushParagraph()
        return blocks
    }

    private fun environmentOf(line: String): String =
        line.substringAfter("\\begin{").substringBefore('}')

    private fun indentLevel(line: String): Int {
        val indent = line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 as Int }
        return (indent / 2).coerceAtMost(3)
    }

    /** Splits a table row on `|`, ignoring pipes inside code spans, math, or escaped. */
    internal fun cells(row: String): List<String> {
        val body = row.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var inCode = false
        var inMath = false
        var k = 0
        while (k < body.length) {
            val ch = body[k]
            when {
                ch == '\\' && body.getOrNull(k + 1) == '|' -> { cell.append('|'); k += 2; continue }
                ch == '`' -> inCode = !inCode
                ch == '$' && !inCode && body.getOrNull(k - 1) != '\\' -> inMath = !inMath
                ch == '|' && !inCode && !inMath -> {
                    out += cell.toString().trim()
                    cell.setLength(0)
                    k++
                    continue
                }
            }
            cell.append(ch)
            k++
        }
        out += cell.toString().trim()
        return out
    }
}

/** Body style with at least a 21sp line height; a theme that asks for more keeps it. */
@Composable
private fun readingBodyStyle(): TextStyle {
    val body = MaterialTheme.typography.bodyMedium
    val lh = body.lineHeight
    return if (lh.isSp && lh.value > 21f) body else body.copy(lineHeight = 21.sp)
}
