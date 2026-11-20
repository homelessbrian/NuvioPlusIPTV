package com.nuvio.tv.livetv.sports

import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

/**
 * Whether a YouTube video's title (or a TheSportsDB game name) is about one game or event:
 *  - a game: both teams in it;
 *  - a fight card or a tennis tournament: both names of one of its bouts or matches
 *    ("Gane vs. Hokit"), or the card's own name and number ("UFC 334", "Zuffa Boxing 13");
 *  - a race, a golf tournament, a show: its name. Either way round, so a sponsor in front
 *    ("MSC Cruises São Paulo Grand Prix") doesn't stop "2026 Sao Paulo Grand Prix" from matching.
 * A channel's video must also come out after the event started and within a few days of its
 * end, and a date or year in its title must be the event's (channels repost old fights).
 */
internal class YtMatch private constructor(
    private val ev: SportsEvent,
    private val sport: Sport?,
    /** The event's name, word by word (folded). */
    private val words: Set<String>,
    /** Its words that tell it apart (not "the", "vs", "highlights"…). */
    private val sig: List<String>,
    /** Surnames of its bouts or matches. */
    private val pairs: List<Pair<String, String>>,
    /** "ufc 334": a card's name with its number. */
    private val card: Regex?,
    private val grandPrix: Boolean
) {
    /** A TheSportsDB game name. */
    fun nameFits(name: String): Boolean {
        if (name.isBlank()) return false
        val a = ev.away
        val h = ev.home
        val joined = joined(name)
        if (a != null && h != null) return teamIn(a, joined) && teamIn(h, joined)
        val w = wordSet(name)
        return forward(w) || reverse(name) || pairIn(w) || cardIn(name)
    }

    /** A video from one of the sports' channels ([atMs]: when it came out; [day]: a day's highlights of a tournament). */
    fun channelFits(title: String, atMs: Long, videoSport: String, day: Boolean): Boolean {
        if (title.isBlank() || sport == null || videoSport != sport.name) return false
        val start = ev.startMs
        val end = maxOf(ev.endMs ?: start, start)
        val earliest = start - if (ev.hasTime) HOUR else 12 * HOUR
        if (atMs < earliest || atMs > end + 4 * DAY) return false
        if (!datesFit(title, start, end)) return false
        if (day) return atMs <= end + 36 * HOUR
        val a = ev.away
        val h = ev.home
        val joined = joined(title)
        if (a != null && h != null) return teamIn(a, joined) && teamIn(h, joined)
        val w = wordSet(title)
        return pairIn(w) || cardIn(title) || forward(w) || reverse(title)
    }

    /** Most of the event's own words are in [w] (all of them for a short name). */
    private fun forward(w: Set<String>): Boolean {
        if (sig.isEmpty()) return false
        val n = sig.count { it in w }
        return if (sig.size <= 4) n == sig.size else n * 10 >= sig.size * 8
    }

    /** One part of [text] ("2026 Sao Paulo Grand Prix") names the event: all its own words are in the event's name. */
    private fun reverse(text: String): Boolean = SEGMENTS.split(text).any { seg ->
        val all = words(seg)
        val own = all.filter(::significant)
        when {
            own.size >= 2 -> own.all { it in words }
            own.size == 1 -> grandPrix && ("prix" in all || "gp" in all) && own[0] in words
            else -> false
        }
    }

    private fun pairIn(w: Set<String>): Boolean = pairs.any { (x, y) -> x in w && y in w }

    private fun cardIn(text: String): Boolean = card?.containsMatchIn(spaced(text)) == true

    private fun teamIn(s: Side, joined: String): Boolean {
        val t = s.team
        return listOfNotNull(t.fullName, t.name, t.location?.let { it + t.name }, t.location?.takeIf { it.length >= 5 })
            .map(::joined).filter { it.length >= 3 }.any { it in joined }
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        fun of(ev: SportsEvent, sport: Sport?): YtMatch {
            val title = ev.title
            val w = wordSet(title)
            val sig = words(title).filter(::significant).distinct()
            val pairs = ArrayList<Pair<String, String>>()
            fun pairOf(text: String) {
                val sides = VERSUS.split(text)
                if (sides.size != 2) return
                val x = surname(sides[0]) ?: return
                val y = surname(sides[1]) ?: return
                if (x != y) pairs += x to y
            }
            pairOf(title.substringAfter(':'))
            ev.standings.forEach { pairOf(it.name) }
            val card = CARD.find(spaced(title.substringBefore(':')))?.takeIf { m ->
                (m.groupValues[2].toIntOrNull() ?: 0) < 1900 && m.groupValues[1].trim().length >= 3
            }?.let { m -> Regex("\\b" + Regex.escape(m.groupValues[1].trim()) + "\\b") }
            return YtMatch(ev, sport, w, sig, pairs, card, "prix" in w || "gp" in w)
        }

        /** Where a video goes in the list: whole events and races first, practice and qualifying last. */
        fun rank(title: String): Int {
            val t = fold(title)
            return when {
                SESSION.containsMatchIn(t) -> 3
                BEST.containsMatchIn(t) -> 0
                "highlight" in t -> 1
                else -> 2
            }
        }

        /** Lower case, without accents ("São" -> "sao"). */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

        private fun words(s: String): List<String> = fold(s).split(NON_WORD).filter { it.isNotEmpty() }
        private fun wordSet(s: String): Set<String> = words(s).toHashSet()
        private fun joined(s: String): String = fold(s).filter { it.isLetterOrDigit() }
        private fun spaced(s: String): String = words(s).joinToString(" ")

        private fun significant(w: String): Boolean = w.length >= 3 && w !in GENERIC && w.any { !it.isDigit() }

        private fun surname(side: String): String? {
            val w = words(side).filter { it.length >= 3 && it !in GENERIC && it.none(Char::isDigit) }
            return w.lastOrNull()
        }

        /** A date or a year in the title is the event's (so old fights and last year's race are left out). */
        private fun datesFit(title: String, start: Long, end: Long): Boolean {
            val t = fold(title)
            val first = start / DAY - 1
            val last = end / DAY + 1
            val days = ArrayList<Long>()
            fun add(y: Int, m: Int, d: Int) {
                if (m !in 1..12 || d !in 1..31 || y < 1950) return
                days += Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis / DAY
            }
            MONTH_DAY_YEAR.findAll(t).forEach { add(it.groupValues[3].toInt(), month(it.groupValues[1]), it.groupValues[2].toInt()) }
            DAY_MONTH_YEAR.findAll(t).forEach { add(it.groupValues[3].toInt(), month(it.groupValues[2]), it.groupValues[1].toInt()) }
            SLASHED.findAll(t).forEach { m ->
                val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                add(y, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
            if (days.isNotEmpty()) return days.any { it in first..last }
            val years = HashSet<Int>()
            SEASON.findAll(t).forEach { m -> val y = m.groupValues[1].toInt(); years += y; years += y + 1 }
            YEAR.findAll(t).forEach { years += it.value.toInt() }
            if (years.isEmpty()) return true
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val ys = listOf(start, end).map { cal.timeInMillis = it; cal.get(Calendar.YEAR) }.toSet()
            return years.any { it in ys }
        }

        private fun month(s: String): Int = MONTHS.indexOfFirst { s.startsWith(it) } + 1

        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val SEGMENTS = Regex("\\s*(?:[|:;–—•·]|\\s-\\s|\\s/\\s)\\s*")
        private val VERSUS = Regex("\\s+(?:vs\\.?|v\\.?|def\\.)\\s+", RegexOption.IGNORE_CASE)
        private val CARD = Regex("^([a-z][a-z ]*? (\\d{1,4}))\\b")
        private val SESSION = Regex("practice|qualifying|sprint|shootout|\\bfp[123]\\b|warm-up|preview")
        private val BEST = Regex("full card|race highlights|extended highlights|condensed|full race|full match|full fight")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private const val MONTH_NAME = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
        private val MONTH_DAY_YEAR = Regex("\\b$MONTH_NAME\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b")
        private val DAY_MONTH_YEAR = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+$MONTH_NAME\\.?,?\\s+(\\d{4})\\b")
        private val SLASHED = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4})\\b")
        private val SEASON = Regex("\\b((?:19|20)\\d{2})/(?:\\d{2}|\\d{4})\\b")
        private val YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val GENERIC = setOf(
            "the", "and", "vs", "of", "at", "in", "on", "an", "from", "with", "for", "to", "by",
            "highlights", "highlight", "extended", "condensed", "official", "full", "fight", "fights", "match", "matches",
            "game", "games", "race", "grand", "prix", "round", "day", "night", "session", "practice", "qualifying",
            "sprint", "shootout", "warm", "recap", "every", "shot", "shots", "all", "goals", "goal", "card", "main",
            "event", "replay", "part", "week", "matchday", "live", "presented", "title",
            "wwe", "aew", "tna", "january", "february", "march", "april", "june", "july", "august",
            "september", "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
            "sep", "sept", "oct", "nov", "dec"
        )
    }
}
