@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.livetv.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.liveCorner
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A recording's artwork: the show's backdrop, and its title logo to put over it. */
data class RecordingArt(val backdrop: String?, val logo: String?)

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    val recorder: LiveTvRecorder,
    val scheduler: LiveTvRecordingScheduler,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbMetadataService: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    private val liveRepository: com.nuvio.tv.livetv.data.LiveTvRepository,
    private val livePrefs: com.nuvio.tv.livetv.data.LiveTvPreferences,
    private val matchFixes: com.nuvio.tv.livetv.data.TitleMatchFixes
) : ViewModel() {
    val recordings = recorder.recordings
    /** How far a move between drives has got (null when nothing is moving). */
    val move = recorder.move

    // Other TVs' recordings on the network folder show up as soon as the page opens.
    init { recorder.refreshLibrary() }

    /** New Recording's channel list: favorites, then channels watched recently. */
    val pickerChannels: kotlinx.coroutines.flow.StateFlow<List<com.nuvio.tv.livetv.model.LiveChannel>> =
        kotlinx.coroutines.flow.combine(liveRepository.channels, livePrefs.userState) { all, user ->
            val byKey = all.associateBy { it.key }
            (user.favorites + user.recent).distinct().mapNotNull { byKey[it] }.take(80)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Playlist id -> its name, to say which playlist a recording is from (and which it uses). */
    val playlistNames: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = livePrefs.playlists
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyMap())

    /** The guide's look (background, panel darkness, highlight style), so this screen matches it. */
    val look: kotlinx.coroutines.flow.StateFlow<com.nuvio.tv.livetv.model.LiveTvSettings> = livePrefs.settings
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.nuvio.tv.livetv.model.LiveTvSettings())

    val use24h: kotlinx.coroutines.flow.StateFlow<Boolean> = livePrefs.settings
        .map { it.use24HourClock }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun scheduleManual(channel: com.nuvio.tv.livetv.model.LiveChannel, title: String, startMs: Long, stopMs: Long, days: Set<Int> = emptySet()) {
        LiveTvNotice.show(scheduler.addManual(channel, title, startMs, stopMs, days))
    }
    val schedules = scheduler.schedules
    val series = scheduler.series
    val repeats = scheduler.repeats

    init {
        // Anything that should have started already (catch-up saves included) starts now.
        scheduler.fireOverdue()
    }

    /** "Saving from catch-up at 7:10 PM", "Not ready yet: trying again at 7:40 PM (2 of 3)". */
    fun catchupNote(s: RecordingSchedule): String {
        val at = scheduler.startsAt(s)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        return when {
            s.attempts > 0 -> "Not ready at the provider yet: trying again at $time (${s.attempts + 1} of ${LiveTvRecordingScheduler.MAX_TRIES + 1})"
            at <= System.currentTimeMillis() -> "Saving from catch-up now"
            else -> "Saved from catch-up at $time, after it ends"
        }
    }

    private val _posters = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Recording id -> poster, looked up by title the same way the guide does. */
    val posters: StateFlow<Map<String, String>> = _posters.asStateFlow()
    private val tried = HashSet<String>()

    /** A show set to record or recorded: its name, channel, and whether it's surely a show (an episode number, a series rule). */
    data class TitleAsk(val title: String, val channelName: String?, val series: Boolean = false, val description: String? = null)

    private fun hintFor(series: Boolean) =
        if (series) com.nuvio.tv.livetv.data.LiveTvPosterResolver.TypeHint(com.nuvio.tv.livetv.data.LiveTvPosterResolver.Kind.SERIES, strong = true) else null

    // ---- "Fix match": your pick wins; otherwise the channel's country can pick between same-named shows.
    private val countryPicks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private object NoPick

    /** The title picked with Fix match, or the one the channel's country points to; null = the automatic lookup. */
    private suspend fun pickedHit(title: String, channelName: String?, series: Boolean): com.nuvio.tv.livetv.data.LiveTvPosterResolver.Hit? {
        matchFixes.get(com.nuvio.tv.livetv.data.TitleMatchFixes.recordingKey(title))?.let { return it }
        val country = com.nuvio.tv.livetv.data.TitleMatchFixes.countryOf(channelName) ?: return null
        val key = posterKey(title) + "|" + country
        countryPicks[key]?.let { return it as? com.nuvio.tv.livetv.data.LiveTvPosterResolver.Hit }
        val clean = com.nuvio.tv.livetv.ui.LiveTvSearchBridge.cleanTitle(title).ifBlank { title.trim() }
        val c = runCatching { matchFixes.byCountry(clean, country) }.getOrNull()
        val hit = c?.let { runCatching { matchFixes.toHit(it) }.getOrNull() }
        countryPicks[key] = hit ?: NoPick
        return hit
    }

    private suspend fun posterOf(ask: TitleAsk): String? {
        if (com.nuvio.tv.livetv.data.LiveTvPosterResolver.isSportsRecording(ask.channelName, ask.title)) return null
        pickedHit(ask.title, ask.channelName, ask.series)?.meta?.let { m -> (m.rawPosterUrl ?: m.poster)?.let { return it } }
        return posterResolver.posterFor(
            ask.title, hintFor(ask.series),
            com.nuvio.tv.livetv.data.LiveTvPosterResolver.Clues(description = ask.description), urgent = false
        )
    }

    private fun askOf(r: LiveTvRecording) = TitleAsk(r.title, r.channelName, !r.episode.isNullOrBlank(), r.description)

    private var lastRecordings: List<LiveTvRecording> = emptyList()

    fun loadPosters(list: List<LiveTvRecording>) {
        lastRecordings = list
        val todo = list.filter { tried.add(it.id) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (r in todo) {
                // Sports recordings: the channel's logo (game titles found random films).
                val poster = runCatching { posterOf(askOf(r)) }.getOrNull()
                if (poster != null) _posters.value = _posters.value + (r.id to poster)
            }
        }
    }

    private val _titlePosters = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Show title (see [posterKey]) -> poster, for the Scheduled and Series tabs. */
    val titlePosters: StateFlow<Map<String, String>> = _titlePosters.asStateFlow()
    private val triedTitles = HashSet<String>()
    private var lastTitles: List<TitleAsk> = emptyList()

    /** Posters for shows set to record (sports keep the channel logo). */
    fun loadTitlePosters(list: List<TitleAsk>) {
        lastTitles = list
        val todo = list.filter { it.title.isNotBlank() && triedTitles.add(posterKey(it.title)) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (ask in todo) {
                val poster = runCatching { posterOf(ask) }.getOrNull()
                if (poster != null) _titlePosters.value = _titlePosters.value + (posterKey(ask.title) to poster)
            }
        }
    }

    // ---- Artwork: the show's backdrop with its logo over it, like Nuvio's landscape cards.
    private val _art = MutableStateFlow<Map<String, RecordingArt>>(emptyMap())
    /** Recording id -> backdrop and logo (from TMDB, else your addons). */
    val art: StateFlow<Map<String, RecordingArt>> = _art.asStateFlow()
    private val triedArt = HashSet<String>()
    private val artPrefs = recorder.appContext.getSharedPreferences("livetv_recording_art", android.content.Context.MODE_PRIVATE)

    fun loadArt(list: List<LiveTvRecording>) {
        val todo = list.filter { triedArt.add(it.id) }
        if (todo.isEmpty()) return
        val fixed = matchFixes.fixes.value
        // Found before: straight away, no lookup (not when a Fix match pick is set: the pick decides).
        val saved = HashMap<String, RecordingArt>()
        todo.forEach { r ->
            if (fixed.containsKey(com.nuvio.tv.livetv.data.TitleMatchFixes.recordingKey(r.title))) return@forEach
            runCatching { artPrefs.getString(r.id, null) }.getOrNull()?.let { v ->
                val parts = v.split('\n')
                saved[r.id] = RecordingArt(parts.getOrNull(0)?.ifBlank { null }, parts.getOrNull(1)?.ifBlank { null })
            }
        }
        if (saved.isNotEmpty()) _art.value = _art.value + saved
        viewModelScope.launch {
            for (r in todo) {
                if (r.id in saved) continue
                // Sports: no guessed artwork (the card shows the channel's logo).
                if (com.nuvio.tv.livetv.data.LiveTvPosterResolver.isSportsRecording(r.channelName, r.title)) continue
                val found = runCatching { findArt(askOf(r)) }.getOrNull() ?: continue
                _art.value = _art.value + (r.id to found)
                runCatching { artPrefs.edit().putString(r.id, (found.backdrop ?: "") + "\n" + (found.logo ?: "")).apply() }
            }
        }
    }

    /** The picked title's artwork, else the show in your addons' catalogs, then its TMDB backdrop and logo. */
    private suspend fun findArt(ask: TitleAsk): RecordingArt? {
        pickedHit(ask.title, ask.channelName, ask.series)?.meta?.let { m ->
            val backdrop = m.background ?: m.landscapePoster ?: return null
            return RecordingArt(backdrop, m.logo)
        }
        val hit = posterResolver.titleFor(ask.title, hintFor(ask.series)) ?: return null
        val movie = hit.type.equals("movie", ignoreCase = true)
        val tmdb = runCatching {
            tmdbService.ensureTmdbId(hit.meta.id, if (movie) "movie" else "series")?.let { id ->
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = id,
                    contentType = if (movie) com.nuvio.tv.domain.model.ContentType.MOVIE else com.nuvio.tv.domain.model.ContentType.SERIES
                )
            }
        }.getOrNull()
        val backdrop = tmdb?.backdrop ?: hit.meta.background ?: hit.meta.landscapePoster
        val logo = tmdb?.logo ?: hit.meta.logo
        if (backdrop == null) return null
        return RecordingArt(backdrop, logo)
    }

    // ---- Fix match

    /** Shows and movies for the Fix match search. */
    suspend fun searchMatches(text: String, series: Boolean?, year: Int?) = matchFixes.search(text, series, year)

    /** The pick for this show's name, if one is set. */
    fun fixFor(title: String) = matchFixes.fixes.value[com.nuvio.tv.livetv.data.TitleMatchFixes.recordingKey(title)]

    /** Every recording named [title] (and future ones) uses [picked]; null goes back to automatic. */
    fun setMatch(title: String, picked: com.nuvio.tv.livetv.data.TitleMatchFixes.Candidate?) {
        viewModelScope.launch {
            val hit = picked?.let { matchFixes.toHit(it) }
            matchFixes.set(com.nuvio.tv.livetv.data.TitleMatchFixes.recordingKey(title), hit, picked?.tmdbId)
            // The collector below redraws the cards (here and when a pick comes in from another TV).
        }
    }

    init {
        // A pick changed (here, or synced from another TV): look those shows up again.
        viewModelScope.launch {
            var before = matchFixes.fixes.value
            matchFixes.fixes.collect { now ->
                val changed = (before.keys + now.keys).filter { k -> k.startsWith("rec:") && before[k]?.meta != now[k]?.meta }.toSet()
                before = now
                if (changed.isNotEmpty()) redo(changed)
            }
        }
    }

    private fun redo(keys: Set<String>) {
        fun hit(title: String) = com.nuvio.tv.livetv.data.TitleMatchFixes.recordingKey(title) in keys
        val recs = lastRecordings.filter { hit(it.title) }
        recs.forEach { r ->
            tried.remove(r.id); triedArt.remove(r.id)
            runCatching { artPrefs.edit().remove(r.id).apply() }
        }
        _posters.value = _posters.value - recs.map { it.id }.toSet()
        _art.value = _art.value - recs.map { it.id }.toSet()
        val titles = lastTitles.filter { hit(it.title) }
        titles.forEach { triedTitles.remove(posterKey(it.title)) }
        _titlePosters.value = _titlePosters.value - titles.map { posterKey(it.title) }.toSet()
        if (recs.isNotEmpty()) { loadPosters(lastRecordings); loadArt(lastRecordings) }
        if (titles.isNotEmpty()) loadTitlePosters(lastTitles)
    }

    fun playUrl(rec: LiveTvRecording): String = recorder.playUrl(rec)
    /** Where you left off (ms), if partly watched. */
    fun resume(rec: LiveTvRecording): Long? = RecordingResume.load(recorder.appContext, RecordingResume.contentIdFor(rec.id))?.first
    fun stop(rec: LiveTvRecording) = recorder.stop(rec.id)
    fun delete(rec: LiveTvRecording) {
        recorder.delete(rec.id)
        runCatching { artPrefs.edit().remove(rec.id).apply() }
    }
    fun freeBytes(): Long = recorder.freeBytes()
    fun setKeepForever(rec: LiveTvRecording, keep: Boolean) = recorder.setKeepForever(rec.id, keep)
    fun isWatched(rec: LiveTvRecording): Boolean = RecordingResume.isWatched(recorder.appContext, rec.id)
    fun markWatched(recs: List<LiveTvRecording>) = recs.forEach { RecordingResume.markWatched(recorder.appContext, it.id) }
    fun markUnwatched(recs: List<LiveTvRecording>) = recs.forEach { RecordingResume.clear(recorder.appContext, it.id) }
    fun moveTo(recs: List<LiveTvRecording>, target: RecordingStorage.Choice) = recorder.moveTo(recs.map { it.id }, target)
    fun storageIdOf(rec: LiveTvRecording): String? = recorder.storageIdOf(rec)
}

