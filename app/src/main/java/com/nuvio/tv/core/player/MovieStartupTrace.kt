package com.nuvio.tv.core.player

import android.os.SystemClock

/** Only these labels (and numeric error codes) can reach the shareable report. */
internal enum class MovieStartupStep(val label: String) {
    STARTED("start"),
    SOURCE_PROBE("checking source"),
    SUBTITLES_AND_DISPLAY("subtitles/display setup"),
    ENGINE_BUILD("building player"),
    SESSION_ENTER("opening Android media session"),
    SESSION_RETURNED("media session call returned"),
    SESSION_FAILED("media session call failed"),
    PREPARE_ENTER("calling prepare"),
    PREPARE_RETURNED("prepare returned"),
    BUFFERING("buffering"),
    READY("player ready"),
    LOAD_STARTED("load started"),
    FIRST_UPSTREAM_BYTES("first progressive upstream bytes"),
    LOAD_COMPLETED("load completed"),
    LOAD_ERROR("load error"),
    VIDEO_FORMAT("video format received"),
    DECODER_READY("video decoder initialized"),
    FIRST_FRAME("first video frame"),
    TUNNELED_READY("tunneled playback ready (frame assumed)"),
    ERROR("player error"),
    STOP("stopping player")
}

internal object MovieStartupTrace {
    private val process = Recorder(SystemClock::uptimeMillis)

    fun begin(): Attempt = process.begin()
    fun snapshot(): String? = process.snapshot()
    fun slowSnapshot(): String? = process.snapshot(onlySlowStartup = true)

    internal class Recorder(private val now: () -> Long) {
        @Volatile private var current: Attempt? = null
        private var sequence = 0L

        @Synchronized
        fun begin(): Attempt = Attempt(++sequence, now(), now).also { current = it }

        fun snapshot(onlySlowStartup: Boolean = false): String? =
            current?.report(now(), onlySlowStartup)
    }

    /** Listeners retain their own attempt, even if another player replaces it. */
    internal class Attempt(private val number: Long, private val start: Long, private val now: () -> Long) {
        private val milestones = LinkedHashMap<MovieStartupStep, Long>().apply {
            put(MovieStartupStep.STARTED, 0L)
        }
        private var errorCode: Int? = null
        private var httpStatus: Int? = null
        private var finishedAt: Long? = null
        private var retired = false
        private var cachedReport: String? = null

        @Synchronized
        fun mark(step: MovieStartupStep, code: Int? = null) {
            if (retired || MovieStartupStep.STOP in milestones || step in milestones) return
            val time = now()
            val finished = finishedAt
            if (finished != null && (time - finished > RECENT_END_MS || !step.endsStartup())) return
            milestones[step] = time - start
            cachedReport = null
            if (step == MovieStartupStep.ERROR) errorCode = code
            if (step == MovieStartupStep.LOAD_ERROR) httpStatus = code?.takeIf { it in 100..599 }
            if (finished == null && step.endsStartup()) finishedAt = time
        }

        @Synchronized
        fun retire() { retired = true }

        @Synchronized
        internal fun report(time: Long, onlySlowStartup: Boolean): String? {
            val age = time - start
            val finished = finishedAt
            if (retired || age !in 0L..MAX_STARTUP_MS) return null
            if (finished != null && time - finished > RECENT_END_MS) return null
            if (onlySlowStartup && (finished != null || age < SLOW_STARTUP_MS)) return null
            return cachedReport ?: buildString {
                append("Movie startup #").append(number).append(" (first observations, ms from player startup):\n")
                var first = true
                for ((step, elapsed) in milestones) {
                    if (!first) append(" · ")
                    first = false
                    append('+').append(elapsed).append(' ').append(step.label)
                    if (step == MovieStartupStep.ERROR) errorCode?.let { append(" (").append(it).append(')') }
                    if (step == MovieStartupStep.LOAD_ERROR) httpStatus?.let { append(" (HTTP ").append(it).append(')') }
                }
            }.also { cachedReport = it }
        }

        private fun MovieStartupStep.endsStartup(): Boolean = when (this) {
            MovieStartupStep.FIRST_FRAME, MovieStartupStep.TUNNELED_READY,
            MovieStartupStep.ERROR, MovieStartupStep.STOP -> true
            else -> false
        }
    }

    const val SLOW_STARTUP_MS = 15_000L
    private const val RECENT_END_MS = 30_000L
    private const val MAX_STARTUP_MS = 5 * 60_000L
}
