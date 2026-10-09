package com.kivan.tether.dibs

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Words and numbers the screens show, kept plain so they're unit-tested.

/** "46 min", "1 h 52 min", "3 h". */
fun duration(minutes: Long): String {
    val m = maxOf(0, minutes)
    if (m < 60) return "$m min"
    val h = m / 60
    val rest = m % 60
    return if (rest == 0L) "$h h" else "$h h $rest min"
}

/** How long ago, short: "now", "12 min", "1 h", "3 d". */
fun age(secs: Long): String = when {
    secs < 60 -> "now"
    secs < 3600 -> "${secs / 60} min"
    secs < 86400 -> "${secs / 3600} h"
    else -> "${secs / 86400} d"
}

/** "980 B", "12 KB", "3.4 MB". */
fun fileSize(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
    bytes < 1_000_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
}

private val awayTitle = Regex("""(\d+)\s*h(?:\s*(\d+)\s*m)?|(\d+)\s*m""")

/**
 * How long the user was away, in minutes: from [Away.since] and [Away.until], else from its title
 * ("While you were away (3h 7m)"); null when neither says.
 */
fun awayMinutes(a: Away): Long? {
    if (a.since > 0 && a.until > a.since) return (a.until - a.since) / 60
    val m = awayTitle.find(a.title.substringAfter('(', "")) ?: return null
    val (h, hm, mOnly) = m.destructured
    return if (h.isNotEmpty()) h.toLong() * 60 + (hm.toLongOrNull() ?: 0) else mOnly.toLong()
}

/** A closed question's outcome as the folded row shows it ("Answered: Yes" → "Yes"). */
fun outcomeWords(outcome: String): String = outcome.removePrefix("Answered: ").removePrefix("Answered:").trim()

/** "Today", "Yesterday", or "Sun 4 Oct" for a chat or feed day. */
fun dayWords(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
}

/** A held thing as a chip says it: "repo:dibs:master" → "dibs master", "phone:…" → "phone". */
fun holdWords(hold: String): String = when {
    hold.startsWith("phone") -> "phone"
    hold.startsWith("repo:") -> hold.removePrefix("repo:").replace(':', ' ')
    else -> hold
}

/** The header's state line, by precedence: the link first, then what dibs is doing. */
fun stateWords(link: Link, state: State?): String = when {
    link == Link.UNPAIRED -> "Pairing lost"
    link == Link.OFFLINE -> "Not connected"
    state == null -> "Waiting for dibs"
    !state.words.isNullOrBlank() -> state.words
    state.busy -> "On it"
    !state.usage.isNullOrBlank() -> "Out of usage"
    else -> "Ready"
}

/** A usage window's short name: "5h", "7d", "spend". */
fun limitName(name: String): String = when (name) {
    "five_hour" -> "5h"
    "seven_day" -> "7d"
    "spend_limit" -> "spend"
    else -> name
}

/** At this percent a window's line turns amber. */
const val LIMIT_WARN = 80.0

/** One line of the header's usage: its words, and whether it's near the end ([LIMIT_WARN]). */
data class LimitLine(val text: String, val warn: Boolean)

/**
 * The header's usage lines, one per window still running at [now]: "5h 87% · resets 12:20",
 * "7d 63% · resets Thu 09:00" (the day when it's more than 20 hours off). [clock] says a time as
 * the phone does; [day] its weekday.
 */
fun limitWords(limits: List<Limit>, now: Long, clock: (Long) -> String, day: (Long) -> String): List<LimitLine> =
    limits.filter { it.resets > now }.map { l ->
        val at = if (l.resets - now > 20 * 3600) "${day(l.resets)} ${clock(l.resets)}" else clock(l.resets)
        LimitLine("${limitName(l.name)} ${kotlin.math.round(l.pct).toInt()}% · resets $at", l.pct >= LIMIT_WARN)
    }

/**
 * The laptop in one line: "11.2 of 15.6 GB used · load 6.2 on 16 cores · 2 building, 11 waiting
 * · 14 sessions on all machines". Parts dibs didn't send are left out; null when it sent none.
 */