/**
 * Recordings: what's recording now in a thin strip across the top, then three tabs: Recorded
 * (posters, shows with several episodes in folders), Scheduled (by day; Left/Right jump a day) and
 * Series. OK plays (full screen in Live TV's player, with its seek bar); the menu button or a long press
 * offers Resume / Play from Beginning, Stop and Delete. Same background as the guide (the
 * theme's glow at the top), not plain black.
 */
@Composable
fun RecordingsScreen(
    onPlay: (url: String, title: String, poster: String?, recordingId: String, fromStart: Boolean) -> Unit,
    /** The gear by the title: Recordings settings (where they're saved, the network folder…). */
    onOpenSettings: () -> Unit = {},
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val seriesRules by viewModel.series.collectAsStateWithLifecycle()
    val repeatRules by viewModel.repeats.collectAsStateWithLifecycle()
    val posters by viewModel.posters.collectAsStateWithLifecycle()
    val art by viewModel.art.collectAsStateWithLifecycle()
    var scheduleMenu by remember { mutableStateOf<RecordingSchedule?>(null) }
    // Back from the player: read where you got to again (the progress bars).
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // The clock, for "recording until" bars (moves on every few seconds).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5_000); nowMs = System.currentTimeMillis() }
    }
    var seriesMenu by remember { mutableStateOf<SeriesRule?>(null) }
    var seriesKeepMenu by remember { mutableStateOf<SeriesRule?>(null) }
    var repeatMenu by remember { mutableStateOf<RepeatRule?>(null) }
    // Which playlist each recording uses: shown on the cards when there's more than one (handy
    // with a one-connection login: don't watch that playlist while it records).
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    fun playlistOf(sourceId: String): String? = playlistNames[sourceId]
    val showPlaylist = playlistNames.size > 1
    fun withPlaylist(channelName: String, sourceId: String): String =
        if (showPlaylist) listOfNotNull(channelName, playlistOf(sourceId)).joinToString(" · ") else channelName
    LaunchedEffect(recordings) { viewModel.loadPosters(recordings); viewModel.loadArt(recordings) }
    var menuFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    // Fix match: the show's name, and whether it's surely a show (null = don't know).
    var fixMatchFor by remember { mutableStateOf<Pair<String, Boolean?>?>(null) }
    var confirmDelete by remember { mutableStateOf<LiveTvRecording?>(null) }
    var infoFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    // New Recording: picking the channel, then the time.
    var pickingChannel by remember { mutableStateOf(false) }
    var manualFor by remember { mutableStateOf<com.nuvio.tv.livetv.model.LiveChannel?>(null) }
    val pickerChannels by viewModel.pickerChannels.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val newButton = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val colors = NuvioTheme.colors
    val look by viewModel.look.collectAsStateWithLifecycle()

    val now = recordings.filter { it.isRecording }
    val done = recordings.filter { !it.isRecording }
    val entries = remember(done) { groupRecorded(done) }
    val recContext = androidx.compose.ui.platform.LocalContext.current
    // A folder that's open, a move being chosen, and a whole folder being deleted.
    // Kept while a recording plays (Recordings leaves the screen), so Back returns to the folder.
    var openShowKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val openShow = entries.firstOrNull { it.key == openShowKey } as? RecordedShow
    LaunchedEffect(openShow == null) {
        if (openShow == null) {
            openShowKey = null
            // Back from a folder: the highlight returns to the top of the Recorded tab.
            kotlinx.coroutines.delay(120)
            runCatching { first.requestFocus() }
        }
    }
    var movePick by remember { mutableStateOf<List<LiveTvRecording>?>(null) }
    var confirmDeleteShow by remember { mutableStateOf<RecordedShow?>(null) }
    val moving by viewModel.move.collectAsStateWithLifecycle()
    val playNow: (LiveTvRecording) -> Unit = { rec ->
        // Nothing saved yet (it's still connecting): say so instead of an error.
        if (rec.durationSec <= 0) LiveTvNotice.show(
            "\"${rec.title}\" hasn't recorded anything yet" + (rec.problem?.let { ": $it" } ?: ". Give it a few seconds.")
        )
        else onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
    }
    // The tabs: Recorded first. Until one is picked, it opens on the first one that has anything.
    var pickedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(-1) }
    val hasSeries = seriesRules.isNotEmpty() || repeatRules.isNotEmpty()
    val tab = if (pickedTab >= 0) pickedTab else when {
        done.isNotEmpty() -> TAB_RECORDED
        schedules.isNotEmpty() -> TAB_SCHEDULED
        hasSeries -> TAB_SERIES
        else -> TAB_RECORDED
    }
    val tabHasItems = when (tab) {
        TAB_RECORDED -> entries.isNotEmpty()
        TAB_SCHEDULED -> schedules.isNotEmpty()
        else -> hasSeries
    }
    val tabReqs = remember { List(3) { FocusRequester() } }
    // Posters for the shows set to record (the recordings' own are looked up above).
    val titlePosters by viewModel.titlePosters.collectAsStateWithLifecycle()
    LaunchedEffect(schedules, seriesRules, repeatRules) {
        viewModel.loadTitlePosters(
            schedules.map { RecordingsViewModel.TitleAsk(it.title, it.channel.name, !it.episode.isNullOrBlank()) } +
                seriesRules.map { RecordingsViewModel.TitleAsk(it.title, it.channel.name, series = true) } +
                repeatRules.map { RecordingsViewModel.TitleAsk(it.title, it.channel.name) }
        )
    }
    fun posterOf(rec: LiveTvRecording): String? = posters[rec.id] ?: titlePosters[posterKey(rec.title)]

    androidx.compose.runtime.CompositionLocalProvider(
        com.nuvio.tv.livetv.ui.LocalLiveSolidHighlight provides look.solidHighlight,
        com.nuvio.tv.livetv.ui.LocalGuideDarkness provides look.guideInfoBackground
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The same background as the guide and Sports (Settings → Live TV → Guide & Sports Background).
            .guideBackdrop(look.guideBackdrop)
    ) {
        // The guide's background: the theme's glow behind the top of the screen.
        com.nuvio.tv.livetv.ui.ThemeGlow(Modifier.fillMaxWidth().height(260.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                // While a folder is open on top, nothing underneath can take the highlight.
                .then(if (openShow != null) Modifier.focusProperties { canFocus = false } else Modifier)
                .padding(start = 56.dp, end = 48.dp, top = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                // Recordings settings; Up or Left from the page reaches it, the page keeps the first highlight.
                RecordingsGearButton(onClick = onOpenSettings)
                Spacer(Modifier.width(14.dp))
                LiveText("Recordings", size = 30.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val free = viewModel.freeBytes()
                if (free != Long.MAX_VALUE) LiveText(
                    "${sizeLabel(free)} free",
                    size = 15.sp,
                    color = colors.TextSecondary
                )
                Spacer(Modifier.width(18.dp))
                // Record a time you pick, on a channel with no listings (PPV events…).
                PillButton(
                    "+ New Recording",
                    primary = false,
                    modifier = Modifier.focusRequester(newButton),
                    onFocus = {},
                    onClick = { pickingChannel = true },
                    onMenu = { pickingChannel = true }
                )
            }
            Spacer(Modifier.height(20.dp))
            if (recordings.isEmpty() && schedules.isEmpty() && seriesRules.isEmpty() && repeatRules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LiveText("No recordings yet", size = 20.sp, weight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LiveText(
                            "In the Live TV guide, long-press a show and choose Record or Record series. While watching, open the menu and choose Record this show. For a channel with no listings, use + New Recording.",
                            size = 14.sp, color = colors.TextSecondary, maxLines = 3
                        )
                    }
                }
                return@Column
            }

            // ---- Recording now: a thin strip each, above the tabs.
            if (now.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    now.take(2).forEach { rec ->
                        RecordingNowCard(
                            rec = rec,
                            nowMs = nowMs,
                            onWatch = { playNow(rec) },
                            onStop = { viewModel.stop(rec) },
                            onMenu = { menuFor = rec }
                        )
                    }
                    if (now.size > 2) {
                        LiveText("And ${now.size - 2} more recording now", size = 13.sp, color = colors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---- Recorded · Scheduled · Series. Coming in from above or below lands on the tab you're on.
            Row(
                modifier = Modifier
                    .focusProperties { enter = { tabReqs[tab] } }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RecordingsTab("Recorded", done.size, tab == TAB_RECORDED, Modifier.focusRequester(tabReqs[TAB_RECORDED])) { pickedTab = TAB_RECORDED }
                RecordingsTab("Scheduled", schedules.size, tab == TAB_SCHEDULED, Modifier.focusRequester(tabReqs[TAB_SCHEDULED])) { pickedTab = TAB_SCHEDULED }
                RecordingsTab("Series", seriesRules.size + repeatRules.size, tab == TAB_SERIES, Modifier.focusRequester(tabReqs[TAB_SERIES])) { pickedTab = TAB_SERIES }
            }
            Spacer(Modifier.height(18.dp))

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    TAB_RECORDED -> {
                        if (done.isEmpty()) EmptyNote("Nothing recorded yet. What you record shows here.")
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(POSTER_COLUMNS),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)
                        ) {
                            gridItems(entries, key = { it.key }) { entry ->
                                val isFirst = entry.key == entries.firstOrNull()?.key
                                when (entry) {
                                    is RecordedShow -> {
                                        val counts = remember(entry, resumeTick) {
                                            val st = entry.episodes.map { viewModel.isWatched(it) to viewModel.resume(it) }
                                            st.count { it.first } to st.count { !it.first && it.second == null }
                                        }
                                        RecordedFolderCard(
                                            show = entry,
                                            poster = entry.episodes.firstNotNullOfOrNull { posters[it.id] } ?: titlePosters[posterKey(entry.title)],
                                            watched = counts.first,
                                            fresh = counts.second,
                                            modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                                            onClick = { openShowKey = entry.key },
                                            onMenu = { openShowKey = entry.key }
                                        )
                                    }
                                    is SingleRecording -> {
                                        val rec = entry.rec
                                        RecordedCard(
                                            rec = rec,
                                            poster = posterOf(rec),
                                            watched = resumeTick.let { viewModel.isWatched(rec) },
                                            resumeFraction = resumeTick.let { viewModel.resume(rec) }?.let { ms ->
                                                if (rec.durationSec > 0) (ms / 1000.0 / rec.durationSec).toFloat().coerceIn(0f, 1f) else null
                                            },
                                            modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                                            onClick = {
                                                if (rec.status != RecordingStatus.FAILED) onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
                                                else menuFor = rec
                                            },
                                            onMenu = { menuFor = rec }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TAB_SCHEDULED -> {
                        if (schedules.isEmpty()) EmptyNote("Nothing scheduled. In the guide, hold OK on a show and choose Record.")
                        else ScheduledByDay(
                            schedules = schedules,
                            posterOf = { titlePosters[posterKey(it.title)] },
                            channelLine = { withPlaylist(it.channel.name, it.channel.sourceId) },
                            noteOf = { if (it.useCatchup) viewModel.catchupNote(it) else null },
                            use24h = use24h,
                            firstModifier = Modifier.focusRequester(first),
                            onClick = { scheduleMenu = it }
                        )
                    }
                    else -> {
                        if (!hasSeries) EmptyNote("No series set to record. In the guide, hold OK on a show and choose Record Series.")
                        else LazyVerticalGrid(
                            columns = GridCells.Fixed(POSTER_COLUMNS),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)
                        ) {
                            gridItems(seriesRules, key = { it.id }) { r ->
                                SeriesPosterCard(
                                    title = r.title,
                                    poster = titlePosters[posterKey(r.title)],
                                    channelLogo = r.channel.logo,
                                    line = (if (r.newOnly) "New episodes" else "All episodes") + " · " +
                                        (if (r.keepLast > 0) "Keep last ${r.keepLast}" else "Keep all"),
                                    channel = withPlaylist(r.channel.name, r.channel.sourceId),
                                    modifier = if (r.id == seriesRules.first().id) Modifier.focusRequester(first) else Modifier,
                                    onClick = { seriesMenu = r }
                                )
                            }
                            // Repeating recordings set by hand (Schedule a Recording → Repeat).
                            gridItems(repeatRules, key = { it.id }) { r ->
                                SeriesPosterCard(
                                    title = r.title,
                                    poster = titlePosters[posterKey(r.title)],
                                    channelLogo = r.channel.logo,
                                    line = RepeatRule.daysLabel(r.days) + " " + repeatTime(r.minuteOfDay, use24h),
                                    channel = withPlaylist(r.channel.name, r.channel.sourceId),
                                    modifier = if (seriesRules.isEmpty() && r.id == repeatRules.first().id) Modifier.focusRequester(first) else Modifier,
                                    onClick = { repeatMenu = r }
                                )
                            }
                        }
                    }
                }
            }
        }
        // An open folder: the show's page, over the tabs.
        openShow?.let { show ->
            RecordedShowPage(
                show = show,
                art = art,
                resumeTick = resumeTick,
                resumeOf = { viewModel.resume(it) },
                isWatched = { viewModel.isWatched(it) },
                onPlay = { playNow(it) },
                onMenu = { menuFor = it },
                onMarkAllWatched = { viewModel.markWatched(show.episodes); resumeTick++ },
                onMoveAll = if (RecordingStorage.choices(recContext).size > 1) ({ movePick = show.episodes }) else null,
                onDeleteAll = { confirmDeleteShow = show },
                onClose = { openShowKey = null },
                backdrop = look.guideBackdrop
            )
        }
        // A move between drives: what's moving and how far along.
        moving?.let { m ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 28.dp)
                    .width(340.dp)
                    .clip(RoundedCornerShape(liveCorner()))
                    .background(com.nuvio.tv.livetv.ui.guideSurface())
                    .border(1.dp, com.nuvio.tv.livetv.ui.GuideGlassEdge, RoundedCornerShape(liveCorner()))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LiveText("Moving recordings" + if (m.count > 1) " (${m.index} of ${m.count})" else "", size = 14.sp, weight = FontWeight.Bold)
                LiveText(m.title, size = 13.sp, color = colors.TextSecondary)
                ProgressLine(if (m.totalBytes > 0) m.doneBytes.toFloat() / m.totalBytes else 0f, colors.Secondary, Modifier.fillMaxWidth())
                LiveText(sizeLabel(m.doneBytes) + " of " + sizeLabel(m.totalBytes), size = 12.sp, color = colors.TextTertiary)
            }
        }
    }
    }
    val anything = recordings.isNotEmpty() || schedules.isNotEmpty() || seriesRules.isNotEmpty() || repeatRules.isNotEmpty()
    LaunchedEffect(anything) {
        kotlinx.coroutines.delay(120)
        // The top of the tab that's open; its tab when it's empty; nothing yet: New Recording.
        runCatching {
            when {
                !anything -> newButton.requestFocus()
                tabHasItems -> first.requestFocus()
                else -> tabReqs[tab].requestFocus()
            }
        }
    }
    scheduleMenu?.let { s ->
        RecordingActionDialog(
            title = s.title,
            detail = s.channel.name + " · " + dateLabel(s.startMs) +
                (playlistOf(s.channel.sourceId)?.let { "\nRecording from playlist: $it" } ?: ""),
            actions = listOf(
                "Cancel Recording" to { scheduleMenu = null; viewModel.scheduler.cancel(s.id) },
                "Fix Match" to { scheduleMenu = null; fixMatchFor = s.title to (if (s.episode.isNullOrBlank()) null else true) },
                "Close" to { scheduleMenu = null }
            ),
            onDismiss = { scheduleMenu = null }
        )
    }
    // A series: stop it, or choose how many episodes to keep (its own list, so Stop is never
    // pushed off the bottom by the keep choices).
    seriesMenu?.let { r ->
        RecordingActionDialog(
            title = r.title,
            detail = (playlistOf(r.channel.sourceId)?.let { "Recording from playlist: $it\n" } ?: "") +
                (if (r.newOnly) "Only airings the guide marks as NEW on " else "Every episode on ") + r.channel.name +
                " (repeats of episodes already recorded are skipped). Stopping it also cancels the airings it set that haven't started; what's recorded is kept.",
            actions = listOf(
                "Stop Recording Series" to { seriesMenu = null; viewModel.scheduler.removeSeries(r.id) },
                ("Record: " + (if (r.newOnly) "New Episodes Only" else "All Episodes")) to {
                    seriesMenu = null; viewModel.scheduler.setNewOnly(r.id, !r.newOnly)
                },
                ("Episodes to Keep: " + (if (r.keepLast == 0) "All" else "Last ${r.keepLast}")) to { seriesMenu = null; seriesKeepMenu = r },
                "Fix Match" to { seriesMenu = null; fixMatchFor = r.title to true },
                "Close" to { seriesMenu = null }
            ),
            onDismiss = { seriesMenu = null }
        )
    }
    repeatMenu?.let { r ->
        RecordingActionDialog(
            title = r.title,
            detail = (playlistOf(r.channel.sourceId)?.let { "Recording from playlist: $it\n" } ?: "") +
                r.channel.name + " · " + RepeatRule.daysLabel(r.days) + " at " + repeatTime(r.minuteOfDay, use24h) +
                ". Stopping it also cancels the airings it set that haven't started; what's recorded is kept.",
            actions = listOf(
                "Stop Repeating" to { repeatMenu = null; viewModel.scheduler.removeRepeat(r.id) },
                "Close" to { repeatMenu = null }
            ),
            onDismiss = { repeatMenu = null }
        )
    }
    seriesKeepMenu?.let { r ->
        RecordingActionDialog(
            title = "Episodes to Keep",
            detail = r.title + ": older episodes are deleted as new ones are recorded.",
            actions = listOf(0, 1, 3, 5, 10).map { n ->
                val label = (if (n == 0) "Keep All Episodes" else "Keep the Last $n") + if (r.keepLast == n) "  ✓" else ""
                label to { seriesKeepMenu = null; viewModel.scheduler.setKeep(r.id, n) }
            },
            onDismiss = { seriesKeepMenu = null }
        )
    }

    menuFor?.let { rec ->
        val actions = buildList<Pair<String, () -> Unit>> {
            if (rec.status != RecordingStatus.FAILED) {
                val resumeAt = viewModel.resume(rec)
                add((if (resumeAt != null) "Resume" else "Play") to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false) })
                if (resumeAt != null) add("Play from Beginning" to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, true) })
            }
            // Another TV's recording is stopped on that TV.
            if (rec.isRecording && !rec.fromOtherTv) add("Stop Recording" to { menuFor = null; viewModel.stop(rec) })
            if (!rec.isRecording && rec.status != RecordingStatus.FAILED) {
                if (viewModel.isWatched(rec)) add("Mark as Unwatched" to { menuFor = null; viewModel.markUnwatched(listOf(rec)); resumeTick++ })
                else add("Mark as Watched" to { menuFor = null; viewModel.markWatched(listOf(rec)); resumeTick++ })
                if (RecordingStorage.choices(recContext).size > 1) add("Move To…" to { menuFor = null; movePick = listOf(rec) })
                // Never removed by "Delete recordings after" (Settings › Live TV › Recordings).
                if (!rec.fromOtherTv) add((if (rec.keepForever) "Keep Forever  ✓" else "Keep Forever") to {
                    menuFor = null; viewModel.setKeepForever(rec, !rec.keepForever)
                })
            }
            add("Information" to { menuFor = null; infoFor = rec })
            // Wrong poster or artwork (a film instead of the show, the US version of a UK show):
            // pick the right one; it's used for every recording of this show.
            if (!com.nuvio.tv.livetv.data.LiveTvPosterResolver.isSportsRecording(rec.channelName, rec.title))
                add("Fix Match" to { menuFor = null; fixMatchFor = rec.title to (if (rec.episode.isNullOrBlank()) null else true) })
            add("Delete" to { menuFor = null; confirmDelete = rec })
        }
        RecordingActionDialog(title = rec.title, detail = rec.problem, actions = actions, onDismiss = { menuFor = null })
    }
    fixMatchFor?.let { (title, series) ->
        val fix = viewModel.fixFor(title)
        com.nuvio.tv.livetv.ui.FixMatchDialog(
            title = title,
            initialQuery = fix?.meta?.name ?: com.nuvio.tv.livetv.ui.LiveTvSearchBridge.cleanTitle(title).ifBlank { title },
            initialSeries = series,
            scopeNote = "Used for every recording of this show, now and later",
            hasFix = fix != null,
            current = com.nuvio.tv.livetv.data.TitleMatchFixes.currentOf(fix),
            search = { q, kind, year -> viewModel.searchMatches(q, kind, year) },
            onPick = { c -> fixMatchFor = null; viewModel.setMatch(title, c) },
            onReset = { fixMatchFor = null; viewModel.setMatch(title, null) },
            onDismiss = { fixMatchFor = null }
        )
    }
    movePick?.let { recs ->
        val here = recs.mapNotNull { viewModel.storageIdOf(it) }.distinct()
        val places = RecordingStorage.choices(recContext).filter { c -> recs.any { viewModel.storageIdOf(it) != c.id } }
        RecordingActionDialog(
            title = if (recs.size == 1) "Move \"${recs.first().title}\"" else "Move ${recs.size} recordings",
            detail = "Copies to the place you choose, checks the copy, then removes it from where it was. " +
                "Leave the app open until it finishes.",
            actions = places.map { c ->
                val label = c.label + (if (c.isNetwork && c.totalBytes <= 0) "" else " · " + RecordingStorage.sizeLabel(c.freeBytes) + " free") +
                    if (here.size == 1 && here.first() == c.id) "  (already here)" else ""
                label to { movePick = null; viewModel.moveTo(recs, c) }
            } + listOf("Cancel" to { movePick = null }),
            onDismiss = { movePick = null }
        )
    }
    confirmDeleteShow?.let { show ->
        RecordingActionDialog(
            title = "Delete all of \"${show.title}\"?",
            detail = "This deletes all ${show.episodes.size} episodes. It can't be undone.",
            actions = listOf(
                "Cancel" to { confirmDeleteShow = null },
                "Delete All" to { confirmDeleteShow = null; show.episodes.forEach { viewModel.delete(it) } }
            ),
            onDismiss = { confirmDeleteShow = null }
        )
    }
    if (pickingChannel) ManualRecordingChannelPicker(
        channels = pickerChannels,
        onPick = { ch -> pickingChannel = false; manualFor = ch },
        onDismiss = { pickingChannel = false }
    )
    manualFor?.let { ch ->
        ManualRecordingDialog(
            channel = ch,
            use24h = use24h,
            onSchedule = { title, start, stop, days -> manualFor = null; viewModel.scheduleManual(ch, title, start, stop, days) },
            onDismiss = { manualFor = null }
        )
    }
    infoFor?.let { picked ->
        // Live: a recording still going keeps its size and length up to date.
        val rec = recordings.firstOrNull { it.id == picked.id } ?: picked
        val context = androidx.compose.ui.platform.LocalContext.current
        RecordingInfoDialog(rec, savedOnLabel(context, rec.dir), playlistOf(rec.channelKey.substringBefore('|')), onDismiss = { infoFor = null })
    }
    confirmDelete?.let { rec ->
        RecordingActionDialog(
            title = "Delete \"${rec.title}\"?",
            detail = if (rec.isRecording) "It's still recording. This stops it and deletes what's been recorded." else "This can't be undone.",
            actions = listOf(
                "Cancel" to { confirmDelete = null },
                "Delete" to { confirmDelete = null; viewModel.delete(rec) }
            ),
            onDismiss = { confirmDelete = null }
        )
    }
}

