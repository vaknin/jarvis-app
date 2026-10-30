package com.kivan.jarvis

/**
 * How a line of a call is drawn, worked out when it's drawn: the saved line stays as it was said, old
 * calls get this too, and nothing goes to a model. Jarvis speaks in short sentences with no marks, so
 * the shape comes from them: the first sentence that says something is bold when there are several,
 * they group into paragraphs, and what's said as a list is drawn as one. It adds only layout and
 * weight: every word stays, in order ([plain] gives them back).
 *
 * A sentence's place depends only on it and the ones before, so a line growing a sentence at a time
 * keeps what's on the screen, with two exceptions: the first sentence turns bold when the third
 * arrives, and "First" indents when "Second" arrives.
 */
object CallText {
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

    sealed interface Block { val spans: List<Span> }

    data class Para(override val spans: List<Span>) : Block

    /** [mark] goes in the margin; [said] means it's the line's own ("1."), not one added here. */
    data class Item(val mark: String, val said: Boolean, override val spans: List<Span>) : Block

    /** [mine]: Aviv's line, which gets paragraph gaps at most. */
    fun blocks(line: String, mine: Boolean = false): List<Block> =
        if (!mine && MARKED.containsMatchIn(line)) marked(line) else spoken(line, mine)

    /** The words of [blocks], in order. */
    fun plain(blocks: List<Block>): String = blocks.joinToString(" ") { b ->
        val mark = if (b is Item && b.said) b.mark + " " else ""
        mark + b.spans.joinToString("") { it.text }
    }

