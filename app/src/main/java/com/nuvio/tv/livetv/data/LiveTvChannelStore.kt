package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The channel list with your renames, numbers, group names and channel name editor applied.
 * One shared copy for the whole app: before, every screen built its own, so each change was
 * worked out two or three times over.
 */
@Singleton
class LiveTvChannelStore @Inject constructor(
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Channels with the user's renames and renumbering applied. */
    val displayChannels: StateFlow<List<LiveChannel>> = combine(
        repository.channels, prefs.userState, prefs.settings, repository.epgLogos,
        // Only the playlists that prefer the guide's logos (not every saved playlist change).
        prefs.playlists.map { list -> list.filter { it.logoSource == "epg" }.mapTo(HashSet()) { it.id } as Set<String> }
            .distinctUntilChanged()
    ) { channels, user, s, epgLogos, preferEpg ->
        withPickedLogos(withLogos(named(channels, user, s), epgLogos, preferEpg), user.channelLogos)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Each playlist's logo choice: "Prefer logos from playlist" keeps the playlist's own (the
     * guide's when it has none); "Prefer logos from EPG" uses the guide's (the playlist's when
     * the guide has none).
     */
    private fun withLogos(
        channels: List<LiveChannel>,
        epgLogos: Map<String, String>,
        preferEpg: Set<String>
    ): List<LiveChannel> {
        if (epgLogos.isEmpty()) return channels
        return channels.map { c ->
            val fromGuide = epgLogos[c.key]
            val logo = if (c.sourceId in preferEpg) fromGuide ?: c.logo
            else c.logo?.takeIf { it.isNotBlank() } ?: fromGuide
            if (logo == c.logo) c else c.copy(logo = logo)
        }
    }

    private fun named(
        channels: List<LiveChannel>,
        user: com.nuvio.tv.livetv.model.LiveUserState,
        s: com.nuvio.tv.livetv.model.LiveTvSettings
    ): List<LiveChannel> = run {
        // Channel name editor: prefixes/suffixes removed from names you haven't renamed yourself.
        val terms = com.nuvio.tv.livetv.model.ChannelNameEditor.terms(s.nameRemovals)
        val cleaned = cleanedNames(channels, s.nameRemovals, terms)
        if (user.channelNames.isEmpty() && user.channelNumbers.isEmpty() && user.groupNames.isEmpty() && terms.isEmpty()) channels
        else {
            // Renames and numbers are saved against the channel's key (playlist + tvg-id + name).
            // Providers often change channel names (event titles, "HD"/"ᴴᴰ" tags, status
            // markers), which changes the key. So also match by playlist + tvg-id when the
            // channel has one, which stays the same when the name changes.
            // Only saved entries whose exact channel no longer exists are carried over, so two
            // channels sharing a tvg-id (ESPN and ESPN HD) never pick up each other's rename.
            val currentKeys = channels.mapTo(HashSet()) { it.key }
            val namesById = stableIndex(user.channelNames.filterKeys { it !in currentKeys })
            val numbersById = stableIndex(user.channelNumbers.filterKeys { it !in currentKeys })
            channels.map { c ->
                val id = stableId(c.key)
                c.copy(
                    name = user.channelNames[c.key] ?: id?.let { namesById[it] }
                        ?: cleaned[c.key] ?: c.name,
                    number = user.channelNumbers[c.key] ?: id?.let { numbersById[it] } ?: c.number,
                    group = user.groupNames[c.groupId] ?: c.group
                )
            }
        }
    }

    // Channel name editor results, kept until the channel list or the terms change. Cleaning
    // tens of thousands of names took long enough that renames seemed to take 20-30 seconds.
    @Volatile private var cleanedFor: Pair<List<LiveChannel>, String>? = null
    @Volatile private var cleanedCache: Map<String, String> = emptyMap()

    private fun cleanedNames(channels: List<LiveChannel>, raw: String, terms: List<String>): Map<String, String> {
        if (terms.isEmpty()) return emptyMap()
        val key = cleanedFor
        if (key != null && key.first === channels && key.second == raw) return cleanedCache
        // Each name is only worked out once: the results are kept by name (in memory and on
        // disk), so starting the app or a playlist update only cleans names it hasn't seen.
        // Prefixes the playlist puts on with only a space ("USA ESPN"): worked out per playlist.
        val spaced = channels.groupBy { it.key.substringBefore('|') }.mapValues { (_, list) ->
            com.nuvio.tv.livetv.model.ChannelNameEditor.spacePrefixes(list.map { it.name }, terms)
        }
        val memoKey = raw + "|spaced:" + spaced.values.flatten().toSortedSet().joinToString(",")
        val memo = nameMemo(memoKey)
        var added = false
        val out = HashMap<String, String>(channels.size)
        channels.forEach { c ->
            val sp = spaced[c.key.substringBefore('|')].orEmpty()
            val memoName = if (sp.isEmpty()) c.name else sp.sorted().joinToString(",") + "\u0001" + c.name
            val n = memo[memoName] ?: com.nuvio.tv.livetv.model.ChannelNameEditor.clean(c.name, terms, sp).also {
                memo[memoName] = it
                added = true
            }
            if (n != c.name) out[c.key] = n
        }
        cleanedCache = out
        cleanedFor = channels to raw
        if (added) saveMemo(memoKey, channels)
        return out
    }

    // Channel name editor results by original name, for the current list of terms.
    private val memoLock = Any()
    @Volatile private var memoFor: String? = null
    private var memo: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
    private val memoFile by lazy { java.io.File(java.io.File(context.filesDir, "livetv"), "name_editor_cache.txt") }
    private var saveJob: kotlinx.coroutines.Job? = null

    // The cleaning rules are part of the header, so saved results from older rules are dropped.
    private fun memoHeader(raw: String) = "v3|" + raw.replace('\n', ' ')

    private fun nameMemo(raw: String): MutableMap<String, String> = synchronized(memoLock) {
        if (memoFor == raw) return memo
        val fresh = java.util.concurrent.ConcurrentHashMap<String, String>()
        // Saved results only count when they were made with exactly these terms.
        runCatching {
            if (memoFile.exists()) memoFile.bufferedReader().use { r ->
                if (r.readLine() == memoHeader(raw)) {
                    while (true) {
                        val line = r.readLine() ?: break
                        val tab = line.indexOf('\t')
                        if (tab > 0) fresh[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
        }
        memo = fresh
        memoFor = raw
        fresh
    }

    /** Saves the results a few seconds later, keeping only names still in the channel list. */
    private fun saveMemo(raw: String, channels: List<LiveChannel>) {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3_000)
            val current = synchronized(memoLock) { if (memoFor == raw) memo else return@launch }
            val names = channels.mapTo(HashSet()) { it.name }
            runCatching {
                memoFile.parentFile?.mkdirs()
                val tmp = java.io.File(memoFile.parentFile, memoFile.name + ".tmp")
                tmp.bufferedWriter().use { w ->
                    w.write(memoHeader(raw)); w.newLine()
                    for ((name, cleaned) in current) {
                        if (name.substringAfter('\u0001') !in names || '\n' in name || '\t' in name || '\n' in cleaned) continue
                        w.write(name); w.write("\t"); w.write(cleaned); w.newLine()
                    }
                }
                if (!tmp.renameTo(memoFile)) { memoFile.delete(); tmp.renameTo(memoFile) }
            }
        }
    }

    /** "playlist|tvg-id" from a channel key, or null when the channel has no tvg-id. */
    private fun stableId(key: String): String? {
        val parts = key.split('|', limit = 3)
        if (parts.size < 3 || parts[1].isBlank()) return null
        return parts[0] + "|" + parts[1]
    }

    /**
     * Logos picked with "Change Logo", over everything else. Like renames, kept by the channel's
     * key and also by playlist + tvg-id, so a provider renaming "ESPN HD" to "ESPN FHD" keeps it.
     */
    private fun withPickedLogos(channels: List<LiveChannel>, picked: Map<String, String>): List<LiveChannel> {
        if (picked.isEmpty()) return channels
        val current = channels.mapTo(HashSet()) { it.key }
        val byId = stableIndex(picked.filterKeys { it !in current })
        return channels.map { c ->
            val logo = picked[c.key] ?: stableId(c.key)?.let { byId[it] }
            if (logo == null || logo == c.logo) c else c.copy(logo = logo)
        }
    }

    private fun <T> stableIndex(byKey: Map<String, T>): Map<String, T> {
        val out = HashMap<String, T>()
        byKey.forEach { (k, v) -> stableId(k)?.let { out.putIfAbsent(it, v) } }
        return out
    }
}