@Composable
internal fun SectionTitle(text: String, color: Color = NuvioTheme.colors.TextSecondary) {
    LiveText(
        text.uppercase(),
        size = 13.sp,
        weight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    LiveText(text, size = 14.sp, color = NuvioTheme.colors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
}

/** A card's look: the guide's tile color, the theme's focus color and ring when highlighted. */
@Composable
internal fun cardColors(focused: Boolean): Pair<Color, Color> {
    val colors = NuvioTheme.colors
    return (if (focused) colors.FocusBackground else com.nuvio.tv.livetv.ui.guideSurface()) to
        (if (focused) colors.FocusRing else com.nuvio.tv.livetv.ui.GuideGlassEdge)
}

/**
 * A recording's picture, like Nuvio's landscape cards: the show's backdrop with its title logo
 * over the bottom (or the title in text when there's no logo). With no backdrop found, the
 * channel's logo on the tile color, with the title under it.
 */
@Composable
internal fun ArtThumb(
    art: RecordingArt?,
    channelLogo: String?,
    title: String,
    modifier: Modifier,
    /** Smaller title and logo (the Recording Now card). */
    compact: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(liveCorner()))
            .background(com.nuvio.tv.livetv.ui.guideSurfaceVariant())
    ) {
        if (art?.backdrop != null) {
            AsyncImage(
                model = art.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Darker toward the bottom, so the logo and the progress bar stand out.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f)
                    )
                )
            )
            if (art.logo != null) {
                AsyncImage(
                    model = art.logo,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = if (compact) 10.dp else 16.dp)
                        .fillMaxWidth(if (compact) 0.75f else 0.6f)
                        .fillMaxHeight(0.36f)
                )
            } else {
                LiveText(
                    title,
                    size = if (compact) 13.sp else 17.sp,
                    weight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = if (compact) 10.dp else 16.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (channelLogo != null) {
                    AsyncImage(
                        model = channelLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.5f).height(if (compact) 36.dp else 52.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LiveText(
                    title,
                    size = if (compact) 13.sp else 15.sp,
                    weight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 2,
                    align = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        content()
    }
}

@Composable
private fun RecTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(LIVE_RED)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        LiveText("REC", size = 12.sp, weight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
internal fun ProgressLine(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** What's recording now, as a thin strip: REC, title, channel and times, how far along, Watch and Stop. */
@Composable
private fun RecordingNowCard(
    rec: LiveTvRecording,
    nowMs: Long,
    onWatch: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    var watchFocused by remember { mutableStateOf(false) }
    var stopFocused by remember { mutableStateOf(false) }
    val (bg, ring) = cardColors(watchFocused || stopFocused)
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    val from = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs
    val to = rec.programStopMs.takeIf { it > 0 } ?: rec.stopAtMs
    // Catch-up downloads come faster than the show and have a long time limit: go by how much
    // is saved against the show's length (show plus extra time when known), not the time limit.
    val catchupLenSec = if (rec.fromCatchup) rec.expectedSec.takeIf { it > 0 } ?: (rec.programStopMs - rec.programStartMs).takeIf { it > 0 }?.div(1000.0) else null
    val fraction = when {
        catchupLenSec != null -> (rec.durationSec / catchupLenSec).toFloat().coerceIn(0f, 1f)
        rec.stopAtMs > rec.startedAtMs -> ((nowMs - rec.startedAtMs).toFloat() / (rec.stopAtMs - rec.startedAtMs))
        else -> 0f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecTag()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LiveText(rec.title + (rec.episode?.let { " · $it" } ?: ""), size = 16.sp, weight = FontWeight.Bold, marquee = watchFocused || stopFocused)
            val status = when {
                rec.durationSec <= 0 -> "Starting…" + (rec.problem?.let { " ($it)" } ?: "")
                else -> durationLabel(rec.durationSec) + " recorded" + (rec.problem?.let { " · $it" } ?: "")
            }
            LiveText(
                listOf(rec.channelName, time.format(Date(from)) + " – " + time.format(Date(to)), status).joinToString(" · "),
                size = 13.sp,
                color = colors.TextSecondary
            )
        }
        Spacer(Modifier.width(14.dp))
        ProgressLine(fraction, LIVE_RED, Modifier.width(140.dp))
        Spacer(Modifier.width(16.dp))
        PillButton("Watch", primary = true, onFocus = { watchFocused = it }, onClick = onWatch, onMenu = onMenu)
        Spacer(Modifier.width(10.dp))
        PillButton("Stop", primary = false, onFocus = { stopFocused = it }, onClick = onStop, onMenu = onMenu)
    }
}

@Composable
internal fun PillButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> colors.TextPrimary
        primary -> colors.TextPrimary.copy(alpha = 0.85f)
        else -> com.nuvio.tv.livetv.ui.guideSurfaceVariant()
    }
    val fg = if (focused || primary) colors.Background else colors.TextPrimary
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, if (focused) colors.FocusRing else com.nuvio.tv.livetv.ui.GuideGlassEdge, shape)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(label, size = 16.sp, weight = FontWeight.SemiBold, color = fg)
    }
}

