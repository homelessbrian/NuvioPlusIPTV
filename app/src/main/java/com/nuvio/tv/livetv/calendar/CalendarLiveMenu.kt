package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveTag
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nuvio.tv.livetv.record.LiveTvNotice
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One line in a Calendar menu. [hint]: a quieter second line. */
internal data class MenuAction(val text: String, val hint: String? = null, val onClick: () -> Unit)

/** What the Live TV options do; the screen showing them decides how (it knows the navigation). */
internal class LiveMenuHandlers(
    val watch: () -> Unit,
    val fromStart: () -> Unit,
    val record: () -> Unit,
    val series: () -> Unit,
    val remind: () -> Unit,
    val choose: () -> Unit,
    val waitRecord: () -> Unit,
    val waitRemind: () -> Unit,
    val playRecording: (() -> Unit)? = null,
    /** "Set the channel for this show" (TV shows): opens the channel search. */
    val setChannel: (() -> Unit)? = null,
    /** The channel you set for the show, if any (its name). */
    val myChannel: String? = null
)

/**
 * The Live TV options for an episode (or a movie airing): watch it live or from the start,
 * record it or every new episode, a reminder, the channel, a recording already made, and, for
 * an episode further ahead than your guide goes, "when it's in my guide" plans.
 */
internal fun liveActions(
    e: CalendarEntry,
    st: CalendarLiveStatus,
    channels: List<CalendarGuideHit>,
    best: CalendarGuideHit?,
    now: Long,
    use24h: Boolean,
    canWait: Boolean,
    h: LiveMenuHandlers
): List<MenuAction> = buildList {
    val onNow = channels.firstOrNull { it == best && now in it.startMs until it.stopMs } ?: channels.firstOrNull { now in it.startMs until it.stopMs }
    val replay = channels.firstOrNull { it.catchup && it.startMs <= now }
    if (onNow != null) add(MenuAction("Watch live on ${onNow.channelName}", if (channels.size > 1) "Or choose the channel below" else null, h.watch))
    if (replay != null) add(MenuAction("Watch from the start", "Through ${replay.channelName}'s catch-up", h.fromStart))
    if (st.recordedId != null && h.playRecording != null) add(MenuAction("Play the recording", null, h.playRecording))
    if (best != null && best.stopMs > now) {
        add(
            if (st.recording) MenuAction("Cancel the recording", null, h.record)
            else MenuAction("Record this ${if (e.kind == CalendarKind.MOVIE) "movie" else "episode"}",
                "On ${best.channelName} · ${whenLabel(best.startMs, now, use24h)}", h.record)
        )
    }
    if (e.kind == CalendarKind.EPISODE) {
        add(
            if (st.series) MenuAction("Stop recording new episodes", "Recordings already made are kept", h.series)
            else MenuAction("Record every new episode", "Each new one, as it shows up in your guide (not repeats)", h.series)
        )
    }
    if (best != null && best.startMs > now) {
        add(if (st.reminder) MenuAction("Cancel the reminder", null, h.remind) else MenuAction("Remind me when it starts", "A message a minute before, with Watch", h.remind))
    }
    if (e.kind != CalendarKind.GAME && (channels.isEmpty() || channels.any { it.stopMs > now })) {
        add(
            MenuAction(
                "Choose the channel",
                when (channels.size) {
                    0 -> "Not found in your guide: search all your channels"
                    1 -> "Found on 1 of your channels · or search all of them"
                    else -> "Found on ${channels.size} of your channels · or search all of them"
                },
                h.choose
            )
        )
    }
    if (h.setChannel != null && e.kind == CalendarKind.EPISODE && !e.streaming) {
        add(
            MenuAction(
                if (h.myChannel != null) "Change the channel for this show" else "Set the channel for this show",
                if (h.myChannel != null) "Now: ${h.myChannel} (pick \"Use the guide's channels\" to undo)"
                else "When it isn't found in your guide, or on the wrong channel",
                h.setChannel
            )
        )
    }
    if (canWait || st.waitRecord || st.waitRemind) {
        add(
            if (st.waitRecord) MenuAction("Don't record it when it's in my guide", null, h.waitRecord)
            else MenuAction("Record it when it's in my guide", "Your guide doesn't go that far ahead yet: it's set the moment it does", h.waitRecord)
        )
        add(
            if (st.waitRemind) MenuAction("Don't remind me when it's in my guide", null, h.waitRemind)
            else MenuAction("Remind me when it's in my guide", null, h.waitRemind)
        )
    }
}

