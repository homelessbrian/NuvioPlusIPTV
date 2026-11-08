package com.nuvio.tv.livetv.data

import android.util.Xml
import com.nuvio.tv.livetv.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val icon: String?
)

data class XmltvResult(
    val channels: Map<String, XmltvChannel>,
    /** Programs keyed by XMLTV channel id, sorted by start time. */
    val programs: Map<String, List<EpgProgram>>,
    /** Reading stopped early here (a broken file): what was read before it is kept. */
    val error: String? = null,
    /**
     * Every channel id the guide's shows mention, kept or not (just the ids). For guides with no
     * channel list, so Change EPG can still offer all of them.
     */
    val programChannelIds: Set<String> = emptySet()
)

/**
 * Streaming XMLTV parser. Large provider guides are often 50–200 MB, so only
 * programs inside [windowStartMs, windowEndMs] and (once the channel list has
 * been read) only channels that match the playlist are kept in memory.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        windowStartMs: Long,
        windowEndMs: Long,
        /**
         * For a guide with no channel list before its shows (so [selectChannels] can't pick
         * any and returns null): whether to keep a channel's shows, asked once per channel id.
         * Null keeps them all. Without it, every show in a big guide was held in memory, which
         * on a low-memory box could slow the app down or close it.
         * (Before [selectChannels], so `parse(…) { channels -> … }` still means selectChannels.)
         */
        keepUnlisted: ((String) -> Boolean)? = null,
        /**
         * Hands over each channel's shows as soon as that channel's run of shows ends (guides list
         * a channel's shows together), instead of keeping the whole guide in memory. A channel whose
         * shows are split up arrives in more than one part. With it, the result has no programs.
         */
        onPrograms: ((String, List<EpgProgram>) -> Unit)? = null,
        selectChannels: (Map<String, XmltvChannel>) -> Set<String>?
    ): XmltvResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Forgiving mode, like other IPTV apps: a stray "&" in a title, an HTML entity such as
        // &nbsp; or an unclosed tag no longer stops the whole guide from being read.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)

        val channels = LinkedHashMap<String, XmltvChannel>()
        val programs = HashMap<String, MutableList<EpgProgram>>()
        var wanted: Set<String>? = null
        var wantedResolved = false
        val seenIds = HashSet<String>()
        val unlistedAnswers = HashMap<String, Boolean>()
        var bufId: String? = null
        var buf = ArrayList<EpgProgram>()
        fun flush() {
            val id = bufId ?: return
            if (buf.isNotEmpty()) onPrograms?.invoke(id, buf.sortedBy { it.startMs })
            buf = ArrayList()
            bufId = null
        }

        var error: String? = null
        try {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let { channels[it.id] = it }
                    "programme" -> { // XMLTV's element name: keep this spelling
                        if (!wantedResolved) {
                            wanted = selectChannels(channels)
                            wantedResolved = true
                        }
                        val channelId = parser.getAttributeValue(null, "channel")
                        if (channelId != null) seenIds += channelId
                        val keep = channelId != null && when {
                            wanted != null -> channelId in wanted!!
                            keepUnlisted != null -> unlistedAnswers.getOrPut(channelId) { keepUnlisted(channelId) }
                            else -> true
                        }
                        if (keep) {
                            val program = readProgramme(parser, windowStartMs, windowEndMs)
                            if (program != null) {
                                if (onPrograms != null) {
                                    if (bufId != channelId) { flush(); bufId = channelId }
                                    buf.add(program)
                                } else {
                                    programs.getOrPut(channelId!!) { ArrayList() }.add(program)
                                }
                            }
                        } else {
                            skipProgramme(parser)
                        }
                        // It ended at the next <programme> (it was never closed): read that one
                        // now rather than stepping past it.
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "programme") {
                            event = parser.eventType
                            continue
                        }
                    }
                }
            }
            event = parser.next()
        }
        } catch (e: Exception) {
            // A guide broken partway (bad characters, cut off): keep everything read up to there
            // instead of throwing the whole guide away.
            error = "line ${runCatching { parser.lineNumber }.getOrDefault(-1)}: " +
                (e.message?.take(140) ?: e.javaClass.simpleName)
            if (!wantedResolved) wanted = null
        }
        // The last channel's shows (also when the guide broke partway: what was read is kept).
        runCatching { flush() }
        val sorted = programs.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return XmltvResult(channels, sorted, error, seenIds)
    }

    private fun readChannel(parser: XmlPullParser): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id") ?: run { skip(parser); return null }
        val names = mutableListOf<String>()
        var icon: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> readText(parser).takeIf { it.isNotBlank() }?.let { names.add(it.trim()) }
                    "icon" -> {
                        icon = parser.getAttributeValue(null, "src")
                        skip(parser)
                    }
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return XmltvChannel(id, names, icon)
    }

    private fun readProgramme(parser: XmlPullParser, windowStart: Long, windowEnd: Long): EpgProgram? {
        val start = parseTime(parser.getAttributeValue(null, "start"))
        val stop = parseTime(parser.getAttributeValue(null, "stop"))
        if (start == null || stop == null || stop <= windowStart || start >= windowEnd) {
            skipProgramme(parser)
            return null
        }
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var episode: String? = null
        var subTitle: String? = null
        var icon: String? = null
        var date: String? = null
        val people = ArrayList<String>()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            // Another show starting inside this one: this one was never closed (some guides
            // have hundreds of these). It ends here; the parser stays on the new one. Before,
            // every show after it was taken as part of it and lost.
            if (event == XmlPullParser.START_TAG && parser.name == "programme") break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> { val t = readText(parser); if (title == null) title = t }
                    "sub-title" -> { val t = readText(parser); if (subTitle == null) subTitle = t }
                    "desc" -> { val t = readText(parser); if (desc == null) desc = t }
                    "category" -> { val t = readText(parser); if (category == null) category = t }
                    "episode-num" -> {
                        val system = parser.getAttributeValue(null, "system")
                        val t = readText(parser)
                        if (system == "onscreen" || episode == null) episode = formatEpisode(system, t)
                    }
                    "icon" -> { icon = parser.getAttributeValue(null, "src"); skip(parser) }
                    "date" -> { val t = readText(parser); if (date == null) date = t }
                    "credits" -> readCredits(parser, people)
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        // Only a bare year ("1990") is a release year. Many guides put the broadcast or episode
        // air date here instead ("20260929"), which would make every poster look "wrong year".
        val year = date?.trim()?.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 1900..2100 }
            ?: yearIn(title) ?: yearIn(desc)
        val fullDesc = when {
            subTitle.isNullOrBlank() -> desc?.takeIf { it.isNotBlank() }
            desc.isNullOrBlank() -> subTitle
            else -> "$subTitle\n$desc"
        }
        return EpgProgram(
            startMs = start,
            stopMs = stop,
            // Repeated words (titles, categories, icon links) are stored once instead of once per
            // show, and very long descriptions are cut: a big guide held several hundred thousand
            // listings in memory, which is what made huge playlists freeze.
            title = title?.trim().orEmpty().ifBlank { "No information" }.let { if (it.length <= 80) it.intern() else it },
            description = fullDesc?.let { if (it.length > 800) it.take(800).trimEnd() + "…" else it },
            category = category?.trim()?.intern(),
            episode = episode?.intern(),
            icon = icon?.intern(),
            year = year,
            people = people.take(12)
        )
    }

    private val yearInParens = Regex("""\((19\d{2}|20\d{2})\)""")

    /** "(1990)" in a title or description. Bare numbers are ignored: too easy to misread. */
    private fun yearIn(text: String?): Int? =
        text?.let { yearInParens.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun readCredits(parser: XmlPullParser, out: MutableList<String>) {
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "actor", "director" -> readText(parser).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    else -> skip(parser)
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
    }

    private fun formatEpisode(system: String?, raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (system == "xmltv_ns") {
            val parts = text.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
            val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
            return when {
                // A year used as the season (news: "S2026 E609") isn't a real episode number.
                season != null && season >= 100 -> null
                season != null && ep != null -> "S${season + 1} E${ep + 1}"
                ep != null -> "E${ep + 1}"
                else -> null
            }
        }
        // Other numbering systems (dd_progid, thetvdb, imdb, crid…) are database ids, not
        // something to show: only the on-screen form (or one without a system) is used.
        if (system != null && system != "onscreen") return null
        return cleanEpisode(text)
    }

    private val episodeIdDigits = Regex("""\d{5,}""")
    private val episodeSeason = Regex("""(?i)\bS(?:eason)?\s*(\d+)""")

    /**
     * An episode label worth showing ("S2 E5", "Ep. 12", "1x05"), or null for the ids some
     * guides put there instead: long strings of digits ("609178259") and year-based numbers
     * ("S2026E609"), which filled the end of every listing with numbers.
     */
    fun cleanEpisode(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.length > 24) return null
        val letters = t.count { it.isLetter() }
        val digits = t.count { it.isDigit() }
        if (digits == 0) return t // "Part One", "Finale"
        if (letters == 0 && digits > 4) return null // a plain id
        if (episodeIdDigits.containsMatchIn(t)) return null // ids with a prefix ("EP012345678")
        // Season numbers that are years.
        episodeSeason.find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 100) return null }
        return t
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return sb.toString()
    }

    /** Skips a show, stopping at the next <programme> if this one was never closed. */
    private fun skipProgramme(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> if (parser.name == "programme") return else depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Days since 1970-01-01 for a calendar date (UTC), without creating a calendar. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Parses "YYYYMMDDhhmmss +zzzz" (seconds and offset optional). */
    internal fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        val digits = v.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31) return null
        var ms = (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
        val offset = v.substring(digits.length).trim()
        if (offset.length >= 5 && (offset[0] == '+' || offset[0] == '-')) {
            val sign = if (offset[0] == '-') -1 else 1
            val oh = offset.substring(1, 3).toIntOrNull() ?: 0
            val om = offset.substring(3, 5).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
