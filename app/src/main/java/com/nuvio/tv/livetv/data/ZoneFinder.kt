package com.nuvio.tv.livetv.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Works out the time zone a provider's server keeps its catch-up on. Catch-up links name the
 * start time in the server's own clock with no zone attached, so a replay is hours off unless
 * the app writes it in that clock.
 *
 * Each answer comes with a few words on where it came from, for the Start-Up Report.
 */
object ZoneFinder {

    /**
     * From the "server_info" part of an Xtream account reply: the time zone it names, else the
     * gap between its clock ("time_now", server time) and the real time ("timestamp_now").
     */
    fun fromServerInfo(info: JSONObject?): Pair<String, String>? {
        if (info == null) return null
        val name = info.optString("timezone").trim()
        if (name.isNotEmpty() && isKnownZone(name)) return name to "reported by the server"
        val clock = info.optString("time_now").trim()
        val epoch = info.optString("timestamp_now").trim().toLongOrNull()
        if (clock.isNotEmpty() && epoch != null && epoch > 0) {
            val asUtc = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(clock)?.time
            }.getOrNull()
            if (asUtc != null) {
                gapToZone(asUtc - epoch * 1000)?.let { return it to "worked out from the server's clock" }
            }
        }
        return null
    }

    /** From the start of an XMLTV guide: the offset written with its first show time ("20261108150000 +0100"). */
    fun fromGuide(text: String): Pair<String, String>? {
        val m = GUIDE_TIME.find(text) ?: return null
        val off = m.groupValues[2]
        val sign = if (off[0] == '-') -1 else 1
        val h = off.substring(1, 3).toIntOrNull() ?: return null
        val mm = off.substring(3, 5).toIntOrNull() ?: return null
        val ms = sign * (h * 3_600_000L + mm * 60_000L)
        return gapToZone(ms)?.let { it to "from the provider's own guide" }
    }

    /** True for a zone this app worked out (a fixed offset), not one the server named. */
    fun isWorkedOut(zone: String): Boolean = WORKED_OUT.matches(zone)

    /** A gap from UTC, rounded to the nearest quarter hour, as a fixed zone ("GMT+01:00"); null if implausible. */
    internal fun gapToZone(gapMs: Long): String? {
        val quarter = 15 * 60_000L
        val rounded = Math.round(gapMs.toDouble() / quarter) * quarter
        if (kotlin.math.abs(rounded) > 14 * 3_600_000L) return null
        val sign = if (rounded < 0) "-" else "+"
        val mins = kotlin.math.abs(rounded) / 60_000L
        return "GMT%s%02d:%02d".format(Locale.US, sign, mins / 60, mins % 60)
    }

    /** A zone name Java knows (an unknown name would quietly become UTC). */
    private fun isKnownZone(name: String): Boolean {
        if (name.equals("UTC", true) || name.equals("GMT", true)) return true
        return TimeZone.getTimeZone(name).id != "GMT"
    }

    private val GUIDE_TIME = Regex("""start\s*=\s*["'](\d{12,14})\s*([+-]\d{4})["']""")
    private val WORKED_OUT = Regex("""^GMT[+-]\d{2}:\d{2}$""")
}
