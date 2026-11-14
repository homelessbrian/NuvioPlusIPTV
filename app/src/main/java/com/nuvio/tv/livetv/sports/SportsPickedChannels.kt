package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.model.LiveChannel

/**
 * Channels you picked yourself (Fix Channel: this game, its network, team or competition) are
 * saved by the channel's key: playlist + tvg-id + name. Providers rename channels in playlist
 * updates (event channels most of all), which changed the key and quietly lost the pick. Like
 * renamed channels and picked logos, a pick whose channel is gone is found again by playlist +
 * tvg-id.
 */
internal object SportsPickedChannels {

    /** "playlist|tvg-id" of a channel key; null with no tvg-id (and for portal channels, whose keys don't change). */
    fun stableId(key: String): String? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3 || parts[1].isBlank() || parts[1] == "stalker") return null
        return parts[0] + "|" + parts[1]
    }

    /** [s] with every picked channel that's no longer in [channels] pointed at its renamed copy. */
    fun resolve(s: SportsSettings, channels: List<LiveChannel>): SportsSettings {
        if (s.eventChannels.isEmpty() && s.networkChannels.isEmpty() && s.teamChannels.isEmpty() && s.leagueChannels.isEmpty()) return s
        val keys = HashSet<String>(channels.size)
        channels.forEach { keys.add(it.key) }
        var byStable: Map<String, List<LiveChannel>>? = null
        fun find(key: String): String {
            if (key in keys) return key
            val id = stableId(key) ?: return key
            val index = byStable ?: channels.groupBy { stableId(it.key) ?: "" }.also { byStable = it }
            val candidates = index[id].orEmpty()
            if (candidates.isEmpty()) return key
            // Several copies under one tvg-id (FHD/HD/SD): the one whose name is closest to the old one.
            val oldWords = words(key.substringAfter('|').substringAfter('|').substringBefore('#'))
            return candidates.maxByOrNull { c -> words(c.name).count { it in oldWords } }!!.key
        }
        var changed = false
        fun mapped(m: Map<String, String>, value: (String) -> String, rebuild: (String, String) -> String): Map<String, String> {
            var out: HashMap<String, String>? = null
            for ((k, v) in m) {
                val old = value(v)
                val now = find(old)
                if (now != old) {
                    if (out == null) out = HashMap(m)
                    out[k] = rebuild(v, now)
                }
            }
            return out?.also { changed = true } ?: m
        }
        val plain = { v: String -> v }
        val replace = { _: String, now: String -> now }
        val events = mapped(s.eventChannels, { it.substringBeforeLast('|') }) { v, now -> now + "|" + v.substringAfterLast('|') }
        val networks = mapped(s.networkChannels, plain, replace)
        val teams = mapped(s.teamChannels, plain, replace)
        val leagues = mapped(s.leagueChannels, plain, replace)
        return if (!changed) s
        else s.copy(eventChannels = events, networkChannels = networks, teamChannels = teams, leagueChannels = leagues)
    }

    /** Every channel key you picked for Sports. */
    fun keys(s: SportsSettings): Set<String> = HashSet<String>().apply {
        s.eventChannels.values.forEach { add(it.substringBeforeLast('|')) }
        addAll(s.networkChannels.values); addAll(s.teamChannels.values); addAll(s.leagueChannels.values)
    }

    private fun words(name: String): Set<String> =
        name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
