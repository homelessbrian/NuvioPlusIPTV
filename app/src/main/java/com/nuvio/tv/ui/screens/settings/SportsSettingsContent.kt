package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.sports.ChannelLink
import com.nuvio.tv.livetv.sports.ChannelRegions
import com.nuvio.tv.livetv.sports.League
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsScoreDelay
import com.nuvio.tv.livetv.sports.SportsSettings
import com.nuvio.tv.livetv.sports.SportsViewModel
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private val REMIND_TIMES = listOf(0, 5, 10, 15, 30, 60)

private fun remindLabel(m: Int): String = if (m <= 0) "When it starts" else "$m minutes before"

/** Sports, in Nuvio's settings (next to Live TV). */
@Composable
fun SportsSettingsContent(
    onOpenTeams: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null,
    viewModel: SportsViewModel = hiltViewModel()
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val f by viewModel.follows.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    fun update(t: (SportsSettings) -> SportsSettings) = viewModel.repo.updateSettings(t)
    val region = SportsCatalog.regionOf(s)
    val shownCount = SportsCatalog.leagues.count { SportsCatalog.shown(it, s) }
    val links = s.networkChannels.size + s.teamChannels.size + s.leagueChannels.size + s.eventChannels.size
    val fixes = links + s.blocked.size

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = "Sports",
            subtitle = "Scores and schedules, and which of your channels show each game"
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            if (!s.showInSidebar) item(key = "off") {
                SettingsGroupCard {
                    SettingsActionRow(
                        title = "Sports Is Turned Off",
                        subtitle = "The Sports page, game reminders and scores on the info bar are off. It's switched on and off in Settings → Menu & Features.",
                        value = "Turn On",
                        onClick = { update { it.copy(showInSidebar = true) } }
                    )
                }
            }
            item(key = "teams") {
                SettingsGroupCard(title = "Your Teams") {
                    val names = (f.teams.map { it.name } + f.pins.mapNotNull { SportsCatalog.league(it)?.name } + f.words.map { "\"$it\"" })
                    SettingsActionRow(
                        title = "Teams and Leagues I Follow",
                        subtitle = if (names.isEmpty()) "Nothing yet. Follow teams and leagues to see them on My Sports."
                        else names.take(6).joinToString(", ") + if (names.size > 6) " and ${names.size - 6} more" else "",
                        value = if (names.isEmpty()) null else names.size.toString(),
                        leadingIcon = Icons.Default.Star,
                        onClick = onOpenTeams,
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                    SettingsActionRow(
                        title = "Arrange My Sports",
                        subtitle = "The order of your teams' and leagues' rows on My Sports",
                        leadingIcon = Icons.Default.Tune,
                        onClick = { com.nuvio.tv.livetv.sports.SportsLaunch.openArrange(); onOpenTeams() }
                    )
                    SettingsActionRow(
                        title = "Your Region",
                        subtitle = "All Events shows your region's leagues, plus anything you follow",
                        value = SportsCatalog.regionName(region) + if (s.region.isBlank()) " (this TV)" else "",
                        leadingIcon = Icons.Default.Public,
                        onClick = { dialog = "region" }
                    )
                }
            }
            item(key = "watching") {
                SettingsGroupCard(title = "Watching") {
                    SettingsToggleRow(
                        "Hide Scores",
                        "For when you're watching on catch-up or later. Games show who's playing and when, but not the score",
                        s.hideScores,
                        { update { it.copy(hideScores = !it.hideScores) } }
                    )
                    if (!s.hideScores) {
                        SettingsActionRow(
                            title = "Score Delay",
                            subtitle = "IPTV runs behind the live game. Scores, game clocks, results and scoring alerts wait this long so they don't show before you see it",
                            value = SportsScoreDelay.label(s.scoreDelaySeconds),
                            leadingIcon = Icons.Default.Timer,
                            onClick = { dialog = "scoreDelay" }
                        )
                    }
                    SettingsToggleRow(
                        "Score on the Info Bar",
                        "Shows the live score on Live TV's info bar while you watch a game",
                        s.scoreOnInfoBar,
                        { update { it.copy(scoreOnInfoBar = !it.scoreOnInfoBar) } }
                    )
                    SettingsToggleRow(
                        "Pinned Scoreboards",
                        "Pin up to 3 live games (from the info bar or a game's page) and their scores stay in the top-left corner while you watch. Off: the pin button is hidden",
                        s.pinnedScores,
                        { update { it.copy(pinnedScores = !it.pinnedScores) } }
                    )
                    SettingsToggleRow(
                        "Video Window in Sports",
                        "Games play in a window on the Sports page while you browse. Off: games open full screen, and the page uses the whole width",
                        s.videoWindow,
                        { update { it.copy(videoWindow = !it.videoWindow) } }
                    )
                    SettingsToggleRow(
                        "Only Games on My Channels",
                        "Leaves out games none of your channels show. Games of teams you follow always show",
                        s.onlyOnMyChannels,
                        { update { it.copy(onlyOnMyChannels = !it.onlyOnMyChannels) } }
                    )
                    SettingsToggleRow(
                        "Best Channel First",
                        "Puts 4K and FHD channels at the top of each game's list, then the rest",
                        s.bestChannelFirst,
                        { update { it.copy(bestChannelFirst = !it.bestChannelFirst) } }
                    )
                }
            }
            item(key = "reminders") {
                SettingsGroupCard(title = "Reminders") {
                    SettingsToggleRow(
                        "Remind Me About My Teams",
                        "A message before your teams play, with a button to watch. Games you set a reminder on are always reminded",
                        s.remindMyTeams,
                        { update { it.copy(remindMyTeams = !it.remindMyTeams) } }
                    )
                    SettingsActionRow(
                        title = "How Early",
                        subtitle = "When the reminder comes up",
                        value = remindLabel(s.remindMinutes),
                        leadingIcon = Icons.Default.Notifications,
                        onClick = { dialog = "remind" }
                    )
                    SettingsToggleRow(
                        "Switch to the Game Automatically",
                        "When a reminder goes off, Nuvio switches to the game a minute before it starts (with 10 seconds to cancel). Only while Nuvio is open. Each game's own choice on its page comes first",
                        s.autoSwitch,
                        { update { it.copy(autoSwitch = !it.autoSwitch) } }
                    )
                    SettingsToggleRow(
                        "Scoring Alerts for My Teams",
                        "A small card in the corner when one of your teams scores, wherever you are in the app",
                        s.scoringAlerts,
                        { update { it.copy(scoringAlerts = !it.scoringAlerts) } }
                    )
                }
            }
            item(key = "channels") {
                SettingsGroupCard(title = "Channels") {
                    SettingsActionRow(
                        title = "Find Channels Again",
                        subtitle = "Looks through your channels and guide for today's games now",
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.repo.refreshNow(); LiveTvNotice.show("Updating Sports…") }
                    )
                    SettingsActionRow(
                        title = "Channels From",
                        subtitle = "Each game lists only this region's (or language's) channels, with the others one click away. " +
                            "Also on each game's page",
                        value = ChannelRegions.shortName(s.channelRegion),
                        leadingIcon = Icons.Default.Public,
                        onClick = { dialog = "channelRegion" }
                    )
                    SettingsActionRow(
                        title = "Preferred Channel Groups",
                        subtitle = "Channels in these groups go to the top of every game's list (your UK sports channels, say). " +
                            "Without any, channels from your region come first",
                        value = if (s.preferredGroups.isEmpty()) "None" else s.preferredGroups.size.toString(),
                        leadingIcon = Icons.Default.Star,
                        onClick = { dialog = "groups" }
                    )
                    SettingsActionRow(
                        title = "Your Channel Links",
                        subtitle = "Channels you picked with Fix Channel for a network, a team, a league or one game. See them, or remove one",
                        value = if (links == 0) "None" else links.toString(),
                        leadingIcon = Icons.Default.LiveTv,
                        onClick = { dialog = "links" }
                    )
                    SettingsActionRow(
                        title = "Forget My Channel Fixes",
                        subtitle = "All channels you picked (or took off) with Fix Channel, including the ones used for whole networks",
                        value = if (fixes == 0) "None" else fixes.toString(),
                        leadingIcon = Icons.Default.Refresh,
                        enabled = fixes > 0,
                        onClick = { dialog = "clear" }
                    )
                }
            }
            item(key = "about") {
                Text(
                    text = "Scores, schedules, team logos and colors come from ESPN's free public feed. " +
                        "Some event dates come from sporting-events.org, and the wrestling schedule and logos from TheSportsDB. " +
                        "Darts, snooker, rugby and other sports they don't cover are found in your own TV guide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.lg)
                )
            }
        }
    }

    val close: () -> Unit = { dialog = null }
    when (dialog) {
        "region" -> SettingsSingleChoiceDialog(
            title = "Your region",
            options = listOf(SettingsPickerOption("", "Same as this TV (${SportsCatalog.regionName(SportsCatalog.deviceRegion())})")) +
                SportsCatalog.regions.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.region,
            onOptionSelected = { v -> update { it.copy(region = v) }; close() },
            onDismiss = close
        )
        "channelRegion" -> SettingsSingleChoiceDialog(
            title = "Channels from",
            options = ChannelRegions.all.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.channelRegion,
            onOptionSelected = { v -> update { it.copy(channelRegion = v) }; close() },
            onDismiss = close
        )
        "scoreDelay" -> SettingsSingleChoiceDialog(
            title = "Score Delay",
            options = SportsScoreDelay.CHOICES.map { SettingsPickerOption(it, SportsScoreDelay.label(it)) },
            selectedValue = s.scoreDelaySeconds,
            onOptionSelected = { v -> update { it.copy(scoreDelaySeconds = v) }; close() },
            onDismiss = close
        )
        "remind" -> SettingsSingleChoiceDialog(
            title = "Remind me",
            options = REMIND_TIMES.map { SettingsPickerOption(it, remindLabel(it)) },
            selectedValue = s.remindMinutes,
            onOptionSelected = { v -> update { it.copy(remindMinutes = v) }; close() },
            onDismiss = close
        )
        "groups" -> GroupsDialog(
            viewModel = viewModel,
            chosen = s.preferredGroups,
            onToggle = { g ->
                val key = g.lowercase().trim()
                update { cur -> cur.copy(preferredGroups = if (key in cur.preferredGroups) cur.preferredGroups - key else cur.preferredGroups + key) }
            },
            onDismiss = close
        )
        "leagues" -> LeaguesDialog(s, onToggle = { l -> update { cur -> SportsCatalog.toggleShown(l, cur) } }, onDismiss = close)
        "links" -> LinksDialog(s, viewModel, onForgetAll = { dialog = "clear" }, onDismiss = close)
        "clear" -> {
            val first = remember { FocusRequester() }
            LiveDialog(onDismiss = close, width = 480.dp) {
                LiveText("Forget your channel fixes?", size = 20.sp, weight = FontWeight.Bold)
                LiveText(
                    "Sports goes back to finding channels by itself. Your teams and settings stay.",
                    color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3
                )
                Spacer(Modifier.height(12.dp))
                MenuItem("Forget Them", Modifier.focusRequester(first)) { viewModel.repo.clearFixes(); close() }
                MenuItem("Cancel") { close() }
            }
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        }
    }
}

