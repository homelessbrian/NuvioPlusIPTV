package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.CatchupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds archive (catch-up) URLs for the common provider formats:
 * default / append (template in catchup-source), shift, flussonic and xtream codes.
 */
object CatchupUrlBuilder {

    fun isAvailable(catchup: CatchupInfo?, programStartMs: Long, nowMs: Long): Boolean {
        if (catchup == null) return false
        if (programStartMs >= nowMs) return false
        val maxAgeMs = catchup.days.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        return nowMs - programStartMs <= maxAgeMs
    }

    fun build(
        liveUrl: String,
        catchup: CatchupInfo,
        startMs: Long,
        stopMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        /** Xtream: ask for HLS (.m3u8) instead of TS. */
        preferHls: Boolean = false,
        /** Xtream: the server's time zone (catch-up times are in server time). */
        serverTimezone: String? = null
    ): String? {
        // "catchup-correction": the playlist's own time fix for this channel, in hours.
        val correctionMs = (catchup.correctionHours * 3_600_000).toLong()
        val startMsC = startMs + correctionMs
        val start = startMsC / 1000
        val end = (stopMs + correctionMs) / 1000
        val now = nowMs / 1000
        val duration = (end - start).coerceAtLeast(60)
        // Xtream-style links (server/user/pass/id) whose playlist doesn't say which kind of
        // catch-up: they use Xtream's /timeshift/ links, like TiviMate assumes.
        val xtreamGuess = { xtream(liveUrl, startMsC, duration, preferHls, serverTimezone) }
        return when (catchup.type) {
            "append" -> catchup.source?.let { liveUrl + fill(it, start, end, now, duration) }
            "default" -> {
                val src = catchup.source
                when {
                    src.isNullOrBlank() -> xtreamGuess() ?: shift(liveUrl, start, now)
                    src.startsWith("http", ignoreCase = true) -> fill(src, start, end, now, duration)
                    else -> liveUrl + fill(src, start, end, now, duration)
                }
            }
            "shift", "timeshift" -> if (catchup.source.isNullOrBlank()) xtreamGuess() ?: shift(liveUrl, start, now) else shift(liveUrl, start, now)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, start, duration)
            "xc", "xtream" -> xtream(liveUrl, startMsC, duration, preferHls, serverTimezone)
            else -> catchup.source?.let { fill(it, start, end, now, duration) }
        }
    }

    private fun shift(url: String, start: Long, now: Long): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}utc=$start&lutc=$now"
    }

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val m = Regex("""^(https?://[^/]+)/(.*)/([^/?]*)(\?.*)?$""").find(url) ?: return null
        val host = m.groupValues[1]
        val stream = m.groupValues[2]
        val file = m.groupValues[3]
        val query = m.groupValues[4]
        return if (file.endsWith(".m3u8") || file.isEmpty()) {
            val base = file.removeSuffix(".m3u8").ifEmpty { "index" }
            "$host/$stream/$base-$start-$duration.m3u8$query"
        } else {
            "$host/$stream/timeshift_abs-$start.ts$query"
        }
    }

    private fun xtream(url: String, startMs: Long, duration: Long, preferHls: Boolean, serverTimezone: String?): String? {
        // http://host:port/(live/)?user/pass/id(.ext)
        val m = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.[a-z0-9]+)?(\?.*)?$""", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        val query = m.groupValues[6]
        val (host, user, pass, id) = m.destructured
        // HLS (.m3u8) replays come with a length, so the seek bar and skipping work properly.
        val ext = if (preferHls) ".m3u8" else m.groupValues[5].ifEmpty { ".ts" }
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        serverTimezone?.takeIf { it.isNotBlank() }?.let { fmt.timeZone = java.util.TimeZone.getTimeZone(it) }
        val minutes = (duration / 60).coerceAtLeast(1)
        return "$host/timeshift/$user/$pass/$minutes/${fmt.format(Date(startMs))}/$id$ext$query"
    }

    /**
     * Other ways to ask for the same catch-up, tried in turn if [url] fails, because Xtream
     * panels differ: TS instead of HLS, the older timeshift.php form, the link in this device's
     * time instead of the server's (or the other way round), and with/without "/live".
     */
    fun alternatives(
        url: String,
        serverTimezone: String?,
        /** The show's own length in minutes: some panels refuse a window longer than that. */
        showMinutes: Long? = null,
        /**
         * A show still on air (watch from the beginning): how many minutes of it have aired.
         * Some panels refuse a window that reaches into the future.
         */
        airedMinutes: Long? = null
    ): List<String> {
        val path = url.substringBefore('?')
        val query = url.substring(path.length)
        val m = Regex("""^(https?://[^/]+)/timeshift/([^/]+)/([^/]+)/(\d+)/([0-9-]+:[0-9-]+)/(\d+)(\.[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)
            .find(path) ?: return listOfNotNull(tsFallback(url))
        val (host, user, pass, minutes, stamp, id) = m.destructured
        val ext = m.groupValues[7]
        val out = LinkedHashSet<String>()
        if (ext.equals(".m3u8", true)) out += "$host/timeshift/$user/$pass/$minutes/$stamp/$id.ts$query"
        // TS answered with something that isn't video: many panels only replay as HLS.
        else out += "$host/timeshift/$user/$pass/$minutes/$stamp/$id.m3u8$query"
        // A show still on air: only the part that has aired so far (in both forms). Tried before
        // the other forms below, so a refusal doesn't end with the "other clock" link, which
        // plays an earlier show when the server's time zone was right all along.
        val aired = airedMinutes?.coerceAtLeast(1)?.takeIf { it < (minutes.toLongOrNull() ?: 0) }
        if (aired != null) {
            out += "$host/timeshift/$user/$pass/$aired/$stamp/$id${ext.ifEmpty { ".ts" }}$query"
            out += "$host/timeshift/$user/$pass/$aired/$stamp/$id${if (ext.equals(".m3u8", true)) ".ts" else ".m3u8"}$query"
        }
        // Just the show, without the extra time after it (in both forms).
        val short = showMinutes?.coerceAtLeast(1)?.takeIf { it < (minutes.toLongOrNull() ?: 0) }
        if (short != null) {
            out += "$host/timeshift/$user/$pass/$short/$stamp/$id${ext.ifEmpty { ".ts" }}$query"
            out += "$host/timeshift/$user/$pass/$short/$stamp/$id${if (ext.equals(".m3u8", true)) ".ts" else ".m3u8"}$query"
        }
        // timeshift.php form (some panels only answer this).
        out += "$host/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$stamp&duration=$minutes"
        // Same request with the time written in the other clock (server vs device). Only a guess
        // for when the server's time zone isn't known: once it is known the first link already
        // has the right time, and this one could only land on a show from hours earlier or later.
        val serverZone = serverTimezone?.takeIf { it.isNotBlank() }?.let { java.util.TimeZone.getTimeZone(it) }
        if (serverZone == null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            fmt.timeZone = java.util.TimeZone.getDefault()
            val startMs = runCatching { fmt.parse(stamp)?.time }.getOrNull()
            if (startMs != null) {
                val other = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(Date(startMs))
                if (other != stamp) out += "$host/timeshift/$user/$pass/$minutes/$other/$id${ext.ifEmpty { ".ts" }}$query"
            }
        }
        out.remove(url)
        return out.toList()
    }

    /** The start time written in a catch-up link (Xtream timeshift or timeshift.php), or null for other kinds. */
    fun askedStamp(url: String): String? =
        Regex("""\d{4}-\d{2}-\d{2}:\d{2}-\d{2}""").find(url)?.value

    /**
     * Xtream replay links name a whole minute (9:10, never 9:10:45): the replay really starts
     * at the start of that minute.
     */
    fun wholeMinutes(url: String): Boolean {
        val path = url.substringBefore('?')
        return TIMESHIFT_PATH.containsMatchIn(path) || path.endsWith("timeshift.php", ignoreCase = true)
    }

    /**
     * Which kind of link [url] is, next to the first link tried ([primary]): "php" (the
     * timeshift.php form), or "hls" / "ts", plus "+clock" when the time is written in the other
     * clock and "+short" when it asks for a shorter window. Used to remember, per provider, the
     * kind that worked, so the next replay (or skip) goes straight to it.
     */
    fun linkForm(url: String, primary: String): String {
        val path = url.substringBefore('?')
        if (path.endsWith("timeshift.php", ignoreCase = true)) return "php"
        val ext = if (path.endsWith(".m3u8", ignoreCase = true)) "hls" else "ts"
        val m = TIMESHIFT_PATH.find(path)
        val pm = TIMESHIFT_PATH.find(primary.substringBefore('?'))
        val clock = if (m != null && pm != null && m.groupValues[2] != pm.groupValues[2]) "+clock" else ""
        val short = if (m != null && pm != null &&
            (m.groupValues[1].toLongOrNull() ?: 0) < (pm.groupValues[1].toLongOrNull() ?: 0)) "+short" else ""
        return ext + clock + short
    }

    /**
     * [primary] and its [alternatives] in the order to try them: the kind of link that worked
     * last time on this provider ([form], from [linkForm]) first.
     */
    fun preferForm(primary: String, alternatives: List<String>, form: String?): List<String> {
        val all = listOf(primary) + alternatives
        if (form == null) return all
        // HLS switched off for this provider (it failed there): don't go back to it.
        if (form.startsWith("hls") && !primary.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) return all
        val pick = all.firstOrNull { linkForm(it, primary) == form }
            ?: all.firstOrNull { linkForm(it, primary) == form.replace("+short", "") }
            ?: return all
        return listOf(pick) + all.filter { it != pick }
    }

    private val TIMESHIFT_PATH = Regex("""/timeshift/[^/]+/[^/]+/(\d+)/([0-9-]+:[0-9-]+)/\d+""", RegexOption.IGNORE_CASE)

    /** The TS version of an Xtream HLS catch-up link, for panels that don't offer HLS. */
    fun tsFallback(url: String): String? {
        val path = url.substringBefore('?')
        if (!path.contains("/timeshift/") || !path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(".m3u8".length) + ".ts" + url.substring(path.length)
    }

    internal fun fill(template: String, start: Long, end: Long, now: Long, duration: Long): String {
        var out = template
        // {utc:YmdHMS} / ${start:Y-m-d} style formatted dates. (Every "}" escaped: Android's regex
        // engine rejects a bare one, which closed the app, though desktop Java accepts it.)
        out = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp):([^}]+)\}""").replace(out) { m ->
            val epoch = when (m.groupValues[1]) {
                "utcend", "end" -> end
                "lutc", "now", "timestamp" -> now
                else -> start
            }
            formatPattern(m.groupValues[2], epoch)
        }
        out = Regex("""\$?\{duration:(\d+)\}""").replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            (duration / divisor).toString()
        }
        out = Regex("""\$?\{offset:(\d+)\}""").replace(out) { m ->
            val divisor = m.groupValues[1].toLongOrNull()?.coerceAtLeast(1) ?: 1
            ((now - start) / divisor).toString()
        }
        val simple = mapOf(
            "utc" to start, "start" to start, "timestamp" to now,
            "utcend" to end, "end" to end,
            "lutc" to now, "now" to now,
            "duration" to duration, "offset" to (now - start)
        )
        simple.forEach { (k, v) ->
            out = out.replace("\${$k}", v.toString()).replace("{$k}", v.toString())
        }
        // Bare {Y}{m}{d}{H}{M}{S} refer to the program start in local time.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start * 1000 }
        out = out
            .replace("{Y}", "%04d".format(cal.get(java.util.Calendar.YEAR)))
            .replace("{m}", "%02d".format(cal.get(java.util.Calendar.MONTH) + 1))
            .replace("{d}", "%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH)))
            .replace("{H}", "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY)))
            .replace("{M}", "%02d".format(cal.get(java.util.Calendar.MINUTE)))
            .replace("{S}", "%02d".format(cal.get(java.util.Calendar.SECOND)))
        return out
    }

    private fun formatPattern(pattern: String, epochSec: Long): String {
        val javaPattern = buildString {
            pattern.forEach { c ->
                append(
                    when (c) {
                        'Y' -> "yyyy"; 'm' -> "MM"; 'd' -> "dd"
                        'H' -> "HH"; 'M' -> "mm"; 'S' -> "ss"
                        else -> if (c.isLetter()) "'$c'" else c.toString()
                    }
                )
            }
        }
        return runCatching {
            SimpleDateFormat(javaPattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochSec * 1000))
        }.getOrDefault(epochSec.toString())
    }
}