/** A round gear button the height of [PillButton], in the same look. */
@Composable
private fun RecordingsGearButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (focused) colors.TextPrimary else com.nuvio.tv.livetv.ui.guideSurfaceVariant())
            .border(2.dp, if (focused) colors.FocusRing else com.nuvio.tv.livetv.ui.GuideGlassEdge, androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = "Recording settings" }
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            androidx.compose.material.icons.Icons.Default.Settings,
            contentDescription = null,
            tint = if (focused) colors.Background else colors.TextPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}

internal const val TAB_RECORDED = 0
internal const val TAB_SCHEDULED = 1
internal const val TAB_SERIES = 2
/** Posters across a row in the Recorded and Series tabs. */
private const val POSTER_COLUMNS = 6

/** The same title written a little differently ("The Chase", "the chase ") finds the same poster. */
internal fun posterKey(title: String): String = title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

/** Recorded, Scheduled or Series: the one you're on filled in, the highlight its own ring. Highlighting it opens it. */
@Composable
private fun RecordingsTab(label: String, count: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val accent = colors.Secondary
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(shape)
            .background(
                when {
                    focused -> colors.FocusBackground
                    selected -> accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }
            )
            .border(
                2.dp,
                when {
                    focused -> colors.FocusRing
                    selected -> accent.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
                shape
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selected) onSelect()
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(
            label,
            size = 17.sp,
            weight = FontWeight.SemiBold,
            color = if (selected || focused) colors.TextPrimary else colors.TextSecondary
        )
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            LiveText("$count", size = 14.sp, color = colors.TextTertiary)
        }
    }
}

