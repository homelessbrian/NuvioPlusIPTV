package com.nuvio.tv.core.player

import android.os.SystemClock

/** Request failures are described without including exception text or network addresses. */
internal enum class MovieSourceFailure { HTTP, DNS, CONNECT, TIMEOUT, TLS, INTERRUPTED, IO }

/** Fixed labels for the shareable startup timeline. */
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
    UPSTREAM_OPEN("opening progressive source"),
    UPSTREAM_OPENED("progressive source open returned"),
    UPSTREAM_REDIRECT("progressive source redirected"),
    UPSTREAM_READ("waiting for first progressive read"),
    FIRST_UPSTREAM_BYTES("first progressive upstream bytes"),
    UPSTREAM_EMPTY("progressive source ended before bytes"),
    UPSTREAM_OPEN_ERROR("progressive open failed"),
    UPSTREAM_READ_ERROR("progressive read failed"),
    CACHE_READ("disk cache reported bytes"),
    LOAD_COMPLETED("load completed"),
    LOAD_ERROR("load error"),
    VIDEO_FORMAT("video format received"),
    CODEC_OPEN("configuring video decoder"),
    CODEC_OPENED("video decoder configure/start returned"),
    CODEC_ERROR("video decoder error"),
    DECODER_READY("video decoder initialized"),
    RENDERER_FRAME_ENTER("submitting first frame to video surface"),
    RENDERER_FRAME_SENT("first frame submitted to surface"),
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
        private val details = mutableMapOf<MovieStartupStep, String>()
        private var videoOptions: String? = null
        private var decoderStarts = 0
        private var latestDecoderRetry: String? = null
        private val workers = MovieStartupWorkers(start, now)
        internal val sessionStateTiming = SessionPlaybackStateTiming(start, now)
        private var finishedAt: Long? = null
        private var retired = false
        private var cachedReport: String? = null

        fun <T> duringWorkerOperation(step: MovieStartupStep, work: () -> T): T {
            val pending = synchronized(this) { !retired && finishedAt == null && now() - start <= MAX_STARTUP_MS }
            return if (pending) workers.during(step, work) else work()
        }

        @Synchronized
        fun requestedVideoOptions(removeHdr10Plus: Boolean, tunneling: Boolean) {
            if (retired || finishedAt != null || videoOptions != null) return
            videoOptions = "Requested at startup: remove HDR10+ data ${if (removeHdr10Plus) "on" else "off"}, " +
                "tunneling ${if (tunneling) "on" else "off"}"
        }

        @Synchronized
        fun mark(step: MovieStartupStep, code: Int? = null, failure: MovieSourceFailure? = null) {
            if (!record(step)) return
            val description = when (step) {
                MovieStartupStep.ERROR -> code?.toString()
                MovieStartupStep.LOAD_ERROR -> code?.takeIf { it in 100..599 }?.let { "HTTP $it" }
                MovieStartupStep.UPSTREAM_OPEN_ERROR, MovieStartupStep.UPSTREAM_READ_ERROR ->
                    if (failure == MovieSourceFailure.HTTP && code != null && code in 100..599) "HTTP $code" else failure?.name
                else -> null
            }
            if (description != null) details[step] = description
        }

        @Synchronized
        fun codecEvent(step: MovieStartupStep, detail: StartupVideoCodec? = null, platformError: Int? = null) {
            if (retired || finishedAt != null) return
            val label = detail?.reportLabel().orEmpty()
            val description = if (platformError != null) "$label Android error $platformError".trim() else label
            if (step == MovieStartupStep.CODEC_OPEN) decoderStarts++
            // The next candidate can itself hang. Keep its latest observation even when an earlier
            // decoder has already populated the first-observation timeline.
            if (decoderStarts > 1 && detail != null) {
                latestDecoderRetry = "Decoder attempt #$decoderStarts at +${now() - start}ms: ${step.label} ($description)"
            }
            if (record(step)) details[step] = description
        }

        private fun record(step: MovieStartupStep): Boolean {
            if (retired || MovieStartupStep.STOP in milestones || step in milestones) return false
            val time = now()
            val finished = finishedAt
            if (finished != null && (time - finished > RECENT_END_MS ||
                    (!step.endsStartup() && step != MovieStartupStep.RENDERER_FRAME_SENT))) return false
            milestones[step] = time - start
            cachedReport = null
            if (finished == null && step.endsStartup()) finishedAt = time
            return true
        }

        @Synchronized
        fun retire() { retired = true }

        @Synchronized
        fun isRecentStartup(): Boolean {
            val time = now()
            return !retired && time - start in 0L..MAX_STARTUP_MS &&
                (finishedAt?.let { time - it <= RECENT_END_MS } ?: true)
        }

        internal fun report(time: Long, onlySlowStartup: Boolean): String? {
            if (!shouldReport(time, onlySlowStartup)) return null
            val workerEvidence = workers.sample(time)
            return formatReport(time, onlySlowStartup, workerEvidence)
        }

        @Synchronized
        private fun shouldReport(time: Long, onlySlowStartup: Boolean): Boolean {
            val age = time - start
            val finished = finishedAt
            return !retired && age in 0L..MAX_STARTUP_MS &&
                (finished == null || time - finished <= RECENT_END_MS) &&
                (!onlySlowStartup || (finished == null && age >= SLOW_STARTUP_MS))
        }

        @Synchronized
        private fun formatReport(time: Long, onlySlowStartup: Boolean, workerEvidence: String?): String? {
            if (!shouldReport(time, onlySlowStartup)) return null
            val timeline = cachedReport ?: buildString {
                var first = true
                for ((step, elapsed) in milestones) {
                    if (!first) append(" · ")
                    first = false
                    append('+').append(elapsed).append(' ').append(step.label)
                    details[step]?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
                }
            }.also { cachedReport = it }
            val session = sessionStateTiming.summary(time, includeInFlightAge = !onlySlowStartup)
            return buildString {
                append("Movie startup #").append(number).appendLine(" (first observations, ms from player startup):")
                if (session != null) appendLine(session)
                videoOptions?.let { appendLine(it) }
                latestDecoderRetry?.let { appendLine(it) }
                if (workerEvidence != null) appendLine(workerEvidence)
                append(timeline)
            }
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
