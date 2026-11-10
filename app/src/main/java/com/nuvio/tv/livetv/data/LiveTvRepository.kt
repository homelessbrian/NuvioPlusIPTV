package com.nuvio.tv.livetv.data

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgChannelEntry
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.EpgSourceChannels
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport as LIVE_REPORT
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.PlaylistGroupOrder
import com.nuvio.tv.livetv.model.PlaylistAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class LiveTvStatus(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val loadedOnce: Boolean = false,
    /** Busy, but nothing worth announcing (reading the saved channels at start-up). */
    val quiet: Boolean = false
)

@Singleton
class LiveTvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val guideDb: EpgDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Declared before anything that starts work on [scope] (a playlist update reads them).
    /** Server time zone lookups in progress, per playlist (shared, so a second replay doesn't ask again). */
    private val zoneLookups = HashMap<String, kotlinx.coroutines.Deferred<String?>>()

    /** Playlist id -> when its server time zone lookup last failed (asked again after [ZONE_RETRY_MS]). */
    private val zoneFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Playlist id -> when its time zone was last found (this session), for the background re-check. */
    private val zoneCheckedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val mutex = Mutex()

    private val http: OkHttpClient = ForkHttp.builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // A provider that trickles data forever would otherwise hold up every other update
        // (deleting a playlist, turning one off) until the app is restarted.
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .apply { ForgivingTls.trustManager?.let { tm -> ForgivingTls.socketFactory?.let { sslSocketFactory(it, tm) } } }
        .build()

    private val baseDir = File(context.filesDir, "livetv")
    private val playlistDir = File(baseDir, "playlists")
    private val epgDir = File(baseDir, "epg")

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    /** Programs keyed by [LiveChannel.key], sorted by start. Offset is not applied here. */
    private val _programs = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<EpgProgram>>> = _programs.asStateFlow()

    /** Channel key -> the guide channel picked automatically (shown in Assign EPG). */
    private val _autoMatches = MutableStateFlow<Map<String, EpgAssignment>>(emptyMap())
    val autoMatches: StateFlow<Map<String, EpgAssignment>> = _autoMatches.asStateFlow()

    /** Every loaded EPG source with its channel list (for "Change EPG"). */
    private val _epgSources = MutableStateFlow<List<EpgSourceChannels>>(emptyList())
    val epgSources: StateFlow<List<EpgSourceChannels>> = _epgSources.asStateFlow()

    private val _status = MutableStateFlow(LiveTvStatus())
    val status: StateFlow<LiveTvStatus> = _status.asStateFlow()

    private var initialized = false
    /** The start-up update check has run (later checks may start). */
    @Volatile private var firstCheckDone = false

    /** Loads cached data immediately, then refreshes anything older than the configured interval. */
    fun ensureLoaded(updateCheckDelayMs: Long = 0L) {
        if (initialized) return
        initialized = true
        scope.launch {
            prefs.settings.map { it.epgOffsetMinutes }.distinctUntilChanged().collectLatest {
                overviewJob?.cancel()
                overviewAt = 0L
                ensureRange(0L, 0L)
            }
        }
        scope.launch {
            // Saved channels and guide first: reading them only uses the device's storage.
            loadFromCache()
            // A playlist running out within a week: say so (once per app start).
            runCatching {
                val names = prefs.currentPlaylists().associate { it.id to it.name }
                prefs.playlistAccounts.first().forEach { (id, a) -> names[id]?.let { warnIfExpiring(id, it, a) } }
            }
            // Groups hidden or shown since the guide was stored (and from now on).
            watchHidden()
            // Space left empty in the guide database by earlier versions or updates.
            scheduleCompact()
            // Checking for playlist / guide updates uses the network and a lot of processing,
            // so when this starts with the app it waits a little, out of Nuvio's way.
            if (updateCheckDelayMs > 0) delay(updateCheckDelayMs)
            refreshStale()
            firstCheckDone = true
            // Then keep checking while the app stays open. TV boxes keep the app running for
            // days (Home only sends it to the background), and the check used to happen only
            // when the app started: the guide ran out after a day or two and never refreshed
            // until the box restarted or someone pressed Update now.
            while (true) {
                delay(STALE_CHECK_MS)
                runCatching { refreshStale() }.onFailure { Log.w(TAG, "Update check failed", it) }
            }
        }
    }

    /** Opening Live TV: update anything past its update time (the playlist or guide interval). */
    fun refreshIfStale() {
        // Not before the start-up check (the saved channels may not be read yet).
        if (!firstCheckDone) return
        if (mutex.isLocked) return // an update is already running
        // Just checked (the start-up check, or Live TV opened a moment ago): nothing can be due yet.
        if (System.currentTimeMillis() - lastAutoCheckAt < RECHECK_GAP_MS) return
        scope.launch { runCatching { refreshStale() } }
    }

    fun refreshAll(force: Boolean = true) {
        scope.launch { refreshInternal(forcePlaylists = force, forceEpg = force) }
    }

    /** After a restore from Google Drive: rebuild from the new sources, downloading what's missing. */
    /** After another TV's setup was applied: [changedPlaylists] get downloaded again straight away. */
    fun reloadAfterSync(changedPlaylists: Set<String> = emptySet()) {
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = changedPlaylists)
        }
    }

    fun refreshEpgOnly() {
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = true) }
    }

    // Automatic updates run at low priority, so the screen and video stay smooth meanwhile.
    private suspend fun refreshStale() = withContext(BackgroundWork.quietUpdate) { refreshInternal(forcePlaylists = false, forceEpg = false) }

    // ---------------------------------------------------------------- sources CRUD

    suspend fun addPlaylist(name: String, url: String, userAgent: String = ""): String {
        val id = UUID.randomUUID().toString()
        prefs.updatePlaylists { it + PlaylistSource(id = id, name = name.ifBlank { "Playlist" }, url = url.trim(), userAgent = userAgent.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun addXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        importVod: Boolean = true,
        importLive: Boolean = true
    ): String {
        val id = UUID.randomUUID().toString()
        // Accept a pasted full link: keep just the server, and take the login from it if needed.
        val fromLink = PlaylistSource.credentialsFromLink(server)
        val user = username.trim().ifBlank { fromLink?.first.orEmpty() }
        val pass = password.trim().ifBlank { fromLink?.second.orEmpty() }
        prefs.updatePlaylists {
            it + PlaylistSource(
                id = id, name = name.ifBlank { "Xtream" }, url = "",
                xtreamServer = PlaylistSource.cleanXtreamServer(server),
                xtreamUsername = user, xtreamPassword = pass,
                importVod = importVod,
                importLive = importLive
            )
        }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updatePlaylist(input: PlaylistSource) {
        val source = if (input.isXtream) {
            val fromLink = PlaylistSource.credentialsFromLink(input.xtreamServer)
            input.copy(
                xtreamServer = PlaylistSource.cleanXtreamServer(input.xtreamServer),
                xtreamUsername = input.xtreamUsername.trim().ifBlank { fromLink?.first.orEmpty() },
                xtreamPassword = input.xtreamPassword.trim().ifBlank { fromLink?.second.orEmpty() }
            )
        } else input
        val wasLive = prefs.currentPlaylists().firstOrNull { it.id == source.id }?.liveEnabled == true
        prefs.updatePlaylists { list -> list.map { if (it.id == source.id) source else it } }
        if (!source.liveEnabled) {
            // TV channels switched off (or never on, for an On Demand-only login): take them out
            // of the guide straight away and leave Live TV alone. Nothing to download for the
            // guide, so no "Loading Live TV" bubble; On Demand imports on its own.
            dropPlaylistNow(source.id)
            if (wasLive) scope.launch { rebuild() }
            return
        }
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id))
        }
    }

    suspend fun removePlaylist(id: String) {
        prefs.updatePlaylists { list -> list.filterNot { it.id == id } }
        File(playlistDir, "$id.m3u").delete()
        groupOrderFile(id).delete()
        embeddedEpgUrls.remove(id)
        dropPlaylistNow(id)
        scope.launch { rebuild() }
    }

    /**
     * Takes a deleted or turned-off playlist's channels out of the guide straight away, without
     * waiting for the full rebuild (which can be queued behind a long guide download).
     */
    private fun dropPlaylistNow(id: String) {
        val remaining = _channels.value.filterNot { it.sourceId == id }
        if (remaining.size == _channels.value.size) return
        val keys = remaining.mapTo(HashSet()) { it.key }
        _channels.value = remaining
        _programs.value = _programs.value.filterKeys { it in keys }
    }

    suspend fun setPlaylistEnabled(id: String, enabled: Boolean) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        if (!enabled) dropPlaylistNow(id)
        // Rebuild from the cached files first (turning a playlist on or off downloads nothing,
        // so the refresh alone wouldn't rebuild the channel list), then fetch anything missing.
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
    }

    suspend fun movePlaylist(id: String, delta: Int) {
        prefs.updatePlaylists { list ->
            val m = list.toMutableList()
            val i = m.indexOfFirst { it.id == id }
            if (i < 0) return@updatePlaylists list
            val item = m.removeAt(i)
            m.add((i + delta).coerceIn(0, m.size), item)
            m
        }
        scope.launch { rebuild() }
    }

    suspend fun addEpg(name: String, url: String): String {
        val id = UUID.randomUUID().toString()
        prefs.updateEpgSources { it + EpgSource(id = id, name = name.ifBlank { "EPG" }, url = url.trim()) }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(id)) }
        return id
    }

    suspend fun updateEpg(source: EpgSource) {
        prefs.updateEpgSources { list -> list.map { if (it.id == source.id) source else it } }
        scope.launch { refreshInternal(forcePlaylists = false, forceEpg = false, forceIds = setOf(source.id)) }
    }

    suspend fun removeEpg(id: String) {
        prefs.updateEpgSources { list -> list.filterNot { it.id == id } }
        File(epgDir, "$id.xml").delete()
        scope.launch { rebuild() }
    }

    suspend fun setEpgEnabled(id: String, enabled: Boolean) {
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        scope.launch {
            rebuild()
            refreshInternal(forcePlaylists = false, forceEpg = false)
        }
    }

    // ---------------------------------------------------------------- core

    private suspend fun loadFromCache() {
        if (_epgLogos.value.isEmpty()) _epgLogos.value = withContext(Dispatchers.IO) { readEpgLogos() }
        mutex.withLock { rebuildLocked() }
    }

    // ---------------------------------------------------------------- logos from the guide

    private val epgLogosFile get() = File(baseDir, "epg_logos.json")
    private val _epgLogos = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Channel key -> the logo its matched guide channel has (playlists can prefer these). */
    val epgLogos: StateFlow<Map<String, String>> = _epgLogos.asStateFlow()

    private fun readEpgLogos(): Map<String, String> = runCatching {
        val f = epgLogosFile
        if (!f.exists()) return@runCatching emptyMap<String, String>()
        val o = org.json.JSONObject(f.readText())
        val out = HashMap<String, String>(o.length())
        o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        out
    }.getOrDefault(emptyMap())

    // ---- The guides' channel lists (Assign EPG), kept between app starts.
    private val sourceListsFile get() = File(baseDir, "epg_channel_lists.bin")

    /** Which guide files the saved lists came from (each file's size and contents stamp). */
    private fun sourceListsStamp(targets: List<EpgTarget>): String = targets.joinToString(",") { targetStamp(it) }

    private fun saveSourceLists(targets: List<EpgTarget>, lists: List<EpgSourceChannels>) {
        runCatching {
            val tmp = File(baseDir, sourceListsFile.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()))).use { out ->
                out.writeInt(SOURCE_LISTS_VERSION)
                out.writeUTF(sourceListsStamp(targets))
                out.writeInt(lists.size)
                lists.forEach { l ->
                    out.writeUTF(l.sourceId); out.writeUTF(l.name); out.writeUTF(l.label)
                    out.writeInt(l.channels.size)
                    l.channels.forEach { e ->
                        out.writeUTF(e.id)
                        out.writeInt(e.names.size)
                        e.names.forEach { out.writeUTF(it.take(500)) }
                        out.writeUTF(e.icon.orEmpty().take(2000))
                    }
                }
            }
            if (!tmp.renameTo(sourceListsFile)) { tmp.copyTo(sourceListsFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the guides' channel lists", it) }
    }

    /** Loads the saved lists if they still match the guide files; marks what's in use. */
    private suspend fun loadSourceLists(targets: List<EpgTarget>) {
        val f = sourceListsFile
        if (!f.exists()) {
            LIVE_REPORT.add("Assign EPG lists: none saved yet (read when Assign EPG is first opened)")
            return
        }
        val lists = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(f.inputStream()))).use { input ->
                if (input.readInt() != SOURCE_LISTS_VERSION) return@use null
                if (input.readUTF() != sourceListsStamp(targets)) return@use null
                val n = input.readInt()
                (0 until n).map {
                    val sourceId = input.readUTF(); val name = input.readUTF(); val label = input.readUTF()
                    val count = input.readInt()
                    val entries = ArrayList<EpgChannelEntry>(count)
                    repeat(count) {
                        val id = input.readUTF()
                        val names = (0 until input.readInt()).map { input.readUTF() }
                        val icon = input.readUTF().ifBlank { null }
                        entries += EpgChannelEntry(id, names, icon)
                    }
                    EpgSourceChannels(sourceId, name, label, entries)
                }
            }
        }.onFailure { Log.w(TAG, "Couldn't read the guides' channel lists", it) }.getOrNull()
        if (lists == null) {
            LIVE_REPORT.add("Assign EPG lists: saved copy out of date (read when Assign EPG is first opened)")
            return
        }
        // Which guide channels already feed a playlist channel (matched or assigned).
        val overrides = prefs.userState.first().epgOverrides
        val used = HashMap<String, MutableSet<String>>()
        (_autoMatches.value.values + overrides.values).forEach { used.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        // Only if nothing filled them in the meantime (a guide update in progress wins).
        if (_epgSources.value.isEmpty()) {
            _epgSources.value = lists.map { it.copy(usedIds = used[it.sourceId].orEmpty()) }
            LIVE_REPORT.add("Assign EPG lists: ${lists.size} guide(s) ready from last time")
        }
    }

    /** Stops reading a guide once its channel list has been read (the programmes aren't needed). */
    private class ChannelListRead(val channels: Map<String, XmltvChannel>) : RuntimeException()

    private val _epgListsLoading = MutableStateFlow(false)
    /** Assign EPG's channel lists are being read (see [ensureSourceLists]). */
    val epgListsLoading: StateFlow<Boolean> = _epgListsLoading.asStateFlow()

    /**
     * Assign EPG opened with no channel lists (no saved copy yet, e.g. the first time after
     * updating, or the guides changed without being read since): reads just each guide's
     * channel list, the short part at the top of the file. No programmes, and nothing at
     * start-up: only when Assign EPG actually needs them.
     */
    fun ensureSourceLists() {
        if (_epgSources.value.isNotEmpty() || _epgListsLoading.value) return
        _epgListsLoading.value = true
        scope.launch(Dispatchers.IO) {
            try {
                readSourceLists(collectEpgTargets())
            } finally {
                _epgListsLoading.value = false
            }
        }
    }

    private suspend fun readSourceLists(targets: List<EpgTarget>) {
        if (_epgSources.value.isNotEmpty()) return
        val lists = ArrayList<EpgSourceChannels>()
        for (t in targets) {
            val file = epgFile(t.fileId)
            if (!file.exists()) continue
            val channels: Map<String, XmltvChannel> = try {
                openMaybeGzip(file).use { input ->
                    XmltvParser.parse(input, 0L, 0L) { xc -> throw ChannelListRead(LinkedHashMap(xc)) }
                }
                emptyMap()
            } catch (e: ChannelListRead) {
                e.channels
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the channel list of ${t.name}", e)
                emptyMap()
            }
            val label = runCatching { java.net.URI(t.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: t.name
            lists += EpgSourceChannels(
                t.fileId, t.name, label,
                channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }.sortedBy { it.displayName.lowercase() }
            )
        }
        if (lists.isEmpty()) return
        val overrides = prefs.userState.first().epgOverrides
        val used = HashMap<String, MutableSet<String>>()
        (_autoMatches.value.values + overrides.values).forEach { used.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        if (_epgSources.value.isEmpty()) {
            _epgSources.value = lists.map { it.copy(usedIds = used[it.sourceId].orEmpty()) }
            saveSourceLists(targets, lists)
            LIVE_REPORT.add("Assign EPG lists: ${lists.size} guide(s) read for Assign EPG")
        }
    }

    private fun saveEpgLogos(map: Map<String, String>) {
        runCatching {
            val o = org.json.JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            baseDir.mkdirs()
            epgLogosFile.writeText(o.toString())
        }
    }

    /** Catch-up time zone picked for one playlist ("" = Automatic): only replay links change, nothing reloads. */
    suspend fun setPlaylistCatchupZone(id: String, zone: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupTimezone = zone) else it } }
    }

    /** "Prefer logos from playlist / EPG" for one playlist: only the logos change, nothing reloads. */
    suspend fun setPlaylistLogoSource(id: String, logoSource: String) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(logoSource = logoSource) else it } }
    }

    /** A playlist's built-in guide time shift: the guide is matched and stored again with it. */
    suspend fun setPlaylistGuideShift(id: String, minutes: Int) {
        val before = prefs.currentPlaylists().firstOrNull { it.id == id }?.guideShiftMinutes ?: return
        if (before == minutes) return
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(guideShiftMinutes = minutes) else it } }
        restoreShiftedGuide()
    }

    /** A playlist's catch-up time shift: only the next catch-up link changes, nothing reloads. */
    suspend fun setPlaylistCatchupShift(id: String, minutes: Int) {
        prefs.updatePlaylists { list -> list.map { if (it.id == id) it.copy(catchupShiftMinutes = minutes) else it } }
    }

    /** A guide source's time shift: the guide is matched and stored again with it. */
    suspend fun setEpgShift(id: String, minutes: Int) {
        val before = prefs.currentEpgSources().firstOrNull { it.id == id }?.shiftMinutes ?: return
        if (before == minutes) return
        prefs.updateEpgSources { list -> list.map { if (it.id == id) it.copy(shiftMinutes = minutes) else it } }
        restoreShiftedGuide()
    }

    private fun restoreShiftedGuide() {
        // Past listings from the Xtream API are fetched again (with the new shift) as you browse.
        archiveFetched.clear()
        rematchEpg()
    }

    private suspend fun rebuild() {
        mutex.withLock { rebuildLocked() }
    }

    private suspend fun refreshInternal(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String> = emptySet()
    ): Unit {
        val automatic = !forcePlaylists && !forceEpg && forceIds.isEmpty()
        if (!automatic) {
            // An update you asked for (Update now, a new playlist…) doesn't queue up behind an
            // automatic check that's waiting for Nuvio's player to close: that check stops
            // waiting and finishes now. (It could wait for hours, and "Update now" did nothing.)
            manualQueued.value = true
            if (mutex.isLocked) LIVE_REPORT.add("Update asked for: finishing the update already running first")
        }
        mutex.withLock { refreshLocked(forcePlaylists, forceEpg, forceIds, automatic) }
    }

    /** When the last automatic check finished (see [refreshIfStale]). */
    @Volatile private var lastAutoCheckAt = 0L

    /** An update you asked for is waiting: automatic checks stop waiting for Nuvio's player. */
    private val manualQueued = MutableStateFlow(false)

    private suspend fun refreshLocked(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String>,
        automatic: Boolean
    ) {
        if (!automatic) manualQueued.value = false
        val t0Update = System.currentTimeMillis()
        LIVE_REPORT.add(if (automatic) "Update check started" else "Update started")
        try { refreshBody(forcePlaylists, forceEpg, forceIds, automatic) }
        finally {
            if (automatic) lastAutoCheckAt = System.currentTimeMillis()
            LIVE_REPORT.add("Update finished in ${(System.currentTimeMillis() - t0Update) / 1000} s")
        }
        runCatching { sweepLeftoverFiles() }.onFailure { Log.w(TAG, "Leftover file sweep failed", it) }
    }

    /**
     * Deletes saved guide and playlist files no source uses any more. A playlist's built-in
     * guide is saved under a name made from its web address, so removing the playlist, or the
     * provider changing that address (a new domain, a new login), left the old file behind for
     * good. Guides are often 50–200 MB, and a box with little storage left slows right down.
     * Only files untouched for two weeks go, so a guide that's just turned off for a while, or
     * a source being added right now, is left alone (one turned back on is downloaded again).
     */
    private suspend fun sweepLeftoverFiles() {
        val now = System.currentTimeMillis()
        val oldEnough = now - 14L * 24 * HOUR
        val guideIds = collectEpgTargets().mapTo(HashSet()) { it.fileId }
        prefs.currentEpgSources().forEach { guideIds += it.id }
        val playlistIds = prefs.currentPlaylists().mapTo(HashSet()) { it.id }
        var freed = 0L
        var count = 0
        fun sweep(dir: File, idOf: (String) -> String, keep: Set<String>) {
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.lastModified() > oldEnough) return@forEach
                if (idOf(f.name) in keep) return@forEach
                val size = f.length()
                if (f.delete()) { freed += size; count++ }
            }
        }
        sweep(epgDir, { it.substringBefore(".xml").substringBefore(".tmp") }, guideIds)
        sweep(playlistDir, { it.substringBefore(".m3u").substringBefore(".tmp") }, playlistIds)
        if (count > 0) LIVE_REPORT.add("Cleanup: deleted $count saved guide/playlist files no source uses (${freed / (1024 * 1024)} MB freed)")
    }

    private suspend fun refreshBody(
        forcePlaylists: Boolean,
        forceEpg: Boolean,
        forceIds: Set<String>,
        automatic: Boolean
    ) {
        val settings = prefs.currentSettings()
        val now = System.currentTimeMillis()
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        var changed = false
        // Automatic checks wait while a movie or episode is playing in Nuvio's player (unless
        // an update you asked for is waiting behind this one).
        suspend fun outOfTheWay() {
            if (!automatic || !(BackgroundWork.nuvioPlaying.value || BackgroundWork.liveHold.value) || manualQueued.value) return
            LIVE_REPORT.add("Update check waiting while a channel is playing full screen or Nuvio's player is open")
            kotlinx.coroutines.flow.combine(BackgroundWork.nuvioPlaying, BackgroundWork.liveHold, manualQueued) { playing, hold, manual -> (!playing && !hold) || manual }
                .first { it }
        }

        for (pl in playlists) {
            outOfTheWay()
            val file = playlistFile(pl.id)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.playlistRefreshHours.coerceAtLeast(1) * HOUR
            if (forcePlaylists || stale || pl.id in forceIds) {
                LIVE_REPORT.add("Checking playlist \"${pl.name}\" for updates")
                setStatus(loading = true, message = "Updating playlist \"${pl.name}\"…")
                val before = if (file.exists()) file.length() to file.lastModified() else null
                val result = runCatching {
                    if (pl.isXtream) {
                        val old = if (file.exists()) File(file.parentFile, file.name + ".prev").also { file.copyTo(it, overwrite = true) } else null
                        downloadXtream(pl, file)
                        // Xtream lists are rebuilt each time: unchanged if identical to before.
                        val unchanged = old != null && sameDecoded(old, file)
                        old?.delete()
                        if (!unchanged) markChanged(file)
                        LIVE_REPORT.add(if (unchanged) "Playlist \"${pl.name}\": no changes" else "Playlist \"${pl.name}\": new content")
                        !unchanged
                    } else download(pl.resolvedUrl(), file, pl.userAgent, progressLabel = "Downloading playlist \"${pl.name}\"")
                }
                // M3U links from Xtream providers (get.php with a login): the account's details.
                if (!pl.isXtream) refreshM3uAccount(pl)
                val err = result.exceptionOrNull()?.let { e ->
                    when (e) {
                        is java.net.UnknownHostException -> "Couldn't find the provider's server (${e.message?.substringAfter("\"")?.substringBefore("\"") ?: "address"}). Check the address, your internet, or try again later."
                        is java.net.SocketTimeoutException -> "The provider took too long to answer (timeout). It may be busy; try again later."
                        else -> e.message ?: e.javaClass.simpleName
                    }
                }
                prefs.updatePlaylists { list ->
                    list.map {
                        if (it.id == pl.id) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it
                    }
                }
                if (err != null) {
                    Log.w(TAG, "Playlist ${pl.name} failed: $err")
                    LIVE_REPORT.add("Playlist \"${pl.name}\" failed: $err" + if (pl.isXtream) " (Xtream login: server ${if (pl.xtreamServer.isBlank()) "missing" else "set"}, user ${if (pl.xtreamUsername.isBlank()) "missing" else "set"}, password ${if (pl.xtreamPassword.isBlank()) "missing" else "set"})" else "")
                }
                if (result.getOrNull() == true || (before == null && file.exists())) changed = true
            }
        }

        // Build channels first so EPG matching knows which channels exist.
        if (changed || _channels.value.isEmpty()) {
            outOfTheWay()
            setStatus(loading = true, message = "Reading channels…")
            buildChannels()
            saveChannels() // so the next start uses it straight away
        }

        val epgTargets = collectEpgTargets()
        // Files saved by earlier versions, unzipped: zipped once (see download()).
        (epgTargets.map { epgFile(it.fileId) } + playlists.map { playlistFile(it.id) }).forEach { zipInPlace(it) }
        for (target in epgTargets) {
            outOfTheWay()
            val file = epgFile(target.fileId)
            val stale = !file.exists() ||
                now - file.lastModified() > settings.epgRefreshHours.coerceAtLeast(1) * HOUR
            // A playlist saved again (its login, user agent…): its own guide is downloaded again too.
            val forcedHere = target.sourceId in forceIds || target.playlistId in forceIds
            if (forceEpg || stale || forcedHere || guideHoles || (target.sourceId == null && changed && !file.exists())) {
                setStatus(loading = true, message = "Downloading guide \"${target.name}\"…")
                LIVE_REPORT.add("Downloading guide \"${target.name}\" (user agent: ${target.userAgent.ifBlank { "app default" }})")
                val result = runCatching {
                    download(
                        target.url, file, target.userAgent,
                        progressLabel = "Downloading guide \"${target.name}\"",
                        // Also whole when the guide has gone stale: a provider that keeps answering
                        // "not changed" to the short check left channels without listings overnight.
                        full = forceEpg || forcedHere || stale || guideHoles,
                        reportAs = target.name
                    )
                }
                if (result.getOrNull() == true) changed = true
                val err = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                if (target.sourceId != null) {
                    prefs.updateEpgSources { list ->
                        list.map { if (it.id == target.sourceId) it.copy(lastUpdatedMs = if (err == null) now else it.lastUpdatedMs, lastError = err) else it }
                    }
                }
                if (err != null) {
                    Log.w(TAG, "EPG ${target.name} failed: $err")
                    LIVE_REPORT.add("Guide \"${target.name}\" failed: $err")
                }
            }
        }

        // A guide kept in the database fills in in the background (empty for a moment), so an
        // empty list then doesn't mean there's no guide: only re-match if something changed.
        // "Update guide now" always matches the guides again, even when the files came back
        // unchanged: a guide matched wrong or stored empty was otherwise kept until a file
        // changed, so pressing Update did nothing for it.
        if (changed || forceEpg || guideHoles || (_programs.value.isEmpty() && (!guideInDb || storedGuideEmpty))) {
            storedGuideEmpty = false
            guideHoles = false
            outOfTheWay()
            setStatus(loading = true, message = "Matching guide data…")
            buildPrograms(settings, epgTargets)
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    private suspend fun rebuildLocked() {
        // Reading the saved channels and guide takes a second or two and changes nothing, so no
        // "Loading channels…" bubble for it (people took it for a full reload every time). The
        // bubble only shows if the playlists really have to be read again.
        setStatus(loading = true, message = null, quiet = true)
        // The channel list as it was last read, when no playlist has changed since: Live TV
        // (Favorites included) appears straight away instead of re-reading every playlist file.
        var t0 = System.currentTimeMillis()
        // The stored guide is read at the same time as the channel list (it only has to be
        // checked against the channels afterwards), so the two waits overlap.
        val guidePreRead = scope.async(Dispatchers.IO) { preReadGuide() }
        if (!loadSavedChannels()) {
            setStatus(loading = true, message = "Loading channels…")
            buildChannels()
            saveChannels()
            LIVE_REPORT.add("Channels re-read from playlists: ${_channels.value.size} in ${System.currentTimeMillis() - t0} ms")
        } else {
            LIVE_REPORT.add("Saved channel list used: ${_channels.value.size} channels in ${System.currentTimeMillis() - t0} ms")
            carryOverRenamedChannels(null, _channels.value)
        }
        val targets = collectEpgTargets()
        val settings = prefs.currentSettings()
        // The guide as it was last matched, if nothing it depends on has changed: the guide
        // fills in straight away instead of re-reading every guide file at each start.
        t0 = System.currentTimeMillis()
        if (!loadGuideFromDb(settings, targets, guidePreRead.await())) {
            if (dropRemovedPlaylistsFromGuide(settings, targets)) {
                LIVE_REPORT.add("Guide kept: turned-off playlists' channels taken out in ${System.currentTimeMillis() - t0} ms")
            } else {
                buildPrograms(settings, targets)
                LIVE_REPORT.add("Guide re-read from guide files in ${System.currentTimeMillis() - t0} ms")
            }
        } else {
            LIVE_REPORT.add("Stored guide used: ${_programs.value.size} channels with listings in ${System.currentTimeMillis() - t0} ms")
        }
        _status.value = LiveTvStatus(loading = false, loadedOnce = true)
    }

    /** Which parts of a saved copy's fingerprint differ (for the start-up report). */
    private fun fingerprintDiff(old: String, new: String): String {
        val a = old.split('|', ',').toSet()
        val b = new.split('|', ',').toSet()
        val changed = (b - a).take(4).joinToString("; ") { it.take(60) }
        return if (changed.isBlank()) "format changed" else "changed: $changed"
    }

    // ---------------------------------------------------------------- saved channel list

    private val savedChannelsFile get() = File(baseDir, "channels_cache3.bin")

    /** Everything the channel list is built from; a different value means it must be re-read. */
    private suspend fun channelsFingerprint(): String {
        val sb = StringBuilder().append(CHANNELS_CACHE_VERSION).append('|')
        prefs.currentPlaylists().filter { it.liveEnabled }.forEach { pl ->
            val f = playlistFile(pl.id)
            sb.append(pl.id).append(':').append(pl.name.hashCode()).append(':').append(pl.userAgent.hashCode())
                .append(':').append(f.length()).append(':').append(contentStamp(f))
            // M3U links: the chosen stream format changes the channel links (Auto adds nothing,
            // so lists saved before this setting existed stay valid).
            if (!pl.isXtream && pl.streamFormat != "auto") sb.append(":fmt=").append(pl.streamFormat)
            sb.append(',')
        }
        return sb.toString()
    }

    /**
     * Saved channel list, format 3: not zipped (unzipping cost more than reading a slightly
     * bigger file on TV boxes), and text that repeats across channels (playlist, group,
     * catch-up type) stored once and referred to by number. Faster to read, less memory.
     */
    private suspend fun loadSavedChannels(): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(baseDir, "channels_cache.bin").delete() } // the old zipped format
        val file = savedChannelsFile
        if (!file.exists()) return@withContext false
        val expected = channelsFingerprint()
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream(), 256 * 1024)).use { input ->
                val stored = input.readUTF()
                // Out of date: the playlists get read again. Its stream links are still read
                // (same format only) so channels renamed in the playlist keep their favorites etc.
                val outdated = stored != expected
                if (outdated) {
                    LIVE_REPORT.add("Saved channel list out of date: ${fingerprintDiff(stored, expected)}")
                    if (stored.substringBefore('|') != expected.substringBefore('|')) return@withContext false
                }
                val links = HashMap<String, String>()
                val epg = HashMap<String, List<String>>()
                repeat(input.readInt()) {
                    val id = input.readUTF()
                    epg[id] = List(input.readInt()) { input.readUTF() }
                }
                val table = Array(input.readInt()) { input.readUTF() }
                fun shared(): String = table[input.readInt()]
                fun opt(): String? = if (input.readBoolean()) input.readUTF() else null
                val count = input.readInt()
                val list = ArrayList<LiveChannel>(if (outdated) 0 else count)
                repeat(count) {
                    val key = input.readUTF()
                    val sourceId = shared()
                    val sourceName = shared()
                    val name = input.readUTF()
                    val tvgId = opt()
                    val tvgName = opt()
                    val logo = opt()
                    val groupId = shared()
                    val group = shared()
                    val number = input.readInt()
                    val url = input.readUTF()
                    val headerCount = input.readInt()
                    val headers: Map<String, String> = if (headerCount == 0) emptyMap()
                    else HashMap<String, String>(headerCount * 2).apply { repeat(headerCount) { put(shared(), input.readUTF()) } }
                    val catchup = if (input.readBoolean()) CatchupInfo(shared(), opt(), input.readInt(), input.readDouble()) else null
                    val drm = if (input.readBoolean()) {
                        val scheme = shared()
                        val license = input.readUTF()
                        val lh = HashMap<String, String>().apply { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } }
                        com.nuvio.tv.livetv.model.DrmInfo(scheme, license, lh, opt())
                    } else null
                    val tvgShift = input.readInt()
                    if (outdated) links[key] = url
                    else list += LiveChannel(key, sourceId, sourceName, name, tvgId, tvgName, logo, groupId, group, number, url, headers, catchup, drm, tvgShift)
                }
                if (outdated) {
                    previousLinks = links
                    return@withContext false
                }
                embeddedEpgUrls.clear()
                embeddedEpgUrls.putAll(epg)
                loadGroupOrder()
                _channels.value = list
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved channel list unreadable", it)
            false
        }
    }

    private suspend fun saveChannels() = withContext(BackgroundWork.cpu()) {
        val fingerprint = channelsFingerprint()
        runCatching {
            val list = _channels.value
            // Text shared by many channels, stored once.
            val index = LinkedHashMap<String, Int>()
            fun id(v: String): Int = index.getOrPut(v) { index.size }
            list.forEach { c ->
                id(c.sourceId); id(c.sourceName); id(c.groupId); id(c.group)
                c.headers.keys.forEach { id(it) }
                c.catchup?.let { id(it.type) }
                c.drm?.let { id(it.scheme) }
            }
            val tmp = File(baseDir, "channels_cache3.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                fun opt(v: String?) { out.writeBoolean(v != null); if (v != null) out.writeUTF(v) }
                out.writeUTF(fingerprint)
                out.writeInt(embeddedEpgUrls.size)
                embeddedEpgUrls.forEach { (eid, urls) -> out.writeUTF(eid); out.writeInt(urls.size); urls.forEach { out.writeUTF(it) } }
                out.writeInt(index.size)
                index.keys.forEach { out.writeUTF(it) }
                out.writeInt(list.size)
                list.forEach { c ->
                    out.writeUTF(c.key)
                    out.writeInt(index.getValue(c.sourceId)); out.writeInt(index.getValue(c.sourceName))
                    out.writeUTF(c.name)
                    opt(c.tvgId); opt(c.tvgName); opt(c.logo)
                    out.writeInt(index.getValue(c.groupId)); out.writeInt(index.getValue(c.group))
                    out.writeInt(c.number)
                    out.writeUTF(c.url)
                    out.writeInt(c.headers.size)
                    c.headers.forEach { (k, v) -> out.writeInt(index.getValue(k)); out.writeUTF(v) }
                    out.writeBoolean(c.catchup != null)
                    c.catchup?.let { cu -> out.writeInt(index.getValue(cu.type)); opt(cu.source); out.writeInt(cu.days); out.writeDouble(cu.correctionHours) }
                    out.writeBoolean(c.drm != null)
                    c.drm?.let { d ->
                        out.writeInt(index.getValue(d.scheme)); out.writeUTF(d.license)
                        out.writeInt(d.licenseHeaders.size); d.licenseHeaders.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
                        opt(d.manifestType)
                    }
                    out.writeInt(c.tvgShiftMinutes)
                }
            }
            if (!tmp.renameTo(savedChannelsFile)) { tmp.copyTo(savedChannelsFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the channel list", it) }
    }

    // ---------------------------------------------------------------- saved guide

    /** Assign EPG needs the full guide-channel lists, which the saved guide skips. */
    @Volatile private var epgDetailsPending = false

    // ---------------------------------------------------------------- hidden channels

    /** The channels the stored guide left out (hidden in every profile), as last stored. */
    private val skippedFile get() = File(baseDir, "guide_skipped.txt")
    @Volatile private var guideSkipped: Set<String>? = null

    private fun saveSkipped(keys: Set<String>) {
        guideSkipped = keys
        runCatching {
            val tmp = File(baseDir, skippedFile.name + ".tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()))).use { out ->
                out.writeInt(keys.size)
                keys.forEach { out.writeUTF(it) }
            }
            if (!tmp.renameTo(skippedFile)) { tmp.copyTo(skippedFile, overwrite = true); tmp.delete() }
        }
    }

    /** Null for a guide stored before channels were left out (it has everything). */
    private fun loadSkipped(): Set<String>? = guideSkipped ?: runCatching {
        if (!skippedFile.exists()) return@runCatching null
        java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(skippedFile.inputStream()))).use { input ->
            HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
        }
    }.getOrNull()?.also { guideSkipped = it }

    private var hiddenWatch: kotlinx.coroutines.Job? = null

    /** Follows hiding and showing groups and channels (in any profile) once the guide is loaded. */
    private fun watchHidden() {
        if (hiddenWatch != null) return
        hiddenWatch = scope.launch {
            prefs.hiddenEverywhere.collectLatest {
                delay(2_000) // a few changes in a row are handled together
                // Once started, finished (a newer change waits its turn instead of cutting it short).
                withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock { syncHidden() } }
            }
        }
    }

    /**
     * Keeps the stored guide in step with what's hidden everywhere:
     *  - newly hidden channels: their listings are deleted (nothing is read);
     *  - channels shown again: the guide is read from the saved guide files, so they get listings.
     */
    private suspend fun syncHidden() = withContext(Dispatchers.IO) {
        if (!guideInDb) return@withContext
        val channels = _channels.value
        if (channels.isEmpty()) return@withContext
        val hidden = prefs.currentHiddenEverywhere()
        val nowHidden = channels.filter { hidden.hides(it.key, it.groupId) }.mapTo(HashSet()) { it.key }
        val skipped = loadSkipped() ?: emptySet() // older guides stored every channel
        val keys = channels.mapTo(HashSet()) { it.key }
        val shownAgain = skipped.filter { it in keys && it !in nowHidden }
        if (shownAgain.isNotEmpty()) {
            LIVE_REPORT.add("Guide: ${shownAgain.size} channels shown again, reading their listings")
            setStatus(loading = true, message = "Updating guide…")
            buildPrograms(prefs.currentSettings(), collectEpgTargets())
            _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            return@withContext
        }
        val newlyHidden = nowHidden - skipped
        if (newlyHidden.isEmpty()) return@withContext
        beginGuideMutation()
        try {
            guideDb.deleteChannels(newlyHidden)
        } finally {
            publishCommittedGuide(newlyHidden)
        }
        saveSkipped(skipped.filterTo(HashSet()) { it in keys } + newlyHidden)
        scheduleCompact()
    }

    private var compactJob: kotlinx.coroutines.Job? = null

    /** Gives the guide database's empty space back, a little after a big change, out of the way. */
    private fun scheduleCompact() {
        compactJob?.cancel()
        compactJob = scope.launch {
            delay(30_000)
            BackgroundWork.awaitIdle()
            mutex.withLock { withContext(Dispatchers.IO) { guideDb.compact() } }
        }
    }

    /**
     * What Assign EPG needs when the stored guide was used: only the guides' channel lists. It
     * used to read every guide again here (minutes on big guides, "Reading guide…" as soon as
     * Assign EPG opened), but the automatic matches come from the stored guide and the channel
     * lists are saved or read on their own, which is all Assign EPG uses.
     */
    fun ensureEpgDetails() {
        if (!epgDetailsPending) return
        epgDetailsPending = false
        ensureSourceLists()
    }

    private val savedGuideFile get() = File(baseDir, "guide_cache.bin")

    // ---------------------------------------------------------------- guide database

    @Volatile private var guideInDb = false
    @Volatile private var storedGuideEmpty = false
    @Volatile private var guideHoles = false
    @Volatile private var holeRefreshTried = false
    private val rangeMutex = Mutex()
    private val windowCache = GuideWindowCache()
    private val cacheEpoch = java.util.concurrent.atomic.AtomicLong()
    private val windowLoader by lazy {
        GuideWindowLoader(windowCache, rangeMutex,
            currentVersion = {
                _committedGuideRevision.value?.let { GuideReadVersion(cacheEpoch.get(), guideDb.changes, it) }
            },
            read = { from, to, keys -> guideDb.range(from, to, light = true, channels = keys) },
            onPublished = ::publishWindows)
    }
    private var overview: Map<String, List<EpgProgram>> = emptyMap()
    private var publishedOverviewSource: Map<String, List<EpgProgram>>? = null
    private var publishedOverviewBase: GuideMapSnapshot.Base<String, List<EpgProgram>>? = null
    private val mergedWindows = HashMap<String, Triple<List<EpgProgram>, List<EpgProgram>, List<EpgProgram>>>()
    private val schedules = LinkedHashMap<String, List<EpgProgram>>()
    private var viewportJob: kotlinx.coroutines.Job? = null
    private var overviewJob: kotlinx.coroutines.Job? = null
    @Volatile private var overviewAt = 0L
    @Volatile private var overviewFullAt = 0L
    @Volatile private var overviewShift = Long.MIN_VALUE
    @Volatile private var viewportKeys: List<String> = emptyList()
    @Volatile private var viewportFrom = 0L
    @Volatile private var viewportTo = 0L

    /** Authoritative disk readiness/revision. Browsing reads and eviction never change this. */
    private val _committedGuideRevision = MutableStateFlow<Long?>(null)
    val committedGuideRevision: StateFlow<Long?> = _committedGuideRevision.asStateFlow()
    private val _guideChannelsWithListings = MutableStateFlow<Set<String>>(emptySet())
    val guideChannelsWithListings: StateFlow<Set<String>> = _guideChannelsWithListings.asStateFlow()
    private val _guideGeneration = MutableStateFlow(0)
    val guideGeneration: StateFlow<Int> = _guideGeneration.asStateFlow()

    @Volatile var lightPrograms = false
        private set

    private fun defaultWindow(now: Long = System.currentTimeMillis()) = (now - HOUR) to (now + 4 * HOUR)
    private class GuidePreRead(val fingerprint: String?, val window: Map<String, List<EpgProgram>>)
    private val priorityFile get() = File(baseDir, "guide_priority.txt")
    private fun priorityKeys(): List<String> = runCatching {
        priorityFile.readLines().filter { it.isNotBlank() }.take(GuideWindowCache.MAX_REQUEST_CHANNELS)
    }.getOrDefault(emptyList())
    private suspend fun preReadGuide(): GuidePreRead? = runCatching {
        val fingerprint = guideDb.fingerprint()
        val shift = prefs.currentSettings().epgOffsetMinutes * 60_000L
        val (from, to) = defaultWindow(System.currentTimeMillis() - shift)
        val keys = priorityKeys()
        val window = if (keys.isEmpty()) emptyMap() else runCatching {
            guideDb.range(from, to, light = true, channels = keys)
        }.getOrDefault(emptyMap())
        GuidePreRead(fingerprint, window)
    }.getOrNull()

    private fun beginGuideMutation() {
        cacheEpoch.incrementAndGet()
        _committedGuideRevision.value = null
    }

    /** Publish after a disk commit, keeping the old visible guide usable while files are parsed. */
    private suspend fun publishCommittedGuide(changedChannels: Set<String>? = null, retry: Int = 0): Unit = withContext(Dispatchers.IO) {
        val epoch = cacheEpoch.incrementAndGet()
        val changes = guideDb.changes
        val revision = guideDb.revision()
        try {
        val shift = prefs.currentSettings().epgOffsetMinutes * 60_000L
        val at = System.currentTimeMillis() - shift
        val next = guideDb.nowNext(at, changedChannels)
        val available = guideDb.channelsWithListings(channels = changedChannels)
        val savedSchedules = rangeMutex.withLock { schedules.keys.toList() }
        val settings = prefs.currentSettings()
        val fullSchedules = savedSchedules.associateWith { key ->
            guideDb.channelRange(key, at - settings.epgPastHours.coerceAtLeast(1) * HOUR,
                at + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR)
        }
        rangeMutex.withLock {
            if (cacheEpoch.get() != epoch || guideDb.changes != changes) return@withLock
            if (changedChannels == null) {
                windowCache.clear()
                mergedWindows.clear()
                overview = next
                _guideChannelsWithListings.value = available
                overviewFullAt = System.currentTimeMillis()
            } else {
                windowCache.removeChannels(changedChannels)
                changedChannels.forEach { mergedWindows.remove(it) }
                overview = (overview - changedChannels) + next
                _guideChannelsWithListings.value = (_guideChannelsWithListings.value - changedChannels) + available
            }
            schedules.clear()
            schedules.putAll(fullSchedules)
            overviewAt = System.currentTimeMillis()
            overviewShift = shift
            publishWindows()
            guideInDb = true
            lightPrograms = true
            _guideGeneration.value++
            _committedGuideRevision.value = revision
        }
        // Reload only the visible screen after replacement, even if its group has 100,000 channels.
        val keys = viewportKeys.ifEmpty { priorityKeys() }
        val (from, to) = if (viewportTo > viewportFrom) viewportFrom to viewportTo else defaultWindow(at)
        requestWindow(keys, from, to, replace = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed optional RAM projection must not hide a successfully committed disk
            // guide from Search/Sports or prevent viewport reads from recovering.
            Log.w(TAG, "Couldn't load guide overview", failure)
            rangeMutex.withLock {
                if (cacheEpoch.get() == epoch && guideDb.changes == changes) {
                    windowCache.clear()
                    mergedWindows.clear()
                    // Keep unaffected partial projections and the two schedule requests, so
                    // a retry can restore them even when no screen/navigation key changes.
                    if (changedChannels == null) {
                        overview = emptyMap()
                        _guideChannelsWithListings.value = emptySet()
                    } else {
                        overview = overview - changedChannels
                        _guideChannelsWithListings.value = _guideChannelsWithListings.value - changedChannels
                    }
                    schedules.keys.toList().forEach { key ->
                        if (changedChannels == null || key in changedChannels) schedules[key] = emptyList()
                    }
                    publishWindows()
                    overviewAt = 0L
                    guideInDb = true
                    lightPrograms = true
                    _guideGeneration.value++
                    _committedGuideRevision.value = revision
                }
            }
            if (retry < 3) scope.launch(BackgroundWork.reads) {
                delay(2_000)
                mutex.withLock {
                    if (cacheEpoch.get() == epoch && guideDb.changes == changes) publishCommittedGuide(changedChannels, retry + 1)
                }
            }
        }
    }

    private fun publishWindows() {
        // Overview maps are replaced, never mutated in place. Freeze each identity once, then
        // every small tile publication snapshots only its bounded windows/schedule overrides.
        val base = if (publishedOverviewSource === overview) publishedOverviewBase!! else {
            GuideMapSnapshot.Base.capture(overview).also {
                publishedOverviewSource = overview
                publishedOverviewBase = it
            }
        }
        val shown = HashMap<String, List<EpgProgram>>()
        val windows = windowCache.programs()
        mergedWindows.keys.retainAll(windows.keys)
        windows.forEach { (key, list) ->
            val current = overview[key].orEmpty()
            val prior = mergedWindows[key]
            val merged = if (prior != null && prior.first === list && prior.second === current) prior.third
                else (list + current).distinctBy { it.startMs }.sortedBy { it.startMs }
            mergedWindows[key] = Triple(list, current, merged)
            shown[key] = merged
        }
        schedules.forEach { (key, list) ->
            val current = if (shown.containsKey(key)) shown[key].orEmpty() else base.snapshot[key].orEmpty()
            shown[key] = mergeGuideSchedules(list, current)
        }
        _programs.value = GuideMapSnapshot(base, shown)
    }

    /** Only requested channel/time tiles are decoded; an old WAL read cannot enter a new guide. */
    private fun requestWindow(keys: List<String>, fromMs: Long, toMs: Long, replace: Boolean) {
        if (!guideInDb || keys.isEmpty() || fromMs >= toMs || _committedGuideRevision.value == null) return
        if (replace) viewportJob?.cancel()
        val job = scope.launch(BackgroundWork.reads) {
            try {
                windowLoader.load(keys, fromMs, toMs)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Couldn't read guide window", failure)
            }
        }
        if (replace) viewportJob = job
    }

    fun loadChannelsNow(keys: List<String>) {
        scope.launch {
            val shift = prefs.currentSettings().epgOffsetMinutes * 60_000L
            val (from, to) = defaultWindow(System.currentTimeMillis() - shift)
            requestWindow(keys, from, to, replace = false)
        }
    }

    fun rememberPriority(keys: List<String>) {
        val list = keys.distinct().take(GuideWindowCache.MAX_REQUEST_CHANNELS)
        if (list.isEmpty()) return
        scope.launch(Dispatchers.IO) { runCatching { priorityFile.writeText(list.joinToString("\n")) } }
    }

    /** The grid's rows and hours, including a small directional prefetch margin. */
    fun ensureGuideWindow(keys: List<String>, fromMs: Long, toMs: Long) {
        viewportKeys = keys.distinct().take(GuideWindowCache.MAX_REQUEST_CHANNELS)
        viewportFrom = fromMs
        viewportTo = toMs
        requestWindow(viewportKeys, fromMs, toMs, replace = true)
    }

    private suspend fun loadGuideFromDb(settings: LiveTvSettings, targets: List<EpgTarget>, pre: GuidePreRead?): Boolean = withContext(Dispatchers.IO) {
        if (_channels.value.isEmpty()) return@withContext false
        runCatching { savedGuideFile.delete() }
        val expected = guideFingerprint(settings, targets)
        val stored = pre?.fingerprint ?: runCatching { guideDb.fingerprint() }.getOrNull()
        if (stored != expected) {
            LIVE_REPORT.add(if (stored == null) "No stored guide yet" else "Stored guide out of date: ${fingerprintDiff(stored, expected)}")
            return@withContext false
        }
        _autoMatches.value = runCatching { guideDb.autoMatches() }.getOrDefault(emptyMap())
        epgDetailsPending = true
        // The first screen must not wait for even the two-listing overview of a huge catalog.
        // Publish a small priority read immediately; fill the global now/next view in background.
        val epoch = cacheEpoch.incrementAndGet()
        val revision = guideDb.revision()
        val requestedSchedules = rangeMutex.withLock {
            val requested = schedules.keys.toList()
            windowCache.clear()
            mergedWindows.clear()
            schedules.clear()
            overview = pre?.window.orEmpty()
            publishWindows()
            overviewAt = 0L
            overviewFullAt = 0L
            guideInDb = true
            lightPrograms = true
            _guideGeneration.value++
            _committedGuideRevision.value = revision
            requested
        }
        ensureRange(0L, 0L)
        loadChannelsNow(viewportKeys.ifEmpty { priorityKeys() })
        requestedSchedules.forEach(::ensureChannelSchedule)
        if (_epgSources.value.isEmpty()) scope.launch(Dispatchers.IO) { loadSourceLists(targets) }
        // Availability uses key-only database reads, not whether a channel's RAM tile was visited.
        scope.launch(BackgroundWork.reads) {
            val changes = guideDb.changes
            val available = guideDb.channelsWithListings()
            rangeMutex.withLock {
                if (cacheEpoch.get() != epoch || guideDb.changes != changes) return@launch
                _guideChannelsWithListings.value = available
            }
            val now = System.currentTimeMillis()
            val present = guideDb.channelsWithListings(now - 3 * HOUR, now + 18 * HOUR)
            if (cacheEpoch.get() != epoch || guideDb.changes != changes) return@launch
            val matched = _autoMatches.value.keys
            val missing = matched.count { it !in present }
            val checkedRecently = targets.none { now - epgFile(it.fileId).lastModified() > 3 * HOUR }
            if (!holeRefreshTried && !checkedRecently && present.isNotEmpty() && matched.size >= 20 && missing * 100 > matched.size * 15 && targets.isNotEmpty()) {
                holeRefreshTried = true
                guideHoles = true
                LIVE_REPORT.add("Stored guide has no listings now for $missing of ${matched.size} matched channels: updating the guides")
                scope.launch { runCatching { refreshStale() } }
            }
            if (present.isEmpty() && _channels.value.isNotEmpty() && targets.isNotEmpty()) {
                storedGuideEmpty = true
                guideHoles = !checkedRecently
                scope.launch { runCatching { refreshStale() } }
            }
        }
        true
    }

    suspend fun programDetails(channelKey: String, startMs: Long): EpgProgram? = withContext(Dispatchers.IO) {
        if (!guideInDb) null else runCatching { guideDb.program(channelKey, startMs) }.getOrNull()
    }

    /** Compatibility for small now/next consumers; never widens every channel's RAM schedule. */
    fun ensureRange(fromMs: Long, toMs: Long) {
        if (!guideInDb || _committedGuideRevision.value == null) return
        if (overviewJob?.isActive == true) return
        overviewJob = scope.launch(BackgroundWork.reads) {
            val shift = prefs.currentSettings().epgOffsetMinutes * 60_000L
            if (System.currentTimeMillis() - overviewAt < 60_000L && shift == overviewShift) return@launch
            val epoch = cacheEpoch.get()
            val changes = guideDb.changes
            val now = System.currentTimeMillis()
            val full = shift != overviewShift || now - overviewFullAt >= 30 * 60_000L
            val due = if (full) null else rangeMutex.withLock { overviewChannelsDue(overview, now - shift) }
            if (due != null && due.isEmpty()) { overviewAt = now; return@launch }
            val next = runCatching { guideDb.nowNext(now - shift, due) }.getOrNull() ?: return@launch
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (prefs.currentSettings().epgOffsetMinutes * 60_000L != shift) return@launch
            rangeMutex.withLock {
                if (cacheEpoch.get() != epoch || guideDb.changes != changes) return@withLock
                overview = if (due == null) next else (overview - due) + next
                if (full) overviewFullAt = now
                overviewAt = System.currentTimeMillis()
                overviewShift = shift
                publishWindows()
            }
        }
    }

    // ---------------------------------------------------------------- Xtream catch-up archive

    /** Channels whose archive listings were already fetched this session. */
    private val archiveFetched = java.util.Collections.synchronizedSet(HashSet<String>())
    private val archivePermits = kotlinx.coroutines.sync.Semaphore(3)
    @Volatile private var archivePausedUntil = 0L
    @Volatile private var apiFillReported = false

    /**
     * Past listings for Xtream catch-up channels, the way TiviMate gets them: many providers'
     * guide file only covers about a day back, while the Xtream API keeps the full catch-up
     * archive (up to 7 days or more) per channel. Fetched for the channels you're looking at,
     * once per session, and stored with the rest of the guide.
     */
    fun loadXtreamArchive(channels: List<LiveChannel>, missingOnly: Boolean = false) {
        // missingOnly: channels the guide file has nothing for. Most Xtream apps read each
        // channel's listings from the provider's API by stream number, so they show a guide
        // even when the guide file's ids don't line up with the channel list; this does the same.
        val wanted = channels.filter { ch ->
            ch.key !in archiveFetched &&
                (if (missingOnly) ch.key !in _guideChannelsWithListings.value else ch.catchup != null)
        }
        if (wanted.isEmpty()) return

        wanted.forEach { archiveFetched += it.key }
        if (System.currentTimeMillis() < archivePausedUntil) {
            wanted.forEach { archiveFetched -= it.key }
            return
        }
        scope.launch {
            var archiveEpoch = cacheEpoch.get()
            val playlists = prefs.currentPlaylists().associateBy { it.id }
            val settings = prefs.currentSettings()
            val overrides = runCatching { prefs.userState.first().epgOverrides }.getOrDefault(emptyMap())
            wanted.forEach { ch ->
                val pl = playlists[ch.sourceId]?.takeIf { it.isXtream } ?: return@forEach
                val streamId = Regex("""/(\d+)(?:\.[a-z0-9]+)?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
                    .find(ch.url)?.groupValues?.get(1) ?: return@forEach
                archivePermits.withPermit {
                    // The provider said "too many requests": leave it alone for a few minutes, so
                    // these lookups never stand between you and playing something.
                    if (System.currentTimeMillis() < archivePausedUntil) { archiveFetched -= ch.key; return@withPermit }
                    runCatching {
                        val url = "${pl.xtreamBase()}/player_api.php?username=" +
                            java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8") +
                            "&password=" + java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8") +
                            "&action=get_simple_data_table&stream_id=$streamId"
                        val json = JSONObject(getText(url, pl.userAgent.ifBlank { DEFAULT_UA }))
                        val arr = json.optJSONArray("epg_listings") ?: return@runCatching
                        fun b64(v: String): String = runCatching {
                            String(android.util.Base64.decode(v, android.util.Base64.DEFAULT), Charsets.UTF_8)
                        }.getOrDefault(v)
                        val list = ArrayList<EpgProgram>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
                            val stop = o.optString("stop_timestamp").toLongOrNull()?.times(1000)
                                ?: o.optString("end_timestamp").toLongOrNull()?.times(1000) ?: continue
                            if (stop <= start) continue
                            val title = b64(o.optString("title")).trim()
                            if (title.isEmpty()) continue
                            list += EpgProgram(
                                startMs = start,
                                stopMs = stop,
                                title = title,
                                description = b64(o.optString("description")).trim().takeIf { it.isNotEmpty() }
                            )
                        }
                        if (list.isEmpty()) return@runCatching
                        list.sortBy { it.startMs }
                        mutex.withLock {
                        if (cacheEpoch.get() != archiveEpoch || _channels.value.none { it.key == ch.key }) {
                            archiveFetched -= ch.key
                            return@withLock
                        }
                        // Moved like the rest of this channel's listings (its guide's time shift).
                        val shift = guideShiftMs(ch, overrides)
                        if (shift != 0L) for (i in list.indices) list[i] = list[i].let { it.copy(startMs = it.startMs + shift, stopMs = it.stopMs + shift) }
                        val now = System.currentTimeMillis()
                        // Keep what the guide file already has from about now on; the archive
                        // fills in the past.
                        val pastEnd = minOf(now, list.last().stopMs)
                        val past = list.filter { it.startMs < pastEnd }
                        // The browsing cache deliberately omits most hours. Never mistake its
                        // end for the end of the authoritative provider guide.
                        val existing = if (guideInDb) guideDb.channelRange(ch.key,
                            minOf(list.first().startMs, now - settings.epgPastHours.coerceAtLeast(1) * HOUR),
                            now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR)
                            else _programs.value[ch.key].orEmpty()
                        // Beyond the end of the guide file's listings, the archive's later
                        // shows are used too (some guide files only cover a day ahead).
                        val existingEnd = existing.maxOfOrNull { it.stopMs } ?: now
                        val later = list.filter { it.startMs >= maxOf(existingEnd, now) }
                        if (past.isEmpty() && later.isEmpty()) return@withLock
                        val from = past.firstOrNull()?.startMs ?: pastEnd
                        val keep = existing.filter { it.startMs >= pastEnd || it.startMs < from }
                        val merged = (past + keep + later).sortedBy { it.startMs }
                            .fold(ArrayList<EpgProgram>()) { acc, p -> if (acc.isEmpty() || p.startMs >= acc.last().stopMs - 60_000) acc += p; acc }
                        if (guideInDb) {
                            beginGuideMutation()
                            try {
                                if (past.isNotEmpty()) guideDb.putChannelRange(ch.key, from, pastEnd, past)
                                if (later.isNotEmpty()) guideDb.putChannelRange(ch.key, later.first().startMs, later.last().stopMs, later)
                            } finally {
                                publishCommittedGuide(setOf(ch.key))
                            }
                            archiveEpoch = cacheEpoch.get()
                        } else {
                            _programs.value = _programs.value + (ch.key to merged)
                        }
                        if (missingOnly && !apiFillReported) {
                            apiFillReported = true
                            LIVE_REPORT.add("Guide: listings for channels the guide file doesn't cover are coming from the Xtream API (first: ${ch.name})")
                        }
                        }
                    }.onFailure {
                        Log.w(TAG, "Catch-up archive for ${ch.name} unavailable", it)
                        if (it.message?.contains("429") == true) {
                            archivePausedUntil = System.currentTimeMillis() + 5 * 60_000L
                            archiveFetched -= ch.key
                            LIVE_REPORT.add("Provider said too many requests (HTTP 429): catch-up lookups paused for 5 minutes")
                        }
                    }
                }
            }
        }
    }

    /** One channel's whole schedule (overlay mode's days, catch-up following into later shows). */
    /** A channel's listings from an hour ago to [days] ahead (series recordings look through these). */
    suspend fun upcomingListings(channelKey: String, days: Int): List<EpgProgram> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!guideInDb) return@withContext _programs.value[channelKey].orEmpty()
        runCatching { guideDb.channelRange(channelKey, now - HOUR, now + days * 24 * HOUR) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: _programs.value[channelKey].orEmpty()
    }

    fun ensureChannelSchedule(channelKey: String) {
        if (!guideInDb) return
        scope.launch(BackgroundWork.reads) {
            val epoch = cacheEpoch.get()
            val changes = guideDb.changes
            val now = System.currentTimeMillis()
            val settings = prefs.currentSettings()
            val list = runCatching {
                guideDb.channelRange(channelKey, now - settings.epgPastHours.coerceAtLeast(1) * HOUR,
                    now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR)
            }.getOrNull() ?: return@launch
            rangeMutex.withLock {
                if (cacheEpoch.get() != epoch || guideDb.changes != changes) return@withLock
                schedules.remove(channelKey)
                schedules[channelKey] = list
                while (schedules.size > 2) schedules.remove(schedules.keys.first())
                publishWindows()
            }
        }
    }

    /** Everything the matched guide depends on; a different value means the cache is stale. */
    private suspend fun guideFingerprint(settings: LiveTvSettings, targets: List<EpgTarget>): String {
        val overrides = prefs.userState.first().epgOverrides
        val sb = StringBuilder()
        sb.append(GUIDE_CACHE_VERSION).append('|')
        sb.append(settings.epgPastHours).append('|').append(settings.epgFutureDays).append('|')
        targets.forEach { t ->
            val f = epgFile(t.fileId)
            sb.append(t.fileId).append(':').append(f.length()).append(':').append(contentStamp(f))
            if (t.shiftMs != 0L) sb.append(":s=").append(t.shiftMs / 60_000L)
            sb.append(',')
        }
        sb.append('|').append(overrides.hashCode())
        sb.append('|').append(_channels.value.size).append(':').append(_channels.value.sumOf { it.key.hashCode().toLong() })
        tvgShiftStamp()?.let { sb.append("|tvg=").append(it) }
        return sb.toString()
    }

    /** One guide file as the fingerprint sees it (also the format of the Assign EPG lists' stamp). */
    private fun targetStamp(t: EpgTarget): String {
        val f = epgFile(t.fileId)
        return t.fileId + ":" + f.length() + ":" + contentStamp(f) + (if (t.shiftMs != 0L) ":s=${t.shiftMs / 60_000L}" else "")
    }

    /** The channels' tvg-shifts as the fingerprint sees them (null when no channel has one: older stamps stay valid). */
    private fun tvgShiftStamp(): String? {
        var h = 0L
        var any = false
        for (c in _channels.value) if (c.tvgShiftMinutes != 0) { any = true; h = h * 31 + c.key.hashCode() * 7L + c.tvgShiftMinutes }
        return if (any) h.toString() else null
    }

    /**
     * The fingerprint's parts, stored next to it: settings and hand-picked guides ("base"), each
     * guide file in order ("targets"), and each playlist's channels ("playlists"). With these, a
     * playlist turned off or removed can be told apart from any other change (see
     * [dropRemovedPlaylistsFromGuide]).
     */
    private suspend fun guideParts(settings: LiveTvSettings, targets: List<EpgTarget>, fingerprint: String): String {
        val overrides = prefs.userState.first().epgOverrides
        val playlists = JSONObject()
        _channels.value.groupBy { it.sourceId }.forEach { (id, list) ->
            playlists.put(id, "${list.size}:${list.sumOf { it.key.hashCode().toLong() }}")
        }
        return JSONObject()
            .put("fp", fingerprint)
            .put("base", "$GUIDE_CACHE_VERSION|${settings.epgPastHours}|${settings.epgFutureDays}|${overrides.hashCode()}" +
                (tvgShiftStamp()?.let { "|tvg=$it" } ?: ""))
            .put("targets", org.json.JSONArray(targets.map { targetStamp(it) }))
            .put("playlists", playlists)
            .toString()
    }

    /**
     * A playlist turned off or removed changes the guide's fingerprint, which used to mean reading
     * every other guide file again from start to finish. But each channel's guide match only
     * depends on that channel and the guides, so taking a playlist away changes nothing for the
     * others: its channels' listings are simply deleted from the stored guide.
     *
     * Only when nothing else changed: same settings and hand-picked guides, every other playlist
     * with exactly the channels it had, and the remaining guide files unchanged and in the same
     * order (a playlist's built-in guide may go with it, as long as no remaining channel was
     * matched to it). Otherwise (or for a guide stored by an older version) false: read it all.
     */
    private suspend fun dropRemovedPlaylistsFromGuide(settings: LiveTvSettings, targets: List<EpgTarget>): Boolean = withContext(Dispatchers.IO) {
        val channels = _channels.value
        if (channels.isEmpty()) return@withContext false
        val stored = runCatching { guideDb.fingerprint() }.getOrNull() ?: return@withContext false
        val old = runCatching { guideDb.parts()?.let { JSONObject(it) } }.getOrNull() ?: return@withContext false
        if (old.optString("fp") != stored) return@withContext false
        val fingerprint = guideFingerprint(settings, targets)
        val partsText = guideParts(settings, targets, fingerprint)
        val now = JSONObject(partsText)
        if (old.optString("base") != now.optString("base")) return@withContext false

        // Guide files: the remaining ones unchanged and in the same order, the rest only taken away.
        fun list(o: JSONObject): List<String> {
            val a = o.optJSONArray("targets") ?: return emptyList()
            return List(a.length()) { a.getString(it) }
        }
        val oldTargets = list(old)
        val newTargets = list(now)
        var i = 0
        for (t in oldTargets) if (i < newTargets.size && newTargets[i] == t) i++
        if (i != newTargets.size) return@withContext false
        val keptTargetIds = targets.mapTo(HashSet()) { it.fileId }
        val removedTargets = oldTargets.map { it.substringBefore(':') }.filterTo(HashSet()) { it !in keptTargetIds }

        // Playlists: every one still on has exactly the channels it had.
        val oldPl = old.optJSONObject("playlists") ?: return@withContext false
        val newPl = now.getJSONObject("playlists")
        for (id in newPl.keys()) if (oldPl.optString(id) != newPl.getString(id)) return@withContext false
        val removedPlaylists = oldPl.keys().asSequence().filterTo(HashSet()) { !newPl.has(it) }
        if (removedPlaylists.isEmpty()) return@withContext false

        val keys = channels.mapTo(HashSet()) { it.key }
        // A remaining channel matched to a guide that went away would need another match: read it all.
        val auto = runCatching { guideDb.autoMatches() }.getOrNull() ?: return@withContext false
        if (auto.any { (key, a) -> key in keys && a.sourceId in removedTargets }) return@withContext false

        // Hidden channels: group ids can change with the number of playlists ("playlist::group"
        // with several, just "group" with one), so check what's hidden against the stored guide.
        val hidden = prefs.currentHiddenEverywhere()
        val nowHidden = channels.filter { hidden.hides(it.key, it.groupId) }.mapTo(HashSet()) { it.key }
        val skipped = (loadSkipped() ?: emptySet()).filterTo(HashSet()) { it in keys }
        if (keys.any { it in skipped && it !in nowHidden }) return@withContext false // shown again: needs listings
        val newlyHidden = nowHidden - skipped
        // Remaining channels hand-assigned to a guide that went away have no listings any more.
        val overrides = prefs.userState.first().epgOverrides
        val lostPinned = overrides.filter { (key, a) -> key in keys && a.sourceId in removedTargets }.keys

        beginGuideMutation()
        runCatching { guideDb.dropPlaylists(removedPlaylists, newlyHidden + lostPinned, fingerprint, partsText) }
            .onFailure { Log.w(TAG, "Couldn't take turned-off playlists out of the guide", it); return@withContext false }
        saveSkipped(skipped + newlyHidden)
        val logos = _epgLogos.value.filterKeys { it in keys && it !in lostPinned }
        if (logos.size != _epgLogos.value.size) { _epgLogos.value = logos; saveEpgLogos(logos) }
        trimSourceLists(removedTargets, oldTargets, targets)
        LIVE_REPORT.add("Guide: ${removedPlaylists.size} playlist(s) taken out without re-reading the guides")
        scheduleCompact()
        // The guide now matches its fingerprint: load it the usual way.
        loadGuideFromDb(settings, targets, preReadGuide())
    }

    /**
     * Guides that went with a turned-off playlist: taken out of Assign EPG's lists, in memory and
     * in the saved copy (re-stamped for the remaining guides, so it isn't thrown away).
     */
    private fun trimSourceLists(removed: Set<String>, oldStamps: List<String>, targets: List<EpgTarget>) {
        if (_epgSources.value.isNotEmpty()) {
            _epgSources.value = _epgSources.value.filter { it.sourceId !in removed }
        }
        val f = sourceListsFile
        if (!f.exists()) return
        val lists = runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(f.inputStream()))).use { input ->
                if (input.readInt() != SOURCE_LISTS_VERSION) return@use null
                if (input.readUTF() != oldStamps.joinToString(",")) return@use null
                List(input.readInt()) {
                    val sourceId = input.readUTF(); val name = input.readUTF(); val label = input.readUTF()
                    val entries = List(input.readInt()) {
                        val id = input.readUTF()
                        val names = List(input.readInt()) { input.readUTF() }
                        EpgChannelEntry(id, names, input.readUTF().ifBlank { null })
                    }
                    EpgSourceChannels(sourceId, name, label, entries)
                }
            }
        }.getOrNull() ?: return
        saveSourceLists(targets, lists.filter { it.sourceId !in removed })
    }

    private suspend fun loadSavedGuide(settings: LiveTvSettings, targets: List<EpgTarget>): Boolean = withContext(Dispatchers.IO) {
        val file = savedGuideFile
        if (!file.exists() || _channels.value.isEmpty()) return@withContext false
        val expected = guideFingerprint(settings, targets)
        runCatching {
            java.io.DataInputStream(java.io.BufferedInputStream(java.util.zip.GZIPInputStream(file.inputStream()), 64 * 1024)).use { input ->
                if (input.readUTF() != expected) return@withContext false
                val programs = HashMap<String, List<EpgProgram>>()
                repeat(input.readInt()) {
                    val key = input.readUTF()
                    val list = ArrayList<EpgProgram>()
                    repeat(input.readInt()) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val desc = readOpt(input)
                        val category = readOpt(input)
                        val episode = XmltvParser.cleanEpisode(readOpt(input))
                        val icon = readOpt(input)
                        val year = input.readInt().takeIf { it > 0 }
                        val people = List(input.readInt()) { input.readUTF() }
                        list += EpgProgram(start, stop, title, desc, category, episode, icon, year, people)
                    }
                    programs[key] = list
                }
                val auto = HashMap<String, EpgAssignment>()
                repeat(input.readInt()) {
                    auto[input.readUTF()] = EpgAssignment(input.readUTF(), input.readUTF())
                }
                // Drop shows that have ended since the cache was saved (outside the window).
                val windowStart = System.currentTimeMillis() - settings.epgPastHours.coerceAtLeast(1) * HOUR
                _programs.value = programs.mapValues { (_, l) -> l.filter { it.stopMs > windowStart } }
                _autoMatches.value = auto
                epgDetailsPending = true
                true
            }
        }.getOrElse {
            Log.w(TAG, "Saved guide unreadable", it)
            false
        }
    }

    private fun saveGuide(fingerprint: String, programs: Map<String, List<EpgProgram>>, auto: Map<String, EpgAssignment>) {
        // Huge guides (hundreds of thousands of listings) would make a very large file that's
        // slow to write and read; those are simply re-matched at start as before.
        if (programs.values.sumOf { it.size } > MAX_SAVED_LISTINGS) {
            savedGuideFile.delete()
            return
        }
        runCatching {
            val tmp = File(baseDir, "guide_cache.tmp")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.util.zip.GZIPOutputStream(tmp.outputStream()), 64 * 1024)).use { out ->
                out.writeUTF(fingerprint)
                out.writeInt(programs.size)
                programs.forEach { (key, list) ->
                    out.writeUTF(key)
                    out.writeInt(list.size)
                    list.forEach { p ->
                        out.writeLong(p.startMs)
                        out.writeLong(p.stopMs)
                        out.writeUTF(p.title.take(500))
                        writeOpt(out, p.description?.take(4000))
                        writeOpt(out, p.category?.take(200))
                        writeOpt(out, p.episode?.take(200))
                        writeOpt(out, p.icon?.take(1000))
                        out.writeInt(p.year ?: 0)
                        out.writeInt(p.people.size.coerceAtMost(20))
                        p.people.take(20).forEach { out.writeUTF(it.take(200)) }
                    }
                }
                out.writeInt(auto.size)
                auto.forEach { (key, a) -> out.writeUTF(key); out.writeUTF(a.sourceId); out.writeUTF(a.xmltvId) }
            }
            if (!tmp.renameTo(savedGuideFile)) { tmp.copyTo(savedGuideFile, overwrite = true); tmp.delete() }
        }.onFailure { Log.w(TAG, "Couldn't save the guide", it) }
    }

    private fun readOpt(input: java.io.DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null

    private fun writeOpt(out: java.io.DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeUTF(value)
    }

    private fun setStatus(loading: Boolean, message: String?, quiet: Boolean = false) {
        _status.value = _status.value.copy(loading = loading, message = message, quiet = quiet)
    }

    private data class EpgTarget(
        val fileId: String,
        val sourceId: String?,
        val name: String,
        val url: String,
        val userAgent: String,
        /** A playlist's own (built-in) guide: that playlist. */
        val playlistId: String? = null,
        /** How far this guide's listings are moved (its own time shift), in ms. */
        val shiftMs: Long = 0L
    )

    /** Each guide file's time shift (ms), and each playlist's built-in guide shift, as last collected. */
    @Volatile private var targetShifts: Map<String, Long> = emptyMap()
    @Volatile private var playlistGuideShifts: Map<String, Long> = emptyMap()

    /**
     * How far a channel's stored listings were moved: the time shift of the guide it's matched to
     * (or given in Assign EPG; its playlist's own guide when it has none) plus the playlist's
     * tvg-shift for it. Catch-up takes this back off: the archive goes by the guide's own times.
     */
    fun guideShiftMs(channel: LiveChannel, overrides: Map<String, EpgAssignment>): Long {
        val a = overrides[channel.key]?.takeUnless { it.isNone } ?: _autoMatches.value[channel.key]
        val source = a?.let { targetShifts[it.sourceId] } ?: playlistGuideShifts[channel.sourceId] ?: 0L
        return source + channel.tvgShiftMinutes * 60_000L
    }

    private fun List<EpgProgram>.shiftedBy(ms: Long): List<EpgProgram> =
        if (ms == 0L || isEmpty()) this else map { it.copy(startMs = it.startMs + ms, stopMs = it.stopMs + ms) }

    /** Named EPG sources first (they win on conflicts), then EPG URLs embedded in playlists. */
    private suspend fun collectEpgTargets(): List<EpgTarget> {
        // The old one-for-everything time shifts, moved onto each playlist and guide (once).
        if (runCatching { prefs.moveTimeShiftsToSources() }.getOrDefault(false)) {
            LIVE_REPORT.add("Time shifts: the old Guide / Catch-Up Time Shift moved onto each playlist and guide")
        }
        val targets = mutableListOf<EpgTarget>()
        // A guide added on its own from the same server as a playlist (often that playlist's own
        // guide link) is asked for with that playlist's user agent, like the playlist itself:
        // some servers answer a different user agent with a different (or empty) guide.
        val allPlaylists = prefs.currentPlaylists()
        fun hostOf(u: String) = runCatching { java.net.URI(u.trim()).host?.lowercase() }.getOrNull()
        prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.forEach { src ->
            val host = hostOf(src.url)
            val ua = allPlaylists.firstOrNull { pl ->
                pl.userAgent.isNotBlank() && host != null &&
                    hostOf(if (pl.isXtream) pl.xtreamBase() else pl.url) == host
            }?.userAgent.orEmpty()
            targets += EpgTarget(src.id, src.id, src.name, src.url, ua, shiftMs = src.shiftMinutes * 60_000L)
        }
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled && it.useEmbeddedEpg }
        for (pl in playlists) {
            val urls = mutableListOf<String>()
            pl.xtreamEpgUrl()?.let { urls += it }
            urls += embeddedEpgUrls[pl.id].orEmpty()
            urls.distinct().forEach { url ->
                if (targets.none { it.url == url }) {
                    targets += EpgTarget(
                        "auto_" + sha1(url), null, "${pl.name} (built-in)", url, pl.userAgent,
                        playlistId = pl.id, shiftMs = pl.guideShiftMinutes * 60_000L
                    )
                }
            }
        }
        targetShifts = targets.associate { it.fileId to it.shiftMs }
        playlistGuideShifts = allPlaylists.associate { it.id to it.guideShiftMinutes * 60_000L }
        return targets
    }

    private val embeddedEpgUrls = HashMap<String, List<String>>()

    /** Channel key → stream link from an out-of-date saved channel list (see [loadSavedChannels]). */
    private var previousLinks: Map<String, String> = emptyMap()

    /**
     * A channel renamed in the playlist (by the provider or a playlist editor) gets a new key.
     * This moves favorites, hidden channels, groups you made, renames, guide choices and so on
     * over to it (see [RenamedChannels]). [before]: each channel's stream link before the
     * playlists were read again, null when there's no earlier list. Without one, any channel can
     * be the match, so that's only done once per TV (for channels renamed before this existed).
     */
    private suspend fun carryOverRenamedChannels(before: Map<String, String>?, now: List<LiveChannel>) {
        runCatching {
            if (now.isEmpty()) return
            val firstTime = !prefs.renamedChannelsChecked()
            if (before == null && !firstTime) return
            val current = now.mapTo(HashSet(now.size * 2)) { it.key }
            val lost = prefs.channelKeysInUse().filter { it !in current }
            val moves = RenamedChannels.match(lost, now, before.orEmpty(), onlyNew = !firstTime)
            if (moves.isNotEmpty()) {
                prefs.moveChannelKeys(moves)
                LIVE_REPORT.add("Renamed channels: settings moved over for ${moves.size}")
            }
            if (firstTime) prefs.setRenamedChannelsChecked()
        }.onFailure { Log.w(TAG, "Carrying settings over to renamed channels failed", it) }
    }

    private suspend fun buildChannels() = withContext(BackgroundWork.cpu()) {
        val before = (_channels.value.takeIf { it.isNotEmpty() }?.associate { it.key to it.url } ?: previousLinks)
            .takeIf { it.isNotEmpty() }
        previousLinks = emptyMap()
        val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
        val multi = playlists.size > 1
        val out = ArrayList<LiveChannel>()
        val usedKeys = HashSet<String>()
        var counter = 1
        for ((plIndex, pl) in playlists.withIndex()) {
            val file = playlistFile(pl.id)
            if (!file.exists()) continue
            setStatus(loading = true, message = "Reading channels from \"${pl.name}\"" + (if (playlists.size > 1) " (${plIndex + 1} of ${playlists.size})" else "") + "…")
            val parsed = runCatching { openMaybeGzip(file).bufferedReader().use { M3uParser.parse(it) } }
                .onFailure { Log.w(TAG, "Parse ${pl.name} failed", it) }
                .getOrNull() ?: continue
            embeddedEpgUrls[pl.id] = parsed.epgUrls
            for (e in parsed.entries) {
                // Many providers' M3U links ("m3u_plus") also list every movie and episode,
                // often 100,000+ entries. Those aren't TV channels, and loading them all into the
                // guide used enough memory to crash Live TV on some devices.
                if (isVodEntry(e.url)) continue
                val groupTitle = e.group?.ifBlank { null } ?: "Uncategorized"
                val baseKey = "${pl.id}|${e.tvgId ?: ""}|${e.name}"
                var key = baseKey
                var n = 2
                while (!usedKeys.add(key)) { key = "$baseKey#${n++}" }
                val number = e.chno ?: counter
                counter = maxOf(counter, number) + 1
                val headers = if (pl.userAgent.isNotBlank() && "User-Agent" !in e.headers) {
                    e.headers + ("User-Agent" to pl.userAgent)
                } else e.headers
                out += LiveChannel(
                    key = key,
                    sourceId = pl.id,
                    sourceName = pl.name,
                    name = e.name,
                    tvgId = e.tvgId,
                    tvgName = e.tvgName,
                    logo = e.logo,
                    groupId = if (multi) "${pl.id}::$groupTitle" else groupTitle,
                    // Names stay exactly as the playlist has them (the playlist is shown, if you want
                    // it, with "Group by playlist").
                    group = groupTitle,
                    number = number,
                    url = pl.m3uStreamLink(e.url),
                    headers = headers,
                    catchup = e.catchup,
                    drm = e.drm,
                    tvgShiftMinutes = e.tvgShiftMinutes
                )
            }
            prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(channelCount = parsed.entries.size) else it } }
        }
        loadGroupOrder()
        _channels.value = out
        carryOverRenamedChannels(before, out)
    }

    private suspend fun buildPrograms(settings: LiveTvSettings, targets: List<EpgTarget>) {
        val t0 = System.currentTimeMillis()
        BackgroundWork.guideBuilding.value = true
        try { BackgroundWork.heavy { buildProgramsInner(settings, targets) } } finally { BackgroundWork.guideBuilding.value = false }
        LIVE_REPORT.add("Guide matched and stored: ${_programs.value.size} channels in ${System.currentTimeMillis() - t0} ms")
    }

    private suspend fun buildProgramsInner(settings: LiveTvSettings, targets: List<EpgTarget>) = withContext(BackgroundWork.cpu()) {
        val all = _channels.value
        if (all.isEmpty()) {
            beginGuideMutation()
            guideDb.replaceAll(guideFingerprint(settings, targets), emptyMap(), emptyMap())
            _autoMatches.value = emptyMap()
            publishCommittedGuide()
            return@withContext
        }
        // Channels no profile shows (hidden, or in a hidden group, in every profile) get no
        // listings: nobody sees them, and big providers' hidden groups made the guide database
        // many times bigger than what's watched. Showing them again reads their listings back.
        val hidden = prefs.currentHiddenEverywhere()
        val skipped = HashSet<String>()
        val channels = all.filter { c -> (!hidden.hides(c.key, c.groupId)).also { shown -> if (!shown) skipped += c.key } }
        LIVE_REPORT.add(
            "Guide matching: ${channels.size} of ${all.size} channels shown somewhere" +
                " (${channels.count { !it.tvgId.isNullOrBlank() }} with a tvg-id)"
        )
        // Which playlist the channels come from (so "171,518 channels" can be told apart: one huge
        // playlist, or several), and how full the app's memory is to begin with.
        run {
            val counts = all.groupingBy { it.sourceId }.eachCount()
            LIVE_REPORT.add("Playlists: " + counts.entries.sortedByDescending { it.value }.joinToString { "${it.key.take(12)} ${it.value}" })
            val rt = Runtime.getRuntime()
            LIVE_REPORT.add("Memory before matching: ${(rt.totalMemory() - rt.freeMemory()) / 1048576} of ${rt.maxMemory() / 1048576} MB")
        }
        val now = System.currentTimeMillis()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR

        // Hand-picked guides (long-press > Change EPG) win over automatic matching.
        val overrides = prefs.userState.first().epgOverrides
            .filterKeys { key -> channels.any { it.key == key } }

        val byTvgId = HashMap<String, MutableList<LiveChannel>>()
        val byName = HashMap<String, MutableList<LiveChannel>>()
        channels.forEach { c ->
            if (c.key in overrides) return@forEach
            c.tvgId?.lowercase()?.let { byTvgId.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.tvgName ?: "").takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            normalize(c.name).takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            // The tvg-id read as a name ("GloboSP.br" ~ a guide channel called "Globo SP").
            c.tvgId?.let { idName(it) }?.takeIf { it.length >= 3 }?.let { k ->
                val l = byName.getOrPut(k) { ArrayList() }
                if (c !in l) l.add(c)
            }
        }

        // Every guide is read; each channel keeps its best candidate:
        //   1. real listings beat placeholder listings ("Programming", "No information", …)
        //   2. a tvg-id match beats a name match
        //   3. then the earlier guide in the list
        // so a guide full of placeholders can't hide real listings from another guide.
        data class Candidate(val score: Long, val sourceId: String, val xmltvId: String)
        val best = HashMap<String, Candidate>()
        val pinned = HashMap<String, List<EpgProgram>>()
        val sourceLists = ArrayList<EpgSourceChannels>()
        val sourceMeta = ArrayList<Triple<EpgTarget, String, List<EpgChannelEntry>>>()
        // Listings stream straight to SQLite. Browsing tiles and now/next are decoded only
        // after commit; keeping a second 21-hour copy for every source exhausted small boxes.
        // What a guide's stored copy depends on besides the file: the channels it was matched for,
        // hand-picked guides and the hours kept. Unchanged, a guide file isn't read again.
        val matchStamp = run {
            var h = channels.size.toLong()
            for (c in channels) h = h * 31 + c.key.hashCode() * 7L + (c.tvgId?.hashCode() ?: 0) * 3L + (c.tvgName?.hashCode() ?: 0) + c.name.hashCode()
            "$h|${overrides.hashCode()}|${settings.epgPastHours}|${settings.epgFutureDays}"
        }

        for ((order, target) in targets.withIndex()) {
            val file = epgFile(target.fileId)
            if (!file.exists()) { LIVE_REPORT.add("Guide \"${target.name}\": no file downloaded yet"); continue }
            val src = target.fileId
            val stageStamp = targetStamp(target) + "|" + matchStamp
            // Reusing a guide read earlier (when its file hasn't changed) is switched off for now:
            // it meant keeping every guide's copy, about twice the storage.
            val reuse = false
            val step = if (targets.size > 1) " (${order + 1} of ${targets.size})" else ""
            val pinnedHere = overrides.filterValues { it.sourceId == target.fileId }
            val idHits = HashMap<String, MutableSet<LiveChannel>>()
            val nameHits = HashMap<String, MutableSet<LiveChannel>>()
            fun matchList(xmlChannels: Collection<Pair<String, List<String>>>) {
                xmlChannels.forEach { (id, names) ->
                    byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    (names.map { normalize(it) } + normalize(id) + idName(id)).distinct().forEach { k ->
                        byName[k]?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    }
                }
            }
            val entries: List<EpgChannelEntry>
            val meta: Map<String, EpgDatabase.StagedMeta>
            if (reuse) {
                val staged = runCatching { guideDb.stagedChannels(src) to guideDb.stagedMeta(src) }.getOrNull()
                if (staged == null) { LIVE_REPORT.add("Guide \"${target.name}\": stored copy couldn't be read"); continue }
                entries = staged.first.map { (id, names, icon) -> EpgChannelEntry(id, names, icon) }
                meta = staged.second
                if (entries.any { it.names.isNotEmpty() }) matchList(entries.map { it.id to it.names })
                LIVE_REPORT.add("Guide \"${target.name}\": file unchanged, used the copy read before (${meta.size} channels)")
            } else {
                // Memory nearly full (a huge playlist): reading another guide would freeze the
                // app, so it's left for later and the report says so.
                if (order > 0) {
                    val rt = Runtime.getRuntime()
                    fun used() = rt.totalMemory() - rt.freeMemory()
                    if (used() > rt.maxMemory() * 0.85) System.gc()
                    if (used() > rt.maxMemory() * 0.85) {
                        LIVE_REPORT.add("Guide \"${target.name}\" skipped: memory nearly full (${used() / 1048576} of ${rt.maxMemory() / 1048576} MB)")
                        setStatus(loading = true, message = "Not enough memory for guide \"${target.name}\" — skipped")
                        continue
                    }
                }
                // How far through each guide file it is, so a big guide doesn't look frozen.
                val fileSize = file.length().coerceAtLeast(1L)
                var shownAt = 0L
                val showProgress: (Long) -> Unit = { done ->
                    val now = System.currentTimeMillis()
                    if (now - shownAt > 750) {
                        shownAt = now
                        setStatus(loading = true, message = "Reading guide \"${target.name}\"$step… ${(done * 100 / fileSize).coerceIn(0, 99)}%")
                    }
                }
                val readStart = System.currentTimeMillis()
                val writer = runCatching { guideDb.startStage(src) }
                    .onFailure { Log.w(TAG, "Couldn't start storing ${target.name}", it) }.getOrNull()
                if (writer == null) { LIVE_REPORT.add("Guide \"${target.name}\": couldn't be stored"); continue }
                val parsed = runCatching {
                    openMaybeGzip(file, showProgress).use { input ->
                        XmltvParser.parse(
                            input, windowStart, windowEnd,
                            // No channel list in this guide: keep only shows for channels the matching
                            // below can use (the same id / name test) or picked by hand.
                            keepUnlisted = { id ->
                                byTvgId.containsKey(id.lowercase()) || byName.containsKey(normalize(id)) || byName.containsKey(idName(id)) ||
                                    pinnedHere.values.any { it.xmltvId == id }
                            },
                            // Each channel's shows go to the database as soon as they're read.
                            onPrograms = { xid, list ->
                                // This guide's own time shift, applied as it's stored.
                                val cleaned = cleanListings(list).shiftedBy(target.shiftMs)
                                writer.add(xid, cleaned) { isPlaceholder(it) }
                            },
                            selectChannels = { xmlChannels ->
                                matchList(xmlChannels.values.map { it.id to it.displayNames })
                                if (xmlChannels.isEmpty()) null
                                else idHits.keys + nameHits.keys + pinnedHere.values.map { it.xmltvId }
                            }
                        )
                    }
                }.onFailure {
                    writer.abort()
                    Log.w(TAG, "EPG parse ${target.name} failed", it)
                    val start = runCatching {
                        openMaybeGzip(file).use { inp -> String(inp.readNBytesCompat(120), Charsets.UTF_8) }
                    }.getOrDefault("").replace(Regex("\\s+"), " ").trim()
                    LIVE_REPORT.add("Guide \"${target.name}\" couldn't be read (${it.message?.take(140) ?: it.javaClass.simpleName}); ${file.length() / 1024} KB starting \"$start\"")
                }.getOrNull() ?: continue
                parsed.error?.let { err ->
                    LIVE_REPORT.add("Guide \"${target.name}\" is broken at $err. Kept what was read before that.")
                }
                entries = if (parsed.channels.isNotEmpty()) {
                    parsed.channels.values.map { EpgChannelEntry(it.id, it.displayNames, it.icon) }
                } else {
                    // No channel list: every channel the shows mention (kept or not), for Change EPG.
                    parsed.programChannelIds.map { EpgChannelEntry(it, emptyList(), null) }
                }
                meta = runCatching { writer.finish(stageStamp, entries.map { Triple(it.id, it.names, it.icon) }) }
                    .onFailure { writer.abort(); Log.w(TAG, "Couldn't store ${target.name}", it) }
                    .getOrNull() ?: run { LIVE_REPORT.add("Guide \"${target.name}\": couldn't be stored"); continue }
                LIVE_REPORT.add("Guide \"${target.name}\": read and set aside in ${(System.currentTimeMillis() - readStart) / 1000} s (${meta.values.sumOf { it.count }} listings)")
            }
            setStatus(loading = true, message = "Matching guide \"${target.name}\"$step…")
            val label = runCatching { java.net.URI(target.url).host?.removePrefix("www.") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: target.name
            sourceMeta += Triple(target, label, entries.sortedBy { it.displayName.lowercase() })
            // How far ahead this guide's listings actually go (the file's own last show), so a
            // guide that stops early can be told apart from the app cutting it short.
            run {
                val lastStop = meta.values.maxOfOrNull { it.lastStop } ?: 0L
                // A guide whose listings have all run out: the next download asks for the whole
                // file (not "only if changed"), in case a cache keeps answering "not changed".
                if (lastStop in 1 until System.currentTimeMillis()) {
                    runCatching { File(file.parentFile, file.name + ".meta").delete() }
                }
                if (lastStop > 0) {
                    val fmt = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.getDefault())
                    val hours = ((lastStop - System.currentTimeMillis()) / HOUR).coerceAtLeast(0)
                    LIVE_REPORT.add(
                        "Guide \"${target.name}\": listings until ${fmt.format(java.util.Date(lastStop))} " +
                            "(about $hours hours ahead; set to ${settings.epgFutureDays} days)"
                    )
                }
            }

            if (pinnedHere.isNotEmpty()) {
                val lists = runCatching { guideDb.stagedProgramsFor(src, pinnedHere.values.map { it.xmltvId }) }.getOrDefault(emptyMap())
                pinnedHere.forEach { (key, a) -> pinned[key] = lists[a.xmltvId].orEmpty() }
            }

            if (idHits.isEmpty() && nameHits.isEmpty()) {
                // Guide had no <channel> list before programs: match program channel ids directly.
                meta.keys.forEach { id ->
                    byTvgId[id.lowercase()]?.let { idHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                    (byName[normalize(id)] ?: byName[idName(id)])?.let { nameHits.getOrPut(id) { LinkedHashSet() }.addAll(it) }
                }
            }
            var count = 0
            fun offer(xmlId: String, chans: Set<LiveChannel>, idMatch: Boolean) {
                val m = meta[xmlId] ?: return
                if (m.count <= 0) return
                count += m.count
                val real = m.placeholders * 10 < m.count * 8
                // Higher is better; then more days ahead, then the guide's position.
                val score = guideRank(real, idMatch, m.lastStop, order)
                chans.forEach { c ->
                    val cur = best[c.key]
                    if (cur == null || score > cur.score) best[c.key] = Candidate(score, target.fileId, xmlId)
                }
            }
            idHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = true) }
            nameHits.forEach { (xmlId, chans) -> offer(xmlId, chans, idMatch = false) }
            // What this guide matched, and when it's nothing, a few ids from each side, so a
            // "0 programs matched" can be told apart: ids that don't line up, a guide with no
            // listings for these days, or no channels left to match.
            run {
                val rt = Runtime.getRuntime()
                LIVE_REPORT.add("  memory after reading \"${target.name}\": ${(rt.totalMemory() - rt.freeMemory()) / 1048576} of ${rt.maxMemory() / 1048576} MB")
            }
            val matchedChannels = (idHits.values.flatten() + nameHits.values.flatten()).mapTo(HashSet()) { it.key }.size
            LIVE_REPORT.add(
                "Guide \"${target.name}\": ${entries.size} guide channels, ${meta.size} with listings kept; " +
                    "matched ${idHits.size} by id, ${nameHits.size} by name → $matchedChannels channels, $count listings"
            )
            // Most of the guide left unmatched: a few names from each side, so it's clear why.
            if (count > 0 && entries.size >= 20 && idHits.size + nameHits.size < entries.size / 5) {
                val hit = idHits.keys + nameHits.keys
                val guideSide = entries.filter { it.id !in hit }.take(4).joinToString { e -> e.id + (e.names.firstOrNull()?.let { " ($it)" } ?: "") }
                val ourSide = channels.filter { it.tvgId?.isNotBlank() == true }.take(4).joinToString { "${it.tvgId} (${it.name})" }
                LIVE_REPORT.add("Guide \"${target.name}\" mostly unmatched: guide has [$guideSide], playlist has [$ourSide]")
            }
            if (count == 0) {
                val guideIds = entries.take(3).joinToString { it.id }
                val ourIds = channels.mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }.take(3).joinToString()
                val totalListings = meta.values.sumOf { it.count }
                LIVE_REPORT.add(
                    "Guide \"${target.name}\" matched nothing: guide ids like [$guideIds], playlist tvg-ids like [$ourIds], " +
                        "$totalListings listings in the guide between ${settings.epgPastHours} h ago and ${settings.epgFutureDays} days ahead"
                )
            }
            if (target.sourceId != null) {
                prefs.updateEpgSources { l -> l.map { if (it.id == target.sourceId) it.copy(programCount = count) else it } }
            }
        }

        val auto = HashMap<String, EpgAssignment>()
        best.forEach { (key, c) -> auto[key] = EpgAssignment(c.sourceId, c.xmltvId) }
        // Unassigned in Assign EPG: no guide for these channels, automatic match included.
        val unassigned = overrides.filterValues { it.isNone }.keys
        unassigned.forEach { key -> auto.remove(key); pinned.remove(key) }
        // Which guide channels ended up feeding a playlist channel (for "Unassigned" in Assign EPG).
        val usedBySource = HashMap<String, MutableSet<String>>()
        auto.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        overrides.values.forEach { usedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        sourceMeta.forEach { (target, label, entries) ->
            sourceLists += EpgSourceChannels(target.fileId, target.name, label, entries, usedBySource[target.fileId].orEmpty())
        }
        _autoMatches.value = auto
        _epgSources.value = sourceLists
        saveSourceLists(targets, sourceLists)
        // Each channel's logo in the guide it's matched to (or assigned), for playlists that
        // prefer the guide's logos, and for channels whose playlist has none.
        val iconsBySource = sourceMeta.associate { (target, _, entries) ->
            target.fileId to entries.mapNotNull { e -> e.icon?.takeIf { it.isNotBlank() }?.let { e.id to it } }.toMap()
        }
        val logos = HashMap<String, String>()
        (auto + overrides).forEach { (key, a) -> iconsBySource[a.sourceId]?.get(a.xmltvId)?.let { logos[key] = it } }
        _epgLogos.value = logos
        saveEpgLogos(logos)
        epgDetailsPending = false
        // The guide shown: from the staged guides, the chosen one for each channel (hand-picked
        // ones as given), all in one step.
        val choice = auto.filterKeys { it !in pinned }
        // Channels with a tvg-shift in the playlist: their listings moved by that too.
        val tvgShifts = channels.filter { it.tvgShiftMinutes != 0 }.associate { it.key to it.tvgShiftMinutes * 60_000L }
        if (tvgShifts.isNotEmpty()) {
            LIVE_REPORT.add("Guide: ${tvgShifts.size} channels moved by the playlist's tvg-shift")
            tvgShifts.forEach { (key, ms) -> pinned[key]?.let { pinned[key] = it.shiftedBy(ms) } }
        }
        val storeStart = System.currentTimeMillis()
        beginGuideMutation()
        val stored = runCatching {
            val fp = guideFingerprint(settings, targets)
            guideDb.replaceAllFromStage(fp, choice, pinned, auto, guideParts(settings, targets, fp), windowStart, windowEnd, tvgShifts)
        }.onFailure { Log.w(TAG, "Couldn't store the guide", it) }.isSuccess
        LIVE_REPORT.add("Guide: best guide per channel stored in ${(System.currentTimeMillis() - storeStart) / 1000} s")
        // The guides' working copies aren't kept once the guide is built (they'd double the storage).
        runCatching { guideDb.dropStagesExcept(emptyList()) }
        if (stored) {
            publishCommittedGuide()
            saveSkipped(skipped)
            if (skipped.isNotEmpty()) LIVE_REPORT.add("Guide: ${skipped.size} hidden channels left out")
            scheduleCompact()
        } else {
            // SQLite rolls back a failed replacement. Retain the last committed guide.
            LIVE_REPORT.add("Guide couldn't be stored: keeping the previous guide")
            publishCommittedGuide()
        }
    }

    /**
     * How good a guide's listings for a channel are, higher is better: real listings over
     * placeholders, then a tvg-id match over a name match, then the one that goes further ahead
     * (in half-days, so a few hours either way don't count), and only then the guide higher in
     * the list. Before, the guide higher in the list won even with a day of listings against
     * another guide's week, so the same provider showed "until Saturday" on one TV and "until
     * Monday" on another depending on the order of their guides.
     */
    private fun guideRank(real: Boolean, idMatch: Boolean, listings: List<EpgProgram>, order: Int): Long =
        guideRank(real, idMatch, listings.maxOfOrNull { it.stopMs } ?: 0L, order)

    private fun guideRank(real: Boolean, idMatch: Boolean, lastStop: Long, order: Int): Long {
        val halfDaysAhead = ((lastStop - System.currentTimeMillis()).coerceAtLeast(0L) / (12 * HOUR)).coerceAtMost(999L)
        return (if (real) 4_000_000L else 0L) + (if (idMatch) 2_000_000L else 0L) + halfDaysAhead * 1_000L - order
    }

    /**
     * Some guides add a long placeholder ("Programming", "No information") that overlaps the real
     * listings for the same time. Keep the real ones wherever they overlap.
     */
    private fun cleanListings(list: List<EpgProgram>): List<EpgProgram> {
        if (list.size < 2) return list
        val real = list.filter { !isPlaceholder(it.title) }
        val kept = if (real.isEmpty() || real.size == list.size) list else {
            // The real shows' time spans, merged into separate stretches, so each placeholder is
            // checked against them with a quick search (every placeholder against every real show
            // took minutes on big guides).
            val spans = ArrayList<LongArray>()
            for (r in real.sortedBy { it.startMs }) {
                val last = spans.lastOrNull()
                if (last != null && r.startMs <= last[1]) { if (r.stopMs > last[1]) last[1] = r.stopMs }
                else spans += longArrayOf(r.startMs, r.stopMs)
            }
            fun overlapsReal(p: EpgProgram): Boolean {
                var lo = 0; var hi = spans.size - 1; var idx = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    if (spans[mid][0] < p.stopMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
                }
                return idx >= 0 && spans[idx][1] > p.startMs
            }
            list.filter { p -> !isPlaceholder(p.title) || !overlapsReal(p) }
        }
        return resolveOverlaps(kept)
    }

    /**
     * Makes sure only one program covers any moment. When a long entry (often a "Programming"
     * filler) runs across later shows, it's cut off where the next show starts. Otherwise the
     * filler, having started earlier, is what counts as "on now" even while a real show is airing.
     */
    private fun resolveOverlaps(list: List<EpgProgram>): List<EpgProgram> {
        val sorted = list.sortedWith(compareBy<EpgProgram>({ it.startMs }, { isPlaceholder(it.title) }, { it.stopMs - it.startMs }))
        val out = ArrayList<EpgProgram>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.stopMs > p.startMs) {
                if (last.startMs == p.startMs) continue // same start: keep the one sorted first
                out[out.lastIndex] = last.copy(stopMs = p.startMs)
            }
            if (p.stopMs > p.startMs) out += p
        }
        return out
    }

    /** True when (almost) everything in a channel's listings is a placeholder. */
    private fun isPlaceholderListing(list: List<EpgProgram>): Boolean {
        val placeholders = list.count { isPlaceholder(it.title) }
        return placeholders * 10 >= list.size * 8
    }

    // Guides repeat the same few titles a million times: each is checked once.
    private val placeholderCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isPlaceholder(title: String): Boolean {
        if (placeholderCache.size > 50_000) placeholderCache.clear()
        return placeholderCache.getOrPut(title) { PLACEHOLDER_TITLES.matches(title.trim().lowercase()) }
    }

    /**
     * Assign EPG changes applied straight to the stored guide instead of reading every guide
     * file again (which took as long as a full guide update, just to change one channel):
     *  - Unassign: the channel's listings are simply dropped. Nothing is read.
     *  - A guide channel picked by hand: only that one guide file is read, for just that channel.
     *  - Back to automatic: the guides' channel lists (already in memory, the ones Assign EPG
     *    shows) say which guide channels could match; only the guides that have one are read,
     *    for just those channels, and the best is picked the same way a full update picks.
     * Only if those lists aren't loaded yet does it fall back to reading every guide.
     * [changes]: channel key -> its new assignment (null = back to automatic).
     */
    fun applyEpgChanges(changes: Map<String, EpgAssignment?>) {
        if (changes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val quick = runCatching { applyEpgChangesQuick(changes) }
                    .onFailure { Log.w(TAG, "Quick EPG change failed; full re-match", it) }
                    .getOrDefault(false)
                if (!quick) {
                    LIVE_REPORT.add("Assign EPG: guide channel lists not ready, so every guide was read")
                    setStatus(loading = true, message = "Updating guide…")
                    buildPrograms(prefs.currentSettings(), collectEpgTargets())
                }
                _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            }
        }
    }

    private suspend fun applyEpgChangesQuick(changes: Map<String, EpgAssignment?>): Boolean = withContext(Dispatchers.IO) {
        val settings = prefs.currentSettings()
        val targets = collectEpgTargets()
        val now = System.currentTimeMillis()
        val windowStart = now - settings.epgPastHours.coerceAtLeast(1) * HOUR
        val windowEnd = now + settings.epgFutureDays.coerceAtLeast(1) * 24 * HOUR
        val assigned = changes.filterValues { it != null && !it.isNone }.mapValues { it.value!! }

        // Back to automatic: possible matches, from the guides' channel lists.
        data class Cand(val order: Int, val sourceId: String, val xmltvId: String, val idMatch: Boolean)
        val autoBack = changes.filterValues { it == null }.keys
        val cands = HashMap<String, MutableList<Cand>>()
        if (autoBack.isNotEmpty()) {
            val lists = _epgSources.value
            if (lists.isEmpty()) return@withContext false
            val chans = _channels.value.filter { it.key in autoBack }
            targets.forEachIndexed { order, t ->
                val list = lists.firstOrNull { it.sourceId == t.fileId } ?: return@forEachIndexed
                chans.forEach { c ->
                    val tvg = c.tvgId?.lowercase()?.takeIf { it.isNotBlank() }
                    val names = listOfNotNull(c.tvgName, c.name).map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
                    list.channels.forEach { e ->
                        val idMatch = tvg != null && e.id.lowercase() == tvg
                        val nameMatch = !idMatch && names.isNotEmpty() && (e.names + e.id).any { normalize(it) in names }
                        if (idMatch || nameMatch) cands.getOrPut(c.key) { ArrayList() } += Cand(order, t.fileId, e.id, idMatch)
                    }
                }
            }
        }

        // Each guide file read once, for just the channels needed from it.
        val wantedBySource = HashMap<String, MutableSet<String>>()
        assigned.values.forEach { wantedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        cands.values.flatten().forEach { wantedBySource.getOrPut(it.sourceId) { HashSet() } += it.xmltvId }
        val programsBySource = HashMap<String, Map<String, List<EpgProgram>>>()
        val iconsBySource = HashMap<String, Map<String, String>>()
        for ((sourceId, wanted) in wantedBySource) {
            val target = targets.firstOrNull { it.fileId == sourceId } ?: continue
            val file = epgFile(target.fileId)
            if (!file.exists()) continue
            setStatus(loading = true, message = "Reading guide \"${target.name}\"…")
            val parsed = openMaybeGzip(file).use { input ->
                XmltvParser.parse(input, windowStart, windowEnd) { wanted }
            }
            programsBySource[sourceId] = if (target.shiftMs == 0L) parsed.programs
                else parsed.programs.mapValues { (_, l) -> l.shiftedBy(target.shiftMs) }
            iconsBySource[sourceId] = parsed.channels.mapNotNull { (id, ch) -> ch.icon?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap()
        }

        val newPrograms = HashMap<String, List<EpgProgram>>()
        val newIcons = HashMap<String, String>()
        val tvgShiftOf = _channels.value.filter { it.key in changes.keys && it.tvgShiftMinutes != 0 }
            .associate { it.key to it.tvgShiftMinutes * 60_000L }
        assigned.forEach { (key, a) ->
            newPrograms[key] = cleanListings(programsBySource[a.sourceId]?.get(a.xmltvId).orEmpty()).shiftedBy(tvgShiftOf[key] ?: 0L)
            iconsBySource[a.sourceId]?.get(a.xmltvId)?.let { newIcons[key] = it }
        }
        // Automatic: the same choice a full update makes (real listings first, then a tvg-id
        // match over a name match, then the guide higher in the list).
        val newAuto = HashMap<String, EpgAssignment>()
        cands.forEach { (key, list) ->
            var bestScore = Long.MIN_VALUE
            var best: Pair<Cand, List<EpgProgram>>? = null
            list.forEach { c ->
                val listings = cleanListings(programsBySource[c.sourceId]?.get(c.xmltvId).orEmpty())
                if (listings.isEmpty()) return@forEach
                val score = guideRank(!isPlaceholderListing(listings), c.idMatch, listings, c.order)
                if (score > bestScore) { bestScore = score; best = c to listings }
            }
            best?.let { (c, listings) ->
                newPrograms[key] = listings.shiftedBy(tvgShiftOf[key] ?: 0L)
                newAuto[key] = EpgAssignment(c.sourceId, c.xmltvId)
                iconsBySource[c.sourceId]?.get(c.xmltvId)?.let { newIcons[key] = it }
            }
        }

        val changed = changes.keys
        // Stored guide first (when the guide is kept in the database), then what's on screen.
        if (guideInDb) {
            val fp = guideFingerprint(settings, targets)
            beginGuideMutation()
            try {
                guideDb.replaceChannels(fp, changed, newPrograms, newAuto, guideParts(settings, targets, fp))
            } finally {
                publishCommittedGuide(changed)
            }
        } else {
            _programs.value = (_programs.value - changed) + newPrograms
        }
        _autoMatches.value = (_autoMatches.value - changed) + newAuto
        val logos = HashMap(_epgLogos.value).apply { changed.forEach { remove(it) }; putAll(newIcons) }
        _epgLogos.value = logos
        saveEpgLogos(logos)
        // Keep the "already used" marks in Assign EPG up to date.
        val used = (assigned.values + newAuto.values).groupBy({ it.sourceId }, { it.xmltvId })
        if (used.isNotEmpty()) {
            _epgSources.value = _epgSources.value.map { s ->
                used[s.sourceId]?.let { ids -> s.copy(usedIds = s.usedIds + ids) } ?: s
            }
        }
        LIVE_REPORT.add(
            "Assign EPG: ${changed.size} channel(s) updated, " +
                (if (programsBySource.isEmpty()) "no guide read" else "read ${programsBySource.size} of ${targets.size} guide(s)")
        )
        true
    }

    fun rematchEpg() {
        scope.launch {
            mutex.withLock {
                setStatus(loading = true, message = "Updating guide…")
                buildPrograms(prefs.currentSettings(), collectEpgTargets())
                _status.value = LiveTvStatus(loading = false, loadedOnce = true)
            }
        }
    }

    // ---------------------------------------------------------------- IO helpers

    private fun playlistFile(id: String) = File(playlistDir, "$id.m3u")
    /** An Xtream login's group names in the provider's order (see [PlaylistGroupOrder]). */
    private fun groupOrderFile(id: String) = File(playlistDir, "$id.groups")

    /** The providers' own group order, for the guide's group list. */
    private suspend fun loadGroupOrder() {
        PlaylistGroupOrder.ranks = runCatching {
            val playlists = prefs.currentPlaylists().filter { it.liveEnabled }
            val multi = playlists.size > 1
            val ranks = HashMap<String, Int>()
            for (pl in playlists) {
                val f = groupOrderFile(pl.id)
                if (!f.exists()) continue
                f.readLines().filter { it.isNotBlank() }.forEachIndexed { i, name ->
                    ranks.putIfAbsent(if (multi) "${pl.id}::$name" else name, i)
                }
            }
            ranks
        }.getOrDefault(emptyMap())
    }
    private fun epgFile(id: String) = File(epgDir, "$id.xml")

    /**
     * Downloads [url] to [target]. Returns false when nothing changed: the server said "not
     * modified" (we send the ETag / Last-Modified from last time), or the new file is identical.
     * Unchanged files skip the re-reading and re-matching that follows a download.
     */
    private suspend fun download(
        url: String,
        target: File,
        userAgent: String,
        progressLabel: String? = null,
        /** Ask for the whole file, not "only if changed" (Update now: a cache that wrongly
         *  answers "not changed" otherwise keeps an old guide forever). */
        full: Boolean = false,
        /** Name for the Start-Up Report line saying what came back (guides only). */
        reportAs: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        require(url.isNotBlank()) { "No URL set" }
        target.parentFile?.mkdirs()
        val meta = File(target.parentFile, target.name + ".meta")
        val (etag, lastModified) = if (!full && target.exists() && meta.exists()) {
            meta.readLines().let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        } else "" to ""
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .apply {
                if (etag.isNotBlank()) header("If-None-Match", etag)
                if (lastModified.isNotBlank()) header("If-Modified-Since", lastModified)
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304 && target.exists()) {
                target.setLastModified(System.currentTimeMillis()) // checked again after the usual interval
                reportAs?.let { LIVE_REPORT.add("Guide \"$it\": server said not changed since last time; kept the copy we have") }
                return@withContext false
            }
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            val tmp = File(target.parentFile, target.name + ".tmp")
            // With a label: how much has come in so far, so a big download doesn't look frozen.
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { raw ->
                // Saved zipped: guide files (XMLTV) and big playlists shrink to about a tenth,
                // and plain guides of several hundred MB made the app take up close to 1 GB.
                // Ones that already arrive zipped (.xml.gz) are kept as they are.
                val input = BufferedInputStream(raw, 64 * 1024)
                input.mark(2)
                val alreadyZipped = input.read() == 0x1f && input.read() == 0x8b
                input.reset()
                val fileOut = tmp.outputStream()
                (if (alreadyZipped) fileOut else fastGzip(fileOut)).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var shownAt = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (progressLabel != null) {
                            val now = System.currentTimeMillis()
                            if (now - shownAt > 750) {
                                shownAt = now
                                setStatus(loading = true, message = "$progressLabel… " + sizeLabel(read) + (total?.let { " of " + sizeLabel(it) } ?: ""))
                            }
                        }
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                error("Empty response")
            }
            runCatching {
                meta.writeText(response.header("ETag").orEmpty() + "\n" + response.header("Last-Modified").orEmpty())
            }
            val same = target.exists() && (
                if (isGzip(target) == isGzip(tmp)) target.length() == tmp.length() && sameContent(target, tmp)
                else sameDecoded(target, tmp)
            )
            val serverDate = response.header("Last-Modified")?.let { " (server's date: $it)" }.orEmpty()
            if (same) {
                tmp.delete()
                target.setLastModified(System.currentTimeMillis())
                reportAs?.let { LIVE_REPORT.add("Guide \"$it\": downloaded ${sizeLabel(target.length())} zipped, same as the copy we had$serverDate") }
                return@withContext false
            }
            reportAs?.let { LIVE_REPORT.add("Guide \"$it\": downloaded, new copy ${sizeLabel(tmp.length())} zipped$serverDate") }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            markChanged(target)
            true
        }
    }

    /**
     * When a downloaded file last really changed. The saved channel list and guide are checked
     * against this, not the file's date: the date is also bumped every time an update finds
     * nothing new, which used to make every restart re-read all playlists and guides.
     */
    private fun stampFile(f: File) = File(f.parentFile, f.name + ".stamp")

    private fun contentStamp(f: File): String =
        runCatching { stampFile(f).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "${f.length()}"

    private fun markChanged(f: File) {
        runCatching { stampFile(f).writeText(System.currentTimeMillis().toString()) }
    }

    private fun isGzip(f: File): Boolean = runCatching {
        f.inputStream().use { val a = it.read(); val b = it.read(); a == 0x1f && b == 0x8b }
    }.getOrDefault(false)

    /**
     * Same content once unzipped. A saved copy is zipped to save space after each update, so a
     * fresh plain download never matched it byte for byte: a playlist that hadn't changed at all
     * was reported as "new content" at every start, redoing every guide and the Sports search.
     */
    private fun sameDecoded(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("MD5")
            openMaybeGzip(f).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

    private fun sameContent(a: File, b: File): Boolean = runCatching {
        fun digest(f: File): ByteArray {
            val md = java.security.MessageDigest.getInstance("MD5")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest()
        }
        digest(a).contentEquals(digest(b))
    }.getOrDefault(false)

    // ---------------------------------------------------------------- Xtream Codes API

    /**
     * Loads an Xtream login through the Xtream API (player_api.php), the way TiviMate does:
     * account check, categories, then live streams, written out as an M3U file so the rest of
     * Live TV treats it like any playlist. Much lighter for the provider than the one-shot
     * get.php playlist, which many panels time out on (HTTP 502) or switch off. Falls back to
     * get.php if the API isn't available.
     */
    private suspend fun downloadXtream(pl: PlaylistSource, target: File) = withContext(Dispatchers.IO) {
        require(pl.xtreamBase().isNotBlank()) { "No server set" }
        require(pl.xtreamUsername.isNotBlank() && pl.xtreamPassword.isNotBlank()) { "Username and password are required" }
        val api = pl.xtreamApiUrl()
        val apiResult = runCatching {
            // 1. Account
            val info = JSONObject(getText(api, pl.userAgent))
            // Which stream formats this account may use (some panels turn off .ts and only
            // allow .m3u8; asking for .ts then fails on every channel with "bad http status").
            allowedFormats = info.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it).lowercase() }.toSet()
            } ?: emptySet()
            // Catch-up links are in the server's local time. When the server's time zone differs
            // from the TV's (say the server runs on UTC and the TV is in London in summer),
            // every replay started an hour off. Remember the server's zone for catch-up.
            // A server that names no time zone: worked out from its clock instead.
            ZoneFinder.fromServerInfo(info.optJSONObject("server_info"))?.let { (tz, _) ->
                if (tz != pl.serverTimezone) {
                    prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
                }
                zoneCheckedAt[pl.id] = System.currentTimeMillis()
            }
            info.optJSONObject("user_info")?.let { ui ->
                accountOf(ui)?.let { saveAccount(pl, it) }
                if (ui.optString("auth") == "0") throw XtreamLoginError("Login failed: check the username and password")
                val status = ui.optString("status")
                if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
                    throw XtreamLoginError("Account $status")
                }
                ui.optString("exp_date").toLongOrNull()?.let { exp ->
                    if (exp > 0 && exp * 1000 < System.currentTimeMillis()) {
                        val date = java.text.DateFormat.getDateInstance().format(java.util.Date(exp * 1000))
                        throw XtreamLoginError("Account expired on $date")
                    }
                }
            } ?: if (info.has("user_info")) Unit else error("Not an Xtream API response")

            // 2. Categories (in the provider's order, kept for the guide's group list)
            val categories = HashMap<String, String>()
            val categoryOrder = ArrayList<String>()
            runCatching {
                val arr = org.json.JSONArray(getText("$api&action=get_live_categories", pl.userAgent))
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    categories[c.optString("category_id")] = c.optString("category_name")
                    c.optString("category_name").ifBlank { null }?.let { categoryOrder += it }
                }
            }
            if (categoryOrder.isNotEmpty()) runCatching {
                groupOrderFile(pl.id).writeText(categoryOrder.joinToString("\n") { it.replace("\n", " ").replace("\"", "'").trim() })
            }

            // 3. Live streams, streamed straight into an M3U file
            writeXtreamPlaylist(pl, "$api&action=get_live_streams", categories, target)
        }
        val error = apiResult.exceptionOrNull() ?: return@withContext
        if (error is XtreamLoginError) throw error
        Log.w(TAG, "Xtream API failed for ${pl.name}, trying get.php: ${error.message}")
        // Fallback: the classic one-shot playlist. If that fails too, report the API error.
        runCatching { download(pl.resolvedUrl(), target, pl.userAgent) }
            .onFailure { throw IllegalStateException(error.message ?: it.message) }
    }

    private class XtreamLoginError(message: String) : Exception(message)

    /** Stream formats the provider allows for the account being loaded (from user_info). */
    @Volatile private var allowedFormats: Set<String> = emptySet()

    private fun xtreamExtension(pl: PlaylistSource): String = when (pl.streamFormat) {
        "ts" -> "ts"
        "m3u8" -> "m3u8"
        // Auto: .ts unless the provider only allows HLS.
        else -> if (allowedFormats.isNotEmpty() && "ts" !in allowedFormats && "m3u8" in allowedFormats) "m3u8" else "ts"
    }

    private fun writeXtreamPlaylist(pl: PlaylistSource, url: String, categories: Map<String, String>, target: File) {
        val ext = xtreamExtension(pl)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val base = pl.xtreamBase()
        val user = java.net.URLEncoder.encode(pl.xtreamUsername.trim(), "UTF-8")
        val pass = java.net.URLEncoder.encode(pl.xtreamPassword.trim(), "UTF-8")
        var count = 0
        val request = Request.Builder().url(url).header("User-Agent", pl.userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            // Written zipped straight away (like downloads), instead of saved plain and zipped
            // again afterwards, which read and wrote the whole list a second time.
            fastGzip(tmp.outputStream()).bufferedWriter().use { out ->
                out.write("#EXTM3U\n")
                android.util.JsonReader(body.charStream()).use { reader ->
                    // Most panels send a list; some send the channels keyed by number instead
                    // ({"0": {...}, "1": {...}}), which failed with "Expected BEGIN_ARRAY but was
                    // BEGIN_OBJECT". Both are read.
                    val keyed = reader.peek() == android.util.JsonToken.BEGIN_OBJECT
                    if (keyed) reader.beginObject() else reader.beginArray()
                    while (reader.hasNext()) {
                        if (keyed) {
                            reader.nextName()
                            if (reader.peek() != android.util.JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                        }
                        val f = HashMap<String, String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            when (reader.peek()) {
                                android.util.JsonToken.STRING -> f[name] = reader.nextString()
                                android.util.JsonToken.NUMBER -> f[name] = reader.nextString()
                                android.util.JsonToken.BOOLEAN -> f[name] = reader.nextBoolean().toString()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val streamId = f["stream_id"]
                        if (streamId.isNullOrBlank()) continue
                        val title = (f["name"] ?: "Channel $streamId").replace("\n", " ").trim()
                        val attrs = buildString {
                            fun attr(k: String, v: String?) {
                                if (!v.isNullOrBlank()) append(" $k=\"${v.replace("\"", "'")}\"")
                            }
                            attr("tvg-id", f["epg_channel_id"])
                            attr("tvg-name", title)
                            attr("tvg-logo", f["stream_icon"])
                            attr("group-title", categories[f["category_id"]] ?: "Uncategorized")
                            attr("tvg-chno", f["num"])
                            if (f["tv_archive"] == "1") {
                                attr("catchup", "xc")
                                attr("catchup-days", f["tv_archive_duration"]?.takeIf { it != "0" } ?: "1")
                            }
                        }
                        out.write("#EXTINF:-1$attrs,$title\n")
                        out.write("$base/live/$user/$pass/$streamId.$ext\n")
                        count++
                        if (count % 500 == 0) setStatus(loading = true, message = "Loading channels from ${pl.name}… ${"%,d".format(count)}")
                    }
                    if (keyed) reader.endObject() else reader.endArray()
                }
            }
        }
        if (count == 0) {
            tmp.delete()
            error("The provider returned no live channels")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * The server's time zone for catch-up, for playlists that don't know it yet. Tried in turn:
     * the zone the server reports (player_api.php), the gap between the server's clock and the
     * real time (same answer, for servers that don't name a zone), and the time zone written in
     * the provider's own guide (xmltv.php on the same server). The first answer is saved with
     * the playlist, so later replays don't ask again.
     *
     * The lookup runs on its own, so a caller that stops waiting (slow server) doesn't stop it:
     * the zone is still saved when the answer comes. A lookup that failed is tried again after
     * [ZONE_RETRY_MS].
     */
    suspend fun learnServerZone(channel: LiveChannel): String? {
        val pl = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId } ?: return null
        pl.serverTimezone.takeIf { it.isNotBlank() }?.let { return it }
        val failed = zoneFailedAt[pl.id]
        if (failed != null && System.currentTimeMillis() - failed < ZONE_RETRY_MS) return null
        return startZoneLookup(pl, channel.url).await()
    }

    /**
     * A time zone worked out from the server's clock or guide is a fixed offset, which goes wrong
     * when the clocks change: looked up again in the background, at most every [ZONE_RECHECK_MS].
     * Zones the server names (Europe/Amsterdam) follow the clock changes themselves; Xtream
     * logins re-read theirs on every playlist update anyway.
     */
    fun recheckServerZone(channel: LiveChannel) {
        scope.launch {
            val pl = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId } ?: return@launch
            if (!ZoneFinder.isWorkedOut(pl.serverTimezone)) return@launch
            val now = System.currentTimeMillis()
            val last = zoneCheckedAt.putIfAbsent(pl.id, now) ?: return@launch // first replay this session: just found or loaded
            if (now - last < ZONE_RECHECK_MS) return@launch
            zoneCheckedAt[pl.id] = now
            startZoneLookup(pl, channel.url)
        }
    }

    private fun startZoneLookup(pl: PlaylistSource, channelUrl: String): kotlinx.coroutines.Deferred<String?> =
        synchronized(zoneLookups) {
            zoneLookups[pl.id] ?: scope.async {
                try {
                    lookupServerZone(pl, channelUrl)
                } finally {
                    synchronized(zoneLookups) { zoneLookups.remove(pl.id) }
                }
            }.also { zoneLookups[pl.id] = it }
        }

    private suspend fun lookupServerZone(pl: PlaylistSource, channelUrl: String): String? {
        // Where to ask: an Xtream login's own server; for an M3U, the server its channel links point to.
        val (host, user, pass) = if (pl.isXtream) {
            Triple(pl.xtreamBase(), pl.xtreamUsername.trim(), pl.xtreamPassword.trim())
        } else {
            val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)
                .find(channelUrl) ?: return null
            Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        val ua = pl.userAgent.ifBlank { DEFAULT_UA }
        val login = "username=" + java.net.URLEncoder.encode(user, "UTF-8") + "&password=" + java.net.URLEncoder.encode(pass, "UTF-8")
        val notes = ArrayList<String>()
        // 1 and 2: the server's account info (its time zone, else its clock).
        var found: Pair<String, String>? = runCatching {
            val info = JSONObject(getText("$host/player_api.php?$login", ua)).optJSONObject("server_info")
            ZoneFinder.fromServerInfo(info).also {
                if (it == null) notes += if (info == null) "server: no server details in its reply" else "server: names no time zone or clock"
            }
        }.getOrElse { notes += "server: " + zoneError(it); null }
        // 3: the provider's own guide, on the same server.
        if (found == null) {
            found = runCatching {
                ZoneFinder.fromGuide(firstBytes("$host/xmltv.php?$login", ua, GUIDE_PROBE_BYTES)).also {
                    if (it == null) notes += "guide: no times in the first part of it"
                }
            }.getOrElse { notes += "guide: " + zoneError(it); null }
        }
        // 4: a guide already downloaded for this provider (its file is on this box, so the server
        // can't refuse it): one on the provider's own site, an Xtream panel guide, or one named
        // after the playlist. Other guides (third-party sites) are written in their own zone.
        if (found == null) {
            found = runCatching { zoneFromAddedGuides(pl, listOf(host, pl.url, channelUrl)) }.getOrNull()
            if (found == null) notes += "added guides: none from this provider with a time zone in it"
        }
        if (found != null) {
            val (tz, how) = found
            LIVE_REPORT.add("Server time zone for \"${pl.name}\": $tz ($how)")
            zoneFailedAt.remove(pl.id)
            zoneCheckedAt[pl.id] = System.currentTimeMillis()
            prefs.updatePlaylists { list -> list.map { if (it.id == pl.id) it.copy(serverTimezone = tz) else it } }
            return tz
        }
        LIVE_REPORT.add("Server time zone for \"${pl.name}\": unknown (${notes.joinToString("; ")})")
        zoneFailedAt[pl.id] = System.currentTimeMillis()
        return null
    }

    /** Step 4 of [lookupServerZone]: the time zone written in a guide of this provider's already on this box. */
    private suspend fun zoneFromAddedGuides(pl: PlaylistSource, links: List<String>): Pair<String, String>? {
        fun site(link: String): String? = runCatching {
            java.net.URI(link.trim()).host?.lowercase()?.split('.')?.filter { it.isNotEmpty() }?.takeLast(2)?.joinToString(".")
        }.getOrNull()?.takeIf { it.contains('.') }
        val sites = links.mapNotNull { site(it) }.toSet()
        val name = normalize(pl.name)
        val guides = prefs.currentEpgSources().filter { it.enabled && it.url.isNotBlank() }.filter { g ->
            site(g.url) in sites || g.url.contains("xmltv.php", ignoreCase = true) ||
                (name.length >= 3 && normalize(g.name).contains(name))
        }
        for (g in guides) {
            val file = epgFile(g.id)
            if (!file.isFile) continue
            val text = withContext(Dispatchers.IO) {
                openMaybeGzip(file).use { String(it.readNBytesCompat(GUIDE_PROBE_BYTES), Charsets.UTF_8) }
            }
            ZoneFinder.fromGuide(text)?.let { (tz, _) -> return tz to "from the guide \"${g.name}\"" }
        }
        return null
    }

    /** Why a time zone lookup failed, in a few words for the Start-Up Report. */
    private fun zoneError(e: Throwable): String = when (e) {
        is org.json.JSONException -> "its reply wasn't the usual account details"
        is java.net.SocketTimeoutException -> "no answer in time"
        is java.net.UnknownHostException -> "server not found"
        is javax.net.ssl.SSLException -> "secure connection failed"
        is java.io.IOException -> e.message?.takeIf { it.isNotBlank() } ?: "connection failed"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    /** The start of a (possibly gzipped) download, as text: enough to read a guide's first times. */
    private fun firstBytes(url: String, userAgent: String, max: Int): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            val body = response.body ?: error("Empty response")
            var input: java.io.InputStream = java.io.BufferedInputStream(body.byteStream())
            input.mark(2)
            val b0 = input.read(); val b1 = input.read()
            input.reset()
            if (b0 == 0x1f && b1 == 0x8b) input = java.util.zip.GZIPInputStream(input)
            val buf = ByteArray(max)
            var n = 0
            while (n < max) {
                val r = input.read(buf, n, max - n)
                if (r < 0) break
                n += r
            }
            return String(buf, 0, n, Charsets.UTF_8)
        }
    }

    /** Xtream user_info → the account details worth showing. */
    private fun accountOf(ui: JSONObject): PlaylistAccount? {
        if (ui.optString("auth") == "0") return null
        val exp = ui.optString("exp_date").toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L
        return PlaylistAccount(
            expiresAtMs = exp,
            status = ui.optString("status"),
            maxConnections = ui.optString("max_connections").toIntOrNull() ?: 0,
            activeConnections = ui.optString("active_cons").toIntOrNull() ?: 0,
            checkedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun saveAccount(pl: PlaylistSource, a: PlaylistAccount) {
        prefs.setPlaylistAccount(pl.id, a)
        a.expiryLabel()?.let { LIVE_REPORT.add("Account for \"${pl.name}\": expires $it") }
        warnIfExpiring(pl.id, pl.name, a)
    }

    private val expiryWarned = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun warnIfExpiring(id: String, name: String, a: PlaylistAccount) {
        if (!a.expiringSoon() || !expiryWarned.add(id)) return
        val days = a.daysLeft() ?: return
        com.nuvio.tv.livetv.record.LiveTvNotice.show(
            when {
                a.expiresAtMs <= System.currentTimeMillis() -> "\"$name\" has expired"
                days == 0L -> "\"$name\" expires today"
                days == 1L -> "\"$name\" expires tomorrow"
                else -> "\"$name\" expires in $days days"
            }
        )
    }

    /**
     * An M3U link from an Xtream provider (…/get.php?username=…&password=…): the same server's
     * player_api.php has the account's expiry date and connections.
     */
    private suspend fun refreshM3uAccount(pl: PlaylistSource) = withContext(Dispatchers.IO) {
        val api = m3uAccountUrl(pl.url) ?: return@withContext
        runCatching {
            val ui = JSONObject(getText(api, pl.userAgent.ifBlank { DEFAULT_UA })).optJSONObject("user_info")
                ?: return@runCatching
            accountOf(ui)?.let { saveAccount(pl, it) }
        }
    }

    private fun m3uAccountUrl(url: String): String? = runCatching {
        val u = java.net.URI(url.trim())
        if (u.path?.endsWith("get.php", ignoreCase = true) != true) return@runCatching null
        val q = (u.rawQuery ?: return@runCatching null).split('&')
            .associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val user = q["username"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val pass = q["password"] ?: return@runCatching null
        "${u.scheme}://${u.rawAuthority}/player_api.php?username=$user&password=$pass"
    }.getOrNull()

    private fun getText(url: String, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { DEFAULT_UA }).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(httpError(response.code))
            return response.body?.string() ?: error("Empty response")
        }
    }

    /** Plain-English reasons for the HTTP errors people actually hit. */
    private fun httpError(code: Int): String = when (code) {
        401, 403 -> "HTTP $code: the provider refused the request (wrong login, or it blocks this app; try setting a user agent)"
        404 -> "HTTP 404: nothing at that address (check the server, port and link)"
        429 -> "HTTP 429: too many requests, try again in a few minutes"
        // Not standard codes: Xtream panels use these when an account is over its connection
        // limit, expired, or blocked for the moment.
        456, 458, 461 -> "HTTP $code: the provider refused this login for now (too many connections, expired, or blocked). Close other apps using it and try again later."
        502, 503, 504, 520, 521, 522, 523, 524 -> "HTTP $code: the provider's server didn't respond, try again later"
        884 -> "HTTP 884: the provider blocked this app (try setting a user agent)"
        else -> "HTTP $code"
    }

    /** A zip stream at the fastest setting: a TV box's processor matters more than the last few MB. */
    private fun fastGzip(out: java.io.OutputStream): java.io.OutputStream =
        object : java.util.zip.GZIPOutputStream(java.io.BufferedOutputStream(out, 64 * 1024), 64 * 1024) {
            init { def.setLevel(java.util.zip.Deflater.BEST_SPEED) }
        }

    /**
     * Zips a saved guide or playlist that isn't zipped yet. Its "last changed" stamp is kept, so
     * this doesn't count as new content (the saved guide is read again once, as its size changed).
     */
    private fun zipInPlace(file: File) {
        runCatching {
            if (!file.exists() || file.length() < 1024 * 1024) return
            val zipped = file.inputStream().use { val a = it.read(); val b = it.read(); a == 0x1f && b == 0x8b }
            if (zipped) return
            val tmp = File(file.parentFile, file.name + ".zip.tmp")
            file.inputStream().use { input -> fastGzip(tmp.outputStream()).use { out -> input.copyTo(out, 64 * 1024) } }
            val modified = file.lastModified()
            if (!stampFile(file).exists()) markChanged(file)
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
            file.setLastModified(modified)
            LIVE_REPORT.add("Saved \"${file.name}\" zipped to save space")
        }.onFailure { Log.w(TAG, "Couldn't zip ${file.name}", it) }
    }

    private fun openMaybeGzip(file: File, onProgress: ((Long) -> Unit)? = null): InputStream {
        val raw: InputStream = if (onProgress == null) file.inputStream() else ProgressInputStream(file.inputStream(), onProgress)
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(input, 64 * 1024)) else input
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} KB"
    }

    /** Counts what's been read from a file, for "Reading guide… 45%". */
    private class ProgressInputStream(inner: InputStream, private val onProgress: (Long) -> Unit) : java.io.FilterInputStream(inner) {
        private var done = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { done++; onProgress(done) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { done += it; onProgress(done) } }
        override fun skip(n: Long): Long = super.skip(n).also { done += it }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        private const val TAG = "LiveTvRepository"
        private const val HOUR = 60L * 60L * 1000L
        /** A server time zone lookup that failed is tried again after this long. */
        private const val ZONE_RETRY_MS = 2L * 60L * 1000L
        /** A worked-out time zone (fixed offset) is looked up again after this long, in the background. */
        private const val ZONE_RECHECK_MS = 12L * 60L * 60L * 1000L
        /** How much of the provider's guide is read to find its time zone. */
        private const val GUIDE_PROBE_BYTES = 64 * 1024
        /** How often to look for playlists and guides past their update time while the app is open. */
        private const val STALE_CHECK_MS = 30L * 60L * 1000L
        /** Opening Live TV doesn't check again this soon after the last automatic check. */
        private const val RECHECK_GAP_MS = 5L * 60L * 1000L
        // 2: guides compared by how far ahead they go (matched again once after updating).
        private const val GUIDE_CACHE_VERSION = 2
        private const val CHANNELS_CACHE_VERSION = 7
        private const val MAX_SAVED_LISTINGS = 300_000
        /** Titles guides use when they have no real listing. */
        private val PLACEHOLDER_TITLES = Regex(
            """(programming|program|programme|no information|no info|no program information|no programme information|""" +
                """to be announced|tba|tbd|n/?a|off air|no data|no epg|not available|information not available|""" +
                """regular programming|scheduled programming|paid programming|coming soon|no program guide available)\.?"""
        )
        private val VOD_PATH = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
        private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)

        /** A movie or episode in an M3U (by its link), not a live channel. */
        fun isVodEntry(url: String): Boolean = VOD_PATH.containsMatchIn(url) || VOD_EXT.containsMatchIn(url)

        /** "Programming", "No information", "To Be Announced"… (no real show to show a poster for). */
        fun isPlaceholderTitle(title: String): Boolean = PLACEHOLDER_TITLES.matches(title.trim().lowercase())

        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private val qualityTokens = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
        private val prefixToken = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val bracketed = Regex("""\[.*?]|\(.*?\)""")

        private const val SOURCE_LISTS_VERSION = 1

        private val countryEnd = Regex("""(\.(com|co|net|tv))?\.[a-z]{2}$""", RegexOption.IGNORE_CASE)

        /** A guide or tvg-id as a name: "GloboSP.br" -> "globosp". */
        internal fun idName(id: String): String = normalize(id.trim().replace(countryEnd, ""))

        internal fun normalize(name: String): String {
            var n = name.lowercase().trim()
            n = n.replace(prefixToken, "")
            n = n.replace(bracketed, " ")
            n = n.replace(qualityTokens, " ")
            return n.filter { it.isLetterOrDigit() }
        }
    }
}

/** Up to [n] bytes from the start of a stream (InputStream.readNBytes needs Android 13). */
private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
    val buf = ByteArray(n)
    var total = 0
    while (total < n) {
        val r = read(buf, total, n - total)
        if (r < 0) break
        total += r
    }
    return buf.copyOf(total)
}