/** "Tonight 10:00 PM", "Tomorrow 9:00 PM", "Fri 8:00 PM", "Now". */
internal fun whenLabel(ms: Long, now: Long, use24h: Boolean): String {
    if (ms <= now) return "now"
    val day = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val d = ChronoUnit.DAYS.between(LocalDate.now(), day)
    val clock = formatClock(ms, use24h)
    return when (d) {
        0L -> "today $clock"
        1L -> "tomorrow $clock"
        in 2L..6L -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) + " " + clock
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d")) + " " + clock
    }
}

/** Little tags for where an entry stands with Live TV: REC, SERIES, REMINDER, WAITING FOR GUIDE, RECORDED. */
@Composable
internal fun LiveBadges(st: CalendarLiveStatus, compact: Boolean = false) {
    val dim = Color.Black.copy(alpha = 0.78f)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (st.recording) LiveTag("● REC", LIVE_RED, Color.White)
        if (st.series) LiveTag(if (compact) "SERIES" else "● SERIES", if (st.recording) dim else LIVE_RED.copy(alpha = 0.85f), Color.White)
        if (st.reminder) LiveTag(if (compact) "⏰" else "⏰ REMINDER", dim, Color.White)
        if (st.waitRecord || st.waitRemind) LiveTag(if (compact) "WAITING" else "WAITING FOR GUIDE", dim, Color(0xFFFFD27A))
        if (st.recordedId != null) LiveTag("▶ RECORDED", Color(0xFF1C6B45), Color.White)
    }
}

/** A Calendar menu in Live TV's glass dialog: a heading, then [actions] (the first one highlighted). */
@Composable
internal fun CalendarMenu(
    kicker: String?,
    title: String,
    subtitle: String?,
    status: CalendarLiveStatus?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        kicker?.let { LiveText(it, size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary) }
        LiveText(title, size = 21.sp, weight = FontWeight.Bold, maxLines = 2)
        subtitle?.let { LiveText(it, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2) }
        if (status != null && status != CalendarLiveStatus()) {
            Spacer(Modifier.height(6.dp))
            LiveBadges(status)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 430.dp)) {
            itemsIndexed(actions) { i, a ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    onClick = a.onClick
                ) { focused ->
                    Column {
                        LiveText(a.text, size = 15.sp, weight = if (focused) FontWeight.SemiBold else FontWeight.Medium, color = focusedTextColor(focused))
                        a.hint?.let { LiveText(it, size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.65f), maxLines = 2) }
                    }
                }
            }
            if (footer != null) item { footer() }
        }
    }
}

/**
 * Pick the channel: every one of yours found showing it (best match first, this very episode,
 * favorites and later feeds marked), the channel saved for its network (with Forget), and a
 * search of all your channels for one that wasn't found.
 */