@Composable
private fun LeaguesDialog(s: SportsSettings, onToggle: (League) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val leagues = remember(s.region) { SportsCatalog.ordered(s) }
    LiveDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("Leagues on the Sports page", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "OK turns one on or off. Leagues you follow a team in always show.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(leagues, key = { it.id }) { l ->
                val on = SportsCatalog.shown(l, s)
                LiveFocusRow(
                    modifier = (if (l == leagues.first()) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    onClick = { onToggle(l) }
                ) { focused ->
                    LiveText(if (on) "✓" else " ", color = focusedTextColor(focused), size = 16.sp, weight = FontWeight.Bold)
                    LiveText("${l.sport.icon}  ${l.name}", color = focusedTextColor(focused), size = 15.sp, modifier = Modifier.weight(1f))
                    LiveText(
                        if (l.guideOnly) "From your guide" else l.sport.label,
                        color = if (focused) focusedTextColor(true) else NuvioTheme.colors.TextSecondary, size = 12.sp
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** Your channel groups (most channels first, sports-looking ones at the top); OK ticks one. */
@Composable
private fun GroupsDialog(viewModel: SportsViewModel, chosen: Set<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val groups = remember {
        viewModel.repo.channelsForPicker()
            .filter { it.group.isNotBlank() }
            .groupingBy { it.group }.eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.key.contains("sport", ignoreCase = true) }
                    .thenBy { it.key.lowercase() }
            )
            .map { it.key to it.value }
    }
    LiveDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("Preferred channel groups", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "OK ticks a group. Its channels go to the top of each game's list, above channels from elsewhere. Channels you picked with Fix Channel still come first.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3
        )
        Spacer(Modifier.height(10.dp))
        if (groups.isEmpty()) LiveText("No channel groups yet. Add a playlist in Live TV first.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(groups, key = { it.first }) { (g, n) ->
                val on = g.lowercase().trim() in chosen
                LiveFocusRow(
                    modifier = (if (g == groups.first().first) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    onClick = { onToggle(g) }
                ) { focused ->
                    LiveText(if (on) "✓" else " ", color = focusedTextColor(focused), size = 16.sp, weight = FontWeight.Bold)
                    LiveText(g, color = focusedTextColor(focused), size = 15.sp, modifier = Modifier.weight(1f))
                    LiveText("$n", color = if (focused) focusedTextColor(true) else NuvioTheme.colors.TextSecondary, size = 12.sp)
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** Your channel links, by kind; OK removes one. */
@Composable
private fun LinksDialog(s: SportsSettings, viewModel: SportsViewModel, onForgetAll: () -> Unit, onDismiss: () -> Unit) {
    val links = remember(s.networkChannels, s.teamChannels, s.leagueChannels, s.eventChannels) { viewModel.repo.channelLinks() }
    val requesters = remember(links) { links.map { FocusRequester() } }
    val forgetAll = remember { FocusRequester() }
    // Where focus goes after a removal (the row that took its place), or -1 on opening.
    var focusAt by remember { mutableStateOf(-1) }
    LiveDialog(onDismiss = onDismiss, width = 620.dp) {
        LiveText("Your channel links", size = 20.sp, weight = FontWeight.Bold)
        LiveText(
            "OK removes a link. Its games go back to finding their channel by themselves.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 2
        )
        Spacer(Modifier.height(10.dp))
        if (links.isEmpty()) LiveText(
            "No links yet. On a game, hold OK → Fix Channel, pick the right channel, then choose a network or a team to keep using it.",
            color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3
        )
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            links.forEachIndexed { i, l ->
                if (i == 0 || links[i - 1].kind != l.kind) item(key = "h:" + l.kind.name) {
                    LiveText(
                        l.kind.heading.uppercase(), color = NuvioTheme.colors.TextSecondary, size = 11.sp, weight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp)
                    )
                }
                item(key = l.kind.name + ":" + l.key) {
                    LiveFocusRow(
                        modifier = Modifier.focusRequester(requesters[i]).fillMaxWidth(),
                        onClick = {
                            viewModel.repo.removeLink(l)
                            LiveTvNotice.show("Removed: ${l.label}")
                            focusAt = i
                        }
                    ) { focused ->
                        LiveText(l.label, color = focusedTextColor(focused), size = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        LiveText(
                            l.channelName ?: "Channel no longer in your playlists",
                            color = if (focused) focusedTextColor(true) else NuvioTheme.colors.TextSecondary, size = 12.sp, maxLines = 1
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MenuItem("Forget All Channel Fixes", Modifier.focusRequester(forgetAll)) { onForgetAll() }
    }
    LaunchedEffect(links) {
        delay(80)
        val target = when {
            links.isEmpty() -> forgetAll
            focusAt < 0 -> requesters.first()
            else -> requesters[focusAt.coerceAtMost(links.size - 1)]
        }
        runCatching { target.requestFocus() }
    }
}
