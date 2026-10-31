package com.nuvio.tv.livetv.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The channel a recording is set for, saved with it so it can start without loading Live TV. */
data class ChannelSnapshot(
    val key: String,
    val sourceId: String,
    val name: String,
    val logo: String?,
    val url: String,
    val headers: Map<String, String>,
    val catchup: CatchupInfo?
) {
    fun toChannel(): LiveChannel = LiveChannel(
        key = key, sourceId = sourceId, sourceName = "", name = name, tvgId = null, tvgName = null,
        logo = logo, groupId = "", group = "", number = 0, url = url, headers = headers, catchup = catchup
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key); put("sourceId", sourceId); put("name", name); put("logo", logo ?: "")
        put("url", url); put("headers", JSONObject(headers as Map<*, *>))
        catchup?.let { c ->
            put("catchup", JSONObject().apply {
                put("type", c.type); put("source", c.source ?: ""); put("days", c.days); put("correction", c.correctionHours)
            })
        }
    }

    companion object {
        fun of(c: LiveChannel) = ChannelSnapshot(c.key, c.sourceId, c.name, c.logo, c.url, c.headers, c.catchup)

        fun fromJson(o: JSONObject): ChannelSnapshot {
            val h = o.optJSONObject("headers")
            val headers = HashMap<String, String>()
            h?.keys()?.forEach { k -> headers[k] = h.optString(k) }
            val c = o.optJSONObject("catchup")?.let {
                CatchupInfo(it.optString("type"), it.optString("source").ifBlank { null }, it.optInt("days"), it.optDouble("correction", 0.0))
            }
            return ChannelSnapshot(
                o.optString("key"), o.optString("sourceId"), o.optString("name"), o.optString("logo").ifBlank { null },
                o.optString("url"), headers, c
            )
        }
    }
}

/** A show set to record. */
data class RecordingSchedule(
    val id: String,
    val channel: ChannelSnapshot,
    val title: String,
    val episode: String?,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
    /** Downloaded from the provider's catch-up after it ends (the reliable way), not recorded live. */
    val useCatchup: Boolean,
    /** Set by a series rule (its id), or null for a single show. */
    val seriesId: String? = null,
    val attempts: Int = 0,
    /** Next time to try (catch-up downloads retry a few times). */
    val nextTryMs: Long = 0L,
    /** Extra time before and after, from the settings when it was set (-1: the old 2 and 5 min). */
    val preMs: Long = -1L,
    val postMs: Long = -1L
) {
    val pre: Long get() = if (preMs >= 0) preMs else LiveTvRecordingScheduler.PRE_MS
    val post: Long get() = if (postMs >= 0) postMs else LiveTvRecordingScheduler.POST_MS

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("channel", channel.toJson()); put("title", title)
        put("episode", episode ?: ""); put("description", description ?: "")
        put("startMs", startMs); put("stopMs", stopMs); put("useCatchup", useCatchup)
        put("seriesId", seriesId ?: ""); put("attempts", attempts); put("nextTryMs", nextTryMs)
        put("preMs", preMs); put("postMs", postMs)
    }

    companion object {
        fun fromJson(o: JSONObject) = RecordingSchedule(
            id = o.optString("id"),
            channel = ChannelSnapshot.fromJson(o.getJSONObject("channel")),
            title = o.optString("title"),
            episode = o.optString("episode").ifBlank { null },
            description = o.optString("description").ifBlank { null },
            startMs = o.optLong("startMs"),
            stopMs = o.optLong("stopMs"),
            useCatchup = o.optBoolean("useCatchup"),
            seriesId = o.optString("seriesId").ifBlank { null },
            attempts = o.optInt("attempts"),
            nextTryMs = o.optLong("nextTryMs"),
            preMs = o.optLong("preMs", -1L),
            postMs = o.optLong("postMs", -1L)
        )
    }
}

/** "Record series": every airing of [title] on this channel. */
data class SeriesRule(
    val id: String,
    val channel: ChannelSnapshot,
    val title: String,
    /** Keep only the newest this many episodes (0 = keep them all). */
    val keepLast: Int = 0,
    /**
     * Episodes this series has recorded (see [LiveTvRecordingScheduler.episodeKey]), kept even
     * after a recording is deleted or dropped by "keep the last N", so a repeat later in the
     * week is never recorded again.
     */
    val seen: List<String> = emptyList()
) {
    val norm: String get() = LiveTvRepository.normalize(title)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("channel", channel.toJson()); put("title", title); put("keepLast", keepLast)
        put("seen", org.json.JSONArray(seen))
    }

    companion object {
        fun fromJson(o: JSONObject) = SeriesRule(
            o.optString("id"), ChannelSnapshot.fromJson(o.getJSONObject("channel")), o.optString("title"), o.optInt("keepLast"),
            o.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        )
    }
}

