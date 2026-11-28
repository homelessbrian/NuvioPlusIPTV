package com.nuvio.tv.data.watchstats

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchStatsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val profiles: ProfileDataStoreFactory
) {
    private val settings = context.getSharedPreferences("watch_stats_settings", Context.MODE_PRIVATE)
    private val _trackingEnabled = MutableStateFlow(settings.getBoolean(TRACKING_ENABLED, false))
    val trackingEnabled: StateFlow<Boolean> = _trackingEnabled.asStateFlow()

    private val submissionLock = Any()
    private var settingsRevision = 0L
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        // Commands run in submission order, including a clear queued after an outstanding write.
        // This worker has no timer or polling and suspends whenever there is no command.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (command in commands) command()
        }
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        val saved = CompletableDeferred<Unit>()
        synchronized(submissionLock) {
            val revision = ++settingsRevision
            if (!enabled) _trackingEnabled.value = false
            commands.trySend {
                saved.completeWith(runCatching {
                    if (!settings.edit().putBoolean(TRACKING_ENABLED, enabled).commit()) {
                        throw IOException("Could not save Watch Stats setting")
                    }
                    synchronized(submissionLock) {
                        if (revision == settingsRevision) _trackingEnabled.value = enabled
                    }
                })
            }.getOrThrow()
        }
        saved.await()
    }

    /** Adds an active segment, or a start with zero duration. No session details are stored. */
    fun record(activity: WatchActivity, startedAtMs: Long, durationMs: Long, plays: Int) {
        if (!_trackingEnabled.value || activity.profileId <= 0 || startedAtMs <= 0L ||
            durationMs !in 0L..MAX_SEGMENT_MS || startedAtMs > Long.MAX_VALUE - MAX_SEGMENT_MS ||
            plays < 0 || (durationMs == 0L && plays == 0)
        ) return

        val input = activity.copy(
            key = activity.key.trim().take(MAX_KEY_LENGTH),
            label = activity.label.trim().take(MAX_NAME_LENGTH),
            genres = if (activity.kind == WatchActivityKind.LIVE) emptyList() else activity.genres.asSequence()
                .map { it.trim().take(MAX_NAME_LENGTH) }
                .filter(String::isNotEmpty)
                .distinctBy(String::lowercase)
                .take(MAX_ACTIVITY_GENRES)
                .toList()
        )
        val zone = ZoneId.systemDefault()
        synchronized(submissionLock) {
            if (!_trackingEnabled.value) return
            commands.trySend {
                runCatching {
                    if (!profiles.isProfileDeleted(input.profileId)) append(input, startedAtMs, durationMs, plays, zone)
                }.onFailure { Log.w(TAG, "Could not save Watch Stats", it) }
            }
        }
    }

    suspend fun read(profileId: Int, range: WatchStatsRange): WatchStatsSummary = submit {
        if (profileId <= 0 || profiles.isProfileDeleted(profileId)) return@submit WatchStatsSummary()
        val data = profileStore(profileId).data.first()
        val days = watchStatsDays(range, LocalDate.now())
        if (days == null) {
            WatchStatsBucket.decode(data[lifetimeKey]).summary()
        } else {
            val combined = WatchStatsBucket()
            for (day in days) combined.merge(WatchStatsBucket.decode(data[dayKey(day)]))
            combined.summary()
        }
    }

    suspend fun clearProfile(profileId: Int) {
        submit {
            if (profileId > 0 && !profiles.isProfileDeleted(profileId)) profileStore(profileId).edit { it.clear() }
        }
    }

    /** Waits for writes already accepted by [record], e.g. before deleting profile stores. */
    suspend fun flushPending() = submit { }

    private fun profileStore(profileId: Int) = profiles.get(profileId, "watch_stats")

    private suspend fun <T> submit(action: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        commands.send { result.completeWith(runCatching { action() }) }
        return result.await()
    }

    private suspend fun append(activity: WatchActivity, startedAtMs: Long, durationMs: Long, plays: Int, zone: ZoneId) {
        val slices = watchStatsDaySlices(startedAtMs, durationMs, plays, zone)
        val today = LocalDate.now(zone).toEpochDay()
        val retainedDays = (today - 7L)..(today + 1L)
        profileStore(activity.profileId).edit { data ->
            val lifetime = WatchStatsBucket.decode(data[lifetimeKey])
            lifetime.record(activity, durationMs, plays)
            data[lifetimeKey] = lifetime.encode()

            for (slice in slices) {
                if (slice.epochDay !in retainedDays) continue
                val key = dayKey(slice.epochDay)
                val bucket = WatchStatsBucket.decode(data[key])
                bucket.record(activity, slice.durationMs, slice.plays)
                data[key] = bucket.encode()
            }

            data.asMap().keys.filter { it.name.startsWith(DAY_PREFIX) }.forEach { key ->
                val day = key.name.removePrefix(DAY_PREFIX).toLongOrNull()
                if (day == null || day !in retainedDays) data.remove(key)
            }
        }
    }

    private companion object {
        const val TAG = "WatchStatsRepository"
        const val TRACKING_ENABLED = "enabled"
        const val MAX_SEGMENT_MS = 24 * 60 * 60_000L
        const val MAX_KEY_LENGTH = 256
        const val MAX_NAME_LENGTH = 160
        const val MAX_ACTIVITY_GENRES = 16
        const val DAY_PREFIX = "day_"
        val lifetimeKey = stringPreferencesKey("lifetime")
        fun dayKey(day: Long) = stringPreferencesKey("$DAY_PREFIX$day")
    }
}
