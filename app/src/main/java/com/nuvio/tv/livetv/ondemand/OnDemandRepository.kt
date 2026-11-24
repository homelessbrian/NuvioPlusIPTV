package com.nuvio.tv.livetv.ondemand

import android.content.Context
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.nuvio.tv.livetv.data.ForkHttp

/** What the provider reports about the file itself (from its own media scan). */
data class VodTech(
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val fps: Double?,
    val hdr: String?,
    val audioCodec: String?,
    val channels: Int?,
    val language: String?,
    val bitrateKbps: Int?,
    val durationSecs: Int?
) {
    /** "4K", "1080p", "720p" or "SD". */
    val quality: String?
        get() {
            val h = height ?: return null
            val w = width ?: 0
            return when {
                h >= 1600 || w >= 3200 -> "4K"
                h >= 1000 || w >= 1800 -> "1080p"
                h >= 700 || w >= 1200 -> "720p"
                h > 0 -> "SD"
                else -> null
            }
        }

    /** Size worked out from bitrate × length (providers rarely give the file size). */
    val estimatedBytes: Long?
        get() {
            val br = bitrateKbps ?: return null
            val d = durationSecs ?: return null
            if (br <= 0 || d <= 0) return null
            return br.toLong() * 1000L / 8L * d
        }
}

/** One episode of a provider series, ready to play. */
data class VodEpisode(
    val season: Int,
    val episode: Int,
    val id: String,
    val ext: String,
    val title: String,
    val plot: String?,
    val image: String?,
    val url: String,
    val tech: VodTech? = null,
    val stalkerRef: com.nuvio.stalker.StalkerStreamRef? = null,
    val playlistId: String = ""
)

/** What the provider says about a movie or series (for the fallback detail screen). */
data class VodInfo(
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val releaseDate: String?,
    val durationText: String?,
    val episodes: List<VodEpisode> = emptyList(),
    val tech: VodTech? = null
)

/** [silent]: a routine re-import of a catalog that's already there (no bubble on screen for it). */
data class OnDemandStatus(val loading: Boolean = false, val message: String? = null, val silent: Boolean = false)

/**
 * Imports movies and series from Xtream logins that have "Import movies & series" on, keeps them
 * in [OnDemandDatabase], and turns them into playable links, including the "Watch On Demand"
 * streams that appear on Nuvio's own movie and episode screens.
 */
