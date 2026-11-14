package com.nuvio.tv.livetv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.ondemand.OnDemandRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LoadingEntryPoint {
    fun liveTvRepository(): LiveTvRepository
    fun onDemandRepository(): OnDemandRepository
    fun liveTvPlayback(): com.nuvio.tv.livetv.player.LiveTvPlaybackController
    fun liveTvPreferences(): com.nuvio.tv.livetv.data.LiveTvPreferences
}

/**
 * A small "working on it" pill in the bottom-right corner, anywhere in Nuvio, while playlists,
 * TV guides or On Demand catalogs are downloading, with what's happening and a running count.
 * Hidden while something is playing full screen.
 */
@Composable
fun LiveTvLoadingHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, LoadingEntryPoint::class.java) }.getOrNull()
    } ?: return
    // Live TV fork: stop Live TV's sound whenever the app leaves the front, from any screen.
    PauseLiveTvInBackground(entry.liveTvPlayback())
    val live by entry.liveTvRepository().status.collectAsState()
    val vod by entry.onDemandRepository().status.collectAsState()
    // Wait for the saved preference before showing progress, so an opted-out TV never flashes it.
    val showImportProgress by remember(entry) {
        entry.liveTvPreferences().settings.map { it.showImportProgress }.distinctUntilChanged()
    }.collectAsState(false)
    val route = navController.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val liveFullscreen by LiveTvFullscreen.active.collectAsState()
    val multiview = route == Screen.LiveTvMultiview.route || route == Screen.SportsMultiview.route
    val playing = route == Screen.LiveTvPlayer.route || route.startsWith("player/") || liveFullscreen || multiview
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, route) {
        fun updateWorkHold() {
            // Multiview releases its streams on pause, so automatic imports may proceed then.
            val activeMultiview = multiview && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            com.nuvio.tv.livetv.data.BackgroundWork.nuvioPlaying.value = route.startsWith("player/") || activeMultiview
        }
        val observer = LifecycleEventObserver { _, _ -> updateWorkHold() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateWorkHold()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (multiview) com.nuvio.tv.livetv.data.BackgroundWork.nuvioPlaying.value = false
        }
    }
    // Nuvio's own player is opening (a movie, an episode, Watch On Demand): make sure the
    // Live TV player has let go of the audio first, so the two never hold it at once.
    androidx.compose.runtime.LaunchedEffect(route) {
        // Also when the stream list opens: the provider gets a few seconds to close the Live TV
        // connection before the movie asks for one (accounts with one connection otherwise
        // refuse the movie with "too many requests" / HTTP 429).
        if (route.startsWith("player/") || route.startsWith("stream/") || multiview) entry.liveTvPlayback().releaseNow()
    }
    // "Start in Full Screen" on: keep the automatic background work away while the last channel
    // starts and plays full screen (otherwise the video stutters on slower boxes).
    val autoPlayStart by remember(entry) {
        entry.liveTvPreferences().settings.map { it.autoPlayLastChannel }.distinctUntilChanged()
    }.collectAsState(false)
    androidx.compose.runtime.LaunchedEffect(autoPlayStart) {
        val hold = com.nuvio.tv.livetv.data.BackgroundWork.liveHold
        if (!autoPlayStart) {
            hold.value = false
            return@LaunchedEffect
        }
        if (!com.nuvio.tv.livetv.data.BackgroundWork.launchHoldDone) {
            com.nuvio.tv.livetv.data.BackgroundWork.launchHoldDone = true
            hold.value = true
            kotlinx.coroutines.withTimeoutOrNull(20_000L) { LiveTvFullscreen.active.first { it } }
        }
        LiveTvFullscreen.active.collect { hold.value = it }
    }
    val message = when {
        live.loading && !live.quiet && !live.silent -> live.message ?: "Updating Live TV…"
        vod.loading && !vod.silent -> vod.message ?: "Importing movies and series…"
        else -> null
    }
    // How long it's been working, so a long first load reads as busy rather than stuck.
    val busy = showImportProgress && message != null
    val startedAt = androidx.compose.runtime.remember(busy) { System.currentTimeMillis() }
    val elapsedSec by androidx.compose.runtime.produceState(0L, busy) {
        value = 0L
        while (busy) {
            kotlinx.coroutines.delay(1_000)
            value = (System.currentTimeMillis() - startedAt) / 1000
        }
    }
    val elapsed = if (elapsedSec >= 20) " · " + "%d:%02d".format(elapsedSec / 60, elapsedSec % 60) else ""
    // Recording messages (set, started, missed, a clash), for a few seconds, wherever you are.
    val notice by com.nuvio.tv.livetv.record.LiveTvNotice.current.collectAsState()
    androidx.compose.runtime.LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        kotlinx.coroutines.delay(if (n.text.length > 60) 6_000 else 4_000)
        com.nuvio.tv.livetv.record.LiveTvNotice.clear(n)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            LiveText(
                notice?.text.orEmpty(),
                modifier = Modifier
                    .padding(bottom = 64.dp, start = 48.dp, end = 48.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                color = Color.White,
                size = 15.sp,
                maxLines = 3
            )
        }
        AnimatedVisibility(visible = busy && !playing, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .padding(end = 28.dp, bottom = 22.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50))
                    .border(1.dp, NuvioTheme.colors.Border, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = NuvioTheme.colors.Secondary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                LiveText(message.orEmpty() + elapsed, color = Color.White, size = 12.sp)
            }
        }
    }
}

/** The same spinner, for use next to a status line in settings. */
@Composable
internal fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(16.dp), color = NuvioTheme.colors.Secondary, strokeWidth = 2.dp)
}
