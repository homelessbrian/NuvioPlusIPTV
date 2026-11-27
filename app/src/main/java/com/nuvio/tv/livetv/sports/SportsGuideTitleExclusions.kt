package com.nuvio.tv.livetv.sports

import java.text.Normalizer
import java.util.Locale

/** Literal phrases matched against a programme's original guide title. */
internal class SportsGuideTitleExclusions(titles: List<String>) {
    private val phrases = titles.map(::key).filter(String::isNotEmpty).distinct().map { " $it " }
    val isEmpty: Boolean get() = phrases.isEmpty()

    fun matches(title: String): Boolean {
        if (isEmpty) return false
        val words = " ${key(title)} "
        return phrases.any { it in words }
    }

    /** A search started before the edit can still deliver results while a fresh search runs. */
    fun visibleMatches(existing: Map<String, List<ChannelMatch>>): Map<String, List<ChannelMatch>> {
        if (isEmpty) return existing
        return existing.mapValues { (_, list) ->
            list.mapNotNull { match ->
                // Older caches kept the guide title only in the reason, or only the network name.
                val title = match.program?.title ?: match.cachedGuideTitle
                    ?: match.how.takeIf { it.startsWith("Guide: ") }?.removePrefix("Guide: ")
                when {
                    title == null -> match.takeUnless {
                        it.how.endsWith(" listed by ESPN") &&
                            (it.score == SportsMatcher.NATIONAL_GUIDE_SCORE || it.score == SportsMatcher.REGIONAL_GUIDE_SCORE)
                    }
                    !matches(title) -> match
                    // A manual pick or an independently matched channel name still stands.
                    match.how.startsWith("Your ") || match.how == "Channel name" ->
                        match.copy(program = null, cachedGuideTitle = null, catchup = false)
                    else -> null
                }
            }
        }.filterValues { it.isNotEmpty() }
    }

    companion object {
        private val separators = Regex("""[^\p{L}\p{M}\p{N}]+""")
        val Empty = SportsGuideTitleExclusions(emptyList())
        fun unique(titles: List<String>): List<String> {
            if (titles.isEmpty()) return emptyList()
            val seen = HashSet<String>()
            return titles.map(String::trim).filter { title -> key(title).let { it.isNotEmpty() && seen.add(it) } }
        }

        fun key(title: String): String =
            Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFC).replace(separators, " ").trim()
    }
}