    /** A line's sentences, each with its punctuation. Dots in "2.4" or "e.g. this" don't end one. */
    fun sentences(line: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            if (line[i] !in ENDS) { i++; continue }
            var end = i + 1
            while (end < line.length && (line[end] in ENDS || line[end] in CLOSERS)) end++
            val next = line.getOrNull(end + 1)
            val ends = when {
                end == line.length -> true
                !line[end].isWhitespace() || next == null || next.isLowerCase() -> false
                line[i] == '.' -> !isMarker(line, start, i) && line.substring(start, i).substringAfterLast(' ') !in TITLES
                else -> true
            }
            if (ends) {
                out += line.substring(start, end).trim()
                start = end
            }
            i = end
        }
        if (line.substring(start).isNotBlank()) out += line.substring(start).trim()
        return out
    }

    /** The dot at [dot] closes a list's "2.": the number opens the sentence or follows a colon. */
    private fun isMarker(line: String, start: Int, dot: Int): Boolean {
        var d = dot
        while (d > start && line[d - 1].isDigit()) d--
        if (d == dot) return false
        val before = line.substring(start, d).trimEnd()
        return before.isEmpty() || before.endsWith(':')
    }

    private fun spoken(line: String, mine: Boolean): List<Block> {
        val all = sentences(line)
        val out = mutableListOf<Block>()
        val point = if (mine || all.size < MANY) null else all.take(MANY).firstOrNull { !filler(it) }?.takeIf { !it.endsWith('?') }
        val said = if (mine) emptyMap() else ordered(all)
        var open = mutableListOf<Span>()
        var item: Item? = null
        var numbered = 0

        fun close() {
            if (open.isEmpty()) return
            val spans = join(open)
            out += item?.copy(spans = spans) ?: Para(spans)
            open = mutableListOf()
            item = null
        }

        fun span(s: String) = Span(s, bold = s === point && s.length <= BOLD_MAX)

        all.forEachIndexed { i, s ->
            val list = if (mine) null else commaList(s)
            val first = if (mine) null else NUMBERED.find(s)
            val listed = first?.groupValues?.get(2)?.toInt() == numbered + 1
            val inline = if (mine || numbered != 0) null else INLINE_ONE.find(s)
            when {
                i in said -> {
                    close()
                    item = Item("${said[i]}.", said = false, emptyList())
                    open += Span(s)
                }
                list != null -> {
                    close()
                    open += span(list.first).copy(bold = s === point && list.first.length <= BOLD_MAX)
                    close()
                    list.second.forEach { out += Item(BULLET, said = false, listOf(Span(it))) }
                }
                inline != null -> {
                    close()
                    open += Span(s.take(inline.range.first + 1), bold = s === point)
                    close()
                    numbered = 1
                    item = Item("1.", said = true, emptyList())
                    open += Span(s.drop(inline.range.last + 1))
                    close()
                }
                first != null && listed -> {
                    close()
                    numbered++
                    item = Item(first.groupValues[1], said = true, emptyList())
                    open += Span(s.drop(first.value.length))
                    close()
                }
                item != null && !turns(s) && !s.endsWith('?') && open.sumOf { it.text.length } < PARA_CHARS -> open += Span(s)
                else -> {
                    if (item != null || i >= MANY - 1 && breaks(s, open)) close()
                    open += span(s)
                }
            }
        }
        close()
        return out
    }

    private fun turns(s: String) = s.substringBefore(' ').trimEnd(',', ':', '.') in TURNS

    /** Whether [s] opens a new paragraph after [open]. Never in the first two sentences. */
    private fun breaks(s: String, open: List<Span>): Boolean {
        if (open.isEmpty()) return false
        val last = open.last().text
        return turns(s) || (s.endsWith('?') && !last.endsWith('?')) || open.size >= MANY || open.sumOf { it.text.length } >= PARA_CHARS
    }

    private fun filler(s: String): Boolean {
        val words = s.split(' ').size
        return STALL.containsMatchIn(s) || (words <= 3 && ACK.containsMatchIn(s)) || (words <= 5 && ACK_I.containsMatchIn(s))
    }

    /** Sentence index to number, for a run said in order ("First, ... Second, ..."): at least two. */
    private fun ordered(all: List<String>): Map<Int, Int> {
        for (words in ORDINALS) {
            val run = mutableMapOf<Int, Int>()
            var from = 0
            for ((n, word) in words.withIndex()) {
                val at = (from until all.size).firstOrNull { said(all[it], word, words === ORDINALS[0]) } ?: break
                run[at] = n + 1
                from = at + 1
            }
            if (run.size >= 2) return run
        }
        return emptyMap()
    }

    /** [s] opens with [word]: "First, ", "One: ", and with [named] "First one: ". */
    private fun said(s: String, word: String, named: Boolean): Boolean {
        val head = s.substringBefore(' ')
        if (head == "$word," || head == "$word:") return true
        return named && head == word && s.split(' ').getOrNull(1)?.let { it.endsWith(':') || it.endsWith(',') } == true
    }

    /**
     * "Three things: a, b, and c." as its opening and the items, each keeping its comma and "and".
     * The opening names them, by a count that fits or a plural, and the items are things, not clauses:
     * most commas after a colon in speech are just a sentence going on.
     */
    private fun commaList(s: String): Pair<String, List<String>>? {
        val colon = s.indexOf(": ")
        if (colon < 0 || s.endsWith('?')) return null
        val intro = s.take(colon + 1)
        val parts = commas(s.drop(colon + 2).trim())
        if (parts.size < 2 || parts.any { it.split(' ').size > ITEM_WORDS || CLAUSE.containsMatchIn(it) }) return null
        if (!parts.last().startsWith("and ") && !parts.last().startsWith("or ")) return null
        val counted = intro.lowercase().split(' ', ',').any { COUNTS[it] == parts.size }
        val plural = parts.size >= 3 && intro.dropLast(1).endsWith('s')
        return if (counted || plural) intro to parts else null
    }

    /** [s] cut after its commas, outside quotes. */
    private fun commas(s: String): List<String> {
        val out = mutableListOf<String>()
        var quoted = false
        var start = 0
        s.forEachIndexed { i, c ->
            if (c == '"' || c == '“' || c == '”') quoted = !quoted
            if (c == ',' && !quoted && s.getOrNull(i + 1) == ' ') {
                out += s.substring(start, i + 1)
                start = i + 2
            }
        }
        return out + s.substring(start)
    }

    private fun join(parts: List<Span>): List<Span> = parts.fold(emptyList()) { spans, p ->
        val last = spans.lastOrNull()
        when {
            last == null -> listOf(p)
            last.bold == p.bold -> spans.dropLast(1) + last.copy(text = last.text + " " + p.text)
            else -> spans + p.copy(text = " " + p.text)
        }
    }

    private fun marked(line: String): List<Block> = line.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
        val heading = HEADING.find(l)
        val number = LIST_NUMBER.find(l)
        when {
            heading != null -> Para(inline(l.drop(heading.value.length), bold = true))
            BULLETED.containsMatchIn(l) -> Item(BULLET, said = false, inline(l.drop(2).trim()))
            number != null -> Item(number.groupValues[1], said = true, inline(l.drop(number.value.length)))
            else -> Para(inline(l))
        }
    }

    /** [l] with its Markdown marks read and dropped; a mark with no partner stays as it is. */
    private fun inline(l: String, bold: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        var at = 0
        for (m in INLINE.findAll(l)) {
            if (m.range.first > at) out += Span(l.substring(at, m.range.first), bold)
            val (strong, em, code) = m.destructured
            out += when {
                strong.isNotEmpty() -> Span(strong, bold = true)
                em.isNotEmpty() -> Span(em, bold, italic = true)
                else -> Span(code, bold)
            }
            at = m.range.last + 1
        }
        if (at < l.length) out += Span(l.substring(at), bold)
        return out
    }

    private const val BULLET = "•"
    private const val ENDS = ".?!…"
    private const val CLOSERS = "\"”’')]"
    /** A line of fewer sentences stays as it was said. */
    private const val MANY = 3
    /** About four lines on the phone: the next sentence starts a new paragraph. */
    private const val PARA_CHARS = 170
    /** A sentence longer than this is no headline. */
    private const val BOLD_MAX = 150
    /** A comma's part longer than this is a clause, not an item. */
    private const val ITEM_WORDS = 14

    private val TITLES = setOf("vs", "Dr", "Mr", "Mrs", "Ms", "St")
    private val TURNS = setOf("Also", "Then", "Next", "But", "However", "Meanwhile", "Still", "Otherwise", "Instead", "Anyway", "Here's", "Now")
    private val ORDINALS = listOf(
        listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth"),
        listOf("One", "Two", "Three", "Four", "Five", "Six"),
    )
    private val COUNTS = mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6)
    private val ACK = Regex("^(Agreed|Understood|Clear|Partly|Yes|Yeah|Yep|No|Nope|Right|Okay|OK|Sure|Fair|Good|Great|Got it|Thanks|Morning|Good morning|Hey|Hi|I agree|That works|On it|Long one)\\b")
    private val ACK_I = Regex("^(Yes|Yeah|Yep|Right|Okay|Sure|Got it),? I\\b")
    private val STALL = Regex("^(\\w+, )?(let me|checking|reading|pulling|getting|looking|filing|adding|passing|sending|starting|one sec|give me a)\\b", RegexOption.IGNORE_CASE)
    private val CLAUSE = Regex("^(and |or )?(I|you|we|it|they|when|if|do|does|should|can|then|none)\\b", RegexOption.IGNORE_CASE)
    private val NUMBERED = Regex("^((\\d+)\\.)\\s+")
    private val INLINE_ONE = Regex(":\\s+1\\.\\s+")
    private val MARKED = Regex("\\*\\*\\S|\\n|`[^`]+`|(?<![\\w*])\\*[^\\s*][^*]*\\*|^\\s*([-*•]|#{1,6})\\s", RegexOption.MULTILINE)
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLETED = Regex("^[-*•]\\s")
    private val LIST_NUMBER = Regex("^(\\d+[.)])\\s+")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|(?<![\\w*])\\*([^\\s*][^*]*)\\*|`([^`]+)`")
}