/**
 * A show's portrait poster, like the guide's. With no poster found, the channel's logo on the
 * tile color with the title under it.
 */
@Composable
internal fun PosterThumb(
    poster: String?,
    channelLogo: String?,
    title: String,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(liveCorner()))
            .background(com.nuvio.tv.livetv.ui.guideSurfaceVariant())
    ) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // A little darker at the bottom, so the progress bar and labels stand out.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.7f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f)
                    )
                )
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (channelLogo != null) {
                    AsyncImage(
                        model = channelLogo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                LiveText(
                    title,
                    size = 14.sp,
                    weight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 3,
                    align = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        content()
    }
}

/** A series (or a repeating recording) set to record: its poster, then the title and how it records. */
@Composable
private fun SeriesPosterCard(
    title: String,
    poster: String?,
    channelLogo: String?,
    line: String,
    channel: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onClick(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick
            ),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        PosterThumb(
            poster, channelLogo, title,
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
        )
        LiveText(title, size = 14.sp, weight = FontWeight.SemiBold, marquee = focused)
        LiveText(line, size = 12.sp, color = colors.TextSecondary)
        LiveText(channel, size = 12.sp, color = colors.TextTertiary)
    }
}

/**
 * Scheduled: a heading for each day ("Today · Sat 21 Nov"), then that day's shows in time order.
 * Left and Right jump to the previous or next day.
 */
