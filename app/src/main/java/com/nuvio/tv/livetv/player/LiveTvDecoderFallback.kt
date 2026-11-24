package com.nuvio.tv.livetv.player

/** Keep format failures sticky, with at most one recovery attempt after a natural background stop. */
internal class LiveTvDecoderFallback {
    private var channel: String? = null
    private val failures = LinkedHashMap<String?, Boolean>()
    private var retryUsed = false

    @Synchronized fun reset() {
        channel = null
        failures.clear()
        retryUsed = false
    }

    @Synchronized fun usesSoftware(key: String?, mime: String?): Boolean =
        channel == key && failures.isNotEmpty() &&
            (failures.containsKey(null) || mime == null || failures.containsKey(mimeKey(mime)))

    @Synchronized fun fallBack(key: String, mime: String?, transientFailure: Boolean): Boolean {
        if (channel != key) reset()
        channel = key
        val format = mimeKey(mime)
        if (failures.containsKey(null) || (format != null && failures.containsKey(format))) {
            if (!transientFailure) {
                if (format == null) failures.clear()
                failures[if (failures.containsKey(null)) null else format] = false
            }
            return false
        }
        if (format == null) {
            val canRecover = transientFailure && !retryUsed && failures.values.all { it }
            failures.clear()
            failures[null] = canRecover
            return true
        }
        if (failures.size >= 16) {
            // An unusual source that keeps changing formats must not create unbounded retry state.
            failures.clear()
            failures[null] = false
        } else {
            failures[format] = transientFailure && !retryUsed
        }
        return true
    }

    /** Called only once playback was already stopped in the background, never during working video. */
    @Synchronized fun retryAfterBackgroundStop(key: String?, stoppedMime: String?): Boolean {
        if (key == null || key != channel || retryUsed) return false
        val format = if (failures.containsKey(null)) null else (mimeKey(stoppedMime) ?: return false)
        if (failures[format] != true) return false
        failures.remove(format)
        retryUsed = true
        return true
    }

    private fun mimeKey(mime: String?): String? = mime?.takeIf { it.length <= 128 }
}
