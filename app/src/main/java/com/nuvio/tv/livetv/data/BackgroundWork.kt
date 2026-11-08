package com.nuvio.tv.livetv.data

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * Keeps Live TV and On Demand's heavy background jobs (playlist, guide and catalog downloads,
 * reading and saving them) out of the way while Nuvio's own player is on screen. On slower TV
 * boxes they competed with the video for the processor, storage and network, and movies and
 * episodes stuttered. Jobs already under way pause at their next step and carry on once the
 * player closes. Updates you start yourself (Update now) aren't held back.
 */
object BackgroundWork {
    /** Nuvio's player is open (set from the app's navigation). */
    val nuvioPlaying = MutableStateFlow(false)

    /**
     * Two low-priority threads for work nobody is waiting on: automatic playlist and guide
     * updates, and finding channels for Sports games when the Sports page isn't open. Android
     * gives the screen and the video first claim on the processor over these, so on a slower box
     * scrolling and playback stay smooth while they run (they just take a little longer).
     */
    val quiet: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val count = java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread({
                // Just short of Android's "background" class (10): that class is held to a small
                // slice of the processor whenever anything else is busy, which stretched a guide
                // update from a minute or two to ten on slower boxes. 9 still yields to the screen.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND - 1)
                r.run()
            }, "nuvio-quiet-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** Marks work started by an automatic update (see [quietUpdate] and [cpu]). */
    private class QuietMark : kotlin.coroutines.AbstractCoroutineContextElement(QuietMark) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<QuietMark>
    }

    /** Where an automatic update runs: the [quiet] threads, and everything it calls knows it (see [cpu]). */
    val quietUpdate: kotlin.coroutines.CoroutineContext get() = quiet + QuietMark()

    /**
     * Where the heavy reading in an update (playlists into channels, guide files into the guide)
     * runs: on the [quiet] threads when an automatic update asked for it, otherwise the usual ones.
     * These steps used to switch to the usual threads themselves, so an automatic update ran at
     * full priority after all, right alongside the video.
     */
    suspend fun cpu(): kotlinx.coroutines.CoroutineDispatcher =
        if (kotlinx.coroutines.currentCoroutineContext()[QuietMark] != null) quiet else kotlinx.coroutines.Dispatchers.IO

    /**
     * Reading listings out of the guide database for the channels (all of them, once the guide
     * opens; more as you scroll ahead). Big reads and lots of memory churn: at normal priority
     * they competed with the screen and made the guide stutter while they ran, so they get
     * their own low-priority threads, which the screen takes precedence over.
     */
    val reads: kotlinx.coroutines.CoroutineDispatcher by lazy {
        val count = java.util.concurrent.atomic.AtomicInteger()
        java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "nuvio-reads-${count.incrementAndGet()}").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * Only for people with "Start in Full Screen" on: true from the moment the app opens until
     * the last channel is playing full screen (at most 20 s), and then for as long as Live TV
     * stays full screen. Set from the app's navigation (LiveTvLoadingHost). The start-up work
     * used to run right while the channel was starting, which made it choppy on slower boxes.
     */
    val liveHold = MutableStateFlow(false)

    /** The 20 s hold at app start has been used up (it only happens once per app launch). */
    @Volatile var launchHoldDone = false

    /** Waits while Nuvio's player is open, or while the start-in-full-screen hold is on. */
    suspend fun awaitIdle() {
        if (nuvioPlaying.value || liveHold.value) {
            kotlinx.coroutines.flow.combine(nuvioPlaying, liveHold) { a, b -> !a && !b }.first { it }
        }
    }

    /** Waits only for the start-in-full-screen hold (Nuvio's player doesn't hold this work back). */
    suspend fun awaitLiveHold() {
        if (liveHold.value) liveHold.first { !it }
    }

    /**
     * Heavy jobs running now (building the guide, finding channels for Sports games). An automatic
     * On Demand import waits for them to finish instead of competing with them for the processor:
     * all at once, they slowed each other (and the video) right down.
     */
    val heavyJobs = MutableStateFlow(0)

    /** The guide is being built from the guide files right now. */
    val guideBuilding = MutableStateFlow(false)

    /** One heavy job at a time: the next one waits for the one under way, instead of both running together. */
    private val oneAtATime = kotlinx.coroutines.sync.Mutex()

    suspend fun <T> heavy(block: suspend () -> T): T {
        heavyJobs.update { it + 1 }
        try {
            // At start-up the guide and Sports' channel search both started within moments of
            // each other (often with the scores update too), and together they kept a slower box
            // busy for minutes. One after the other they take the same work, but never pile up.
            return oneAtATime.withLock { block() }
        } finally { heavyJobs.update { (it - 1).coerceAtLeast(0) } }
    }

    /** Waits (up to 10 minutes) until no heavy job is running. */
    suspend fun awaitNoHeavy() {
        if (heavyJobs.value == 0) return
        kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { heavyJobs.first { it == 0 } }
    }
}