@Composable
private fun ScheduledByDay(
    schedules: List<RecordingSchedule>,
    posterOf: (RecordingSchedule) -> String?,
    channelLine: (RecordingSchedule) -> String,
    noteOf: (RecordingSchedule) -> String?,
    use24h: Boolean,
    firstModifier: Modifier,
    onClick: (RecordingSchedule) -> Unit
) {
    val days = remember(schedules) {
        schedules.sortedBy { it.startMs }.groupBy { dayStartOf(it.startMs) }.toList()
    }
    // Where each day's heading sits in the list, and the highlight for its first show.
    val headerIndex = remember(days) {
        var i = 0
        days.map { (_, list) -> i.also { i += 1 + list.size } }
    }
    val dayReqs = remember(days.size) { List(days.size) { FocusRequester() } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val time = remember(use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    fun jumpTo(day: Int) {
        if (day !in days.indices) return
        scope.launch {
            runCatching { listState.animateScrollToItem(headerIndex[day]) }
            kotlinx.coroutines.delay(60)
            runCatching { dayReqs[day].requestFocus() }
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)
    ) {
        days.forEachIndexed { d, (dayStart, list) ->
            item(key = "day:$dayStart") {
                LiveText(
                    dayHeading(dayStart),
                    size = 15.sp,
                    weight = FontWeight.Bold,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(top = if (d == 0) 0.dp else 14.dp, bottom = 2.dp)
                )
            }
            items(list, key = { it.id }) { s ->
                val isFirstOfDay = s.id == list.first().id
                ScheduleRow(
                    s = s,
                    poster = posterOf(s),
                    times = time.format(Date(s.startMs)) + " – " + time.format(Date(s.stopMs)),
                    channel = channelLine(s),
                    note = noteOf(s),
                    modifier = Modifier
                        .then(if (isFirstOfDay) Modifier.focusRequester(dayReqs[d]) else Modifier)
                        .then(if (d == 0 && isFirstOfDay) firstModifier else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionRight -> { jumpTo(d + 1); true }
                                Key.DirectionLeft -> { jumpTo(d - 1); true }
                                else -> false
                            }
                        },
                    onClick = { onClick(s) }
                )
            }
        }
    }
}

/** One show set to record: a small poster, the title, when and where, and Series when a series set it. */
@Composable
private fun ScheduleRow(
    s: RecordingSchedule,
    poster: String?,
    times: String,
    channel: String,
    note: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val (bg, ring) = cardColors(focused)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onClick(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PosterThumb(poster, s.channel.logo, "", Modifier.width(44.dp).aspectRatio(2f / 3f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LiveText(s.title + (s.episode?.let { "  ·  $it" } ?: ""), size = 16.sp, weight = FontWeight.SemiBold, marquee = focused)
            LiveText("$times · $channel", size = 13.sp, color = colors.TextSecondary)
            note?.let { LiveText(it, size = 12.sp, color = colors.TextTertiary) }
        }
        if (s.seriesId != null) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(colors.Secondary.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) { LiveText("Series", size = 12.sp, weight = FontWeight.SemiBold, color = colors.TextSecondary) }
        }
    }
}

/** Midnight (local time) of the day [ms] falls on. */
private fun dayStartOf(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today · Sat 21 Nov", "Tomorrow · Sun 22 Nov", else "Friday 27 Nov". */
private fun dayHeading(dayStart: Long): String {
    val noon = dayStart + 12 * 3_600_000L
    val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(noon))
    return when (shortDayLabel(noon)) {
        "Today" -> "Today · $date"
        "Tomorrow" -> "Tomorrow · $date"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(noon))
    }
}

