package com.nuvio.tv.livetv.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.File
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Shows crashes on screen instead of just closing the app, so they can be reported without a
 * computer. Installed by [CrashCatcherInitProvider] before anything else in the app starts.
 *
 * Three kinds of problems are reported:
 *  - app crashes (caught here, shown straight away),
 *  - the app freezing (noticed by a watchdog, shown next time the app opens),
 *  - the app being closed by Android for any other reason, such as running out of memory or a
 *    crash in native code (read from Android's own record on Android 11 and newer, shown next
 *    time the app opens).
 */
object CrashCatcher {
    private const val TAG = "LiveTvCrashCatcher"
    private const val FILE_NAME = "last_crash.txt"
    private const val FREEZE_FILE = "pending_freeze.txt"
    private const val BUSY_FILE = "busy_report.txt"
    private const val PREFS = "livetv_crash_catcher"
    private const val KEY_EXIT_SEEN = "exit_seen_at"
    private const val KEY_SELF_KILL = "self_kill_at"
    const val EXTRA_REPORT = "report"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var pendingReport: String? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        // Reports from last time (freeze, or Android closing the app) are put together off the
        // main thread and saved. Nothing pops up: the report waits in Settings › Live TV ›
        // Help › Crash report, for when someone is asked to send it.
        Thread({
            runCatching {
                val parts = mutableListOf<String>()
                val frozen = File(app.filesDir, FREEZE_FILE)
                if (frozen.exists()) {
                    // A crash saved after the freeze began is what actually closed the app: keep
                    // it on top. (This used to be overwritten by the freeze note, which hid the
                    // real error.)
                    val crash = File(app.filesDir, FILE_NAME)
                    if (crash.exists() && crash.lastModified() >= frozen.lastModified() - 2_000) {
                        runCatching { crash.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                            parts += it.trimEnd()
                            parts += "BEFORE THAT CRASH:"
                        }
                    }
                    parts += frozen.readText()
                    frozen.delete()
                }
                exitReport(app)?.let { parts += it }
                if (parts.isNotEmpty()) {
                    val report = parts.joinToString("\n\n")
                    File(app.filesDir, FILE_NAME).writeText(report)
                    Log.e(TAG, report)
                }
            }
        }, "CrashCatcherStart").apply { isDaemon = true }.start()

