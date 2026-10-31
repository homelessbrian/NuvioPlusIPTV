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
    private const val PREFS = "livetv_crash_catcher"
    private const val KEY_EXIT_SEEN = "exit_seen_at"
    private const val KEY_SELF_KILL = "self_kill_at"
    const val EXTRA_REPORT = "report"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed: WeakReference<Activity>? = null
    @Volatile private var pendingReport: String? = null

    fun install(context: Context) {
        val app = context.applicationContext
        // Reports from last time (freeze, or Android closing the app) are put together off the
        // main thread and saved. Nothing pops up: the report waits in Settings › Live TV ›
        // Help › Crash report, for when someone is asked to send it.
        Thread({
            runCatching {
                val parts = mutableListOf<String>()
                val frozen = File(app.filesDir, FREEZE_FILE)
                if (frozen.exists()) {
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

    fun lastReport(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText() }.getOrNull()

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
            while (true) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val now = SystemClock.uptimeMillis()
                // The whole app was paused (in the background, device asleep): start over.
                if (now - lastLoop > 3_000) { answeredAt.set(now); askedAt = 0L }
                lastLoop = now
                if (askedAt == 0L || answeredAt.get() >= askedAt) {
                    if (written) { runCatching { File(app.filesDir, FREEZE_FILE).delete() }; written = false }
                    askedAt = now
                    main.post { answeredAt.set(SystemClock.uptimeMillis()) }
                } else if (now - askedAt > 4_000 && !written) {
                    written = true
                    val mainThread = Looper.getMainLooper().thread
                    val report = buildString {
                        appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine()
                        appendLine("APP FROZE (stopped responding for ${(now - askedAt) / 1000}s and was closed)")
                        appendLine("Main thread was stuck at:")
                        mainThread.stackTrace.take(40).forEach { appendLine("    at $it") }
                    }
                    runCatching { File(app.filesDir, FREEZE_FILE).writeText(report) }
                    Log.e(TAG, report)
                }
            }
        }, "FreezeWatchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun buildReport(thread: Thread, error: Throwable): String {
        // Put the root cause first: it's the most useful line when reading from a TV screen.
        var root = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return buildString {
            appendLine("Nuvio + IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · kit ${com.nuvio.tv.livetv.model.LiveTvLoadReport.KIT_STAMP}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
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
