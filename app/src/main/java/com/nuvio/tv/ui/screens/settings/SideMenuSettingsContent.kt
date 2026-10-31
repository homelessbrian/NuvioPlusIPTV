package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.calendar.CalendarEntryPoint
import com.nuvio.tv.livetv.calendar.SideMenuPages
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/** One page's row in Menu & Features: a switch, or a choice when [value] is set. */
private data class PageRow(val title: String, val subtitle: String, val on: Boolean, val value: String? = null, val onClick: () -> Unit)

/**
 * Settings → Menu & Features: the one place that decides which pages are in the app (each
 * switch turns its whole feature on or off, not just its menu entry), their order in the side
 * menu, and the page the app opens on. Settings is always there, at the bottom.
 */
@Composable
fun SideMenuSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java) }
    val prefs = remember { entry.liveTvPreferences() }
    val sports = remember { entry.sportsRepository() }
    val calendar = remember { entry.calendarRepository() }
    val layout = remember { entry.layoutPreferences() }
    val onDemand = remember { entry.onDemandRepository() }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = LiveTvSettings())
    val sportsSettings by sports.settings.collectAsStateWithLifecycle()
    val follows by calendar.follows.collectAsStateWithLifecycle()
    val discover by layout.discoverLocation.collectAsStateWithLifecycle(initialValue = DiscoverLocation.IN_SEARCH)
    val onDemandHas by onDemand.hasContent.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(t: (LiveTvSettings) -> LiveTvSettings) = scope.launch { prefs.updateSettings(t) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val order = SideMenuPages.order(s.sideMenuOrder)
    val hidden = SideMenuPages.hidden(s.sideMenuHidden)
    val startRoute = com.nuvio.tv.livetv.startup.StartPage.routeFor(s, sportsSettings.showInSidebar)
    fun move(id: String, by: Int) {
        val list = order.toMutableList()
        val i = list.indexOf(id)
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list.removeAt(i)
        list.add(j, id)
        update { it.copy(sideMenuOrder = list.joinToString(",")) }
    }
    fun setHidden(id: String, off: Boolean) = update {
        val now = SideMenuPages.hidden(it.sideMenuHidden)
        it.copy(sideMenuHidden = (if (off) now + id else now - id).joinToString(","))
    }

    // What each page's row says and does.
    fun rowFor(id: String): PageRow = when (id) {
        SideMenuPages.HOME -> {
            val off = id in hidden && startRoute != null
            PageRow("Home", if (startRoute == null) "Continue Watching, new-episode cards and your rows. To take Home off the menu, open the app on another page first (above)."
                else "Continue Watching, new-episode cards and your rows. Off: Home leaves the menu, and anything that would go back to Home goes to the page the app opens on.",
                !off) {
                if (!off && startRoute == null) {
                    LiveTvNotice.show("Pick another page in Open the App On first")
                    dialog = "start"
                } else setHidden(id, !off)
            }
        }
        SideMenuPages.SEARCH -> PageRow("Search", "Search movies, shows, channels and On Demand", id !in hidden) { setHidden(id, id !in hidden) }
        SideMenuPages.LIBRARY -> PageRow("Library", "Your saved movies and shows", id !in hidden) { setHidden(id, id !in hidden) }
        SideMenuPages.DISCOVER -> PageRow(
            "Discover", "Browse by genre, network and more", discover != DiscoverLocation.OFF,
            value = when (discover) { DiscoverLocation.IN_SIDEBAR -> "In the menu"; DiscoverLocation.IN_SEARCH -> "Inside Search"; DiscoverLocation.OFF -> "Off" }
        ) { dialog = "discover" }
        SideMenuPages.CALENDAR -> {
            val mode = when { id in hidden -> "OFF"; s.calendarInMenu -> "ALWAYS"; else -> "BELL" }
            PageRow(
                "Calendar",
                when (mode) {
                    "OFF" -> "Off: no bells on show pages, no new-episode cards on Home"
                    "ALWAYS" -> "Release dates of your shows and movies, bells on show pages and new-episode cards on Home"
                    else -> "Shows up in the menu by itself once your shows (watch history, Library or a bell) have episodes coming"
                },
                mode != "OFF",
                value = when (mode) { "OFF" -> "Off"; "ALWAYS" -> "Always in the menu"; else -> "Automatic" }
            ) { dialog = "calendar" }
        }
        SideMenuPages.LIVE_TV -> PageRow("Live TV", "The guide, Recordings, the Live TV row on Home and channels in Search", s.showInSidebar) {
            update { it.copy(showInSidebar = !it.showInSidebar) }
        }
        SideMenuPages.SPORTS -> PageRow("Sports", "The Sports page, game reminders and scores on the info bar", sportsSettings.showInSidebar) {
            sports.updateSettings { it.copy(showInSidebar = !it.showInSidebar) }
        }
        SideMenuPages.ON_DEMAND -> PageRow(
            "On Demand",
            if (onDemandHas) "Your provider's movies and series: the page, in Search and in the stream list"
            else "Your provider's movies and series. Shows up once they're imported (Settings → Live TV → On Demand)",
            s.onDemandInSidebar
        ) { update { it.copy(onDemandInSidebar = !it.onDemandInSidebar) } }
        SideMenuPages.RECORDINGS -> PageRow(
            "Recordings",
            if (s.recordingsInSidebar) "In the menu once anything is recorded or set to record. Always in Live TV too (● by the guide's ⚙)"
            else "Off the menu only: recording still works, and Recordings is always in Live TV (● by the guide's ⚙)",
            s.recordingsInSidebar
        ) { update { it.copy(recordingsInSidebar = !it.recordingsInSidebar) } }
        else -> PageRow(id, "", true) {}
    }

    val startChoices = buildList {
        add("HOME" to "Home")
        if (s.showInSidebar) add("LIVE_TV" to "Live TV")
        if (sportsSettings.showInSidebar) add("SPORTS" to "Sports")
        if (s.onDemandInSidebar && onDemandHas) add("ON_DEMAND" to "On Demand")
        if (SideMenuPages.CALENDAR !in hidden) add("CALENDAR" to "Calendar")
        if (SideMenuPages.LIBRARY !in hidden) add("LIBRARY" to "Library")
        if (SideMenuPages.SEARCH !in hidden) add("SEARCH" to "Search")
    }
    val startLabel = if (startRoute == null) "Home" else startChoices.firstOrNull { it.first == s.openOn }?.second ?: "Home"

    val first = initialFocusRequester ?: remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsDetailHeader(
            title = "Menu & Features",
            subtitle = "What's in the app, its order in the side menu, and the page it opens on. A page that's off is off everywhere."
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "start") {
                SettingsGroupCard(title = "When the App Starts") {
                    SettingsActionRow(
                        title = "Open the App On",
                        subtitle = "The app opens straight on this page; Back opens the menu as usual. Takes effect the next time the app starts.",
                        value = startLabel,
                        onClick = { dialog = "start" },
                        modifier = Modifier.focusRequester(first)
                    )
                }
            }
            item(key = "pages") {
                SettingsGroupCard(title = "Pages", subtitle = "OK turns a page on or off. The arrows move it up or down the menu. Settings always stays at the bottom.") {
                    order.forEachIndexed { index, id ->
                        val r = rowFor(id)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (r.value != null) {
                                SettingsActionRow(
                                    title = r.title,
                                    subtitle = r.subtitle,
                                    value = r.value,
                                    onClick = r.onClick,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                SettingsToggleRow(
                                    title = r.title,
                                    subtitle = r.subtitle,
                                    checked = r.on,
                                    onToggle = r.onClick,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            ArrowButton(up = true, enabled = index > 0) { move(id, -1) }
                            ArrowButton(up = false, enabled = index < order.lastIndex) { move(id, 1) }
                        }
                    }
                }
            }
            item(key = "reset") {
                SettingsGroupCard {
                    SettingsActionRow(
                        title = "Put Back the Usual Order",
                        subtitle = "Home, Discover, Search, Library, Calendar, Live TV, Sports, On Demand, Recordings",
                        onClick = {
                            update { it.copy(sideMenuOrder = "") }
                            LiveTvNotice.show("Menu order put back")
                        }
                    )
                }
            }
        }
    }

    when (dialog) {
        "start" -> SettingsSingleChoiceDialog(
            title = "Open the App On",
            options = startChoices.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = if (startRoute == null) "HOME" else s.openOn,
            onOptionSelected = { v ->
                update {
                    // Back to Home: Home goes back in the menu too.
                    if (v == "HOME") it.copy(openOn = v, sideMenuHidden = (SideMenuPages.hidden(it.sideMenuHidden) - SideMenuPages.HOME).joinToString(","))
                    else it.copy(openOn = v)
                }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        "discover" -> SettingsSingleChoiceDialog(
            title = "Discover",
            options = listOf(
                SettingsPickerOption(DiscoverLocation.IN_SIDEBAR, "In the menu", "Its own page in the side menu"),
                SettingsPickerOption(DiscoverLocation.IN_SEARCH, "Inside Search", "A tab on the Search page"),
                SettingsPickerOption(DiscoverLocation.OFF, "Off", null)
            ),
            selectedValue = discover,
            onOptionSelected = { v -> scope.launch { layout.setDiscoverLocation(v) }; dialog = null },
            onDismiss = { dialog = null }
        )
        "calendar" -> SettingsSingleChoiceDialog(
            title = "Calendar",
            options = listOf(
                SettingsPickerOption("BELL", "Automatic", "In the menu once your shows have episodes coming"),
                SettingsPickerOption("ALWAYS", "Always in the menu", "Including Airing Today, before you follow anything"),
                SettingsPickerOption("OFF", "Off", "No Calendar, no bells, no new-episode cards on Home")
            ),
            selectedValue = when { SideMenuPages.CALENDAR in hidden -> "OFF"; s.calendarInMenu -> "ALWAYS"; else -> "BELL" },
            onOptionSelected = { v ->
                update {
                    val h = SideMenuPages.hidden(it.sideMenuHidden)
                    it.copy(
                        calendarInMenu = v == "ALWAYS",
                        sideMenuHidden = (if (v == "OFF") h + SideMenuPages.CALENDAR else h - SideMenuPages.CALENDAR).joinToString(",")
                    )
                }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArrowButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.White.copy(alpha = 0.08f))
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(46.dp)
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (up) "Move up" else "Move down",
            tint = focusedTextColor(focused).copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Settings → Calendar. */
@Composable
fun CalendarSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java) }
    val prefs = remember { entry.liveTvPreferences() }
    val calendar = remember { entry.calendarRepository() }
    val s by prefs.settings.collectAsStateWithLifecycle(initialValue = LiveTvSettings())
    val follows by calendar.follows.collectAsStateWithLifecycle()
    val hiddenShows by calendar.hiddenShows.collectAsStateWithLifecycle()
    val state by calendar.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun update(t: (LiveTvSettings) -> LiveTvSettings) = scope.launch { prefs.updateSettings(t) }
    val countries = listOf(
        "US" to "United States", "CA" to "Canada", "GB" to "United Kingdom", "IE" to "Ireland", "AU" to "Australia",
        "NZ" to "New Zealand", "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "NL" to "Netherlands",
        "BE" to "Belgium", "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "BR" to "Brazil",
        "MX" to "Mexico", "AR" to "Argentina", "IN" to "India", "JP" to "Japan", "KR" to "South Korea", "ZA" to "South Africa"
    )
    val first = initialFocusRequester ?: remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsDetailHeader(
            title = "Calendar",
            subtitle = "When your shows' new episodes and your movies come out (shows whose bell you rang, your Library, and what you're watching)"
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "home") {
                SettingsGroupCard(title = "On Home") {
                    SettingsToggleRow(
                        "New Episode Cards",
                        "A card in Continue Watching when a new episode of a show whose bell you rang is out. OK finds its sources.",
                        s.calendarNewEpisodeCards,
                        { update { it.copy(calendarNewEpisodeCards = !it.calendarNewEpisodeCards) } },
                        modifier = Modifier.focusRequester(first)
                    )
                    SettingsToggleRow(
                        "Your Shows in the Upcoming Row",
                        "The next episodes of your shows in the coming week, in Home's Upcoming row",
                        s.calendarUpcomingRow,
                        { update { it.copy(calendarUpcomingRow = !it.calendarUpcomingRow) } }
                    )
                }
            }
            item(key = "page") {
                SettingsGroupCard(title = "The Calendar") {
                    SettingsToggleRow(
                        "Games for Teams You Follow",
                        "Your Sports teams' games, with the channel showing them (not whole leagues). Scores follow Hide Scores in Sports settings",
                        s.calendarSports,
                        { update { it.copy(calendarSports = !it.calendarSports) } }
                    )
                    SettingsToggleRow(
                        "Hide Spoilers",
                        "Episodes past the next one you'd watch don't show their titles or pictures",
                        s.calendarHideSpoilers,
                        { update { it.copy(calendarHideSpoilers = !it.calendarHideSpoilers) } }
                    )
                    SettingsActionRow(
                        title = "Opens In",
                        subtitle = "The list by day, a week, or a month at a time",
                        value = when (s.calendarView) { "MONTH" -> "Month"; "WEEK" -> "Week"; else -> "List" },
                        onClick = {
                            update {
                                it.copy(calendarView = when (it.calendarView) { "MONTH" -> "LIST"; "WEEK" -> "MONTH"; else -> "WEEK" })
                            }
                        }
                    )
                    SettingsActionRow(
                        title = "Picking an Episode in Your Guide",
                        subtitle = "OK on an episode that's in your Live TV guide opens its Live TV options (watch, record, remind) or the show's page",
                        value = if (s.calendarOkAction == "PAGE") "Show page" else "Live TV options",
                        onClick = { update { it.copy(calendarOkAction = if (it.calendarOkAction == "PAGE") "LIVE" else "PAGE") } }
                    )
                    var savedOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    SettingsActionRow(
                        title = "Saved Channels",
                        subtitle = "Channels you told the Calendar to use, for a network or for one show: change or forget them",
                        value = "Open",
                        onClick = { savedOpen = true }
                    )
                    if (savedOpen) com.nuvio.tv.livetv.calendar.CalendarSavedChannelsDialog(onDismiss = { savedOpen = false })
                    SettingsActionRow(
                        title = "Your Country",
                        subtitle = "For Airing Today and movie release dates",
                        value = countries.firstOrNull { it.first == s.calendarCountry }?.second ?: s.calendarCountry,
                        onClick = {
                            val i = countries.indexOfFirst { it.first == s.calendarCountry }
                            update { it.copy(calendarCountry = countries[(i + 1) % countries.size].first) }
                        }
                    )
                    SettingsToggleRow(
                        "Airing Today: Leave Out News and Talk Shows",
                        "Also sports and panel shows",
                        s.calendarSkipNewsTalk,
                        { update { it.copy(calendarSkipNewsTalk = !it.calendarSkipNewsTalk) } }
                    )
                }
            }
            item(key = "data") {
                SettingsGroupCard(title = "Your Shows") {
                    SettingsActionRow(
                        title = "Look Up All Shows Again",
                        subtitle = if (state.loading) "Looking up…" else
                            "${state.showCount} shows and movies on your Calendar · ${follows.size} bell${if (follows.size == 1) "" else "s"} rung" +
                                if (hiddenShows.isNotEmpty()) " · ${hiddenShows.size} hidden" else "",
                        onClick = {
                            calendar.refresh(force = true)
                            LiveTvNotice.show("Looking up your shows again")
                        }
                    )
                }
            }
        }
    }
}