/** A finished recording: its poster with how much you've watched, then title, day and length. */
@Composable
internal fun RecordedCard(
    rec: LiveTvRecording,
    poster: String?,
    resumeFraction: Float?,
    modifier: Modifier,
    /** Watched to the end (or marked watched): a tick on the picture, as in a show's folder. */
    watched: Boolean = false,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val failed = rec.status == RecordingStatus.FAILED
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PosterThumb(
            poster, rec.channelLogo, rec.title,
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
        ) {
            resumeFraction?.let { f ->
                ProgressLine(
                    f, colors.Secondary,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
            if (watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(50))
                        .background(colors.Secondary).padding(horizontal = 8.dp, vertical = 2.dp)
                ) { LiveText("✓", size = 13.sp, weight = FontWeight.Bold, color = colors.Background) }
            }
        }
        LiveText(
            rec.title + (rec.episode?.let { " · $it" } ?: ""),
            size = 14.sp,
            weight = FontWeight.SemiBold,
            color = if (focused) colors.TextPrimary else colors.TextPrimary.copy(alpha = 0.9f),
            marquee = focused
        )
        LiveText(
            if (failed) rec.problem ?: "Couldn't be recorded"
            else listOfNotNull(
                shortDayLabel(rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs),
                durationLabel(rec.durationSec).takeIf { rec.durationSec > 0 }
            ).joinToString(" · ") + (rec.problem?.let { " · $it" } ?: ""),
            size = 12.sp,
            color = if (failed) colors.Error else colors.TextSecondary
        )
    }
}