        startFreezeWatchdog(app)
        startBusyWatch(app)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildReport(thread, error)
                val file = File(app.filesDir, FILE_NAME)
                // Saved for Settings › Live TV › Help › Crash report (no screen pops up).
                runCatching { file.writeText(report) }
                Log.e(TAG, report)
                runCatching {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_SELF_KILL, System.currentTimeMillis()).commit()
                }
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } catch (t: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** Keeps track of the app's screen that is showing, so a report can be opened over it. */
    private object Tracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is CrashReportActivity) return
            resumed = WeakReference(activity)
            showPending()
        }
        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Opens a waiting report, 2 seconds after the app's screen is up (main thread only). */
    private fun showPending() {
        if (pendingReport == null || resumed?.get() == null) return
        main.postDelayed({
            val activity = resumed?.get() ?: return@postDelayed
            val report = pendingReport ?: return@postDelayed
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            pendingReport = null
            runCatching {
                activity.startActivity(Intent(activity, CrashReportActivity::class.java).putExtra(EXTRA_REPORT, report))
            }
        }, 2_000)
    }

    /** Opens the last saved crash or freeze report (developer tools in Live TV settings). */
    fun openLastReport(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, CrashReportActivity::class.java)
                    .putExtra(EXTRA_REPORT, lastReport(context) ?: "No crash or freeze has been saved yet.")
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        }
    }

    fun lastReport(context: Context): String? {
        val crash = runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()
        val busy = runCatching { File(context.filesDir, BUSY_FILE).takeIf { it.exists() }?.readText() }.getOrNull()
        return listOfNotNull(crash?.trimEnd()?.ifBlank { null }, busy?.trimEnd()?.ifBlank { null })
            .joinToString("\n\n────────\n\n").ifBlank { null }
    }

    // ------------------------------------------------------------------ busy processor

    /**
     * Notices when the app keeps the processor busy while it's on screen (more than 60% of one
     * core over half a minute) and writes down which of its threads use it and what they run.
     * Checking costs almost nothing: two small reads every 30 seconds. At most 3 reports per
     * app start, 5 minutes apart; the newest is shown under Crash report.
     */
    private fun startBusyWatch(app: Context) {
        Thread({
            val tick = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)
            var lastCpu = processTicks()
            var lastAt = SystemClock.elapsedRealtime()
            var reports = 0
            while (reports < 3) {
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@Thread }
                val cpu = processTicks()
                val at = SystemClock.elapsedRealtime()
                val pct = if (cpu >= 0 && lastCpu >= 0) (cpu - lastCpu) * 100_000.0 / tick / (at - lastAt).coerceAtLeast(1L) else 0.0
                lastCpu = cpu; lastAt = at
                if (pct < 60.0 || !onScreen()) continue
                val report = runCatching { busyReport(app, pct, tick) }.getOrNull() ?: continue
                runCatching { File(app.filesDir, BUSY_FILE).writeText(report) }
                Log.w(TAG, report)
                reports++
                try { Thread.sleep(5 * 60_000L) } catch (_: InterruptedException) { return@Thread }
                lastCpu = processTicks(); lastAt = SystemClock.elapsedRealtime()
            }
        }, "BusyWatch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun onScreen(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /** The processor time a /proc stat line has used (user + system), in clock ticks; -1 if unreadable. */
    private fun ticksOf(stat: String): Long {
        val rest = stat.substringAfterLast(") ").trim().split(' ')
        // After the name: state is field 3, utime 14 and stime 15 (so 11 and 12 here).
        return (rest.getOrNull(11)?.toLongOrNull() ?: return -1L) + (rest.getOrNull(12)?.toLongOrNull() ?: return -1L)
    }

    private fun processTicks(): Long = runCatching { ticksOf(File("/proc/self/stat").readText()) }.getOrDefault(-1L)

    /** Thread id -> (its name as Android keeps it, up to 15 letters; processor ticks). */
    private fun threadTicks(): Map<Int, Pair<String, Long>> {
        val out = HashMap<Int, Pair<String, Long>>()
        File("/proc/self/task").listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            runCatching {
                val stat = File(dir, "stat").readText()
                val name = stat.substringAfter('(').substringBeforeLast(')')
                out[tid] = name to ticksOf(stat)
            }
        }
        return out
    }

    private fun busyReport(app: Context, pct: Double, tick: Long): String {
        // Five seconds of looking: each thread's processor time, and what the app's threads run
        // (sampled 20 times).
        val before = threadTicks()
        val started = SystemClock.elapsedRealtime()
        val ownLines = HashMap<String, HashMap<String, Int>>()
        val mainThread = Looper.getMainLooper().thread
        repeat(20) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { return@repeat }
            runCatching {
                Thread.getAllStackTraces().forEach { (t, stack) ->
                    if (t.state != Thread.State.RUNNABLE) return@forEach
                    val key = if (t === mainThread) "main" else t.name.take(15)
                    val lines = ownLines.getOrPut(key) { HashMap() }
                    stack.map { it.toString() }
                        .filter { it.startsWith("com.nuvio.tv.") && !it.startsWith("com.nuvio.tv.livetv.crash.") }
                        .distinct().take(6)
                        .forEach { lines[it] = (lines[it] ?: 0) + 1 }
                }
            }
        }
        val after = threadTicks()
        val ms = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
        val pid = Process.myPid()
        val busiest = after.mapNotNull { (tid, a) ->
            val b = before[tid] ?: return@mapNotNull null
            val used = (a.second - b.second) * 100_000.0 / tick / ms
            Triple(if (tid == pid) "main" else a.first, used, tid)
        }.filter { it.second >= 3.0 }.sortedByDescending { it.second }.take(8)
        return buildString {
            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(situation(app))
            appendLine()
            appendLine("APP KEPT THE PROCESSOR BUSY (${pct.toInt()}% of one core over the last 30 s, while on screen)")
            appendLine("Busiest threads (% of one core over 5 s):")
            if (busiest.isEmpty()) appendLine("    (none stood out)")
            busiest.forEach { (name, used, _) -> appendLine("    ${used.toInt()}%  $name") }
            busiest.map { it.first }.distinct().forEach { name ->
                // Several threads can share a name (the pool threads): their samples go together.
                val lines = ownLines.entries.filter { it.key == name || (name != "main" && it.key.startsWith(name)) }
                    .flatMap { it.value.entries }
                    .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
                if (lines.isEmpty()) return@forEach
                appendLine()
                appendLine("$name runs (app's own code, times seen in 20 looks):")
                lines.entries.sortedByDescending { it.value }.take(12).forEach { (l, n) -> appendLine("    ${n}x  $l") }
            }
        }.trimEnd()
    }

    /**
     * Why Android closed the app last time, when it wasn't a normal close or a crash this class
     * already reported: out of memory, frozen (ANR), a crash in native code, and so on.
     */
    private fun exitReport(app: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val am = app.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching { am.getHistoricalProcessExitReasons(app.packageName, 0, 5) }.getOrNull()
            ?: return null
        val last = exits.firstOrNull { it.processName == app.packageName } ?: return null
        val seenAt = prefs.getLong(KEY_EXIT_SEEN, 0L)
        prefs.edit().putLong(KEY_EXIT_SEEN, last.timestamp).apply()
        // First start with this feature: only remember where we are.
        if (seenAt == 0L || last.timestamp <= seenAt) return null
        val selfKillAt = prefs.getLong(KEY_SELF_KILL, 0L)
        val reason = when (last.reason) {
            ApplicationExitInfo.REASON_ANR -> "FROZE (Android closed it for not responding)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASHED IN NATIVE CODE (video decoder, graphics or a library)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "RAN OUT OF MEMORY (Android closed it to free memory)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "USED TOO MUCH OF THE DEVICE (Android closed it)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "FAILED TO START"
            ApplicationExitInfo.REASON_SIGNALED ->
                // Our own crash handler ends the app this way; that crash was already shown.
                if (kotlin.math.abs(last.timestamp - selfKillAt) < 15_000) return null
                else "KILLED (signal ${last.status}; on TV boxes this is usually the memory cleaner)"
            else -> return null
        }
        return buildString {
            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("APP $reason")
            appendLine("When: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(last.timestamp))}")
            last.description?.takeIf { it.isNotBlank() }?.let { appendLine("Android says: $it") }
            appendLine("Was ${if (last.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "on screen" else "in the background"}")
            appendLine("App memory: ${last.pss / 1024} MB used, ${last.rss / 1024} MB total")
            runCatching {
                val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                appendLine("Device memory: ${mi.totalMem / (1024 * 1024)} MB, app limit ${am.memoryClass} MB / large ${am.largeMemoryClass} MB")
            }
            if (last.reason == ApplicationExitInfo.REASON_ANR) {
                val stuck = runCatching { mainThreadFromAnr(last) }.getOrNull()
                if (!stuck.isNullOrBlank()) {
                    appendLine("Main thread was stuck at:")
                    append(stuck)
                }
            }
        }.trimEnd()
    }

    /** The main thread's part of Android's freeze (ANR) report. */
    private fun mainThreadFromAnr(info: ApplicationExitInfo): String? {
        val text = info.traceInputStream?.bufferedReader()?.use { r ->
            val out = StringBuilder()
            var inMain = false
            var lines = 0
            while (true) {
                val line = r.readLine() ?: break
                if (!inMain && line.startsWith("\"main\"")) inMain = true
                if (inMain) {
                    if (line.isBlank() && lines > 0) break
                    // Keep the stack lines ("at ...") and lock lines, they're what matter.
                    val t = line.trim()
                    if (t.startsWith("at ") || t.startsWith("- ") || t.startsWith("native:") || lines == 0) {
                        out.appendLine("    $t")
                        if (++lines >= 40) break
                    }
                }
            }
            out.toString()
        }
        return text
    }

    /**
     * Notices when the app stops responding: every second it asks the main thread to answer;
     * if it hasn't for 4 seconds, what the main thread is stuck on is written down. If the app
     * recovers, the note is thrown away; if Android closes it while frozen, the note is shown
     * as a crash report next time the app opens.
     */
    private fun startFreezeWatchdog(app: Context) {
        Thread({
            val answeredAt = java.util.concurrent.atomic.AtomicLong(SystemClock.uptimeMillis())
            var askedAt = 0L
            var written = false
            var lastLoop = SystemClock.uptimeMillis()
            // While frozen, the main thread is looked at every half second. One look only shows
            // a single moment (often deep inside a library); the app's own lines seen across all
            // the looks show which part of the app is keeping it busy.
            val ownLines = LinkedHashMap<String, Int>()
            val samples = ArrayList<String>()
            var looks = 0
            var nextLookAt = 0L
            var lastWriteAt = 0L
            while (true) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                // The whole app was paused (in the background, device asleep): start over.
                if (now - lastLoop > 3_000) { answeredAt.set(now); askedAt = 0L }
                lastLoop = now
                if (askedAt == 0L || answeredAt.get() >= askedAt) {
                    if (written) { runCatching { File(app.filesDir, FREEZE_FILE).delete() }; written = false }
                    ownLines.clear(); samples.clear(); looks = 0; nextLookAt = 0L; lastWriteAt = 0L
                    askedAt = now
                    main.post { answeredAt.set(SystemClock.uptimeMillis()) }
                } else if (now - askedAt > 1_500 && now >= nextLookAt && looks < 60) {
                    // Looks start after 1.5 s (well before Android or the user gives up).
                    nextLookAt = now + 500
                    looks++
                    val stack = runCatching { Looper.getMainLooper().thread.stackTrace }.getOrNull().orEmpty()
                    stack.map { it.toString() }
                        .filter { it.startsWith("com.nuvio.tv.") && !it.startsWith("com.nuvio.tv.livetv.crash.") }
                        .distinct()
                        .forEach { ownLines[it] = (ownLines[it] ?: 0) + 1 }
                    // A few full looks, spread out, for reading by hand.
                    if (looks == 1 || looks == 5 || looks == 10 || looks == 20) {
                        samples += buildString {
                            appendLine("Look $looks (${(now - askedAt) / 100 / 10.0}s into the freeze):")
                            stack.take(45).forEach { appendLine("    at $it") }
                        }.trimEnd()
                    }
                    // Written down from 4 s on, then kept up to date every 2 s.
                    if (now - askedAt > 4_000 && now - lastWriteAt >= 2_000) {
                        written = true
                        lastWriteAt = now
                        val report = buildString {
                            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
                            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine(situation(app))
                            appendLine()
                            appendLine("APP FROZE (stopped responding for ${(now - askedAt) / 1000}s and was closed)")
                            appendLine("The app's own code seen in $looks looks (most often first):")
                            if (ownLines.isEmpty()) appendLine("    (none: only Android or library code)")
                            ownLines.entries.sortedByDescending { it.value }.take(25)
                                .forEach { (line, n) -> appendLine("    ${n}x  $line") }
                            samples.forEach { appendLine(); appendLine(it) }
                        }
                        runCatching { File(app.filesDir, FREEZE_FILE).writeText(report) }
                        Log.e(TAG, report)
                    }
                }
            }
        }, "FreezeWatchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    @Volatile private var appContext: Context? = null

    /**
     * One line on when it happened: how long after the app started, how long the box had been on
     * (problems that a restart of the box clears), when the app was last updated, and memory.
     */
    private fun situation(context: Context?): String = runCatching {
        val ctx = context ?: appContext
        val sinceStart = (SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) / 1000
        val boxOn = SystemClock.elapsedRealtime() / 60_000
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val updated = ctx?.let { c ->
            runCatching {
                val at = c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime
                val ago = (System.currentTimeMillis() - at) / 60_000
                if (ago < 120) "${ago}m ago" else "${ago / 60}h ago"
            }.getOrNull()
        } ?: "?"
        "App open ${sinceStart}s · box on ${boxOn / 1440}d ${boxOn / 60 % 24}h ${boxOn % 60}m · app updated $updated · memory ${usedMb}/${maxMb} MB"
    }.getOrDefault("")

    private fun buildReport(thread: Thread, error: Throwable): String {
        // Put the root cause first: it's the most useful line when reading from a TV screen.
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return buildString {
            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(situation(null))
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("ROOT CAUSE: $root")
            root.stackTrace.take(12).forEach { appendLine("    at $it") }
            appendLine()
            appendLine("FULL TRACE:")
            append(Log.getStackTraceString(error))
        }
    }
}

/** Runs before Application.onCreate, so crashes during startup are caught too. */
class CrashCatcherInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { CrashCatcher.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
