package com.nuvio.tv.livetv.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.media3.common.Player
import com.nuvio.tv.core.player.MovieStartupStep
import com.nuvio.tv.core.player.MovieStartupTrace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class CrashCatcherContextTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var exit: ApplicationExitInfo
    private lateinit var directory: File
    private val timestamp = 1_795_516_200_000L

    @Before fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences("livetv_crash_catcher", Context.MODE_PRIVATE)
        preferences.edit().clear().putLong("exit_seen_at", timestamp - 1).commit()
        directory = temporary.newFolder()
        val manager = mockk<ActivityManager>()
        exit = mockk()
        every { exit.processName } returns application.packageName
        every { exit.timestamp } returns timestamp
        every { exit.reason } returns ApplicationExitInfo.REASON_SIGNALED
        every { exit.status } returns 9
        every { exit.description } returns null
        every { exit.importance } returns ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        every { exit.pss } returns 461L * 1024
        every { exit.rss } returns 304L * 1024
        every { manager.getHistoricalProcessExitReasons(application.packageName, 0, 5) } returns listOf(exit)
        every { manager.getMemoryInfo(any()) } answers {
            firstArg<ActivityManager.MemoryInfo>().totalMem = 1669L * 1024 * 1024
        }
        every { manager.memoryClass } returns 192
        every { manager.largeMemoryClass } returns 384
        context = mockk {
            every { packageName } returns application.packageName
            every { filesDir } returns directory
            every { packageManager } returns application.packageManager
            every { getSharedPreferences("livetv_crash_catcher", Context.MODE_PRIVATE) } returns preferences
            every { getSystemService(ActivityManager::class.java) } returns manager
        }
    }

    @Test fun `signal exit reports historical PSS and RSS without inventing a memory kill cause`() {
        val report = requireNotNull(exitReport())

        assertTrue(report.contains("APP KILLED (signal 9)"))
        assertFalse(report.contains("memory cleaner"))
        assertTrue(report.contains("Last sampled process memory: PSS 461 MiB, RSS 304 MiB"))
        assertTrue(report.contains("Historical samples, not Java heap usage/limit or exact memory at exit."))
        assertTrue(report.contains("Device total RAM: 1669 MiB, Java heap class 192 MiB / large 384 MiB"))
        assertFalse(report.contains("MB used"))
        assertFalse(report.contains("MB total"))
    }

    @Test fun `Android low memory reason remains distinct from a signal termination`() {
        every { exit.reason } returns ApplicationExitInfo.REASON_LOW_MEMORY

        val report = requireNotNull(exitReport())

        assertTrue(report.contains("APP RAN OUT OF MEMORY (Android closed it to free memory)"))
        assertFalse(report.contains("APP KILLED"))
    }

    @Test fun `signal from the apps own crash handler is still suppressed`() {
        context.getSharedPreferences("livetv_crash_catcher", Context.MODE_PRIVATE).edit()
            .putLong("self_kill_at", timestamp).commit()

        assertNull(exitReport())
    }

    @Test fun `legacy CPU snapshot retains its contents and saved date beside a newer crash`() {
        val crash = "APP KILLED (signal 9)\nWhen: 2026-11-25 12:00:00"
        val legacy = "APP KEPT THE PROCESSOR BUSY (128% of one core over the last 30 s, while on screen)"
        File(directory, "last_crash.txt").writeText(crash)
        val busy = File(directory, "busy_report.txt").apply { writeText(legacy) }
        val savedAt = timestamp - 24 * 60 * 60_000L
        assertTrue(busy.setLastModified(savedAt))

        val report = requireNotNull(CrashCatcher.lastReport(context))
        val savedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(savedAt))

        assertTrue(report.startsWith(crash))
        assertTrue(report.contains("SAVED CPU SNAPSHOT (file last saved: $savedDate)"))
        assertTrue(report.contains("This snapshot may be from a different app run than any crash or freeze report."))
        assertTrue(report.endsWith(legacy))
        assertEquals(legacy, busy.readText())
    }

    @Test fun `CPU only report is identified as an independent saved snapshot`() {
        File(directory, "busy_report.txt").writeText("Legacy CPU sample")

        val report = requireNotNull(CrashCatcher.lastReport(context))

        assertTrue(report.startsWith("SAVED CPU SNAPSHOT (file last saved:"))
        assertTrue(report.endsWith("Legacy CPU sample"))
    }

    @Test fun `slow movie startup is readable without a crash and stays separate from other runs`() {
        val save = CrashCatcher::class.java.getDeclaredMethod("saveSlowStart", Context::class.java, String::class.java)
            .apply { isAccessible = true }
        save.invoke(CrashCatcher, context, "Movie startup attempt 1: prepare returned")
        val slowOnly = requireNotNull(CrashCatcher.lastReport(context))
        assertTrue(slowOnly.startsWith(CrashCatcher.SLOW_START_HEADING))
        assertTrue(slowOnly.contains("Movie startup attempt 1: prepare returned"))
        assertFalse(File(directory, "last_crash.txt").exists())

        val slowFile = File(directory, "slow_movie_startup.txt")
        val saved = slowFile.lastModified()
        val crash = File(directory, "last_crash.txt").apply { writeText("Older freeze report") }
        assertTrue(crash.setLastModified(saved - 60_000))
        val both = requireNotNull(CrashCatcher.lastReport(context))
        assertTrue(both.startsWith(slowOnly))
        assertTrue(both.contains("PREVIOUS SAVED CRASH OR FREEZE\nOlder freeze report"))
        assertTrue(shortCrash(both).contains("Movie startup attempt 1"))
        assertEquals("Older freeze report", crash.readText())

        assertTrue(crash.setLastModified(saved + 60_000))
        val latestCrash = requireNotNull(CrashCatcher.lastReport(context))
        assertTrue(latestCrash.startsWith("Older freeze report"))
        assertTrue(latestCrash.endsWith(slowOnly))
    }

    @Test fun `current memory snapshot explicitly identifies Java heap usage and maximum`() {
        val method = CrashCatcher::class.java.getDeclaredMethod("situation", Context::class.java).apply {
            isAccessible = true
        }
        val report = method.invoke(CrashCatcher, context) as String

        assertTrue(report, Regex("Java heap \\d+/\\d+ MiB used/max").containsMatchIn(report))
    }

    @Test fun `a recovered startup freeze is retained without overwriting the actual crash`() {
        val prior = File(directory, "last_crash.txt").apply { writeText("Prior crash") }
        val method = CrashCatcher::class.java.getDeclaredMethod(
            "saveStartupIncident", Context::class.java, String::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        method.invoke(CrashCatcher, context, "recovered_movie_freeze.txt", CrashCatcher.RECOVERED_HEADING,
            "The UI heartbeat returned after 16300ms.\nMovie startup #3")
        assertTrue(prior.setLastModified(timestamp - 60_000L))
        val report = requireNotNull(CrashCatcher.lastReport(context))
        assertTrue(report.startsWith(CrashCatcher.RECOVERED_HEADING))
        assertTrue(shortCrash(report).contains("heartbeat returned after 16300ms"))
        assertTrue(report.endsWith("PREVIOUS SAVED CRASH OR FREEZE\nPrior crash"))
        assertEquals("Prior crash", prior.readText())
    }

    @Test fun `short report keeps session timing Binder evidence and the last milestone of a long timeline`() {
        var time = 0L
        val trace = MovieStartupTrace.Recorder { time }
        val attempt = trace.begin()
        attempt.sessionStateTiming.measure(Player.STATE_BUFFERING) { time += 8_000 }
        attempt.mark(MovieStartupStep.UPSTREAM_READ)
        val timeline = requireNotNull(trace.snapshot()).replace("+0 start · ", "+0 start · " + "+1 earlier step · ".repeat(120))
        val samples = PlaybackFreezeSamples()
        samples.observe(10_200, arrayOf(StackTraceElement("android.os.BinderProxy", "transactNative", null, -2),
            StackTraceElement("android.media.session.MediaSession", "setPlaybackState", null, 444)), timeline)
        val full = buildString {
            appendLine("APP FROZE (stopped responding for 16s and was closed)")
            samples.appendTo(this)
            appendLine("PREVIOUS SAVED CRASH OR FREEZE")
        }
        assertTrue(full.length > 1_900)
        val short = shortCrash(full)
        assertTrue(short.length <= 1_900)
        assertTrue(short.contains("1/1 main-thread samples (at +10200ms)"))
        assertTrue(short.contains("longest BUFFERING at +0ms took 8000ms"))
        assertTrue(short.contains("+8000 waiting for first progressive read"))
        assertFalse(short.contains("PREVIOUS SAVED"))
    }

    private fun exitReport(): String? {
        val method = CrashCatcher::class.java.getDeclaredMethod("exitReport", Context::class.java).apply {
            isAccessible = true
        }
        return method.invoke(CrashCatcher, context) as String?
    }
}
