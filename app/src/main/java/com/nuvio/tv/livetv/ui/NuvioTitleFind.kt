package com.nuvio.tv.livetv.ui

/**
 * "Find & stream in Nuvio" from a show in the guide: the movie or series in your addons'
 * catalogs it clearly is, so its page opens straight away (on the episode, when the guide
 * gives the season and episode). With no clear match, Nuvio's search opens as before.
 */
data class NuvioTitle(
    val id: String,
    val type: String,
    val addonBaseUrl: String?,
    val season: Int? = null,
    val episode: Int? = null
)

private val SEASON_EPISODE = Regex("""(?i)\bs\s*0*(\d{1,3})\s*[.\- ]?\s*e\s*0*(\d{1,4})\b""")
private val CROSS_EPISODE = Regex("""\b(\d{1,2})x(\d{1,3})\b""")

/** Season and episode from a guide's episode label ("S2 E5", "S02E05", "2x05"), if both are there. */
internal fun guideSeasonEpisode(vararg texts: String?): Pair<Int, Int>? {
    for (t in texts) {
        if (t.isNullOrBlank()) continue
        val m = SEASON_EPISODE.find(t) ?: CROSS_EPISODE.find(t) ?: continue
        val s = m.groupValues[1].toIntOrNull() ?: continue
        val e = m.groupValues[2].toIntOrNull() ?: continue
        if (s in 0..99 && e > 0) return s to e
    }
    return null
}
