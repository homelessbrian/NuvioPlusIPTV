package com.nuvio.tv.livetv.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * The bell for a show in Live TV (guide menus, the play bar, the full-screen menu):
 *  - a TV show: follow it (its new episodes on the Calendar and on Home),
 *  - a movie: put this airing on the Calendar.
 */
data class LiveBell(
    val on: Boolean,
    /** What the menu says ("Ring the bell: follow this show"…). */
    val label: String,
    /** "On" / "Off", for places with room for a value. */
    val value: String,
    val isMovie: Boolean,
    val toggle: () -> Unit
)

/** The bell for [program] on [channel], or null when there's nothing to ring it for. */
@Composable
fun rememberLiveBell(program: EpgProgram?, channel: LiveChannel?): LiveBell? {
    if (program == null) return null
    val context = LocalContext.current
    val repo = remember {
        runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java).calendarRepository()
        }.getOrNull()
    } ?: return null
    val settings by repo.settings.collectAsState()
    if (SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.sideMenuHidden)) return null
    // Redrawn when a bell is rung anywhere.
    val follows by repo.follows.collectAsState()
    var found by remember(program.title, program.startMs, channel?.key) { mutableStateOf(repo.knownLiveTitle(program, channel)) }
    LaunchedEffect(program.title, program.startMs, channel?.key) {
        if (!found.second) found = repo.liveTitle(program, channel) to true
    }
    val scope = rememberCoroutineScope()
    val (title, known) = found
    // Not a show or movie TMDB knows (news, sports…): no bell.
    if (known && title == null) return null
    if (title == null) {
        // Still being worked out: ringing it works it out first.
        return LiveBell(on = false, label = "Ring the bell", value = "Off", isMovie = false) {
            scope.launch {
                val t = repo.liveTitle(program, channel)
                LiveTvNotice.show(if (t == null) "Couldn't find \"${program.title}\" to follow" else repo.toggleLiveBell(t, program, channel))
            }
        }
    }
    val movie = title.kind != "series"
    // A movie that's already been on: nothing to put on the Calendar.
    if (movie && program.stopMs <= System.currentTimeMillis()) return null
    val on = follows.let { repo.isLiveBellOn(title, program, channel) }
    val label = when {
        movie && on -> "Take this movie off your Calendar"
        movie -> "Ring the bell: put this movie on your Calendar"
        on -> "Turn off the bell for this show"
        else -> "Ring the bell: follow this show"
    }
    return LiveBell(on = on, label = label, value = if (on) "On" else "Off", isMovie = movie) {
        scope.launch { LiveTvNotice.show(repo.toggleLiveBell(title, program, channel)) }
    }
}