fun laptopWords(l: Laptop): String? {
    fun gb(b: Long) = String.format(Locale.ROOT, "%.1f", b / 1_073_741_824.0)
    val parts = listOfNotNull(
        l.memUsed?.let { u -> l.memTotal?.let { "${gb(u)} of ${gb(it)} GB used" } ?: "${gb(u)} GB used" },
        l.load?.let { ld -> String.format(Locale.ROOT, "load %.1f", ld) + (l.cores?.let { " on $it cores" } ?: "") },
        when {
            l.builds != null && (l.waiting ?: 0) > 0 -> "${l.builds} building, ${l.waiting} waiting"
            l.builds != null -> "${l.builds} building"
            (l.waiting ?: 0) > 0 -> "${l.waiting} builds waiting"
            else -> null
        },
        l.agents?.let { if (it == 1) "1 session on all machines" else "$it sessions on all machines" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The task page's line under "Full story": what it is before it was ever asked for, then where it
 * stands. [written] is when the kept one was written, as the phone says times.
 */
fun storyWords(s: Story?, written: String?): String = when {
    s == null -> "A long read: what it tried, what failed, the choices, what's left"
    s.writing && s.have && written != null -> "Written $written · a new one is being written"
    s.writing -> if (s.by == "agent") "Its agent is writing it" else "dibs is writing it"
    s.state == "failed" && !s.have -> "It couldn't be written"
    written == null -> "Written"
    s.stale -> "Written $written · the task moved on since"
    else -> "Written $written"
}

/** The chip over the chat box while a message is about a full story: "About the full story of X", "Follow-up to X". */
fun aboutWords(a: About): String = when {
    a.kind == "follow" -> "Follow-up to #${a.story} ${a.title}"
    a.quote != null -> "About a part of the full story of #${a.story} ${a.title}"
    else -> "About the full story of #${a.story} ${a.title}"
}

/** An Ask about subject's name before dibs lists the conversation ("task:85" → "Task #85"), when no button named it. */
fun askSubject(about: String): String {
    val kind = about.substringBefore(':')
    val rest = about.substringAfter(':', "")
    return when (kind) {
        "task" -> "Task #$rest"
        "note" -> "A note"
        "project" -> rest.ifBlank { "A project" }
        "file" -> rest.substringAfterLast('/').ifBlank { "A file" }
        else -> about
    }
}

/**
 * A paragraph asked about, as the start of the box's draft: "About “It first folded the lines by
 * day”: ". Its first sentence when short, else its first words (never cut with "…").
 */
fun askQuote(paragraph: String): String {
    val text = paragraph.trim().replace(Regex("\\s+"), " ")
    // A sentence ends at . ! or ? before a space, but not at an abbreviation's dot ("e.g.", "etc.").
    val end = Regex("[.!?](?=\\s|$)").findAll(text).firstOrNull { m ->
        text[m.range.first] != '.' || !abbreviation(text.substring(0, m.range.first).substringAfterLast(' '))
    }
    val sentence = end?.let { text.substring(0, it.range.last + 1) } ?: text
    val words = sentence.removeSuffix(".").split(' ')
    val quote = if (words.size <= 12) words.joinToString(" ") else text.split(' ').take(8).joinToString(" ").trimEnd(',', ';', ':')
    return "About “$quote”: "
}

// The word before a dot is an abbreviation: single letters between dots ("e.g", "i.e") or a common
// short one. A file name ("PLAN.md"), a number or a lone letter ("plan B") ending a sentence isn't.
private fun abbreviation(word: String): Boolean {
    val w = word.trimStart('(', '“', '"', '\'').lowercase()
    return DOTTED.matches(w) || w in ABBREVIATIONS
}

private val DOTTED = Regex("([a-z]\\.)+[a-z]")
private val ABBREVIATIONS = setOf("etc", "vs", "cf", "approx", "incl", "mr", "mrs", "ms", "dr")

/** What the reading card says before dibs words it ("Reading the note"). */
fun askReading(about: String): String = when (about.substringBefore(':')) {
    "task" -> "Reading the task"
    "note" -> "Reading the note"
    "project" -> "Reading the project"
    "file" -> "Reading the file"
    else -> "Reading"
}
