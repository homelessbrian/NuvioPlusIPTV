package com.nuvio.tv.livetv.sports

import java.text.Normalizer

/**
 * Decides whether an add-on's catalog entry ("Manchester United vs Roma", "Man Utd v AS Roma -
 * Highlights") is the same game as one on the Sports page. Pure text and date work (no Android),
 * so it's quick and easy to test. Add-ons name games their own way, so this is forgiving about
 * wording but strict about the two teams, women's / youth sides and the date.
 */
object SportsAddonMatch {
    /** Words that say nothing about which team it is. */
    private val filler = setOf("fc", "cf", "afc", "sc", "ac", "the", "club", "de", "vs", "v", "at", "and", "versus")

    /** Words add-ons put around a game's name (and the league's name); they don't count against a match. */
    private val extras = setOf(
        "highlights", "highlight", "replay", "replays", "full", "game", "match", "recap", "condensed", "extended",
        "live", "hd", "fhd", "sd", "4k", "uhd", "1080p", "720p", "stream", "free", "round", "week", "final", "semi", "quarter"
    )

    /** A women's or youth side is never the same game as the first team's. */
    private val other = setOf("women", "womens", "w", "ladies", "u15", "u16", "u17", "u18", "u19", "u20", "u21", "u23", "reserves", "youth", "ii")

    private val shorthand = mapOf("man" to "manchester", "utd" to "united")

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** How many days apart the game and the entry's date may be (time zones, late kick-offs, recaps a day later). */
    private const val DAY_SLACK = 2L

    /** Words of [text], lower case, no accents or punctuation ("Atlético-MG" -> atletico, mg). */
    fun tokens(text: String): List<String> {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map { shorthand[it] ?: it }
    }

    private fun nameTokens(name: String): Set<String> {
        val all = tokens(name)
        val kept = all.filter { it !in filler }
        return (kept.ifEmpty { all }).toSet()
    }

    /** One side of the game: the names it goes by (short, full, abbreviation). */
    data class Side(val names: List<String>)

    /**
     * [sides]: the game's two sides (empty for races, fights and shows: then [title] is used).
     * [startMs]: the kick-off (only its day is compared).
     * [item] is the add-on's name for it, [released] / [releaseInfo] its date fields.
     */
    fun matches(
        sides: List<Side>,
        title: String,
        startMs: Long,
        item: String,
        released: String?,
        releaseInfo: String?
    ): Boolean {
        val itemTokens = tokens(item).toSet()
        if (itemTokens.isEmpty()) return false
        val eventTokens = (tokens(title) + sides.flatMap { s -> s.names.flatMap { tokens(it) } }).toSet()

        val used = HashSet<String>()
        if (sides.size == 2) {
            for (side in sides) {
                val hit = side.names
                    .filter { it.isNotBlank() && (it.length >= 3 || side.names.size == 1) }
                    .map { nameTokens(it) }
                    .filter { it.isNotEmpty() && itemTokens.containsAll(it) }
                    .maxByOrNull { it.size } ?: return false
                used += hit
            }
        } else {
            val words = nameTokens(title)
            if (words.size < 2 || !itemTokens.containsAll(words)) return false
            used += words
        }

        val leftover = itemTokens - used - filler - extras
        // A women's or youth game is its own game; only a match if the event says so too.
        if (leftover.any { it in other } && eventTokens.none { it in other }) return false
        if (leftover.count { !it.all(Char::isDigit) } > 4) return false

        return dateFits(startMs, released, releaseInfo)
    }

    private fun dateFits(startMs: Long, released: String?, releaseInfo: String?): Boolean {
        val gameDay = Math.floorDiv(startMs, 86_400_000L)
        val day = dayOf(released) ?: dayOf(releaseInfo)
        if (day != null) return kotlin.math.abs(day - gameDay) <= DAY_SLACK
        // Only a year given: the same year as the game, at least.
        val year = Regex("^\\s*(\\d{4})\\s*(?:-\\s*)?$").find(releaseInfo.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        if (year != null) return year == yearOf(gameDay)
        return true
    }

    /** The day (days since 1970, UTC) in "2026-11-03T20:00:00Z" or "03 Nov 2026 · 20:00", else null. */
    internal fun dayOf(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return civil(y.toInt(), mo.toInt(), d.toInt())
        }
        Regex("(\\d{1,2})\\s+([A-Za-z]{3})[A-Za-z]*\\.?,?\\s+(\\d{4})").find(text)?.let { m ->
            val mo = months.indexOf(m.groupValues[2].lowercase()) + 1
            if (mo > 0) return civil(m.groupValues[3].toInt(), mo, m.groupValues[1].toInt())
        }
        return null
    }

    /** Days since 1970-01-01 for a calendar date (proleptic Gregorian, no time zones). */
    private fun civil(y: Int, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = Math.floorDiv(yy, 400)
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    private fun yearOf(day: Long): Int {
        val z = day + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = if (mp < 10) mp + 3 else mp - 9
        return (if (m <= 2) y + 1 else y).toInt()
    }
}
