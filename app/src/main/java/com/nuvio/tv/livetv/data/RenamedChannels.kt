package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel

/**
 * A channel's key is its playlist, guide id and name, so renaming a channel in the playlist
 * (the provider, or a playlist editor) gives it a new key and its favorites, hidden status,
 * groups you made and so on would stay with the old name. For each saved key with no channel
 * any more, this finds the channel it most likely became, in the same playlist: the same stream
 * link first, then the same guide id, then a guide name (tvg-name) equal to the old name. Only
 * a single clear match counts; anything unclear is left as it was.
 */
internal object RenamedChannels {
    private val DUPLICATE = Regex("""#\d+$""")

    /**
     * [lost]: saved keys that match no channel now. [links]: old key → stream link, from the
     * channel list as it was before the playlists were read again. [onlyNew]: only channels that
     * weren't in that list can be the match (a channel that was already there wasn't renamed).
     * Returns old key → new key.
     */
    fun match(
        lost: Collection<String>,
        now: List<LiveChannel>,
        links: Map<String, String>,
        onlyNew: Boolean
    ): Map<String, String> {
        if (lost.isEmpty()) return emptyMap()
        val sources = lost.mapTo(HashSet()) { it.substringBefore('|') }
        val byLink = HashMap<String, MutableList<LiveChannel>>()
        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byTvgName = HashMap<String, MutableList<LiveChannel>>()
        for (c in now) {
            if (c.sourceId !in sources || (onlyNew && c.key in links)) continue
            byLink.getOrPut(c.sourceId + "\n" + c.url) { ArrayList(1) } += c
            c.tvgId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { byTvgId.getOrPut(c.sourceId + "\n" + it) { ArrayList(1) } += c }
            c.tvgName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { byTvgName.getOrPut(c.sourceId + "\n" + it) { ArrayList(1) } += c }
        }
        if (byLink.isEmpty()) return emptyMap()
        val taken = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        for (old in lost) {
            val source = old.substringBefore('|')
            val rest = old.substringAfter('|', "")
            val tvgId = rest.substringBefore('|').trim().lowercase()
            val name = rest.substringAfter('|', "").replace(DUPLICATE, "").trim().lowercase()
            fun one(index: Map<String, List<LiveChannel>>, value: String?): LiveChannel? =
                if (value.isNullOrEmpty()) null
                else index[source + "\n" + value]?.singleOrNull()?.takeIf { it.key !in taken && it.key != old }
            val hit = one(byLink, links[old]) ?: one(byTvgId, tvgId) ?: one(byTvgName, name) ?: continue
            taken += hit.key
            out[old] = hit.key
        }
        return out
    }
}
