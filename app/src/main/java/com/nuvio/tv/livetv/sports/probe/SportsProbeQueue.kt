package com.nuvio.tv.livetv.sports.probe

import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** Call on the owning scope's dispatcher. Only the engine switches dispatchers. */
internal class SportsProbeQueue(
    scope: CoroutineScope,
    private val engine: SportsChannelProbe,
    private val canCheck: suspend (LiveChannel) -> Boolean,
    private val busy: () -> Boolean,
    private val observation: (LiveChannel) -> SportsProbeResult? = { null },
    private val epoch: () -> Any = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
    private val publishable: (LiveChannel) -> Boolean = { true },
) {
    private data class Key(val channel: LiveChannel, val epoch: Any)
    private data class Cached(val value: SportsProbeResult, val savedAt: Long)
    private data class Work(val key: Key, val generation: Long)
    private val cache = object : LinkedHashMap<Key, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>?) = size > 128
    }
    private val completed = mutableMapOf<Key, SportsProbeResult>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val _results = MutableStateFlow<Map<String, SportsProbeResult>>(emptyMap())
    val results: StateFlow<Map<String, SportsProbeResult>> = _results.asStateFlow()
    private val _blockedByPlayback = MutableStateFlow(false)
    val blockedByPlayback: StateFlow<Boolean> = _blockedByPlayback.asStateFlow()
    private var event: String? = null
    private var channels = linkedMapOf<String, LiveChannel>()
    private var priority = emptyList<String>()
    private var automatic = false
    private var requested = false
    private var closed = false
    private var generation = 0L
    private var pageEpoch: Any = epoch()
    private var active: Deferred<SportsProbeResult>? = null
    private var activeWork: Work? = null
    private var cancelledForPlayback = false

    // This actor never gets replaced. A cancelled child must finish before its next iteration.
    private val worker: Job = scope.launch {
        for (signal in changed) {
            while (isActive && !closed) {
                syncEpoch()
                seed()
                val channel = (priority + channels.keys).asSequence().distinct()
                    .mapNotNull { channels[it] }.firstOrNull { _results.value[it.key]?.status == SportsProbeStatus.QUEUED }
                    ?: break
                val work = Work(Key(channel, pageEpoch), generation)
                val permitted = permitted(channel)
                if (!current(work)) continue
                if (!permitted) {
                    completed[work.key] = deferred("Channel is no longer available")
                    continue
                }
                if (busy()) { seed(); break }
                _results.value = _results.value + (channel.key to SportsProbeResult(SportsProbeStatus.CHECKING))
                val result = try {
                    supervisorScope {
                        val child = async(start = CoroutineStart.LAZY) { engine.check(channel) }
                        cancelledForPlayback = false
                        active = child
                        activeWork = work
                        try { child.start(); child.await() } finally {
                            // Awaiting cancellation must not allow a later check to overlap cleanup.
                            if (!child.isCompleted) child.cancel()
                            child.join()
                            active = null
                            activeWork = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (current(work) && !busy() && !cancelledForPlayback) completed[work.key] = deferred("Check cancelled")
                    continue
                } catch (_: Exception) {
                    SportsProbeResult(SportsProbeStatus.UNVERIFIED, detail = "Check could not finish")
                }
                if (!current(work) || !permitted(channel) || !current(work)) {
                    if (current(work)) completed[work.key] = deferred("Channel changed; check again")
                    continue
                }
                val observed = result.copy(checkedAtMs = result.checkedAtMs.takeIf { it > 0 } ?: now())
                completed[work.key] = observed
                if (observed.status in CACHEABLE) cache[work.key] = Cached(observed, now())
                seed()
            }
        }
    }

    fun open(eventId: String, channels: List<LiveChannel>, automatic: Boolean) {
        if (closed) return
        val next = channels.distinctBy { it.key }.associateByTo(linkedMapOf()) { it.key }
        val newPage = event != eventId
        val changedChannels = this.channels != next
        val changedAutomatic = this.automatic != automatic
        if (newPage || changedChannels || changedAutomatic) {
            cancelCurrent()
            completed.clear()
            if (newPage) priority = emptyList()
            if (newPage || changedAutomatic) requested = automatic
            event = eventId
            this.channels = next
            this.automatic = automatic
        }
        refresh()
    }

    fun close(eventId: String) {
        if (event != eventId) return
        cancelCurrent()
        event = null
        channels.clear()
        priority = emptyList()
        requested = false
        completed.clear()
        seed()
    }

    fun prioritize(keys: List<String>) {
        if (closed) return
        priority = keys.distinct().filter { it in channels }
        changed.trySend(Unit)
    }

    fun refresh() {
        if (closed) return
        syncEpoch()
        if (busy()) active?.let { cancelledForPlayback = true; it.cancel() }
        if (activeWork?.let { !publishable(it.key.channel) } == true) active?.cancel()
        seed()
        changed.trySend(Unit)
    }

    fun invalidate() {
        if (closed) return
        cancelCurrent()
        cache.clear()
        completed.clear()
        pageEpoch = epoch()
        refresh()
    }

    fun stop() {
        requested = false
        cancelCurrent()
        seed()
    }

    suspend fun stopAndJoin() {
        val previous = active
        stop()
        previous?.join()
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        event = null
        channels.clear()
        completed.clear()
        cache.clear()
        _results.value = emptyMap()
        changed.close()
        worker.cancel()
    }

    private fun cancelCurrent() { generation++; active?.cancel() }

    private fun syncEpoch() {
        val next = epoch()
        if (pageEpoch != next) {
            cancelCurrent()
            pageEpoch = next
            cache.clear()
            completed.clear()
        }
    }

    private fun current(work: Work): Boolean = !closed && requested && event != null &&
        work.generation == generation && work.key.epoch == epoch() &&
        channels[work.key.channel.key] == work.key.channel && publishable(work.key.channel)

    private suspend fun permitted(channel: LiveChannel): Boolean = try {
        canCheck(channel)
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    } catch (_: Exception) { false }

    private fun cached(key: Key): SportsProbeResult? {
        val entry = cache[key] ?: return null
        val ttl = if (entry.value.status == SportsProbeStatus.WORKING) 120_000L else 15_000L
        return entry.value.takeIf { now() - entry.savedAt in 0 until ttl }.also {
            if (it == null) cache.remove(key)
        }
    }

    private fun seed() {
        val waiting = requested && busy()
        val values = linkedMapOf<String, SportsProbeResult>()
        var blocked = false
        for (channel in channels.values) {
            val key = Key(channel, pageEpoch)
            if (!publishable(channel)) {
                completed.remove(key)
                cache.remove(key)
                continue
            }
            val observed = observation(channel)?.let { it.copy(checkedAtMs = it.checkedAtMs.takeIf { t -> t > 0 } ?: now()) }
            if (observed != null && observed.status in CACHEABLE) {
                completed[key] = observed
                cache[key] = Cached(observed, now())
            }
            val value = observed ?: completed[key] ?: cached(key)?.also { completed[key] = it }
                ?: when {
                    !requested -> null
                    waiting -> deferred("Video is playing; check when idle").also { blocked = true }
                    activeWork?.let { current(it) && it.key == key } == true -> SportsProbeResult(SportsProbeStatus.CHECKING)
                    else -> SportsProbeResult(SportsProbeStatus.QUEUED)
                }
            if (value != null) values[channel.key] = value
        }
        _results.value = values
        _blockedByPlayback.value = blocked
    }

    private fun deferred(message: String) = SportsProbeResult(SportsProbeStatus.DEFERRED, detail = message)
    private companion object {
        val CACHEABLE = setOf(SportsProbeStatus.WORKING, SportsProbeStatus.UNAVAILABLE, SportsProbeStatus.UNVERIFIED, SportsProbeStatus.PROTECTED)
    }
}