/**
 * Scheduled recordings: shows set to record from the guide, and series rules. Each one gets an
 * alarm that wakes the app at the right time:
 *  - Channels with catch-up: after the show ends, the whole show is downloaded from the
 *    provider's catch-up (the box doesn't have to be on at air time, and nothing's lost to a
 *    dropped connection). Tried again later if the provider hasn't got it yet.
 *  - Other channels: recorded live, from 2 minutes before until 5 minutes after.
 * Series rules add each new airing they find in the guide.
 */
@Singleton
class LiveTvRecordingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: LiveTvRecorder,
    private val repository: LiveTvRepository,
    private val prefs: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val file = File(File(context.filesDir, "livetv"), "recording_schedules.json")

    private val _schedules = MutableStateFlow<List<RecordingSchedule>>(emptyList())
    val schedules: StateFlow<List<RecordingSchedule>> = _schedules.asStateFlow()
    private val _series = MutableStateFlow<List<SeriesRule>>(emptyList())
    val series: StateFlow<List<SeriesRule>> = _series.asStateFlow()

    @Volatile private var started = false
    /** Extra time before and after recordings (Settings → Live TV → Recordings), kept up to date. */
    @Volatile private var padPreMs = PRE_MS
    @Volatile private var padPostMs = POST_MS

    /** Loads the schedules and sets their alarms (called when the app starts, or by an alarm). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            prefs.settings.collect { st ->
                padPreMs = st.recordPreMinutes.coerceAtLeast(0) * 60_000L
                padPostMs = st.recordPostMinutes.coerceAtLeast(0) * 60_000L
            }
        }
        scope.launch {
            load()
            armAll()
            watchNetwork()
            // New guide listings: look for new airings of series.
            repository.programs.debounce(10_000).collect { if (_series.value.isNotEmpty()) expandSeries() }
        }
    }

    // ------------------------------------------------------------------ setting recordings

    /** Extra time before and after recordings (Settings → Live TV → Recordings), in ms. */
    fun padding(): Pair<Long, Long> = padPreMs to padPostMs

    /** True if [program] on [channelKey] is set to record. */
    fun isScheduled(channelKey: String, program: EpgProgram): Boolean =
        _schedules.value.any { it.channel.key == channelKey && it.startMs == program.startMs }

    fun seriesFor(channelKey: String, title: String): SeriesRule? {
        val n = LiveTvRepository.normalize(title)
        return _series.value.firstOrNull { it.channel.key == channelKey && it.norm == n }
    }

    /**
     * A recording set by hand (Schedule a Recording): [title] from [startMs] to [stopMs] on
     * [channel], for channels with no listings. Recorded like a show from the guide.
     */
    fun addManual(channel: LiveChannel, title: String, startMs: Long, stopMs: Long): String =
        add(channel, EpgProgram(startMs = startMs, stopMs = stopMs, title = title))

    /**
     * Sets [program] on [channel] to record. A show on now on a channel without catch-up starts
     * recording straight away. Returns a message to show.
     */
    fun add(channel: LiveChannel, program: EpgProgram, seriesId: String? = null): String {
        val now = System.currentTimeMillis()
        val useCatchup = channel.catchup != null && (channel.catchup.days > 0)
        val title = cleanTitle(program.title)
        if (!useCatchup && now >= program.startMs && now < program.stopMs) {
            // On now, no catch-up: record from now.
            if (recorder.activeFor(channel.key) == null) {
                recorder.start(channel, channel.url, title, program.episode, program.description,
                    program.startMs, program.stopMs, program.stopMs + padPostMs)
            }
            return "Recording \"$title\" now"
        }
        if (program.stopMs <= now && !useCatchup) return "That show has already been on"
        val s = RecordingSchedule(
            id = "s" + System.nanoTime(),
            channel = ChannelSnapshot.of(channel),
            title = title,
            episode = program.episode,
            description = program.description,
            startMs = program.startMs,
            stopMs = program.stopMs,
            useCatchup = useCatchup,
            seriesId = seriesId,
            preMs = padPreMs,
            postMs = padPostMs
        )
        scope.launch {
            mutex.withLock {
                if (_schedules.value.none { it.channel.key == channel.key && it.startMs == program.startMs }) {
                    _schedules.value = (_schedules.value + s).sortedBy { it.startMs }
                    save()
                }
            }
            arm(s)
        }
        val clash = clashWith(s)
        val whenText = timeLabel(program.startMs)
        return when {
            clash != null -> "Set to record \"$title\" ($whenText). It overlaps \"${clash.title}\"; your account may only allow one at a time."
            useCatchup && program.stopMs <= now -> "Saving \"$title\" from catch-up"
            useCatchup -> "Set to record \"$title\" ($whenText). It's saved from catch-up after it ends."
            else -> "Set to record \"$title\" ($whenText)"
        }
    }

    /** Cancels a scheduled show. */
    fun cancel(channelKey: String, startMs: Long) {
        scope.launch {
            mutex.withLock {
                val gone = _schedules.value.filter { it.channel.key == channelKey && it.startMs == startMs }
                gone.forEach { disarm(it) }
                _schedules.value = _schedules.value - gone.toSet()
                save()
            }
        }
    }

    fun cancel(id: String) {
        scope.launch {
            mutex.withLock {
                _schedules.value.firstOrNull { it.id == id }?.let { disarm(it) }
                _schedules.value = _schedules.value.filterNot { it.id == id }
                save()
            }
        }
    }

    /** Record series: every airing of this show's title on this channel. */
    fun addSeries(channel: LiveChannel, program: EpgProgram): String {
        val title = cleanTitle(program.title)
        seriesFor(channel.key, title)?.let { return "\"$title\" is already set to record" }
        val rule = SeriesRule("r" + System.nanoTime(), ChannelSnapshot.of(channel), title)
        scope.launch {
            mutex.withLock {
                _series.value = _series.value + rule
                save()
            }
            expandSeries()
        }
        return "Recording every \"$title\" on ${channel.name}"
    }

    /** Stops a series rule (and cancels the airings it set that haven't started). */
    fun removeSeries(id: String) {
        scope.launch {
            mutex.withLock {
                _series.value = _series.value.filterNot { it.id == id }
                val gone = _schedules.value.filter { it.seriesId == id }
                gone.forEach { disarm(it) }
                _schedules.value = _schedules.value - gone.toSet()
                save()
            }
        }
    }

    /** "Keep the last N" for a series. */
    fun setKeep(seriesId: String, keepLast: Int) {
        scope.launch {
            mutex.withLock {
                _series.value = _series.value.map { if (it.id == seriesId) it.copy(keepLast = keepLast) else it }
                save()
            }
            keepOnlyNewest(seriesId)
        }
    }

    /** Deletes a series' older recordings past its "keep the last N". */
    private fun keepOnlyNewest(seriesId: String) {
        val keep = _series.value.firstOrNull { it.id == seriesId }?.keepLast ?: return
        if (keep <= 0) return
        recorder.recordings.value
            .filter { it.seriesId == seriesId && !it.isRecording }
            .sortedByDescending { it.programStartMs.takeIf { s -> s > 0 } ?: it.startedAtMs }
            .drop(keep)
            .forEach { recorder.delete(it.id) }
    }

    /** Another live recording on the same provider at the same time, if any. */
    private fun clashWith(s: RecordingSchedule): RecordingSchedule? {
        if (s.useCatchup) return null
        return _schedules.value.firstOrNull {
            it.id != s.id && !it.useCatchup && it.channel.sourceId == s.channel.sourceId &&
                it.startMs < s.stopMs && s.startMs < it.stopMs
        }
    }

    // ------------------------------------------------------------------ series

    private suspend fun expandSeries() {
        val rules = _series.value
        if (rules.isEmpty()) return
        val now = System.currentTimeMillis()
        for (rule in rules) {
            val listings = runCatching { repository.upcomingListings(rule.channel.key, days = 7) }.getOrDefault(emptyList())
            val channel = repository.channels.value.firstOrNull { it.key == rule.channel.key } ?: rule.channel.toChannel()
            listings.filter { LiveTvRepository.normalize(cleanTitle(it.title)) == rule.norm && it.stopMs > now }.forEach { p ->
                // The episode by its season and episode number from the guide, however the guide
                // writes it ("S02E05", "S2 E5", "1.4."): a repeat of one recorded before (even
                // one deleted since) or already set is skipped. Airings without an episode
                // number are all recorded, as before.
                val ep = episodeKey(p.episode)
                val current = _series.value.firstOrNull { it.id == rule.id } ?: rule
                val already = _schedules.value.any { it.channel.key == rule.channel.key && it.startMs == p.startMs } ||
                    recorder.recordings.value.any { it.channelKey == rule.channel.key && it.programStartMs == p.startMs } ||
                    (ep != null && (ep in current.seen ||
                        _schedules.value.any { it.seriesId == rule.id && episodeKey(it.episode) == ep } ||
                        recorder.recordings.value.any {
                            (it.seriesId == rule.id || it.channelKey == rule.channel.key) && episodeKey(it.episode) == ep &&
                                LiveTvRepository.normalize(cleanTitle(it.title)) == rule.norm
                        }))
                if (!already && p.startMs > now) add(channel, p, seriesId = rule.id)
            }
        }
    }

    // ------------------------------------------------------------------ alarms

    private fun fireTime(s: RecordingSchedule): Long =
        if (s.nextTryMs > 0) s.nextTryMs
        else if (s.useCatchup) s.stopMs + CATCHUP_DELAY_MS
        else s.startMs - s.pre

    private fun armAll() {
        val now = System.currentTimeMillis()
        _schedules.value.forEach { s ->
            if (!s.useCatchup && s.stopMs < now) {
                // Missed while the box was off.
                scope.launch { drop(s, "\"${s.title}\" was missed (the box was off or asleep)") }
            } else arm(s)
        }
    }

    /** When a schedule will start: recording, or saving from catch-up (for the Recordings screen). */
    fun startsAt(s: RecordingSchedule): Long = fireTime(s)

    /**
     * Anything whose time has already passed but is still waiting (its alarm came late or not at
     * all: the box was asleep, the app was force stopped, or Android held the alarm back) starts
     * now. Called when the Recordings screen opens.
     */
    fun fireOverdue() {
        val now = System.currentTimeMillis()
        _schedules.value.filter { fireTime(it) < now - 60_000L }.forEach { fire(it.id) }
    }

    private fun pending(s: RecordingSchedule): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, s.id)
        return PendingIntent.getBroadcast(
            context, s.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun arm(s: RecordingSchedule) {
        val at = fireTime(s)
        if (at <= System.currentTimeMillis()) { scope.launch { fire(s.id) }; return }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(s))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(s))
        }.onFailure { Log.w(TAG, "Couldn't set the alarm for ${s.title}", it) }
    }

    private fun disarm(s: RecordingSchedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(s)) }
    }

    /** An alarm went off (or a time already passed): start the recording or the download. */
    fun fire(id: String) {
        scope.launch {
            if (!started) { load() }
            val s = _schedules.value.firstOrNull { it.id == id } ?: run { recorder.stopServiceIfIdle(); return@launch }
            val now = System.currentTimeMillis()
            if (!s.useCatchup) {
                if (now >= s.stopMs) { drop(s, "\"${s.title}\" was missed"); return@launch }
                // Too early (the alarm came early): set it again.
                if (now < s.startMs - s.pre - 60_000L) { arm(s); recorder.stopServiceIfIdle(); return@launch }
                val channel = freshChannel(s.channel)
                markSeen(s)
                recorder.start(channel, channel.url, s.title, s.episode, s.description, s.startMs, s.stopMs, s.stopMs + s.post,
                    onFinished = { rec -> s.seriesId?.let { keepOnlyNewest(it) } }, seriesId = s.seriesId)
                remove(s)
                LiveTvNotice.show("Recording \"${s.title}\" on ${channel.name}")
                return@launch
            }
            // Catch-up: download the whole show.
            val channel = freshChannel(s.channel)
            val catchup = channel.catchup ?: s.channel.catchup
            val settings = prefs.currentSettings()
            val zone = prefs.currentPlaylists().firstOrNull { it.id == channel.sourceId }?.serverTimezone
            // As when watching catch-up: the guide's shift off, the catch-up shift on.
            val shift = settings.epgOffsetMinutes * 60_000L - settings.catchupShiftMinutes * 60_000L
            val url = catchup?.let {
                CatchupUrlBuilder.build(channel.url, it, s.startMs - s.pre - shift, s.stopMs + s.post - shift, now,
                    preferHls = false, serverTimezone = zone)
            }
            if (url == null) {
                // No catch-up link after all: nothing to download.
                drop(s, "\"${s.title}\" couldn't be saved: this channel's catch-up isn't available")
                return@launch
            }
            remove(s)
            markSeen(s)
            recorder.start(
                channel, url, s.title, s.episode, s.description, s.startMs, s.stopMs,
                stopAtMs = now + (s.stopMs - s.startMs) * 3 + 30 * 60_000L,
                finite = true,
                fallbacks = CatchupUrlBuilder.alternatives(url, zone),
                seriesId = s.seriesId,
                // Cut off part way: the rest of the show, from a little before where it got to
                // (catch-up links often go by the minute, so a few seconds may repeat).
                resumeFrom = { doneMs ->
                    catchup?.let {
                        val from = s.startMs - s.pre - shift + doneMs - 30_000L
                        CatchupUrlBuilder.build(channel.url, it, from, s.stopMs + s.post - shift, System.currentTimeMillis(),
                            preferHls = false, serverTimezone = zone)
                    }
                },
                onFinished = { rec ->
                    if (rec.status != RecordingStatus.FAILED) s.seriesId?.let { keepOnlyNewest(it) }
                    // No connection (the box asleep in standby, Wi-Fi off overnight): that's not the
                    // show's fault, so it doesn't use up a try. It's tried again while the provider
                    // still keeps the show, and straight away once the connection is back.
                    val offline = rec.status == RecordingStatus.FAILED && (!online() || looksOffline(rec.problem))
                    val keptUntil = s.stopMs + (catchup?.days ?: 1).coerceAtLeast(1) * 24 * 60 * 60_000L - 60 * 60_000L
                    val retryable = offline || s.attempts < MAX_TRIES
                    if (rec.status == RecordingStatus.FAILED && retryable && System.currentTimeMillis() < keptUntil) {
                        // Not ready at the provider yet (or a hiccup): try again in a while.
                        recorder.delete(rec.id)
                        if (offline) com.nuvio.tv.livetv.model.LiveTvLoadReport.add("\"${s.title}\": no connection, so it's saved once the connection is back")
                        val retry = s.copy(attempts = if (offline) s.attempts else s.attempts + 1, nextTryMs = System.currentTimeMillis() + RETRY_MS)
                        scope.launch {
                            mutex.withLock { _schedules.value = (_schedules.value + retry).sortedBy { it.startMs }; save() }
                            arm(retry)
                        }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------ connection

    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager

    /** Whether the app can reach the internet right now (false while Android has it cut off). */
    private fun online(): Boolean = runCatching {
        val cm = connectivity ?: return@runCatching true
        val n = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** Failures that mean "no connection at all", not "the provider said no". */
    private fun looksOffline(problem: String?): Boolean {
        val p = problem?.lowercase() ?: return false
        return listOf("unable to resolve host", "unknownhost", "failed to connect", "network is unreachable",
            "connectexception", "no address associated", "software caused connection abort").any { it in p }
    }

    @Volatile private var watchingNetwork = false

    /**
     * When the connection comes back (the box woke up), catch-up saves that are waiting for
     * their next try go now instead of at their set time.
     */
    private fun watchNetwork() {
        if (watchingNetwork) return
        watchingNetwork = true
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = retryWaitingSoon()
                override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                    if (!blocked) retryWaitingSoon()
                }
            })
        }.onFailure { Log.w(TAG, "Couldn't watch the connection", it) }
    }

    private var retryJob: kotlinx.coroutines.Job? = null

    private fun retryWaitingSoon() {
        // Several "connection back" signals in a row start it once.
        retryJob?.cancel()
        retryJob = scope.launch {
            kotlinx.coroutines.delay(20_000) // let the connection settle
            val now = System.currentTimeMillis()
            val waiting = _schedules.value.filter { it.useCatchup && it.nextTryMs > now }
            if (waiting.isEmpty()) return@launch
            mutex.withLock {
                _schedules.value = _schedules.value.map { s -> if (s in waiting) s.copy(nextTryMs = now) else s }
                save()
            }
            waiting.forEach { fire(it.id) }
        }
    }

    /** The channel as it is now (its link may have changed), or as saved. */
    private fun freshChannel(saved: ChannelSnapshot): LiveChannel =
        repository.channels.value.firstOrNull { it.key == saved.key } ?: saved.toChannel()

    /** A series episode is being recorded: remember it, so its repeats are skipped from now on. */
    private suspend fun markSeen(s: RecordingSchedule) {
        val id = s.seriesId ?: return
        val ep = episodeKey(s.episode) ?: return
        mutex.withLock {
            _series.value = _series.value.map { r ->
                if (r.id == id && ep !in r.seen) r.copy(seen = (r.seen + ep).takeLast(500)) else r
            }
            save()
        }
    }

    private suspend fun remove(s: RecordingSchedule) {
        mutex.withLock {
            _schedules.value = _schedules.value.filterNot { it.id == s.id }
            save()
        }
    }

    private suspend fun drop(s: RecordingSchedule, message: String) {
        remove(s)
        recorder.stopServiceIfIdle()
        LiveTvNotice.show(message)
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add(message)
    }

    // ------------------------------------------------------------------ saving

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val o = JSONObject()
            o.put("schedules", JSONArray().apply { _schedules.value.forEach { put(it.toJson()) } })
            o.put("series", JSONArray().apply { _series.value.forEach { put(it.toJson()) } })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
    }

    private suspend fun load() = mutex.withLock {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@withLock
        val s = o.optJSONArray("schedules")
        val r = o.optJSONArray("series")
        _schedules.value = (0 until (s?.length() ?: 0)).mapNotNull { i -> runCatching { RecordingSchedule.fromJson(s!!.getJSONObject(i)) }.getOrNull() }
            .sortedBy { it.startMs }
        _series.value = (0 until (r?.length() ?: 0)).mapNotNull { i -> runCatching { SeriesRule.fromJson(r!!.getJSONObject(i)) }.getOrNull() }
    }

    private fun cleanTitle(raw: String): String = com.nuvio.tv.livetv.ui.titleMarks(raw).title.trim()

    private fun timeLabel(ms: Long): String {
        val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let { it.format(java.util.Date(ms)) == it.format(java.util.Date()) }
        val fmt = if (sameDay) "h:mm a" else "EEE h:mm a"
        return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    companion object {
        private const val TAG = "LiveTvRecScheduler"
        const val ACTION_FIRE = "com.nuvio.tv.livetv.RECORD"
        const val EXTRA_ID = "id"
        /** Live recordings start this early and run this late. */
        const val PRE_MS = 2 * 60_000L
        const val POST_MS = 5 * 60_000L
        /** Catch-up downloads start this long after the show ends (providers need a moment). */
        const val CATCHUP_DELAY_MS = 10 * 60_000L
        const val RETRY_MS = 30 * 60_000L
        const val MAX_TRIES = 3

        private val SE = Regex("""(?i)\bS(?:eason)?\s*0*(\d{1,3})\s*[\-.:,x]?\s*E(?:p(?:isode)?)?\s*0*(\d{1,4})\b""")
        private val SXE = Regex("""\b0*(\d{1,2})\s*x\s*0*(\d{1,4})\b""")
        private val XMLTV_NS = Regex("""^\s*(\d{1,3})\s*(?:/\s*\d+)?\s*\.\s*(\d{1,4})""")
        private val EP_ONLY = Regex("""(?i)^\s*(?:e|ep|episode)\.?\s*0*(\d{1,4})\s*$""")

        /**
         * One key per episode, whatever way the guide writes its number: "S02E05", "S2 E5",
         * "Season 2 Episode 5", "2x05" and XMLTV's "1.4." (counted from 0) all give "s2e5".
         * Other episode codes (a guide's own ids) are kept as they are. Null: no episode number.
         */
        fun episodeKey(episode: String?): String? {
            val e = episode?.trim().orEmpty()
            if (e.isEmpty()) return null
            SE.find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
            XMLTV_NS.find(e)?.let { return "s${it.groupValues[1].toInt() + 1}e${it.groupValues[2].toInt() + 1}" }
            SXE.find(e)?.let { return "s${it.groupValues[1].toInt()}e${it.groupValues[2].toInt()}" }
            EP_ONLY.find(e)?.let { return "e${it.groupValues[1].toInt()}" }
            return "x:" + e.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { return null }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingSchedulerEntryPoint {
    fun scheduler(): LiveTvRecordingScheduler
}

/** Wakes the app for a scheduled recording, and sets the alarms again after the box restarts. */
class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, RecordingSchedulerEntryPoint::class.java).scheduler()
        }.getOrNull() ?: return
        when (intent.action) {
            LiveTvRecordingScheduler.ACTION_FIRE -> {
                val id = intent.getStringExtra(LiveTvRecordingScheduler.EXTRA_ID) ?: return
                // Keep the app awake long enough for the recording service to take over.
                val pending = goAsync()
                LiveTvRecordingService.start(context.applicationContext)
                scheduler.fire(id)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { pending.finish() } }, 8_000)
            }
            else -> scheduler.start() // boot: set the alarms again
        }
    }
}