@Composable
internal fun RecordingActionDialog(
    title: String,
    detail: String?,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    // The same dark glass menu as the guide's.
    com.nuvio.tv.livetv.ui.LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            LiveText(detail, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 6)
        }
        Spacer(Modifier.height(14.dp))
        // Scrolls when the choices don't all fit (the last ones used to sit off the bottom of
        // the screen, out of reach).
        Column(
            modifier = Modifier
                .heightIn(max = 340.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEachIndexed { i, (label, action) ->
                com.nuvio.tv.livetv.ui.MenuItem(label, if (i == 0) Modifier.focusRequester(first) else Modifier, action)
            }
        }
    }
}

/** Hold OK → Information: what a recording is, how big it is and where it's kept. */
@Composable
private fun RecordingInfoDialog(rec: LiveTvRecording, savedOn: String, playlist: String?, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    val day = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    val aired = if (rec.programStartMs > 0 && rec.programStopMs > rec.programStartMs)
        day.format(Date(rec.programStartMs)) + ", " + time.format(Date(rec.programStartMs)) + " – " + time.format(Date(rec.programStopMs))
    else day.format(Date(rec.startedAtMs)) + ", " + time.format(Date(rec.startedAtMs))
    val rows = buildList {
        add("Channel" to rec.channelName)
        playlist?.let { add("Playlist" to it) }
        rec.episode?.let { add("Episode" to it) }
        add("Aired" to aired)
        add("Length" to if (rec.durationSec > 0) durationLabel(rec.durationSec) + (if (rec.isRecording) " so far" else "") else "—")
        add("Size" to if (rec.sizeBytes > 0) sizeLabel(rec.sizeBytes) + (if (rec.isRecording) " so far" else "") else "—")
        add("Recorded" to when {
            rec.isRecording && rec.fromCatchup -> "Saving from catch-up now"
            rec.isRecording && rec.fromOtherTv -> "Recording now, on another TV"
            rec.isRecording -> "Recording now"
            rec.fromCatchup -> "From catch-up"
            rec.fromOtherTv -> "Live, as it aired, on another TV"
            else -> "Live, as it aired"
        })
        add("Saved on" to savedOn)
        if (rec.keepForever) add("Kept" to "Forever (never deleted by age)")
        rec.problem?.let { add("Note" to it) }
    }
    com.nuvio.tv.livetv.ui.LiveDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText(rec.title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { (k, v) ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    LiveText(k, color = NuvioTheme.colors.TextSecondary, size = 14.sp, modifier = Modifier.width(110.dp))
                    LiveText(v, size = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            com.nuvio.tv.livetv.ui.MenuItem("Close", Modifier.focusRequester(first), onDismiss)
        }
    }
}

/** "This Device" or the drive's name, for the folder a recording is in. */
private fun savedOnLabel(context: android.content.Context, dir: String): String {
    val choice = runCatching { RecordingStorage.choices(context) }.getOrDefault(emptyList())
        .filter { dir.startsWith(it.dir.absolutePath) }
        .maxByOrNull { it.dir.absolutePath.length }
    return choice?.label ?: if (dir.startsWith("/storage/emulated") || dir.startsWith("/data/")) "This Device" else "A drive that isn't plugged in"
}

internal fun dateLabel(ms: Long): String {
    val day = shortDayLabel(ms)
    return day + ", " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

/** "Today", "Tomorrow", "Yesterday", the weekday within a week, else the date. */
internal fun shortDayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    fun dayIndex(t: Long): Long {
        cal.timeInMillis = t
        return cal.get(java.util.Calendar.YEAR) * 400L + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val diff = dayIndex(ms) - dayIndex(System.currentTimeMillis())
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff in -6L..6L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

internal fun durationLabel(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

/** "8:30 PM" / "20:30" for a time of day in minutes after midnight. */
private fun repeatTime(minuteOfDay: Int, use24h: Boolean): String {
    val c = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(java.util.Calendar.MINUTE, minuteOfDay % 60)
    }
    return java.text.SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", java.util.Locale.getDefault()).format(c.time)
}
