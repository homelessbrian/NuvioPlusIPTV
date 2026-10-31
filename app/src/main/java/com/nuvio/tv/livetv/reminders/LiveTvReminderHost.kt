package com.nuvio.tv.livetv.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.Reminder
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun liveTvPreferences(): LiveTvPreferences
    fun liveTvRepository(): LiveTvRepository
    fun liveTvPlayback(): LiveTvPlaybackController
}

/**
 * Shows "Remind me" messages anywhere in Nuvio: a minute before the show starts, with Watch
 * (switches to the channel, full screen) or Dismiss. Lives next to the app's navigation.
 */
@Composable
fun LiveTvReminderHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java) }.getOrNull()
    } ?: return
    val scope = rememberCoroutineScope()
    // A show's page asked for Live TV (Watch live on its next episode).
    LaunchedEffect(Unit) {
        com.nuvio.tv.livetv.calendar.CalendarNav.openLive.collect {
            com.nuvio.tv.livetv.sports.openLiveTvFullscreen(navController)
        }
    }
    var due by remember { mutableStateOf<Reminder?>(null) }
    val shown = remember { HashSet<String>() }

    LaunchedEffect(Unit) {
        val prefs = entry.liveTvPreferences()
        var loaded = false
        while (true) {
            // Load channels in the background once there's a reminder, so Watch can tune in.
            if (!loaded && runCatching { prefs.userState.first().reminders.isNotEmpty() }.getOrDefault(false)) {
                entry.liveTvRepository().ensureLoaded(updateCheckDelayMs = 20_000)
                loaded = true
            }
            val now = System.currentTimeMillis()
            runCatching { prefs.pruneReminders(now) }
            if (due == null) {
                val reminders = runCatching { prefs.userState.first().reminders }.getOrDefault(emptyList())
                due = reminders.firstOrNull { r ->
                    "${r.channelKey}|${r.startMs}" !in shown && now >= r.startMs - 60_000 && now < r.startMs + 10 * 60_000
                }?.also { shown += "${it.channelKey}|${it.startMs}" }
            }
            delay(10_000)
        }
    }

    val r = due ?: return
    fun finish() {
        due = null
        scope.launch { entry.liveTvPreferences().removeReminder(r.channelKey, r.startMs) }
    }
    val first = remember(r) { FocusRequester() }
    // Goes away by itself after a couple of minutes.
    LaunchedEffect(r) { delay(120_000); if (due == r) finish() }
    LiveDialog(onDismiss = { finish() }, width = 460.dp) {
        val now = System.currentTimeMillis()
        LiveText(
            if (now < r.startMs) "Starting in a minute" else "Starting now",
            color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold
        )
        LiveText(r.title, size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(
            "${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(r.startMs))} · ${r.channelName}",
            color = NuvioTheme.colors.TextSecondary, size = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MenuItem("Watch", Modifier.focusRequester(first)) {
                val channel = entry.liveTvRepository().channels.value.firstOrNull { it.key == r.channelKey }
                finish()
                if (channel != null) {
                    entry.liveTvPlayback().play(channel)
                    navController.navigate(Screen.LiveTv.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    navController.navigate(Screen.LiveTvPlayer.route) { launchSingleTop = true }
                }
            }
            MenuItem("Dismiss") { finish() }
        }
    }
    LaunchedEffect(r) { delay(80); runCatching { first.requestFocus() } }
}
