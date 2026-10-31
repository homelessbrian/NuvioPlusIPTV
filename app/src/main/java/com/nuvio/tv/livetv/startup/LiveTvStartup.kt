package com.nuvio.tv.livetv.startup

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvStartupEntryPoint {
    fun liveTvPreferences(): LiveTvPreferences
    fun onDemandRepository(): com.nuvio.tv.livetv.ondemand.OnDemandRepository
    fun liveTvRepository(): com.nuvio.tv.livetv.data.LiveTvRepository
    fun profileManager(): com.nuvio.tv.core.profile.ProfileManager
    fun epgDatabase(): com.nuvio.tv.livetv.data.EpgDatabase
    fun channelStore(): com.nuvio.tv.livetv.data.LiveTvChannelStore
    fun liveTvRecorder(): com.nuvio.tv.livetv.record.LiveTvRecorder
}

/**
 * Gets Live TV ready in the background shortly after the app starts (when it's in use), so
 * the guide is already there when you open it instead of loading only then.
 */
object LiveTvPreload {
    @Volatile private var started = false

    suspend fun start(context: android.content.Context) {
        if (started) return
        started = true
        clearOldBuffers(context.applicationContext, System.currentTimeMillis())
        val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, LiveTvStartupEntryPoint::class.java
        )
        // Reading the saved channel list and guide is quick and only uses storage, so it starts
        // almost straight away, as the app opens. Checking for updates (network, heavy) waits
        // until the app has settled.
        kotlinx.coroutines.delay(300)
        val prefs = entry.liveTvPreferences()
        val settings = prefs.settings.first()
        val playlists = prefs.playlists.first()
        if (!settings.showInSidebar || playlists.none { it.liveEnabled }) return
        entry.liveTvRepository().ensureLoaded(updateCheckDelayMs = 20_000)
    }
}

/**
 * Pause and Rewind Anytime buffers left behind when the app was closed or crashed mid-buffer
 * (up to about 35 MB a minute, in the app's own storage). Normally they were only cleared hours
 * later, the next time a buffer stopped. Buffers started from now on are left alone.
 */
private fun clearOldBuffers(context: android.content.Context, before: Long) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        runCatching {
            val roots = listOf(java.io.File(context.cacheDir, "timeshift")) +
                com.nuvio.tv.livetv.record.RecordingStorage.choices(context).mapNotNull { c ->
                    c.dir.parentFile?.let { java.io.File(it, "timeshift") }
                }
            roots.distinct().forEach { root ->
                root.listFiles()?.forEach { f ->
                    val started = f.name.toLongOrNull() ?: 0L
                    if (started < before - 1_000L) runCatching { f.deleteRecursively() }
                }
            }
        }
    }
}

/**
 * "Open the App On" (Live TV settings → Integration): the page the app opens on.
 *
 * The app starts straight on that page: it's the first screen, in place of Home. (The old way
 * opened Home and then jumped, which raced Home's own loading: laggy, Home flashed, and on some
 * boxes it didn't jump at all.) The choice has to be known before the first screen is drawn,
 * so a copy is kept in a small file that reads instantly, refreshed whenever the setting changes.
 */
object StartPage {
    private const val PREFS = "livetv_start_page"
    private const val KEY = "route"

    private val ROUTES = setOf(
        Screen.LiveTv.route, Screen.OnDemand.route, Screen.Sports.route,
        Screen.Calendar.route, Screen.Library.route, Screen.Search.route
    )

    /** The page to open on, or null for Home. */
    fun cachedRoute(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    }.getOrNull()?.takeIf { it in ROUTES }

    /** The page for a setting ([LiveTvSettings.openOn]); null for Home. Live TV only while it's in the menu. */
    fun routeFor(s: com.nuvio.tv.livetv.model.LiveTvSettings, sportsOn: Boolean = true): String? {
        val off = com.nuvio.tv.livetv.calendar.SideMenuPages.hidden(s.sideMenuHidden)
        // Only pages that are switched on (Settings → Menu & Features); otherwise Home.
        return when (s.openOn) {
            "LIVE_TV" -> Screen.LiveTv.route.takeIf { s.showInSidebar }
            "ON_DEMAND" -> Screen.OnDemand.route.takeIf { s.onDemandInSidebar }
            "SPORTS" -> Screen.Sports.route.takeIf { sportsOn }
            "CALENDAR" -> Screen.Calendar.route.takeIf { com.nuvio.tv.livetv.calendar.SideMenuPages.CALENDAR !in off }
            "LIBRARY" -> Screen.Library.route.takeIf { com.nuvio.tv.livetv.calendar.SideMenuPages.LIBRARY !in off }
            "SEARCH" -> Screen.Search.route.takeIf { com.nuvio.tv.livetv.calendar.SideMenuPages.SEARCH !in off }
            else -> null
        }
    }

    fun save(context: Context, route: String?) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY, null) == route) return
            prefs.edit().apply { if (route == null) remove(KEY) else putString(KEY, route) }.apply()
        }
    }
}
