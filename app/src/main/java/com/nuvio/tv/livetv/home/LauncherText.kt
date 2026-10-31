package com.nuvio.tv.livetv.home

import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.data.XmltvParser
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.ui.LiveTvSearchBridge
import com.nuvio.tv.livetv.ui.titleMarks

/** A show as a launcher card shows it: a clean title, a short detail line, and its tags. */
internal class CardText(val title: String, val detail: List<String>, val isNew: Boolean, val live: Boolean)

/**
 * Tidies guide titles for the launcher cards. Guides are messy: "NEW: NORTHERN LIGHTS S02E05
 * [HD]", the channel's name in front of the show's, tags and emoji in the title, "Programming"
 * for no information at all.
 */
internal object LauncherText {
    private val SYMBOLS = Regex("[\\p{So}\\p{Cs}\\p{Co}\\uFE0F\\u200B-\\u200D\\u2060]")
    private val SPACES = Regex("\\s+")
    private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "v")
    private val VOWELS = Regex("[aeiouyAEIOUY]")
    private val EPISODE_CODE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b""")

    /** Null: nothing worth showing (no information, or just the channel's name). */
    fun forShow(p: EpgProgram, channelName: String): CardText? {
        val marks = titleMarks(p.title)
        var t = LiveTvSearchBridge.cleanTitle(marks.title).ifBlank { marks.title }
        t = t.replace(SYMBOLS, "").replace(SPACES, " ").trim().trim('-', '|', ':', '·', '•').trim()
        // The channel's own name in front: "SKY NEWS: Sky News Tonight" -> "Sky News Tonight".
        val ch = channelName.replace(SYMBOLS, "").trim()
        if (ch.length >= 2) {
            val m = Regex("^" + Regex.escape(ch) + "\\s*[:|\\-–]\\s*", RegexOption.IGNORE_CASE).find(t)
            if (m != null && t.length > m.value.length + 1) t = t.substring(m.value.length)
        }
        if (t.isBlank() || LiveTvRepository.isPlaceholderTitle(t) || same(t, ch)) return null
        t = caseFixed(t)

        val detail = ArrayList<String>()
        // A long "Name: The Rest" title: the name big, the rest on the line under it.
        if (t.length > 26) {
            val i = t.indexOf(": ")
            if (i in 3..(t.length - 4)) {
                detail += t.substring(i + 2).trim()
                t = t.substring(0, i).trim()
            }
        }
        episodeLabel(p)?.let { detail += it }
        subtitle(p)?.let { s -> if (detail.none { same(it, s) } && !same(s, t)) detail += caseFixed(s) }
        val cat = category(p)
        val isMovie = cat?.contains("movie", true) == true || cat?.contains("film", true) == true
        if (detail.isEmpty() || isMovie) cat?.let { detail += it }
        if (isMovie || detail.size < 2) p.year?.takeIf { it in 1900..2100 }?.let { detail += it.toString() }
        return CardText(t, detail.distinct(), marks.isNew, marks.live)
    }

    /** "S2 E5" from the guide's episode number or a code left in the title. */
    private fun episodeLabel(p: EpgProgram): String? {
        val raw = XmltvParser.cleanEpisode(p.episode) ?: EPISODE_CODE.find(p.title)?.value ?: return null
        EPISODE_CODE.find(raw)?.let { m -> return "S${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}" }
        return raw.takeIf { it.length <= 16 }
    }

    /** The episode's own title: many guides put it as the description's first, short line. */
    private fun subtitle(p: EpgProgram): String? {
        val d = p.description ?: return null
        if ('\n' !in d) return null
        val first = d.substringBefore('\n').trim()
        return first.takeIf { it.length in 2..60 && !it.endsWith('.') }
    }

    /** The guide's category, short and tidy: "Movie / Drama" -> "Movie". */
    private fun category(p: EpgProgram): String? {
        val c = p.category?.split('/', ',', ';', '|')?.map { it.trim() }?.firstOrNull { it.isNotBlank() } ?: return null
        if (c.length > 18 || c.any { it.isDigit() }) return null
        return c.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun same(a: String, b: String): Boolean {
        fun k(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return k(a).isNotEmpty() && k(a) == k(b)
    }

    /**
     * ALL CAPS titles in normal case ("THE LAST HARBOUR" -> "The Last Harbour"), keeping short
     * words that are meant to be capitals (NFL, UFC, BBC, CNN). Titles already in mixed case are
     * left exactly as the guide wrote them.
     */
    fun caseFixed(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 4 || letters.any { it.isLowerCase() }) return s
        var first = true
        return s.split(' ').joinToString(" ") { word ->
            val core = word.filter { it.isLetter() }
            val out = when {
                core.isEmpty() -> word
                core in ACRONYMS -> word
                core.length <= 4 && !VOWELS.containsMatchIn(core) -> word
                !first && core.lowercase() in SMALL_WORDS -> word.lowercase()
                else -> word.lowercase().replaceFirstChar { it.uppercase() }
            }
            if (core.isNotEmpty()) first = false
            out
        }
    }

    private val ACRONYMS = setOf("USA", "UFC", "AEW", "WWE", "NBA", "NHL", "NFL", "MLB", "MLS", "UEFA", "ESPN", "BBC", "ITV", "ABC", "CBS", "NBC", "FOX", "HBO", "AMC", "TNT", "TBS", "EPL", "F1", "PGA", "LIV", "ATP", "WTA", "UK", "US", "TV", "HD", "MMA", "NCAA", "LA", "NY", "DC", "AFC", "NFC", "CNN", "MSNBC", "PBS", "A&E")
}
