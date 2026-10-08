package com.kivan.tether.dibs

import kotlin.math.max

/** A chat line cut into plain text and ``` code blocks. The desktop app (dibs `desktop/src/lib/chatText.ts`) follows the same rule. */
sealed interface TextPart {
    val text: String
}

data class Plain(override val text: String) : TextPart

data class Code(override val text: String) : TextPart

private const val FENCE = "```"

/**
 * Cuts [text] at its fences. A line whose first non-blank characters are ``` opens a block (the rest of
 * that line, a language word, is dropped) or closes the open one; a block never closed runs to the end.
 * ``` in the middle of a line is plain text. Code keeps its lines exactly; plain text loses the line
 * breaks next to a block. Empty or blank parts are dropped.
 */
fun textParts(text: String): List<TextPart> {
    if (!text.contains(FENCE)) return listOf(Plain(text))
    // Segments in order: whether each is code, and its lines.
    val segments = ArrayList<Pair<Boolean, MutableList<String>>>()
    var cur: Pair<Boolean, MutableList<String>>? = null
    for (line in text.replace("\r\n", "\n").split("\n")) {
        if (line.trimStart().startsWith(FENCE)) {
            if (cur?.first == true) {
                cur = null
            } else {
                cur = true to mutableListOf()
                segments += cur
            }
            continue
        }
        if (cur == null) {
            cur = false to mutableListOf()
            segments += cur
        }
        cur.second += line
    }
    val parts = ArrayList<TextPart>()
    for ((i, seg) in segments.withIndex()) {
        var body = seg.second.joinToString("\n")
        if (seg.first) {
            if (body.isNotBlank()) parts += Code(body)
        } else {
            // A fence on a side takes the line break with it; the parts are drawn apart.
            if (i > 0) body = body.trimStart('\n')
            if (i < segments.size - 1) body = body.trimEnd('\n')
            if (body.isNotBlank()) parts += Plain(body)
        }
    }
    return parts
}

/** A line's parts cut to fit [FOLD_ROWS] rows; [cut] marks that something is left out. */
data class Folded(val parts: List<TextPart>, val cut: Boolean)

const val FOLD_ROWS = 14
const val ROW_CHARS = 34

private fun rowsOf(line: String) = max(1, (line.length + ROW_CHARS - 1) / ROW_CHARS)

private fun rowsOf(part: TextPart): Int = when (part) {
    is Code -> part.text.split("\n").size
    is Plain -> part.text.split("\n").sumOf { rowsOf(it) }
}

/** The parts that fit [rows] (a code line is one row, a plain line one row per [ROW_CHARS] chars, at least one); cut marks a fold. */
fun foldParts(parts: List<TextPart>, rows: Int = FOLD_ROWS): Folded {
    if (parts.sumOf { rowsOf(it) } <= rows) return Folded(parts, false)
    val out = ArrayList<TextPart>()
    var remaining = rows
    for (p in parts) {
        val need = rowsOf(p)
        if (need <= remaining) {
            out += p
            remaining -= need
            continue
        }
        if (remaining > 0) {
            val cut: TextPart? = when (p) {
                is Code -> Code(p.text.split("\n").take(remaining).joinToString("\n"))
                is Plain -> {
                    val kept = ArrayList<String>()
                    var left = remaining
                    for (line in p.text.split("\n")) {
                        val r = rowsOf(line)
                        if (r > left) break
                        kept += line
                        left -= r
                    }
                    if (kept.isEmpty()) {
                        Plain(p.text.split("\n").first().take(remaining * ROW_CHARS))
                    } else {
                        Plain(kept.joinToString("\n"))
                    }
                }
            }
            if (cut != null && cut.text.isNotEmpty()) out += cut
        }
        break
    }
    return Folded(out, true)
}