@Singleton
class OnDemandRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: OnDemandDatabase,
    private val prefs: LiveTvPreferences,
    private val stalker: com.nuvio.tv.livetv.stalker.StalkerGateway,
    private val languages: OnDemandLanguages
) {
    private fun text(@StringRes id: Int, vararg args: Any): String = context.withAppLocale().getString(id, *args)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http = ForkHttp.builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        // Big catalogs (100,000+ titles) on slow panels can take a long time to send.
        .callTimeout(40, TimeUnit.MINUTES)
        .dns(com.nuvio.tv.livetv.data.ForkDns.provider)
        .build()

    /**
     * One title's details (a movie's file info, a series' episode list): small answers, so a
     * provider that stalls is given up on in seconds instead of minutes (the catalog limits above
     * left a stream list waiting up to two minutes per title).
     */
    private val infoHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _hasContent = MutableStateFlow(false)
    /** True when at least one provider's movies or series are imported (shows the menu item). */
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    /**
     * Whether any movies or series are imported, looked up right now when the start-up check
     * hasn't run yet (it waits behind guide and playlist updates, and a stream list asked for
     * before then used to show none of your provider's copies).
     */
    suspend fun contentReady(): Boolean {
        if (_hasContent.value) return true
        val has = withContext(Dispatchers.IO) { runCatching { db.hasAny() }.getOrDefault(false) }
        if (has) _hasContent.value = true
        return has
    }

    private val _version = MutableStateFlow(0)
    /** Goes up after every import, so screens reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _genreUpdates = MutableStateFlow(0)
    /** Goes up as background genre lookups add genres (the genre list refreshes, nothing else). */
    val genreUpdates: StateFlow<Int> = _genreUpdates.asStateFlow()

    private val _genreProgress = MutableStateFlow<String?>(null)
    /** "Adding genres… 12,400 to go", shown in settings while it runs. */
    val genreProgress: StateFlow<String?> = _genreProgress.asStateFlow()

    private var genreJob: kotlinx.coroutines.Job? = null

    private val _status = MutableStateFlow(OnDemandStatus())
    val status: StateFlow<OnDemandStatus> = _status.asStateFlow()

    private val infoCache = com.nuvio.tv.livetv.data.boundedCache<String, VodInfo>(300)
    /** The last import problem, shown in settings (cleared by a clean import). */
    @Volatile private var lastProblem: String? = null

    /** Each login's own last problem (by playlist id), so one login's success or failure doesn't hide another's. */
    private val problemByPlaylist = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            kotlinx.coroutines.flow.combine(hasContent, prefs.settings) { has, s -> has && s.onDemandInStreams && s.onDemandEnabled }
                .collect { OnDemandStreams.offeredInStreams = it }
        }
        // "Hide non-English titles": switched on, titles labelled as another language are set
        // aside at once, and the rest are checked in the background; either way, lists reload.
        scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            var first = true
            prefs.settings.map { it.vodHideNonEnglish }.distinctUntilChanged().collect { on ->
                hideForeign = on
                if (first) {
                    // At start-up lists already leave marked titles out; the background check
                    // waits until the app has settled.
                    first = false
                    if (on) { kotlinx.coroutines.delay(START_DELAY_MS); startLanguageCheck() }
                    return@collect
                }
                if (on) {
                    runCatching { db.markForeignAll() }
                    startLanguageCheck()
                } else languages.stopSweep()
                _version.value++
            }
        }
        scope.launch {
            // Auto-play may pick your provider's copy only when that's on and its tab is first.
            prefs.settings.collect { s ->
                OnDemandStreams.autoPlayAllowed = s.onDemandAutoPlay && s.onDemandStreamsPosition == "FIRST"
                OnDemandStreams.tabPosition = s.onDemandStreamsPosition
            }
        }
        // Start-up upkeep and the automatic catalog check: low priority, out of the screen's way.
        scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            // A failed check (database busy) keeps the current answer instead of hiding On Demand.
            _hasContent.value = runCatching { db.hasAny() }.getOrDefault(_hasContent.value)
            // The heavier work below waits a little, so it never competes with Nuvio's own
            // start-up (Continue watching looks up each title's details with a short time limit;
            // a busy box made those lookups miss, leaving the home hero without year, genres
            // and description).
            kotlinx.coroutines.delay(START_DELAY_MS)
            // Release years for titles imported before years were read from their names.
            runCatching { db.fillMissingYears() }
            // Space freed since last time (a playlist removed, smaller catalogs) back to the box.
            com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            runCatching { db.compact() }
            // Learn each provider's title tags (for catalogs imported before this existed).
            runCatching {
                db.loadPrefixes()
                prefs.playlists.first().filter { it.hasVod && it.importVod }.forEach { pl ->
                    if (!db.hasLearnedPrefixes(pl.id)) db.learnPrefixes(pl.id)
                }
                _version.value++
            }
            refresh(force = false)
        }
        // A login with movies and series that arrives or is switched on later (from Google Drive
        // sync or a restore, which used to leave On Demand empty until "Update now" or a restart):
        // import it straight away. One that's gone is dropped the same way.
        scope.launch {
            prefs.playlists
                .map { list -> list.filter { it.enabled && it.hasVod && it.importVod }.map { it.id to if (it.isStalker) it.stalkerConfig else null }.toSet() }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshSoon() }
        }
    }

    /** Imports new providers and drops ones that were turned off (no re-download of the rest). */
    fun refreshSoon() {
        scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) { kotlinx.coroutines.delay(1_500); refresh(force = false) }
    }

    fun refreshNow() {
        scope.launch { refresh(force = true) }
    }

    /** "Update now" on one login: re-imports just that provider's movies and series. */
    fun refreshNow(playlistId: String) {
        scope.launch { kotlinx.coroutines.delay(500); refresh(force = true, onlyId = playlistId) }
    }

    /**
     * Imports providers that are due (or all, when [force]); drops ones that were turned off.
     * With [onlyId], only that provider is imported (the others are kept as they are).
     */
    suspend fun refresh(force: Boolean, onlyId: String? = null) = mutex.withLock {
        val playlists = prefs.playlists.first().filter { it.enabled && it.hasVod && it.importVod }
        if (force) infoCache.clear()
        val keep = playlists.map { it.id }.toSet()
        val dropped = runCatching { db.removeExcept(keep) }.getOrDefault(emptySet())
        // A provider switched off forgets when it was last imported, so switching it back on
        // imports it again straight away (it used to wait up to a day, with On Demand gone from
        // the menu in the meantime).
        dropped.forEach { prefs.setOnDemandImportTime(it, 0L) }
        val stamps = prefs.onDemandImportTimes()
        val now = System.currentTimeMillis()
        for (pl in playlists) {
            if (onlyId != null && pl.id != onlyId) continue
            val last = stamps[pl.id] ?: 0L
            if (!force && now - last < REFRESH_MS) continue
            // Automatic imports wait while a movie or episode is playing in Nuvio's player, and
            // while the guide is being built or Sports is finding channels.
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitNoHeavy()
            // The daily re-import of a catalog that's already there happens without a bubble
            // (people took it for the app reloading everything); a first import still shows it.
            quietImport = !force && last > 0L
            _status.value = OnDemandStatus(true, text(R.string.iptv_status_importing_movies_series, pl.name), silent = quietImport)
            lastProblem = null
            // Movies and series import separately, so a problem with one keeps the other.
            val movies = runCatching { importKind(pl, VodKind.MOVIE) }
                .onFailure { if (pl.isStalker) Log.w(TAG, "Movies for ${pl.name} failed: ${problemText(pl, it)}") else Log.w(TAG, "Movies for ${pl.name} failed", it); lastProblem = text(R.string.iptv_status_import_movies_failed, pl.name, problemText(pl, it)) }
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitIdle()
            if (!force) com.nuvio.tv.livetv.data.BackgroundWork.awaitNoHeavy()
            val series = runCatching { importKind(pl, VodKind.SERIES) }
                .onFailure { if (pl.isStalker) Log.w(TAG, "Series for ${pl.name} failed: ${problemText(pl, it)}") else Log.w(TAG, "Series for ${pl.name} failed", it); lastProblem = text(R.string.iptv_status_import_series_failed, pl.name, problemText(pl, it)) }
            val ok = movies.isSuccess && series.isSuccess && lastProblem == null
            lastProblem?.let { problemByPlaylist[pl.id] = it } ?: problemByPlaylist.remove(pl.id)
            // Success: next import in a day. Failure: try again in an hour, not on every start.
            prefs.setOnDemandImportTime(
                pl.id,
                if (ok) System.currentTimeMillis() else System.currentTimeMillis() - REFRESH_MS + 60 * 60 * 1000L
            )
            runCatching { db.learnPrefixes(pl.id) }
            // Titles in another language, marked again (an import writes changed titles afresh).
            runCatching { db.markForeign(pl.id) }
            _version.value++
        }
        if (hideForeign) startLanguageCheck()
        _hasContent.value = runCatching { db.hasAny() }.getOrDefault(_hasContent.value)
        // A problem stays visible in Settings → Live TV → On Demand (no more silent failures).
        // Every login's problem, not only the last one imported.
        problemByPlaylist.keys.retainAll(keep)
        quietImport = false
        _status.value = OnDemandStatus(false, problemByPlaylist.values.joinToString("\n").ifBlank { null })
    }

    /** The import running now is a routine re-import (see [OnDemandStatus.silent]). */
    @Volatile private var quietImport = false

    /**
     * No longer used: nothing in On Demand shows genres any more, so they're not looked up
     * (this used to run after every import, for every title). Kept for reference only.
     *
     * Fills in genres in the background from TMDB, using the TMDB
     * id most providers include for each title. Newest titles first, about 20 a second (well
     * within TMDB's limits), and each title only once. Runs while the app is open; picks up
     * where it left off next time.
     */
    fun startGenreFill() {
        if (genreJob?.isActive == true) return
        val key = com.nuvio.tv.BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return
        // Low priority: it runs for a long time in the background, and mustn't slow the screen.
        genreJob = scope.launch(com.nuvio.tv.livetv.data.BackgroundWork.quiet) {
            var added = 0
            var shownOnce = false
            while (true) {
                val batch = runCatching { db.needingGenres(40) }.getOrDefault(emptyList())
                if (batch.isEmpty()) break
                kotlinx.coroutines.coroutineScope {
                    batch.chunked(10).map { part ->
                        async {
                            for (item in part) {
                                val genres = tmdbGenres(item, key)
                                runCatching { db.setGenre(item, genres.orEmpty()) }
                                if (!genres.isNullOrBlank()) added++
                                kotlinx.coroutines.delay(200) // 4 at a time × 5/s ≈ 20 a second
                            }
                        }
                    }.forEach { it.await() }
                }
                val left = runCatching { db.countNeedingGenres() }.getOrDefault(0)
                _genreProgress.value = if (left > 0) "Adding genres… ${"%,d".format(left)} to go" else null
                // Show the first genres soon after you switch to By genre, then every few hundred.
                if (added >= if (shownOnce) 400 else 40) { _genreUpdates.value++; added = 0; shownOnce = true }
            }
            _genreProgress.value = null
            _genreUpdates.value++
        }
    }

    /** "Action, Comedy" from TMDB for one title, "" if it has none, null if TMDB didn't answer. */
    private fun tmdbGenres(item: VodItem, key: String): String? {
        val id = item.tmdbId?.toIntOrNull() ?: return ""
        val path = if (item.kind == VodKind.SERIES) "tv" else "movie"
        return runCatching {
            val text = getText("https://api.themoviedb.org/3/$path/$id?api_key=$key&language=en-US", "")
            val arr = JSONObject(text).optJSONArray("genres") ?: return@runCatching ""
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                .joinToString(", ")
        }.getOrElse { e ->
            // 404: TMDB doesn't know this id, so don't try again. Anything else: try next time.
            if (e.message?.contains("404") == true) "" else null
        }
    }

    private suspend fun importKind(pl: PlaylistSource, kind: VodKind) {
        val t0 = System.currentTimeMillis()
        try {
            when {
                pl.isStalker -> importStalkerKind(pl, kind)
                pl.hasCatalog -> importKindInner(pl, kind)
                else -> importM3uSource(pl, kind)
            }
        } finally {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "On Demand \"${pl.name}\": ${if (kind == VodKind.MOVIE) "movies" else "series"} imported in ${(System.currentTimeMillis() - t0) / 1000} s"
            )
        }
    }

    /**
     * An M3U playlist's movies or series. With a login the provider accepts, they come from the
     * provider like an Xtream login's (fuller details); otherwise, or when that doesn't work,
     * from the playlist's own movie and episode entries.
     */
    private suspend fun importM3uSource(pl: PlaylistSource, kind: VodKind) {
        if (pl.m3uLogin != null) {
            val fromProvider = runCatching { importKindInner(pl.withVodLogin(), kind) }
            val got = runCatching { db.count(pl.id, kind) }.getOrDefault(0)
            if (fromProvider.isSuccess && got > 0) {
                if (kind == VodKind.SERIES) runCatching { db.clearM3uEpisodes(pl.id) }
                return
            }
            if (!pl.vodInPlaylist) fromProvider.getOrThrow()
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
                "On Demand \"${pl.name}\": the provider gave no ${if (kind == VodKind.MOVIE) "movies" else "series"}" +
                    (fromProvider.exceptionOrNull()?.let { " (${it.message ?: it.javaClass.simpleName})" } ?: "") +
                    "; reading them from the playlist"
            )
        }
        importFromPlaylist(pl, kind)
    }

    /** A movie or episode entry in an M3U playlist. */
    private class M3uVodEntry(val name: String, val logo: String?, val group: String, val url: String)

    /** The playlist as saved by Live TV (zipped or not). */
    private fun playlistFile(pl: PlaylistSource) = java.io.File(java.io.File(context.filesDir, "livetv/playlists"), "${pl.id}.m3u")

    /** The playlist's movie and episode entries, read one at a time (never all in memory). */
    private fun m3uVodEntries(file: java.io.File): Sequence<M3uVodEntry> = sequence {
        val raw = java.io.BufferedInputStream(file.inputStream(), 64 * 1024)
        raw.mark(2)
        val zipped = raw.read() == 0x1f && raw.read() == 0x8b
        raw.reset()
        val input = if (zipped) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
        input.reader(Charsets.UTF_8).buffered(64 * 1024).use { reader ->
            var info: String? = null
            var extGroup: String? = null
            while (true) {
                val line = reader.readLine()?.trim()?.removePrefix("\uFEFF") ?: break
                when {
                    line.isEmpty() -> {}
                    line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; extGroup = null }
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim().ifEmpty { null }
                    line.startsWith("#") -> {}
                    else -> {
                        val url = line.substringBefore('|')
                        val inf = info
                        info = null
                        if (inf != null && com.nuvio.tv.livetv.data.LiveTvRepository.isVodEntry(url)) {
                            val attrs = com.nuvio.tv.livetv.data.M3uParser.attributes(inf)
                            val name = com.nuvio.tv.livetv.data.M3uParser.displayName(inf).ifBlank { attrs["tvg-name"].orEmpty() }.trim()
                            if (name.isNotEmpty()) yield(
                                M3uVodEntry(
                                    name = name,
                                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) },
                                    group = (attrs["group-title"] ?: extGroup)?.trim()?.ifEmpty { null } ?: "Uncategorized",
                                    url = url
                                )
                            )
                        }
                        extGroup = null
                    }
                }
            }
        }
    }

    /** An episode entry: its show, season and episode ("Show S01E02", "Show 1x02", or a /series/ link). */
    private class EpisodeName(val show: String, val season: Int, val episode: Int?)

    private fun episodeName(e: M3uVodEntry): EpisodeName? {
        // Xtream-style links say which it is: /movie/ is always a movie (even with "S01E01" in
        // its name); "1x02" only counts on a /series/ link ("10x10" is a film's name).
        if (MOVIE_LINK.containsMatchIn(e.url)) return null
        val seriesLink = SERIES_LINK.containsMatchIn(e.url)
        val m = EPISODE_TAG.find(e.name) ?: if (seriesLink) EPISODE_X_TAG.find(e.name) else null
        if (m != null) {
            val show = e.name.substring(0, m.range.first).trim().trimEnd('-', '|', ':', '.', '_', '(', '[').trim()
            return EpisodeName(show.ifEmpty { e.name }, m.groupValues[1].toIntOrNull() ?: 1, m.groupValues[2].toIntOrNull())
        }
        if (seriesLink) return EpisodeName(e.name, 1, null)
        return null
    }

    /**
     * Movies or series from the playlist's own entries: categories from their groups, posters
     * from their logos, and episodes grouped into series and seasons by their names.
     */
    private fun importFromPlaylist(pl: PlaylistSource, kind: VodKind) {
        val file = playlistFile(pl)
        if (!file.exists()) error("the playlist hasn't been downloaded yet")
        val what = if (kind == VodKind.MOVIE) "movies" else "series"
        _status.value = OnDemandStatus(true, text(if (kind == VodKind.MOVIE) R.string.iptv_status_importing_movies else R.string.iptv_status_importing_series, pl.name), silent = quietImport)
        var n = 0
        fun progress() {
            if (++n % 500 == 0) _status.value = OnDemandStatus(true, text(if (kind == VodKind.MOVIE) R.string.iptv_status_importing_movies_count else R.string.iptv_status_importing_series_count, pl.name, n), silent = quietImport)
        }
        if (kind == VodKind.MOVIE) {
            // Categories first (the database writes them before the titles), then the titles.
            val categories = LinkedHashSet<String>()
            m3uVodEntries(file).forEach { if (episodeName(it) == null) categories += it.group }
            val saved = db.replace(pl.id, kind, categories.map { it to it }, m3uVodEntries(file).filter { episodeName(it) == null }.map { e ->
                progress()
                VodItem(
                    pl.id, kind, M3U_ID + e.url, e.name, e.logo, e.group,
                    M3U_EXT.find(e.url)?.groupValues?.get(1)?.lowercase(), null,
                    OnDemandDatabase.releaseYearInName(e.name), null, 0L
                )
            })
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("On Demand \"${pl.name}\": ${"%,d".format(saved)} $what from the playlist, ${categories.size} categories")
            return
        }
        // Series: the episodes are written while reading; each show is kept once (its first
        // poster), then the shows are saved.
        val shows = LinkedHashMap<String, VodItem>()
        val categories = LinkedHashSet<String>()
        val nextEpisode = HashMap<String, Int>()
        val episodes = db.replaceM3uEpisodes(pl.id, m3uVodEntries(file).mapNotNull { e ->
            val ep = episodeName(e) ?: return@mapNotNull null
            val key = OnDemandDatabase.normalize(ep.show).ifEmpty { ep.show.lowercase() } + "|" + e.group
            val id = M3U_ID + key
            if (id !in shows) {
                progress()
                categories += e.group
                shows[id] = VodItem(pl.id, kind, id, ep.show, e.logo, e.group, null, null, OnDemandDatabase.releaseYearInName(ep.show), null, 0L)
            }
            val seasonKey = "$id#${ep.season}"
            val number = ep.episode ?: ((nextEpisode[seasonKey] ?: 0) + 1)
            nextEpisode[seasonKey] = maxOf(nextEpisode[seasonKey] ?: 0, number)
            M3uEpisodeRow(id, ep.season, number, e.name, e.url, e.logo)
        })
        val saved = db.replace(pl.id, kind, categories.map { it to it }, shows.values.asSequence())
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "On Demand \"${pl.name}\": ${"%,d".format(saved)} $what (${"%,d".format(episodes)} episodes) from the playlist, ${categories.size} categories"
        )
    }

    /** A title read from an M3U playlist's own entries: its details are all in the playlist. */
    private fun m3uInfo(item: VodItem, pl: PlaylistSource): VodInfo {
        if (item.kind == VodKind.MOVIE) return VodInfo(plot = null, cover = item.icon, backdrop = null, genre = null, releaseDate = item.year?.toString(), durationText = null)
        val episodes = runCatching { db.m3uEpisodes(pl.id, item.id) }.getOrDefault(emptyList()).map { r ->
            VodEpisode(
                season = r.season, episode = r.episode,
                id = STREAM_ID.find(r.url)?.groupValues?.get(1) ?: r.url.hashCode().toUInt().toString(),
                ext = M3U_EXT.find(r.url)?.groupValues?.get(1)?.lowercase() ?: "mp4",
                title = r.title, plot = null, image = r.image, url = r.url, playlistId = pl.id
            )
        }
        return VodInfo(plot = null, cover = item.icon, backdrop = null, genre = null, releaseDate = item.year?.toString(), durationText = null, episodes = episodes)
    }

    private suspend fun ensureCurrentStalker(source: PlaylistSource) {
        stalker.assertCurrent(source)
    }

    private suspend fun importStalkerKind(pl: PlaylistSource, kind: VodKind) {
        val client = stalker.client(pl)
        val identity = com.nuvio.tv.livetv.stalker.StalkerGateway.identity(pl)
        // Fetch every page successfully before replacing anything; a truncated import keeps the old catalogue.
        if (kind == VodKind.MOVIE) {
            val categories = client.vodCategories().associate { it.id to it.name }.toMutableMap()
            val movies = client.movies()
            movies.forEach { if (!it.isSeries) categories.putIfAbsent(it.categoryId.orEmpty(), "Uncategorized") }
            ensureCurrentStalker(pl)
            prefs.withCurrentPlaylist(pl) { db.replace(pl.id, kind, categories.toList(), movies.asSequence().filterNot { it.isSeries }.map { movie ->
                VodItem(pl.id, kind, movie.id, movie.title, movie.poster, movie.categoryId.orEmpty(),
                    null, null, movie.year?.take(4)?.toIntOrNull(), movie.rating, 0L, genre = movie.genre,
                    stalkerRef = movie.stream.copy(sourceIdentity = identity), providerPlot = movie.plot, providerDurationSeconds = movie.durationSeconds)
            }) }
        } else {
            val nativeCategories = try { client.seriesCategories() } catch (e: com.nuvio.stalker.StalkerException) {
                if (e.error == com.nuvio.stalker.StalkerError.UNSUPPORTED) emptyList() else throw e
            }
            val vodCategories = try { client.vodCategories() } catch (e: com.nuvio.stalker.StalkerException) {
                if (e.error == com.nuvio.stalker.StalkerError.UNSUPPORTED) emptyList() else throw e
            }
            val categories = (nativeCategories.map { "CLASSIC:${it.id}" to it.name } + vodCategories.map { "VOD:${it.id}" to it.name }).toMap().toMutableMap()
            val series = client.series()
            series.forEach { categories.putIfAbsent("${it.variant.name}:${it.categoryId.orEmpty()}", "Uncategorized") }
            ensureCurrentStalker(pl)
            prefs.withCurrentPlaylist(pl) { db.replace(pl.id, kind, categories.toList(), series.asSequence().map { show ->
                VodItem(pl.id, kind, "${show.variant.name}:${show.id}", show.title, show.poster, "${show.variant.name}:${show.categoryId.orEmpty()}",
                    null, null, show.year?.take(4)?.toIntOrNull(), show.rating, 0L, genre = show.genre,
                    stalkerRef = show.stream.copy(sourceIdentity = identity), stalkerSeriesVariant = show.variant.name, providerPlot = show.plot)
            }) }
        }
    }

    private fun importKindInner(pl: PlaylistSource, kind: VodKind) {
        val api = pl.xtreamApiUrl()
        val catAction = if (kind == VodKind.MOVIE) "get_vod_categories" else "get_series_categories"
        val listAction = if (kind == VodKind.MOVIE) "get_vod_streams" else "get_series"
        val what = if (kind == VodKind.MOVIE) "movies" else "series"
        val categories = ArrayList<Pair<String, String>>()
        runCatching {
            val arr = org.json.JSONArray(getText("$api&action=$catAction", pl.userAgent))
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                categories += c.optString("category_id") to c.optString("category_name").ifBlank { "Other" }
            }
        }
        _status.value = OnDemandStatus(true, text(if (kind == VodKind.MOVIE) R.string.iptv_status_importing_movies else R.string.iptv_status_importing_series, pl.name), silent = quietImport)
        var n = 0
        fun progress() {
            if (++n % 500 == 0) _status.value = OnDemandStatus(true, text(if (kind == VodKind.MOVIE) R.string.iptv_status_importing_movies_count else R.string.iptv_status_importing_series_count, pl.name, n), silent = quietImport)
        }

        // 1. The whole list in one go (fastest).
        var stoppedEarly: Throwable? = null
        val saved = db.replace(
            pl.id, kind, categories,
            streamItems(pl, kind, "$api&action=$listAction", onStop = { stoppedEarly = it }, onItem = ::progress)
        )
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(
            "On Demand \"${pl.name}\": ${"%,d".format(saved)} $what, ${categories.size} categories; " +
                "${"%,d".format(db.lastChanges.first)} new or changed, ${"%,d".format(db.lastChanges.second)} removed"
        )
        val firstProblem = stoppedEarly ?: return

        // 2. Some panels cut off huge lists part-way (an "EOFException" partway through).
        //    Ask again one category at a time: many small lists instead of one huge one.
        if (categories.isEmpty()) {
            reportStop(pl, kind, saved, firstProblem)
            if (saved == 0) throw firstProblem
            return
        }
        Log.w(TAG, "${pl.name}: $what list stopped after $saved; importing by category", firstProblem)
        n = 0
        _status.value = OnDemandStatus(true, text(if (kind == VodKind.MOVIE) R.string.iptv_status_importing_movies_by_category else R.string.iptv_status_importing_series_by_category, pl.name), silent = quietImport)
        var failedCategories = 0
        val byCategory = sequence {
            for ((catId, _) in categories) {
                var problem: Throwable? = null
                yieldAll(
                    streamItems(
                        pl, kind, "$api&action=$listAction&category_id=${enc(catId)}",
                        onStop = { problem = it }, onItem = ::progress
                    )
                )
                if (problem != null) failedCategories++
                // "Too many requests": stop here instead of asking for every remaining category
                // (that kept the provider refusing, including when you then pressed play).
                if (problem?.message?.contains("429") == true) break
            }
        }
        val total = db.replace(pl.id, kind, categories, byCategory)
        if (total < saved) {
            // Category by category did worse: go back to what the full list gave.
            db.replace(pl.id, kind, categories, streamItems(pl, kind, "$api&action=$listAction", onStop = {}, onItem = {}))
            reportStop(pl, kind, saved, firstProblem)
        } else if (failedCategories > 0) {
            lastProblem = text(
                if (kind == VodKind.MOVIE) R.string.iptv_status_import_movies_incomplete else R.string.iptv_status_import_series_incomplete,
                pl.name, total, failedCategories
            )
        }
    }

    private fun reportStop(pl: PlaylistSource, kind: VodKind, saved: Int, e: Throwable) {
        lastProblem = text(
            if (kind == VodKind.MOVIE) R.string.iptv_status_import_movies_stopped else R.string.iptv_status_import_series_stopped,
            pl.name, saved, problemText(pl, e)
        )
    }

    /** A Stalker portal's errors never show account details (see StalkerGateway.safeUpdateError). */
    private fun problemText(pl: PlaylistSource, e: Throwable): String =
        if (pl.isStalker) com.nuvio.tv.livetv.stalker.StalkerGateway.safeUpdateError(e) else e.message ?: e.javaClass.simpleName

    /**
     * Streams one provider list straight into the database, a title at a time. If the provider
     * stops part-way, [onStop] is told and everything received so far is still yielded.
     */
    private fun streamItems(
        pl: PlaylistSource,
        kind: VodKind,
        url: String,
        onStop: (Throwable) -> Unit,
        onItem: () -> Unit
    ): Sequence<VodItem> = sequence {
        val request = Request.Builder().url(url)
            .header("User-Agent", pl.userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            onStop(e); return@sequence
        }
        response.use { r ->
            if (!r.isSuccessful) { onStop(IllegalStateException("HTTP ${r.code}")); return@sequence }
            val body = r.body ?: run { onStop(IllegalStateException("Empty response")); return@sequence }
            JsonReader(body.charStream()).use { reader ->
                // Panels don't always send perfectly valid JSON; be forgiving.
                reader.isLenient = true
                try {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return@sequence }
                    reader.beginArray()
                } catch (e: Exception) {
                    onStop(e); return@sequence
                }
                while (true) {
                    val f = try {
                        if (!reader.hasNext()) break
                        readFlat(reader)
                    } catch (e: Exception) {
                        onStop(e)
                        break
                    }
                    val id = (if (kind == VodKind.MOVIE) f["stream_id"] else f["series_id"]) ?: continue
                    val name = f["name"]?.trim().orEmpty().ifBlank { f["title"].orEmpty() }
                    if (name.isBlank()) continue
                    yield(
                        VodItem(
                            playlistId = pl.id,
                            kind = kind,
                            id = id,
                            name = name,
                            icon = (if (kind == VodKind.MOVIE) f["stream_icon"] else f["cover"])?.takeIf { it.startsWith("http") },
                            categoryId = f["category_id"].orEmpty(),
                            ext = f["container_extension"]?.takeIf { it.isNotBlank() },
                            tmdbId = (f["tmdb"] ?: f["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
                            // The first of these that has a year: providers often send an empty
                            // year field, which used to stop it trying the others (and the name).
                            year = listOf(f["year"], f["releaseDate"], f["release_date"]).firstNotNullOfOrNull { yearOf(it) }
                                ?.takeIf { it <= OnDemandDatabase.latestYear() }
                                ?: OnDemandDatabase.releaseYearInName(name),
                            rating = f["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                            addedSec = (f["added"] ?: f["last_modified"])?.toLongOrNull() ?: 0L,
                            genre = f["genre"]?.trim()?.takeIf { it.isNotBlank() }
                        )
                    )
                    onItem()
                }
            }
        }
    }

    /** One JSON object's simple fields as strings (nested values are skipped). */
    private fun readFlat(reader: JsonReader): Map<String, String> {
        val f = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> f[name] = reader.nextString()
                JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return f
    }

    // ------------------------------------------------------------------ reading

    /** "Hide non-English titles" is on (kept up to date from the settings; read by every list). */
    @Volatile private var hideForeign: Boolean = false

    private suspend fun hidingForeign(): Boolean =
        runCatching { prefs.settings.first().vodHideNonEnglish }.getOrDefault(hideForeign).also { hideForeign = it }

    /** Works through titles not checked yet in the background; lists reload when some were set aside. */
    private fun startLanguageCheck() {
        languages.startSweep(enabled = { hideForeign }, onMarked = { _version.value++ })
    }

    /** What TMDB says about a title (artwork, details, original language): see [OnDemandLanguages.facts]. */
    suspend fun tmdbFacts(item: VodItem): OnDemandLanguages.Facts? = languages.facts(item)

    suspend fun categories(kind: VodKind): List<VodCategory> = withContext(Dispatchers.IO) {
        val hide = hidingForeign()
        runCatching { db.categories(kind, hide) }.getOrDefault(emptyList())
    }

    /**
     * A page of titles. With "Hide non-English titles" on, titles in another language are never
     * in it: those already known are left out by the catalog itself, and titles on the page not
     * checked yet are checked first (a short wait, once per title) and the page read again
     * without the ones that turned out to be in another language.
     */
    suspend fun items(kind: VodKind, category: VodCategory?, hidden: Set<String>, newestFirst: Boolean, limit: Int, offset: Int, sort: VodSort = VodSort.DEFAULT, genre: String? = null, merge: Boolean = true, seed: Long = 0) =
        withContext(Dispatchers.IO) {
            val hide = hidingForeign()
            fun read() = runCatching { db.items(kind, category, hidden, newestFirst, limit, offset, sort, genre, merge, seed, hide) }.getOrDefault(emptyList())
            var page = read()
            if (hide && page.isNotEmpty()) {
                // A page or two may come back with newly found titles to set aside.
                repeat(2) {
                    if (!languages.check(page, PAGE_CHECK_MS)) return@withContext page
                    page = read()
                }
            }
            page
        }

    /** One version of a title you can pick: where it's from and what it is. */
    data class Version(val item: VodItem, val label: String, val detail: String)

    /** Every copy of [item] (other qualities, other categories, other providers). */
    suspend fun versions(item: VodItem): List<Version> = withContext(Dispatchers.IO) {
        val all = runCatching { db.versions(item) }.getOrDefault(listOf(item)).ifEmpty { listOf(item) }
        val playlists = prefs.playlists.first().associateBy { it.id }
        val catNames = HashMap<String, Map<String, String>>()
        all.map { v ->
            val cats = catNames.getOrPut(v.playlistId) { runCatching { db.categoryNames(v.playlistId, v.kind) }.getOrDefault(emptyMap()) }
            val quality = qualityOf(v.name)
            val label = listOfNotNull(quality, v.ext?.uppercase()).joinToString(" · ").ifBlank { "Standard" }
            val detail = listOfNotNull(
                playlists[v.playlistId]?.name.takeIf { playlists.size > 1 },
                cats[v.categoryId],
                v.year?.toString()
            ).joinToString(" · ")
            Version(v, label, detail)
        }.sortedByDescending { qualityRank(it.label) }
    }

    private fun qualityOf(name: String): String? {
        val n = name.lowercase()
        return when {
            Regex("""\b(4k|uhd|2160p)\b""").containsMatchIn(n) -> "4K"
            Regex("""\b(fhd|1080p)\b""").containsMatchIn(n) -> "Full HD"
            Regex("""\b(hd|720p)\b""").containsMatchIn(n) -> "HD"
            Regex("""\b(sd|480p)\b""").containsMatchIn(n) -> "SD"
            else -> null
        }?.let { q -> if (Regex("""\b(hevc|x265|h265)\b""").containsMatchIn(n)) "$q HEVC" else q }
    }

    private fun qualityRank(label: String) = when {
        label.startsWith("4K") -> 4
        label.startsWith("Full HD") -> 3
        label.startsWith("HD") -> 2
        label.startsWith("SD") -> 1
        else -> 0
    }

    /** Genres from your addons' metadata, saved for titles the provider sent none for. */
    suspend fun fillGenres(item: VodItem, genres: List<String>) = withContext(Dispatchers.IO) {
        val text = genres.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        if (text.isNotBlank() && item.genre.isNullOrBlank()) runCatching { db.fillGenre(item, text) }
        Unit
    }

    suspend fun genres(kind: VodKind): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching { db.genres(kind) }.getOrDefault(emptyList())
    }

    /** A search that stops as soon as it isn't wanted any more (typing on, leaving the screen). */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun search(kind: VodKind, text: String, hidden: Set<String>, sort: VodSort = VodSort.DEFAULT, merge: Boolean = true): List<VodItem> = withContext(Dispatchers.IO) {
        val signal = android.os.CancellationSignal()
        val stop = coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) signal.cancel() }
        try {
            val hide = hidingForeign()
            val found = runCatching { db.search(kind, text, hidden, sort = sort, merge = merge, signal = signal, hideForeign = hide) }.getOrDefault(emptyList())
            if (hide && found.isNotEmpty() && languages.check(found.take(SEARCH_CHECK), PAGE_CHECK_MS)) {
                runCatching { db.search(kind, text, hidden, sort = sort, merge = merge, signal = signal, hideForeign = true) }.getOrDefault(found)
            } else found
        } finally {
            stop?.dispose()
        }
    }

    private suspend fun playlist(id: String): PlaylistSource? = prefs.playlists.first().firstOrNull { it.id == id }?.withVodLogin()

    /**
     * Adult titles (by their own name or their category's name). These only ever play straight
     * from your provider, so they never land in Continue watching, the library or your watched list.
     */
    suspend fun isAdult(item: VodItem): Boolean {
        if (com.nuvio.tv.livetv.parental.ParentalControls.isAdult(item.name)) return true
        val cat = withContext(Dispatchers.IO) {
            runCatching { db.categoryNames(item.playlistId, item.kind)[item.categoryId] }.getOrNull()
        }
        return cat != null && com.nuvio.tv.livetv.parental.ParentalControls.isAdult(cat)
    }

    /** A title by its uid ("movie:<playlist>:<id>"). */
    suspend fun itemByUid(uid: String): VodItem? = withContext(Dispatchers.IO) {
        val parts = uid.split(':', limit = 3)
        if (parts.size != 3) return@withContext null
        val kind = if (parts[0] == VodKind.SERIES.key) VodKind.SERIES else VodKind.MOVIE
        runCatching { db.item(parts[1], kind, parts[2]) }.getOrNull()
    }

    /** Movie link: /movie/user/pass/<id>.<ext> */
    suspend fun movieUrl(item: VodItem): String? {
        val pl = playlist(item.playlistId) ?: return null
        if (pl.isStalker) return item.stalkerRef?.let { com.nuvio.stalker.StalkerPlaybackLocator(pl.id, it).encode() }
        if (item.id.startsWith(M3U_ID)) return item.id.removePrefix(M3U_ID)
        return "${pl.xtreamBase()}/movie/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/${item.id}.${item.ext ?: "mp4"}"
    }

    suspend fun episodeUrl(episode: VodEpisode): String? =
        if (episode.stalkerRef != null) com.nuvio.stalker.StalkerPlaybackLocator(episode.playlistId, episode.stalkerRef).encode()
        else episode.url

    private suspend fun stalkerInfo(item: VodItem, pl: PlaylistSource): VodInfo {
        val episodes = ArrayList<VodEpisode>()
        val identity = com.nuvio.tv.livetv.stalker.StalkerGateway.identity(pl)
        if (item.kind == VodKind.SERIES) {
            val ref = item.stalkerRef ?: throw com.nuvio.stalker.StalkerException(com.nuvio.stalker.StalkerError.INVALID_RESPONSE)
            if (ref.sourceIdentity != identity)
                throw com.nuvio.stalker.StalkerException(com.nuvio.stalker.StalkerError.STALE_SESSION)
            val variant = com.nuvio.stalker.StalkerSeriesVariant.entries.firstOrNull { it.name == item.stalkerSeriesVariant }
                ?: com.nuvio.stalker.StalkerSeriesVariant.CLASSIC
            val show = com.nuvio.stalker.StalkerSeries(ref.providerId, item.name, ref, variant,
                categoryId = item.categoryId, poster = item.icon, plot = item.providerPlot)
            val client = stalker.client(pl)
            for ((seasonIndex, season) in client.seasons(show).withIndex()) {
                for ((episodeIndex, episode) in client.episodes(season).withIndex()) {
                    val episodeRef = episode.stream.copy(sourceIdentity = identity)
                    val number = episode.number ?: episodeIndex + 1
                    episodes += VodEpisode(season.number ?: episode.seasonNumber ?: seasonIndex + 1,
                        number, episode.id, "", episode.title.ifBlank { "Episode $number" }, episode.plot, episode.poster,
                        com.nuvio.stalker.StalkerPlaybackLocator(pl.id, episodeRef).encode(),
                        stalkerRef = episodeRef, playlistId = pl.id)
                }
            }
        }
        return VodInfo(item.providerPlot, item.icon, null, item.genre, item.year?.toString(),
            item.providerDurationSeconds?.let { "${it / 60} min" }, episodes.sortedWith(compareBy({ it.season }, { it.episode })))
    }

    /** Provider details for a movie or series (series include every episode). Cached. */
    suspend fun info(item: VodItem): VodInfo? = withContext(Dispatchers.IO) {
        val pl = playlist(item.playlistId) ?: return@withContext null
        val cacheKey = if (pl.isStalker) "${item.uid}:${com.nuvio.tv.livetv.stalker.StalkerGateway.identity(pl)}" else item.uid
        infoCache[cacheKey]?.let { return@withContext it }
        if (pl.isStalker) return@withContext stalkerInfo(item, pl).also { ensureCurrentStalker(pl); infoCache[cacheKey] = it }
        if (item.id.startsWith(M3U_ID)) return@withContext m3uInfo(item, pl).also { infoCache[cacheKey] = it }
        val api = pl.xtreamApiUrl()
        val action = if (item.kind == VodKind.MOVIE) "get_vod_info&vod_id=${item.id}" else "get_series_info&series_id=${item.id}"
        val json = runCatching { JSONObject(getText("$api&action=$action", pl.userAgent, infoHttp)) }.getOrNull() ?: return@withContext null
        val info = json.optJSONObject("info") ?: JSONObject()
        val episodes = ArrayList<VodEpisode>()
        json.optJSONObject("episodes")?.let { byseason ->
            byseason.keys().forEach { seasonKey ->
                val arr = byseason.optJSONArray(seasonKey) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.optString("id").ifBlank { continue }
                    val ext = e.optString("container_extension").ifBlank { "mp4" }
                    val season = e.optString("season").toIntOrNull() ?: seasonKey.toIntOrNull() ?: 1
                    val num = e.optString("episode_num").toIntOrNull() ?: (i + 1)
                    val einfo = e.optJSONObject("info")
                    episodes += VodEpisode(
                        season = season,
                        episode = num,
                        id = id,
                        ext = ext,
                        title = e.optString("title").ifBlank { "Episode $num" },
                        plot = einfo?.optString("plot")?.takeIf { it.isNotBlank() },
                        image = einfo?.optString("movie_image")?.takeIf { it.startsWith("http") },
                        url = "${pl.xtreamBase()}/series/${enc(pl.xtreamUsername)}/${enc(pl.xtreamPassword)}/$id.$ext",
                        tech = einfo?.let { parseTech(it) }
                    )
                }
            }
        }
        val result = VodInfo(
            plot = info.optString("plot").ifBlank { info.optString("description") }.takeIf { it.isNotBlank() },
            cover = (info.optString("movie_image").ifBlank { info.optString("cover") }).takeIf { it.startsWith("http") },
            backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.startsWith("http") },
            genre = info.optString("genre").takeIf { it.isNotBlank() },
            releaseDate = (info.optString("releasedate").ifBlank { info.optString("releaseDate") }).takeIf { it.isNotBlank() },
            durationText = info.optString("duration").takeIf { it.isNotBlank() },
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode })),
            tech = parseTech(info)
        )
        infoCache[item.uid] = result
        result
    }

    // ------------------------------------------------------------------ poster cache

    suspend fun cachedPoster(item: VodItem): Pair<String, Long>? = withContext(Dispatchers.IO) {
        runCatching { db.cachedPoster(item.uid) }.getOrNull()
    }

    suspend fun cachedPosters(items: List<VodItem>): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching { db.cachedPosters(items.map { it.uid }) }.getOrDefault(emptyMap())
    }

    suspend fun cachePoster(item: VodItem, poster: String?, title: String? = null) = withContext(Dispatchers.IO) {
        runCatching { db.cachePoster(item.uid, poster, title) }
        Unit
    }

    /** Saved matches for these titles (see [VodMatchJson]), by uid, with whether each is complete. */
    suspend fun savedMatches(items: List<VodItem>): Map<String, Pair<com.nuvio.tv.livetv.data.LiveTvPosterResolver.Hit, Boolean>> =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext emptyMap()
            val raw = runCatching { db.savedMatches(items.map { it.uid }) }.getOrDefault(emptyMap())
            raw.mapNotNull { (uid, v) -> VodMatchJson.decode(v.first)?.let { uid to (it to v.second) } }.toMap()
        }

    suspend fun saveMatch(item: VodItem, json: String, detailed: Boolean) = withContext(Dispatchers.IO) {
        runCatching { db.saveMatch(item.uid, json, detailed) }
        Unit
    }

    suspend fun cachedTitle(item: VodItem): String? = withContext(Dispatchers.IO) {
        runCatching { db.cachedTitle(item.uid) }.getOrNull()
    }

    /** The name (and year) typed for this title with "Fix poster", if any. */
    suspend fun posterFix(item: VodItem): Pair<String, Int?>? = withContext(Dispatchers.IO) {
        runCatching { db.posterFix(item.uid) }.getOrNull()
    }

    /** "Fix poster": the name (and year) to look this title up by; a blank name goes back to automatic. */
    suspend fun setPosterFix(item: VodItem, title: String, year: Int?) = withContext(Dispatchers.IO) {
        runCatching { db.setPosterFix(item.uid, title, year) }
        Unit
    }

    // ------------------------------------------------------------------ "Watch On Demand"

    /** A playable On Demand copy of something you opened in Nuvio. */
    data class OnDemandSource(
        val label: String,
        val url: String,
        val headers: Map<String, String>,
        val title: String = "",
        val year: Int? = null,
        val providerName: String = "",
        val ext: String? = null,
        val tech: VodTech? = null,
        val episodeTag: String? = null
    )

    /**
     * Finds [title] in your providers' catalogs: by TMDB id when both sides have one, otherwise
     * by name (and year when known). For series, [season]/[episode] pick the episode.
     */
    suspend fun sourcesFor(
        type: String,
        tmdbId: String?,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<OnDemandSource> = withContext(Dispatchers.IO) {
        val kind = when (type.lowercase()) {
            "movie" -> VodKind.MOVIE
            "series", "tv" -> VodKind.SERIES
            else -> return@withContext emptyList()
        }
        if (!contentReady()) return@withContext emptyList()
        val candidates = LinkedHashMap<String, VodItem>()
        tmdbId?.let { id -> runCatching { db.byTmdb(kind, id) }.getOrDefault(emptyList()).forEach { candidates[it.uid] = it } }
        if (title.isNotBlank()) {
            runCatching { db.byTitle(kind, title) }.getOrDefault(emptyList())
                .filter { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 }
                .forEach { candidates.putIfAbsent(it.uid, it) }
        }
        // Titles without an IMDb / TMDB match on either side (like "Big Brother UK 2023"):
        // a looser match on the core title, with the year deciding between versions.
        if (candidates.isEmpty() && title.isNotBlank()) {
            val core = OnDemandDatabase.coreKey(title)
            if (core.length >= 3) {
                val loose = runCatching { db.search(kind, OnDemandDatabase.displayTitle(title), emptySet(), limit = 80) }
                    .getOrDefault(emptyList())
                    .filter { OnDemandDatabase.coreKey(it.name) == core }
                val withYears = loose.map { it to (it.year ?: OnDemandDatabase.yearInTitle(it.name)) }
                val picked = when {
                    year == null -> withYears.map { it.first }
                    withYears.any { it.second != null && kotlin.math.abs(it.second!! - year) <= 1 } ->
                        withYears.filter { it.second != null && kotlin.math.abs(it.second!! - year) <= 1 }.map { it.first }
                    else -> withYears.filter { it.second == null }.map { it.first }
                }
                picked.forEach { candidates.putIfAbsent(it.uid, it) }
            }
        }
        // Titles in categories you hid (or that are locked) never show up in Nuvio.
        val user = prefs.userState.first()
        val settings = prefs.settings.first()
        val allowed = candidates.values.filter { item ->
            val uid = "${item.kind.key}:${item.playlistId}:${item.categoryId}"
            if (user.hidesVodCategory(uid)) return@filter false
            val name = runCatching { db.categoryName(item.playlistId, item.kind, item.categoryId) }.getOrNull().orEmpty()
            !com.nuvio.tv.livetv.parental.ParentalControls.isLocked("vod:$uid", name, settings, user.lockedGroups)
        }
        // Best copies first: by the quality in the name here (so a 4K copy isn't cut by the limit),
        // then by what the provider reports about each file below.
        val picked = allowed.sortedByDescending { qualityRank(qualityOf(it.name).orEmpty()) }.take(6)
        // All copies are asked about at the same time (they used to go one after another, so six
        // slow answers added up to minutes). A Stalker portal's copies still go one at a time:
        // a portal works through one session, and parallel requests can upset it.
        val playlists = picked.map { it.playlistId }.distinct().associateWith { playlist(it) }
        val out: List<OnDemandSource> = kotlinx.coroutines.coroutineScope {
            picked.groupBy { it.playlistId }.map { (plId, items) ->
                async {
                    val pl = playlists[plId] ?: return@async emptyList<OnDemandSource>()
                    if (pl.isStalker) items.mapNotNull { sourceOf(it, pl, kind, season, episode) }
                    else items.map { item -> async { sourceOf(item, pl, kind, season, episode) } }.awaitAll().filterNotNull()
                }
            }.awaitAll().flatten()
        }
        // Highest quality first (4K, 1080p, 720p, SD); copies with no hint keep their order, last.
        out.sortedByDescending { sourceRank(it) }
    }

    /** One provider copy for the stream list, or null when it doesn't have that movie or episode. */
    private suspend fun sourceOf(item: VodItem, pl: PlaylistSource, kind: VodKind, season: Int?, episode: Int?): OnDemandSource? {
        val headers = if (pl.effectiveUserAgent.isNotBlank()) mapOf("User-Agent" to pl.effectiveUserAgent) else emptyMap()
        if (kind == VodKind.MOVIE) {
            val url = movieUrl(item) ?: return null
            // The file info (4K, HDR, audio) is only a nice extra: the link never waits long for it.
            // (asked in the background: a late answer is still kept for next time).
            val details = scope.async { runCatching { info(item) }.getOrNull() }
            val tech = kotlinx.coroutines.withTimeoutOrNull(MOVIE_INFO_WAIT_MS) { details.await() }?.tech
            return OnDemandSource(
                "${pl.name} · ${(item.ext ?: "mp4").uppercase()}", url, headers,
                title = item.name, year = item.year, providerName = pl.name, ext = item.ext ?: "mp4", tech = tech
            )
        }
        if (season == null || episode == null) return null
        val episodes = runCatching { info(item)?.episodes }.getOrNull().orEmpty()
        // Exact season and episode; if the provider lists the show as a single season
        // (common for daily shows), match the episode number alone.
        val ep = episodes.firstOrNull { it.season == season && it.episode == episode }
            ?: episodes.takeIf { list -> list.map { it.season }.distinct().size == 1 }
                ?.firstOrNull { it.episode == episode }
            ?: return null
        return OnDemandSource(
            "${pl.name} · S${season}E${episode} · ${ep.ext.uppercase()}", episodeUrl(ep) ?: return null, headers,
            title = item.name, year = item.year, providerName = pl.name, ext = ep.ext, tech = ep.tech,
            episodeTag = "S%02dE%02d".format(season, episode)
        )
    }

    /** 4 = 4K … 1 = SD, 0 = unknown: the file's own picture size when known, else its name. */
    private fun sourceRank(s: OnDemandSource): Int = when (s.tech?.quality) {
        "4K" -> 4
        "1080p" -> 3
        "720p" -> 2
        "SD" -> 1
        else -> qualityRank(qualityOf(s.title).orEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun getText(url: String, userAgent: String, client: okhttp3.OkHttpClient = http): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { LiveTvRepository.DEFAULT_UA }).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string() ?: error("Empty response")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

    /** Reads the provider's media scan ("video" / "audio" / "bitrate" / "duration_secs"). */
    private fun parseTech(info: JSONObject): VodTech? {
        val v = info.optJSONObject("video")
        val a = info.optJSONObject("audio")
        val bitrate = info.optString("bitrate").toIntOrNull()?.takeIf { it > 0 }
        val duration = info.optString("duration_secs").toIntOrNull()?.takeIf { it > 0 }
            ?: info.optString("duration").split(':').takeIf { it.size == 3 }?.let { p ->
                (p[0].toIntOrNull() ?: 0) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 + (p[2].toIntOrNull() ?: 0)
            }?.takeIf { it > 0 }
        if (v == null && a == null && bitrate == null && duration == null) return null
        val fps = v?.optString("r_frame_rate")?.let { r ->
            val parts = r.split('/')
            val n = parts.getOrNull(0)?.toDoubleOrNull()
            val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (n != null && d > 0) n / d else null
        }?.takeIf { it in 1.0..240.0 }
        val transfer = v?.optString("color_transfer").orEmpty()
        val hdr = when {
            v?.optJSONArray("side_data_list")?.toString()?.contains("DOVI", ignoreCase = true) == true -> "Dolby Vision"
            transfer.contains("smpte2084") -> "HDR10"
            transfer.contains("arib-std-b67") -> "HLG"
            else -> null
        }
        val lang = a?.optJSONObject("tags")?.optString("language")?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            runCatching { java.util.Locale(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && it != code } ?: code.uppercase()
        }
        return VodTech(
            width = v?.optInt("width")?.takeIf { it > 0 },
            height = v?.optInt("height")?.takeIf { it > 0 },
            videoCodec = v?.optString("codec_name")?.takeIf { it.isNotBlank() },
            fps = fps,
            hdr = hdr,
            audioCodec = a?.optString("codec_name")?.takeIf { it.isNotBlank() },
            channels = a?.optInt("channels")?.takeIf { it > 0 },
            language = lang,
            bitrateKbps = bitrate,
            durationSecs = duration
        )
    }

    private fun yearOf(s: String?): Int? =
        s?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }

    companion object {
        /** Titles read from an M3U playlist's own entries: the movie's link, or the show's key, follows. */
        const val M3U_ID = "m3u:"
        private val M3U_EXT = Regex("""\.([A-Za-z0-9]{2,4})(?:\?.*)?$""")
        private val STREAM_ID = Regex("""/(\d+)\.[A-Za-z0-9]+(?:\?.*)?$""")
        /** "S01E02", "S1 E2", "S01.E02". */
        private val EPISODE_TAG = Regex("""\bS(\d{1,3})\s*[._-]?\s*E(\d{1,4})\b""", RegexOption.IGNORE_CASE)
        /** "1x02". */
        private val EPISODE_X_TAG = Regex("""\b(\d{1,2})x(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        private val SERIES_LINK = Regex("""/series/""", RegexOption.IGNORE_CASE)
        private val MOVIE_LINK = Regex("""/movies?/""", RegexOption.IGNORE_CASE)
        private const val TAG = "OnDemand"
        /** Re-import each provider once a day. */
        private const val REFRESH_MS = 24L * 60 * 60 * 1000
        /** How long a list waits for its unchecked titles' languages before showing ("Hide non-English titles"). */
        private const val PAGE_CHECK_MS = 2_500L
        /** Search results checked before showing (the best matches; the rest are checked in the background). */
        private const val SEARCH_CHECK = 40
    }
}

/** How long On Demand waits after the app starts before its heavier checks. */
private const val START_DELAY_MS = 20_000L
/** How long a movie's link waits for its file info (4K, HDR, audio) before showing without it. */
private const val MOVIE_INFO_WAIT_MS = 4_000L
