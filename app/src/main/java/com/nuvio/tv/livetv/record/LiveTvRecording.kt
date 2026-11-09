package com.nuvio.tv.livetv.record

import org.json.JSONObject

/** Where a recording is up to. */
enum class RecordingStatus { RECORDING, DONE, FAILED }

/**
 * One recording: what it is (show, channel, times) and how it went. Saved as `info.json` in the
 * recording's own folder, next to its pieces and playlist, so a folder is complete on its own.
 */
data class LiveTvRecording(
    val id: String,
    val channelKey: String,
    val channelName: String,
    val channelLogo: String?,
    val title: String,
    val episode: String? = null,
    val description: String? = null,
    /** The show's times in the guide (0 when recorded with no guide listing). */
    val programStartMs: Long = 0L,
    val programStopMs: Long = 0L,
    /** When recording began, and when it stops (or stopped). */
    val startedAtMs: Long,
    val stopAtMs: Long,
    val endedAtMs: Long? = null,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    /** Length recorded so far, and its size on disk. */
    val durationSec: Double = 0.0,
    val sizeBytes: Long = 0L,
    /** Why it failed or stopped early, in plain words. */
    val problem: String? = null,
    /** The folder it's in (absolute path). */
    val dir: String,
    /** Recorded by a series rule (its id): "keep the last N" applies. */
    val seriesId: String? = null,
    /** Downloaded from the provider's catch-up (rather than recorded as it aired). */
    val fromCatchup: Boolean = false,
    /** Catch-up: how long the whole download should be (show plus extra time), in seconds; 0 = not known. */
    val expectedSec: Double = 0.0
) {
    val isRecording: Boolean get() = status == RecordingStatus.RECORDING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("channelKey", channelKey); put("channelName", channelName)
        put("channelLogo", channelLogo ?: ""); put("title", title)
        put("episode", episode ?: ""); put("description", description ?: "")
        put("programStartMs", programStartMs); put("programStopMs", programStopMs)
        put("startedAtMs", startedAtMs); put("stopAtMs", stopAtMs)
        put("endedAtMs", endedAtMs ?: 0L); put("status", status.name)
        put("durationSec", durationSec); put("sizeBytes", sizeBytes)
        put("problem", problem ?: "")
        put("seriesId", seriesId ?: "")
        put("fromCatchup", fromCatchup)
        put("expectedSec", expectedSec)
    }

    companion object {
        fun fromJson(o: JSONObject, dir: String): LiveTvRecording? = runCatching {
            LiveTvRecording(
                id = o.getString("id"),
                channelKey = o.optString("channelKey"),
                channelName = o.optString("channelName"),
                channelLogo = o.optString("channelLogo").ifBlank { null },
                title = o.optString("title").ifBlank { "Recording" },
                episode = o.optString("episode").ifBlank { null },
                description = o.optString("description").ifBlank { null },
                programStartMs = o.optLong("programStartMs"),
                programStopMs = o.optLong("programStopMs"),
                startedAtMs = o.optLong("startedAtMs"),
                stopAtMs = o.optLong("stopAtMs"),
                endedAtMs = o.optLong("endedAtMs").takeIf { it > 0 },
                status = runCatching { RecordingStatus.valueOf(o.optString("status")) }.getOrDefault(RecordingStatus.DONE),
                durationSec = o.optDouble("durationSec", 0.0),
                sizeBytes = o.optLong("sizeBytes"),
                problem = o.optString("problem").ifBlank { null },
                dir = dir,
                seriesId = o.optString("seriesId").ifBlank { null },
                fromCatchup = o.optBoolean("fromCatchup", false),
                expectedSec = o.optDouble("expectedSec", 0.0).takeIf { !it.isNaN() } ?: 0.0
            )
        }.getOrNull()
    }
}
