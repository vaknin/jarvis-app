package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Jarvis's words as blocks: a paragraph, a bullet or a block of code is one thing to read and one
 * thing to answer, which is what the reply page walks. [plain] is the block's words as they stand in
 * the answer, since that's what a quote carries.
 */
@Immutable
sealed interface Block {
    val plain: String

    data class Para(override val plain: String, val spans: List<Span>) : Block
    data class Head(override val plain: String, val spans: List<Span>) : Block
    data class Item(override val plain: String, val mark: String, val spans: List<Span>, val depth: Int) : Block
    data class Lifted(override val plain: String, val spans: List<Span>) : Block
    data class Code(override val plain: String) : Block
}

/** The words as they read on screen, which is what a selection gives: no stars, no backticks. */
val Block.shown: String get() = when (this) {
    is Block.Para -> spans.joinToString("") { it.text }
    is Block.Head -> spans.joinToString("") { it.text }
    is Block.Item -> spans.joinToString("") { it.text }
    is Block.Lifted -> spans.joinToString("") { it.text }
    is Block.Code -> plain
}

enum class Kind { Plain, Bold, Italic, Mono, Link }

@Immutable
data class Span(val text: String, val kind: Kind = Kind.Plain, val url: String = "")

object Prose {
    private val BULLET = Regex("""^(\s*)[-*•]\s+(.*)$""")
    private val NUMBER = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")
    private val HEAD = Regex("""^#{1,4}\s+(.*)$""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((https?://[^)\s]+)\)|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])|(https?://[^\s)]+)""")

    fun parse(text: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) para.toString().trim().let { out += Block.Para(it, spans(it)) }
            para.clear()
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += Block.Code(code.toString())
                }
                t.isEmpty() -> flush()
                HEAD.matches(t) -> {
                    flush()
                    val w = HEAD.find(t)!!.groupValues[1]
                    out += Block.Head(w, spans(w))
                }
                t.startsWith("> ") -> {
                    flush()
                    val w = t.removePrefix("> ")
                    out += Block.Lifted(w, spans(w))
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!.groupValues
                    out += Block.Item(m[2], "•", spans(m[2]), m[1].length / 2)
                }
                NUMBER.matches(line) -> {
                    flush()
                    val m = NUMBER.find(line)!!.groupValues
                    out += Block.Item(m[3], m[2], spans(m[3]), m[1].length / 2)
                }
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(t)
                }
            }
            i++
        }
        flush()
        return out
    }

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        var at = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > at) out += Span(text.substring(at, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Span(g[1], Kind.Bold)
                g[2].isNotEmpty() -> Span(g[2], Kind.Mono)
                g[3].isNotEmpty() -> Span(g[3], Kind.Link, g[4])
                g[5].isNotEmpty() -> Span(g[5], Kind.Italic)
                else -> Span(g[6], Kind.Link, g[6])
            }
            at = m.range.last + 1
        }
        if (at < text.length) out += Span(text.substring(at))
        return out
    }
}

/** Bold is ink at any tone of text; a link is underlined, since blue here means new. */
@Composable
fun annotated(spans: List<Span>, ink: Color = look.ink): AnnotatedString {
    val mono = Words.code.fontFamily ?: FontFamily.Monospace
    return buildAnnotatedString {
        for (s in spans) when (s.kind) {
            Kind.Plain -> append(s.text)
            Kind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.W600, color = ink)) { append(s.text) }
            Kind.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
            Kind.Mono -> withStyle(SpanStyle(fontFamily = mono, fontSize = 0.92.em)) { append(s.text) }
            Kind.Link -> withLink(
                LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))),
            ) { append(s.text) }
        }
    }
}

/** Words he quoted light up where they stand, with soft ends; a span's own background has square ones. */
@Composable
private fun Marked(spans: List<Span>, mark: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val at = if (mark.isEmpty()) -1 else spans.joinToString("") { it.text }.indexOf(mark)
    var laid by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lit = look.line
    val soft = with(LocalDensity.current) { 6.dp.toPx() }
    Text(
        annotated(spans),
        if (at < 0) modifier else modifier.drawBehind {
            val text = laid ?: return@drawBehind
            val paint = Paint().apply {
                this.color = lit
                pathEffect = PathEffect.cornerPathEffect(soft)
            }
            val end = at + mark.length
            drawIntoCanvas { canvas ->
                // Line by line, up to the last visible letter: a wrap's trailing space would draw its own blob.
                for (line in text.getLineForOffset(at)..text.getLineForOffset(end - 1)) {
                    val from = maxOf(at, text.getLineStart(line))
                    val to = minOf(end, text.getLineEnd(line, visibleEnd = true))
                    if (from < to) canvas.drawPath(text.getPathForRange(from, to), paint)
                }
            }
        },
        color,
        style = style,
        onTextLayout = { if (at >= 0) laid = it },
    )
}

@Composable
fun BlockView(block: Block, color: Color = look.ink, modifier: Modifier = Modifier, mark: String = "") {
    val style = Words.prose
    when (block) {
        is Block.Para -> Marked(block.spans, mark, color, style, modifier)
        is Block.Head -> Marked(block.spans, mark, color, Words.proseHead, modifier.padding(top = 6.dp))
        is Block.Lifted -> Row(modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(look.line, RoundedCornerShape(1.dp)))
            Marked(block.spans, mark, look.soft, style, Modifier.padding(start = 12.dp))
        }
        is Block.Item -> Row(modifier.padding(start = (block.depth * 18).dp)) {
            Text(block.mark, Modifier.width(if (block.mark.length > 1) 26.dp else 18.dp), look.faint, style = style)
            Marked(block.spans, mark, color, style)
        }
        is Block.Code -> CodeView(block.plain, modifier)
    }
}

/** A long line of code scrolls sideways on its own; the prose around it never does. */
@Composable
private fun CodeView(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .background(look.surface, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(code, color = look.soft, style = Words.code, softWrap = false)
    }
}

@Composable
fun ProseView(blocks: List<Block>, modifier: Modifier = Modifier, color: Color = look.ink) {
    Column(modifier) {
        var last: Block? = null
        for (b in blocks) {
            // A list's items sit closer together than paragraphs do.
            val above = when {
                last == null -> 0.dp
                last is Block.Item && b is Block.Item -> 6.dp
                else -> Space.para
            }
            BlockView(b, color, Modifier.padding(top = above))
            last = b
        }
    }
}