@Composable
internal fun ChannelPicker(
    entry: CalendarEntry,
    record: Boolean,
    options: List<CalendarGuideHit>,
    now: Long,
    use24h: Boolean,
    pickedKey: String?,
    isRecording: (CalendarGuideHit) -> Boolean,
    onDismiss: () -> Unit,
    /** The channel, whether to always use it for this network, and whether to record it (hold OK). */
    onPick: (CalendarGuideHit, Boolean, Boolean) -> Unit,
    /** A channel's name, with its group when another of yours has the same name. */
    label: (CalendarGuideHit) -> String = { it.channelName },
    /** "Always using Comedy Central · UK | Entertainment for Comedy Central" (null: nothing saved). */
    savedText: String? = null,
    onForget: (() -> Unit)? = null,
    /** Opens the search of all your channels (it sets the channel for the show). */
    onSearchAll: (() -> Unit)? = null
) {
    var always by remember { mutableStateOf(pickedKey != null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 600.dp) {
        LiveText(if (record) "RECORD · CHOOSE THE CHANNEL" else "WATCH · CHOOSE THE CHANNEL", size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary)
        LiveText(entry.title, size = 21.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(
            listOfNotNull(
                entry.episodeCode,
                entry.airMs?.let { whenLabel(it, now, use24h).replaceFirstChar { c -> c.uppercase() } },
                if (options.isEmpty()) "not found in your guide" else "found on ${options.size} of your channels"
            ).joinToString(" · "),
            size = 13.sp, color = NuvioTheme.colors.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        // The first row gets the focus: the saved choice, else the best channel, else the search.
        val savedRow = savedText != null && onForget != null
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (savedRow) item {
                LiveFocusRow(modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = { onForget?.invoke() }) { focused ->
                    Column(Modifier.weight(1f)) {
                        LiveText(savedText, size = 13.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused), maxLines = 2)
                        LiveText("OK: forget it (the Calendar picks the channel itself again)", size = 11.sp, color = focusedTextColor(focused).copy(alpha = 0.65f))
                    }
                }
            }
            if (options.isEmpty()) item {
                LiveText(
                    "None of your channels were found showing it in your guide. Search for the channel below: it's used for this show from now on.",
                    size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 3, modifier = Modifier.padding(12.dp)
                )
            }
            itemsIndexed(options) { i, h ->
                val onNow = now in h.startMs until h.stopMs
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (!savedRow && i == 0) Modifier.focusRequester(first) else Modifier),
                    onClick = { onPick(h, always, false) },
                    onLongClick = { onPick(h, always, true) }
                ) { focused ->
                    Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                        com.nuvio.tv.livetv.ui.ChannelLogo(h.channelLogo, 28.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        LiveText(h.channelName, size = 15.sp, weight = FontWeight.Bold, color = focusedTextColor(focused).copy(alpha = if (h.later && !focused) 0.65f else 1f))
                        LiveText(
                            listOfNotNull(
                                h.group ?: h.playlist,
                                if (h.favorite) "Favorite" else null,
                                if (h.catchup) "catch-up" else null,
                                when {
                                    onNow -> "on now"
                                    h.later -> "airs ${formatClock(h.startMs, use24h)} (later)"
                                    else -> "starts ${formatClock(h.startMs, use24h)}"
                                }
                            ).joinToString(" · "),
                            size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f), maxLines = 2
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isRecording(h)) LiveTag("● REC", LIVE_RED, Color.White)
                        when {
                            h.mine -> LiveTag("YOUR CHANNEL", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                            h.channelKey == pickedKey -> LiveTag("YOUR CHANNEL", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                            i == 0 && !h.later -> LiveTag("BEST MATCH", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                            h.later -> LiveTag("LATER", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                            else -> Unit
                        }
                        if (h.confirmed) LiveTag("THIS EPISODE", Color(0xFF1C6B45), Color.White)
                    }
                }
            }
            if (entry.network != null && options.isNotEmpty()) item {
                LiveFocusRow(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), onClick = { always = !always }) { focused ->
                    SwitchMark(always)
                    Spacer(Modifier.width(4.dp))
                    LiveText("Always use this channel for ${entry.title}", size = 14.sp, color = focusedTextColor(focused))
                }
            }
            if (onSearchAll != null) item {
                LiveFocusRow(modifier = Modifier.fillMaxWidth().then(if (options.isEmpty() && !savedRow) Modifier.focusRequester(first) else Modifier), onClick = onSearchAll) { focused ->
                    Column {
                        LiveText("Search all my channels…", size = 15.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused))
                        LiveText("For a channel that wasn't found: it's used for this show from now on", size = 11.sp, color = focusedTextColor(focused).copy(alpha = 0.65f))
                    }
                }
            }
            if (options.isNotEmpty()) item {
                LiveText(
                    if (record) "OK records it on that channel"
                    else "OK watches it (or records it, if it hasn't started there) · Hold OK to record",
                    size = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** A small on/off switch. */
@Composable
internal fun SwitchMark(on: Boolean) {
    Box(
        Modifier.size(width = 34.dp, height = 19.dp).clip(RoundedCornerShape(50))
            .background(if (on) Color(0xFF2E7D4F) else Color.White.copy(alpha = 0.22f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.padding(2.dp).size(15.dp).clip(RoundedCornerShape(50)).background(Color.White)) }
}

/**
 * Type part of a channel's name and pick it: that channel is used for the show from now on
 * (like Fix Channel in Sports), whatever the guide calls the show.
 */
@Composable
internal fun ShowChannelPicker(
    title: String,
    initial: String,
    channels: List<com.nuvio.tv.livetv.model.LiveChannel>,
    hasPick: Boolean,
    onPick: (com.nuvio.tv.livetv.model.LiveChannel?) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initial) }
    fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    val list = remember(q, channels) {
        val n = norm(q)
        if (n.length < 2) emptyList() else channels.asSequence().filter { norm(it.name).contains(n) }.take(80).toList()
    }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(80); runCatching { if (initial.isBlank()) field.requestFocus() else firstResult.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("THE CHANNEL FOR THIS SHOW", size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary)
        LiveText(title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText("Its episodes are watched and recorded on the channel you pick, whatever your guide calls them.",
            size = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        com.nuvio.tv.livetv.ui.LiveTextField(
            value = q, onValueChange = { q = it }, hint = "Type part of the channel's name", focusRequester = field,
            onDone = {
                keyboard?.hide()
                if (list.isNotEmpty()) runCatching { firstResult.requestFocus() }
            }
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (hasPick) item {
                LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = { onPick(null) }) { focused ->
                    LiveText("Use the guide's channels again", size = 15.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused))
                }
            }
            if (q.trim().length >= 2 && list.isEmpty()) item {
                LiveText("No channel names match.", size = 13.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(12.dp))
            }
            itemsIndexed(list) { i, ch ->
                LiveFocusRow(
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(firstResult) else Modifier),
                    onClick = { onPick(ch) }
                ) { focused ->
                    Box(Modifier.size(width = 52.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                        com.nuvio.tv.livetv.ui.ChannelLogo(ch.logo, 26.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        LiveText(ch.name, size = 15.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused))
                        LiveText(ch.sourceName, size = 11.sp, color = focusedTextColor(focused).copy(alpha = 0.65f))
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------- Live TV options outside the Calendar

/** Which of the Live TV pop-ups is open, for which episode. */
@androidx.compose.runtime.Stable
internal class LiveMenuState {
    var entry by mutableStateOf<CalendarEntry?>(null)
    var menu by mutableStateOf(false)
    /** The channel list is open (true: to record, false: to watch). */
    var picker by mutableStateOf<Boolean?>(null)
    var search by mutableStateOf(false)
}

@Composable
internal fun rememberLiveMenuState(): LiveMenuState = remember { LiveMenuState() }

private fun entryPointOf(context: android.content.Context): CalendarEntryPoint? = runCatching {
    dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java)
}.getOrNull()

/**
 * Record or watch [e] on the channel to use: the one saved for the show or its network, else
 * (watching) the only one showing it. Recording with nothing saved, or [forceList], opens the
 * channel list, so you always see which channel it is. Looks in the guide again first.
 */
internal fun liveAct(
    repo: CalendarRepository,
    entryPoint: CalendarEntryPoint,
    state: LiveMenuState,
    scope: kotlinx.coroutines.CoroutineScope,
    e: CalendarEntry,
    record: Boolean,
    forceList: Boolean = false
) {
    scope.launch {
        val f = repo.rematch(e)
        state.entry = f
        if (forceList) { state.picker = record; return@launch }
        val opts = repo.channelsOf(f)
        val now = System.currentTimeMillis()
        if (record) opts.firstOrNull { repo.isRecording(it) }?.let { LiveTvNotice.show(repo.record(f, it)); return@launch }
        val usable = if (record) opts.filter { it.stopMs > now } else opts.filter { now in it.startMs until it.stopMs }
        val saved = opts.firstOrNull { it.mine }?.channelKey ?: repo.savedKey(f)
        val direct = usable.firstOrNull { it.channelKey == saved } ?: if (!record) usable.singleOrNull() else null
        if (direct == null) { state.picker = record; return@launch }
        if (record) LiveTvNotice.show(repo.record(f, direct))
        else repo.channelOf(direct)?.let { entryPoint.liveTvPlayback().play(it); CalendarNav.openLiveTv() }
    }
}

/** "S2 · E5 · Tonight 10:00 PM · Comedy Central". */
internal fun liveSubtitle(e: CalendarEntry, now: Long, use24h: Boolean): String = listOfNotNull(
    e.episodeCode,
    e.airMs?.takeIf { !e.streaming }?.let { whenLabel(it, now, use24h).replaceFirstChar { c -> c.uppercase() } },
    e.guide?.channelName ?: e.network
).joinToString(" · ")

/** The Live TV menu, channel list and channel search for one episode, on a show's page. */
@Composable
internal fun LiveMenuHost(state: LiveMenuState, kicker: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entryPoint = remember { entryPointOf(context) } ?: return
    val repo = remember { entryPoint.calendarRepository() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Changes (recordings, plans, reminders, picks) redraw it.
    repo.plans.collectAsState().value
    repo.reminders.collectAsState().value
    repo.channelPicks.collectAsState().value
    val e = state.entry ?: return
    val now = System.currentTimeMillis()
    val use24h = false
    if (state.menu) {
        val close = { state.menu = false }
        val st = repo.liveStatus(e)
        val mine = repo.showChannel(e.contentId)?.let { k -> repo.channelsForPicker().firstOrNull { it.key == k }?.name }
        val h = LiveMenuHandlers(
            watch = { close(); liveAct(repo, entryPoint, state, scope, e, record = false) },
            fromStart = {
                close()
                val replay = repo.channelsOf(e).firstOrNull { it.catchup && it.startMs <= System.currentTimeMillis() }
                if (replay != null && repo.channelOf(replay) != null) {
                    com.nuvio.tv.livetv.sports.SportsLaunch.catchup(
                        com.nuvio.tv.livetv.sports.SportsLaunch.Catchup(
                            replay.channelKey,
                            com.nuvio.tv.livetv.model.EpgProgram(startMs = replay.startMs, stopMs = replay.stopMs, title = replay.title.ifBlank { e.title })
                        )
                    )
                    CalendarNav.openLiveTv()
                } else LiveTvNotice.show("This channel can't replay it from the start")
            },
            record = { close(); liveAct(repo, entryPoint, state, scope, e, record = true) },
            series = { close(); scope.launch { LiveTvNotice.show(repo.toggleSeries(e)) } },
            remind = { close(); repo.bestHit(e)?.let { hit -> scope.launch { LiveTvNotice.show(repo.toggleReminder(e, hit)) } } },
            choose = { close(); liveAct(repo, entryPoint, state, scope, e, record = repo.channelsOf(e).none { now in it.startMs until it.stopMs }, forceList = true) },
            waitRecord = { close(); scope.launch { LiveTvNotice.show(repo.toggleWait(e, CalendarPlan.RECORD)) } },
            waitRemind = { close(); scope.launch { LiveTvNotice.show(repo.toggleWait(e, CalendarPlan.REMIND)) } },
            setChannel = { close(); state.search = true },
            myChannel = mine
        )
        CalendarMenu(
            kicker = kicker,
            title = e.title,
            subtitle = liveSubtitle(e, now, use24h),
            status = st,
            actions = liveActions(e, st, repo.channelsOf(e), repo.bestHit(e), now, use24h, repo.canWaitForGuide(e), h),
            onDismiss = close
        )
    }
    state.picker?.let { record ->
        val saved = repo.savedChoice(e)
        ChannelPicker(
            entry = e,
            record = record,
            options = repo.channelsOf(e).filter { it.stopMs > now || it.catchup },
            now = now,
            use24h = use24h,
            pickedKey = repo.savedKey(e),
            isRecording = { repo.isRecording(it) },
            onDismiss = { state.picker = null },
            onPick = { hit, always, holdRecord ->
                state.picker = null
                scope.launch {
                    val had = repo.savedKey(e)
                    if (always && had != hit.channelKey) repo.setAlways(e, hit)
                    else if (!always && had != null) repo.setAlways(e, null)
                }
                if (holdRecord || record || System.currentTimeMillis() !in hit.startMs until hit.stopMs) LiveTvNotice.show(repo.record(e, hit))
                else repo.channelOf(hit)?.let { entryPoint.liveTvPlayback().play(it); CalendarNav.openLiveTv() }
            },
            label = { repo.channelLabel(it) },
            savedText = saved?.first,
            onForget = if (saved == null) null else ({
                state.picker = null
                scope.launch {
                    repo.forgetSaved(saved.second)
                    state.entry = repo.rematch(e)
                    LiveTvNotice.show("Forgotten: the Calendar picks the channel itself again")
                }
            }),
            onSearchAll = { state.picker = null; state.search = true }
        )
    }
    if (state.search) {
        ShowChannelPicker(
            title = e.title,
            initial = e.network.orEmpty(),
            channels = repo.channelsForPicker(),
            hasPick = repo.showChannel(e.contentId) != null,
            onPick = { ch ->
                state.search = false
                scope.launch {
                    repo.setShowChannel(e.contentId, ch?.key)
                    state.entry = repo.rematch(e)
                    LiveTvNotice.show(if (ch != null) "${e.title} uses ${ch.name} from now on" else "${e.title} uses the channels found in your guide again")
                }
            },
            onDismiss = { state.search = false }
        )
    }
}

/** Settings → Calendar → Saved Channels: every channel you told the Calendar to use, OK forgets one. */
@Composable
fun CalendarSavedChannelsDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { entryPointOf(context)?.calendarRepository() } ?: run { onDismiss(); return }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val picks by repo.channelPicks.collectAsState()
    val list = remember(picks) { repo.savedChannels() }
    val first = remember { FocusRequester() }
    LaunchedEffect(list.size) { delay(80); runCatching { first.requestFocus() } }
    LiveDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("Saved Channels", size = 21.sp, weight = FontWeight.Bold)
        LiveText("Channels you told the Calendar to use, for a network or for one show. OK forgets one: the Calendar picks the channel itself again.",
            size = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 3)
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            LiveText("Nothing saved.", size = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(12.dp))
            com.nuvio.tv.livetv.ui.MenuItem("Close", Modifier.focusRequester(first)) { onDismiss() }
        } else {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(list, key = { _, c -> c.pickKey }) { i, c ->
                    LiveFocusRow(
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        onClick = { scope.launch { repo.forgetSaved(c.pickKey) } }
                    ) { focused ->
                        Column(Modifier.weight(1f)) {
                            LiveText(if (c.forShow) c.label else "${c.label} (network)", size = 15.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused))
                            LiveText(c.channel, size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                        }
                        LiveText(if (focused) "OK: forget" else "", size = 11.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}
