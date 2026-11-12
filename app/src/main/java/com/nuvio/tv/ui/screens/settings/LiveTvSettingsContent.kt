package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.EpgSource
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.model.ZapMode
import com.nuvio.tv.livetv.ui.LiveTvSettingsViewModel
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.livetv.model.effectiveGuideSize
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.brandWordmarkResource
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Context.settingsLocale: java.util.Locale
    get() = resources.configuration.locales[0]

private val LIVE_TV_TITLE = R.string.iptv_settings_live_tv
private val LIVE_TV_SUBTITLE = R.string.iptv_settings_playlists_tv_guide_and_channel_options

/**
 * Live TV settings are one place in Settings with a row of tabs across the top of it (Setup,
 * Guide, Playback, Integration): [HUB] is that tabbed page. [ALL] is the old single
 * long list, kept for the guide's long-press menu. The others are the tabs themselves.
 */
internal enum class LiveTvPage(
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val subtitle: Int,
    @androidx.annotation.StringRes val tab: Int = 0
) {
    ALL(R.string.iptv_settings_live_tv, R.string.iptv_settings_playlists_tv_guide_and_channel_options),
    HUB(R.string.iptv_settings_live_tv, R.string.iptv_settings_playlists_tv_guide_and_channel_options),
    MAIN(R.string.iptv_settings_live_tv, R.string.iptv_settings_your_playlists_guides_channels_groups_and_parental_controls, R.string.iptv_settings_setup),
    GUIDE(R.string.iptv_settings_live_tv, R.string.iptv_settings_how_the_guide_looks_and_behaves_while_you, R.string.iptv_settings_guide),
    PLAYBACK(R.string.iptv_settings_live_tv, R.string.iptv_settings_picture_sound_pausing_and_recording, R.string.iptv_settings_playback),
    ON_DEMAND(R.string.iptv_settings_on_demand, R.string.iptv_settings_movies_and_shows_from_your_provider, R.string.iptv_settings_on_demand),
    INTEGRATION(R.string.iptv_settings_live_tv, R.string.iptv_settings_how_live_tv_shows_up_in_the_rest, R.string.iptv_settings_integration)
}

private val LIVE_TABS = listOf(LiveTvPage.MAIN, LiveTvPage.GUIDE, LiveTvPage.PLAYBACK, LiveTvPage.INTEGRATION)

/** The row of tabs across the top of Live TV settings; moving onto one shows it. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun LiveTabRow(selected: LiveTvPage, first: FocusRequester?, onSelect: (LiveTvPage) -> Unit) {
    val context = LocalContext.current
    // Coming up from the page below lands on the tab you're on, not whichever one is nearest.
    // Only the tab you're on can take focus from outside the row; once focus is in the row, all can.
    var rowHasFocus by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .onFocusChanged { rowHasFocus = it.hasFocus }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LIVE_TABS.forEach { tab ->
            var focused by remember { mutableStateOf(false) }
            val on = tab == selected
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
            Text(
                context.getString(tab.tab),
                color = if (focused) NuvioTheme.colors.OnSecondary else if (on) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .then(if (on && first != null) Modifier.focusRequester(first) else Modifier)
                    .focusProperties { canFocus = on || rowHasFocus }
                    .clip(shape)
                    .background(
                        when {
                            focused -> NuvioTheme.colors.Secondary
                            on -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)
                            else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)
                        }
                    )
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect(tab) }
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            )
        }
    }
}


/** On the four sub-tabs the sections sit open (there are only a few per tab), not folded. */
private val LocalLiveFlat = androidx.compose.runtime.compositionLocalOf { false }

/** Standalone version, opened from the guide's long-press menu. */
@Composable
fun LiveTvSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: LiveTvSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    SettingsStandaloneScaffold(title = context.getString(LIVE_TV_TITLE), subtitle = context.getString(LIVE_TV_SUBTITLE)) {
        LiveTvSettingsContent(viewModel = viewModel, page = LiveTvPage.ALL)
    }
}

/** Match Frame Rate's choices, worded as in Nuvio's own Playback settings. */
private fun frameRateModes(context: Context) = listOf(
    Triple("OFF", context.getString(R.string.playback_afr_off), context.getString(R.string.playback_afr_off_sub)),
    Triple("START", context.getString(R.string.playback_afr_on_start), context.getString(R.string.playback_afr_on_start_sub)),
    Triple("START_STOP", context.getString(R.string.playback_afr_on_start_stop), context.getString(R.string.playback_afr_on_start_stop_sub))
)

private fun clockModes(context: Context) = listOf(
    "always" to context.getString(R.string.iptv_settings_always),
    "panel" to context.getString(R.string.iptv_settings_with_player_controls),
    "never" to context.getString(R.string.iptv_settings_never)
)
private fun clockPositions(context: Context) = listOf(
    "top_left" to context.getString(R.string.iptv_settings_top_left),
    "top_right" to context.getString(R.string.iptv_settings_top_right),
    "bottom_left" to context.getString(R.string.iptv_settings_bottom_left),
    "bottom_right" to context.getString(R.string.iptv_settings_bottom_right)
)
private fun padLabel(context: Context, minutes: Int): String = if (minutes <= 0) context.getString(R.string.playback_afr_off) else context.resources.getQuantityString(R.plurals.iptv_settings_minutes_short, minutes, minutes)

private fun clockSizes(context: Context) = listOf("small" to context.getString(R.string.iptv_settings_small), "medium" to context.getString(R.string.iptv_settings_medium), "large" to context.getString(R.string.iptv_settings_large))

private sealed interface LiveDialog {
    data object AddM3u : LiveDialog
    data object AddXtream : LiveDialog
    data object AddStalker : LiveDialog
    data object AddEpg : LiveDialog
    data object AddFromPhone : LiveDialog
    data class PlaylistActions(val source: PlaylistSource) : LiveDialog
    data class CatchupZone(val source: PlaylistSource) : LiveDialog
    data class EditPlaylist(val source: PlaylistSource) : LiveDialog
    data class EpgActions(val source: EpgSource) : LiveDialog
    data class EditEpg(val source: EpgSource) : LiveDialog
    data object HiddenChannels : LiveDialog
    data object HiddenGroups : LiveDialog
    data class PlaylistGuideShift(val source: PlaylistSource) : LiveDialog
    data class PlaylistCatchupShift(val source: PlaylistSource) : LiveDialog
    data class EpgSourceShift(val source: EpgSource) : LiveDialog
    data object ClockMode : LiveDialog
    data object FrameRate : LiveDialog
    data object RecordPre : LiveDialog
    data object RecordPost : LiveDialog
    data object ClockPosition : LiveDialog
    data object ClockSize : LiveDialog
    data object ClockOpacity : LiveDialog
    data object ZapModeChoice : LiveDialog
    data object BannerTime : LiveDialog
    data object PlaylistRefresh : LiveDialog
    data object EpgRefresh : LiveDialog
    data object FutureDays : LiveDialog
    data object PastHours : LiveDialog
    data object SortChoice : LiveDialog
    data object HighlightChoice : LiveDialog
    data object CustomGroups : LiveDialog
    data object ChannelRow : LiveDialog
    data object ConfirmRestore : LiveDialog
    data object PinCheck : LiveDialog
    data object PinNew : LiveDialog
    data object PinRemove : LiveDialog
    data object Reminders : LiveDialog
    data object StartPage : LiveDialog
    data object NameEditor : LiveDialog
    data object PosterTest : LiveDialog
    data object ButtonTester : LiveDialog
    data object LoadReport : LiveDialog
    data object HomeRowSource : LiveDialog
    data object HomeRowTitle : LiveDialog
    data class ConfirmDeletePlaylist(val source: PlaylistSource) : LiveDialog
    data class ConfirmDeleteEpg(val source: EpgSource) : LiveDialog
    /** "Are you sure?" before a reset, clear or similar. */
    data class Confirm(val title: String, val message: String, val label: String, val action: () -> Unit) : LiveDialog
}

private val EPG_OFFSETS = (-48..48).map { it * 30 }
private val REFRESH_HOURS = listOf(3, 6, 12, 24, 48, 72, 168)
private val BANNER_SECONDS = listOf(3, 5, 8, 10, 15)
private val FUTURE_DAYS = listOf(1, 2, 3, 5, 7, 10, 14)
private val PAST_HOURS = listOf(6, 12, 24, 48, 72, 168)

/** Time zones offered for a playlist's catch-up (the common ones IPTV servers run on). */
private fun catchupZones(context: Context) = listOf(
    "UTC" to "UTC",
    "Europe/London" to context.getString(R.string.iptv_settings_uk_ireland_london),
    "Europe/Amsterdam" to context.getString(R.string.iptv_settings_central_europe_amsterdam_paris_berlin),
    "Europe/Athens" to context.getString(R.string.iptv_settings_eastern_europe_athens_bucharest),
    "Europe/Istanbul" to context.getString(R.string.iptv_settings_turkey_istanbul),
    "Europe/Moscow" to context.getString(R.string.iptv_settings_moscow),
    "Asia/Dubai" to context.getString(R.string.iptv_settings_gulf_dubai),
    "Asia/Kolkata" to context.getString(R.string.iptv_settings_india),
    "America/New_York" to context.getString(R.string.iptv_settings_us_eastern_new_york),
    "America/Chicago" to context.getString(R.string.iptv_settings_us_central_chicago),
    "America/Denver" to context.getString(R.string.iptv_settings_us_mountain_denver),
    "America/Los_Angeles" to context.getString(R.string.iptv_settings_us_pacific_los_angeles)
)

/** A zone id in words: one of the list's names, else as found (e.g. "GMT+01:00"). */
private fun zoneName(context: Context, id: String): String =
    catchupZones(context).firstOrNull { it.first == id }?.second ?: id

/** The time on that zone's clock now, to match against the provider's. */
private fun zoneClock(context: Context, id: String): String =
    java.text.SimpleDateFormat("HH:mm", context.settingsLocale)
        .apply { timeZone = java.util.TimeZone.getTimeZone(id) }
        .format(java.util.Date())

private fun catchupZoneLabel(context: Context, p: PlaylistSource): String = when {
    p.catchupTimezone.isNotBlank() -> zoneName(context, p.catchupTimezone)
    p.serverTimezone.isNotBlank() -> context.getString(R.string.iptv_settings_automatic_1_s, zoneName(context, p.serverTimezone))
    else -> context.getString(R.string.iptv_settings_automatic)
}

private fun offsetLabel(context: Context, min: Int): String {
    if (min == 0) return context.getString(R.string.iptv_settings_none)
    val sign = if (min > 0) "+" else "−"
    val a = kotlin.math.abs(min)
    return if (a % 60 == 0) context.getString(R.string.iptv_settings_1_s_2_s_h, sign, a / 60) else context.getString(R.string.iptv_settings_1_s_2_s_h_3_s_min, sign, a / 60, a % 60)
}

private fun hoursLabel(context: Context, h: Int): String = when {
    h >= 24 && h % 24 == 0 -> context.resources.getQuantityString(R.plurals.iptv_settings_days, h / 24, h / 24)
    else -> context.resources.getQuantityString(R.plurals.iptv_settings_hours, h, h)
}

/** Typed into the channel name editor, this turns the hidden developer tools on or off. */
private const val DEVELOPER_WORD = "#nuviodev"

private fun openOnLabel(context: Context, page: String): String = when (page) {
    "LIVE_TV" -> context.getString(R.string.iptv_settings_live_tv)
    "ON_DEMAND" -> context.getString(R.string.iptv_settings_on_demand)
    "SPORTS" -> context.getString(R.string.iptv_settings_sports)
    else -> context.getString(R.string.nav_home)
}

private fun sourceStatus(context: Context, enabled: Boolean, updated: Long, error: String?, count: String): String {
    if (!enabled) return context.getString(R.string.iptv_settings_disabled)
    val parts = mutableListOf<String>()
    if (error != null) parts += context.getString(R.string.iptv_settings_error_1_s, error)
    if (updated > 0) {
        parts += count
        parts += context.getString(R.string.iptv_settings_updated_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.settingsLocale).format(Date(updated)))
    } else if (error == null) {
        parts += context.getString(R.string.iptv_settings_not_downloaded_yet)
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun LiveTvSettingsContent(
    viewModel: LiveTvSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    page: LiveTvPage = LiveTvPage.ALL
) {
    val context = LocalContext.current
    // HUB: one Live TV page with its own row of tabs; the tab picked is the page shown.
    val hub = page == LiveTvPage.HUB
    var tab by remember { mutableStateOf(LiveTvPage.MAIN) }
    @Suppress("NAME_SHADOWING") val page = if (hub) tab else page
    fun show(p: LiveTvPage) = page == LiveTvPage.ALL || page == p
    val flat = page != LiveTvPage.ALL && page != LiveTvPage.MAIN
    /** The first section takes the focus when you come in from the list on the left (the tab row does, in a hub). */
    val firstRequester = if (hub) null else initialFocusRequester
    fun firstFocus(p: LiveTvPage): FocusRequester? = firstRequester.takeIf { page == p }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val epgs by viewModel.epgSources.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LiveDialog?>(null) }
    val drive by viewModel.driveSync.state.collectAsStateWithLifecycle()
    val cloud by viewModel.cloudSync.state.collectAsStateWithLifecycle()
    val syncParts by viewModel.cloudSync.enabledParts.collectAsStateWithLifecycle()
    val developerToolsOn by com.nuvio.tv.livetv.model.DeveloperTools.enabled.collectAsStateWithLifecycle()
    val onDemandHas by viewModel.onDemand.hasContent.collectAsStateWithLifecycle()
    val onDemandStatus by viewModel.onDemand.status.collectAsStateWithLifecycle()
    var pinWrong by remember { mutableStateOf(false) }

    fun update(t: (LiveTvSettings) -> LiveTvSettings) = viewModel.update(t)

    // Every section starts folded; OK on its header opens or closes it (not remembered).
    var open by remember { mutableStateOf(setOf<String>()) }
    fun toggle(key: String) { open = if (key in open) open - key else open + key }
    /** Asks before doing something that can't simply be undone. */
    fun confirm(title: String, message: String, label: String, action: () -> Unit) {
        dialog = LiveDialog.Confirm(title, message, label, action)
    }
    val hasPin = s.parentalPin.length >= 4

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = context.getString(page.title),
            subtitle = if (status.loading && !flat) (status.message ?: context.getString(R.string.iptv_settings_updating)) else context.getString(page.subtitle)
        )
        // Live TV switched off (Settings → Menu & Features): say so, with a way to turn it back on.
        if (!s.showInSidebar) {
            SettingsGroupCard {
                SettingsActionRow(
                    title = context.getString(R.string.iptv_settings_live_tv_is_turned_off),
                    subtitle = context.getString(R.string.iptv_settings_the_guide_its_row_on_home_and_everything),
                    value = context.getString(R.string.iptv_settings_turn_on),
                    onClick = { update { it.copy(showInSidebar = true) } }
                )
            }
        }
        if (hub) LiveTabRow(selected = tab, first = initialFocusRequester, onSelect = { tab = it })
        androidx.compose.runtime.key(page) {
        androidx.compose.runtime.CompositionLocalProvider(LocalLiveFlat provides flat) {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val settleScope = androidx.compose.runtime.rememberCoroutineScope()
        val hiddenTop = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onPreviewKeyEvent { e ->
                    // Going up onto the first rows of a section: bring its heading into view too
                    // (it was left cut off at the top, as if behind the tabs).
                    if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) {
                        settleScope.launch {
                            kotlinx.coroutines.delay(90)
                            val top = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                            if (top != null && top.offset < 0 && top.offset > -hiddenTop) listState.animateScrollToItem(top.index)
                        }
                    }
                    false
                },
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            // ------------------------------------------------------------ Setup Sync
            if (show(LiveTvPage.MAIN) && cloud.available) item(key = "cloud") {
                CloudSyncCard(
                    modifier = if (firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                    state = cloud,
                    enabledParts = syncParts,
                    onTogglePart = { viewModel.cloudSync.setPart(it, it !in syncParts) },
                    expanded = "cloud" in open,
                    onToggleExpanded = { toggle("cloud") },
                    onTurnOn = { viewModel.cloudSync.turnOn() },
                    driveAvailable = drive.available,
                    onImportDrive = {
                        confirm(
                            context.getString(R.string.iptv_settings_import_from_google_drive),
                            if (drive.connected) context.getString(R.string.iptv_settings_your_google_drive_backup_is_combined_with_this)
                            else context.getString(R.string.iptv_settings_you_sign_in_to_google_with_a_code),
                            context.getString(R.string.iptv_settings_import)
                        ) { viewModel.cloudSync.importFromDrive() }
                    },
                    onSyncNow = { viewModel.cloudSync.syncNow() },
                    onRestorePrevious = {
                        confirm(
                            context.getString(R.string.iptv_settings_restore_the_earlier_copy),
                            context.getString(R.string.iptv_settings_this_tv_s_setup_is_replaced_with_the),
                            context.getString(R.string.iptv_settings_restore)
                        ) { viewModel.cloudSync.restorePrevious() }
                    },
                    onTurnOff = { viewModel.cloudSync.turnOff() },
                    onDelete = {
                        confirm(
                            context.getString(R.string.iptv_settings_delete_the_saved_setup),
                            context.getString(R.string.iptv_settings_the_saved_copy_is_deleted_and_this_tv),
                            context.getString(R.string.action_delete)
                        ) { viewModel.cloudSync.deleteCloudCopy() }
                    }
                )
            }

            // ------------------------------------------------------------ Backup & Sync
            if (show(LiveTvPage.MAIN)) item(key = "drive") {
                DriveSyncCard(
                    modifier = if (firstRequester != null && !cloud.available) Modifier.focusRequester(firstRequester) else Modifier,
                    state = drive,
                    expanded = "drive" in open,
                    onToggleExpanded = { toggle("drive") },
                    onConnect = { viewModel.driveSync.beginSignIn() },
                    onAutoSync = { viewModel.driveSync.setAutoSync(it) },
                    onBackUp = {
                        confirm(
                            context.getString(R.string.iptv_settings_back_up_now),
                            context.getString(R.string.iptv_settings_the_copy_in_your_google_drive_is_replaced),
                            context.getString(R.string.iptv_settings_back_up)
                        ) { viewModel.driveSync.backUpNow() }
                    },
                    onRestore = { dialog = LiveDialog.ConfirmRestore },
                    onDisconnect = {
                        confirm(
                            context.getString(R.string.iptv_settings_disconnect_google_drive),
                            context.getString(R.string.iptv_settings_this_tv_stops_syncing_your_backup_stays_in),
                            context.getString(R.string.iptv_settings_disconnect)
                        ) { viewModel.driveSync.disconnect() }
                    }
                )
            }

            // ------------------------------------------------------------ Playlists & Guides
            if (show(LiveTvPage.MAIN)) item(key = "sources") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_playlists_guides),
                    summary = if (status.loading) (status.message ?: context.getString(R.string.iptv_settings_updating))
                    else context.getString(R.string.iptv_settings_source_counts, context.resources.getQuantityString(R.plurals.iptv_settings_playlist_count, playlists.size, playlists.size), context.resources.getQuantityString(R.plurals.iptv_settings_guide_count, epgs.size, epgs.size)),
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    open = "sources" in open,
                    onToggle = { toggle("sources") }
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_playlists))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_add_from_your_phone),
                        subtitle = context.getString(R.string.iptv_settings_scan_a_qr_code_then_paste_your_playlist),
                        leadingIcon = Icons.Default.PhoneAndroid,
                        onClick = { dialog = LiveDialog.AddFromPhone }
                    )
                    if (status.loading) LoadingLine(status.message ?: context.getString(R.string.iptv_settings_updating))
                    playlists.forEach { pl ->
                        val account = accounts[pl.id]
                        val expiring = pl.enabled && account?.expiringSoon() == true
                        SettingsActionRow(
                            title = pl.name + when { pl.isStalker -> context.getString(R.string.iptv_settings_stalker); pl.isXtream -> context.getString(R.string.iptv_settings_xtream); else -> "" },
                            subtitle = when {
                                pl.hasCatalog && pl.enabled && !pl.importLive && pl.importVod -> context.getString(R.string.iptv_settings_movies_series_only_tv_channels_off)
                                pl.hasCatalog && pl.enabled && !pl.importLive && !pl.importVod -> context.getString(R.string.iptv_settings_nothing_included_turn_on_tv_channels_or_movies)
                                else -> sourceStatus(context, pl.enabled, pl.lastUpdatedMs, pl.lastError, context.resources.getQuantityString(R.plurals.iptv_settings_channel_count, pl.channelCount, pl.channelCount)) +
                                    (if (pl.hasCatalog && pl.importVod && pl.enabled) context.getString(R.string.iptv_settings_movies_series) else "")
                            } + (account?.let { expiryLabel(context, it) }?.let { context.getString(R.string.iptv_settings_expires_1_s, it) } ?: ""),
                            value = when {
                                !pl.enabled -> context.getString(R.string.playback_afr_off)
                                expiring && (account?.expiresAtMs ?: 0L) <= System.currentTimeMillis() -> context.getString(R.string.iptv_settings_expired)
                                expiring -> context.getString(R.string.iptv_settings_expires_soon)
                                else -> context.getString(R.string.iptv_settings_on)
                            },
                            valueColor = if (expiring) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                            leadingIcon = Icons.Default.LiveTv,
                            onClick = { dialog = LiveDialog.PlaylistActions(pl) }
                        )
                    }
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_add_m3u_playlist),
                        subtitle = context.getString(R.string.iptv_settings_a_channel_list_from_the_m3u_link_your),
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddM3u }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_add_xtream_codes_login),
                        subtitle = context.getString(R.string.iptv_settings_sign_in_with_the_server_username_and_password),
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddXtream }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_add_stalker_ministra_portal),
                        subtitle = context.getString(R.string.iptv_settings_use_the_portal_url_and_mac_address_supplied),
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddStalker }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_tv_guides_epg))
                    epgs.forEach { e ->
                        SettingsActionRow(
                            title = e.name,
                            subtitle = sourceStatus(context, e.enabled, e.lastUpdatedMs, e.lastError, context.resources.getQuantityString(R.plurals.iptv_settings_program_count, e.programCount, e.programCount)),
                            value = if (e.enabled) context.getString(R.string.iptv_settings_on) else context.getString(R.string.playback_afr_off),
                            leadingIcon = Icons.Default.Link,
                            onClick = { dialog = LiveDialog.EpgActions(e) }
                        )
                    }
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_add_epg_source),
                        subtitle = context.getString(R.string.iptv_settings_extra_tv_listings_from_an_xmltv_link_ending),
                        leadingIcon = Icons.Default.Add,
                        onClick = { dialog = LiveDialog.AddEpg }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_updates))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_update_everything_now),
                        subtitle = context.getString(R.string.iptv_settings_downloads_the_latest_channels_and_listings_instead_of),
                        leadingIcon = Icons.Default.Refresh,
                        onClick = { viewModel.refreshAll() }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_check_playlists_for_updates_every), context.getString(R.string.iptv_settings_how_often_channel_lists_are_downloaded_again_from),
                        hoursLabel(context, s.playlistRefreshHours), onClick = { dialog = LiveDialog.PlaylistRefresh }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_check_guides_for_updates_every), context.getString(R.string.iptv_settings_how_often_tv_listings_are_downloaded_again),
                        hoursLabel(context, s.epgRefreshHours), onClick = { dialog = LiveDialog.EpgRefresh }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_update_banners),
                        context.getString(R.string.iptv_settings_show_playlist_epg_and_on_demand_update_progress),
                        s.showImportProgress,
                        { update { it.copy(showImportProgress = !it.showImportProgress) } }
                    )
                }
            }

            // ------------------------------------------------------------ Channels
            if (show(LiveTvPage.MAIN)) item(key = "channels") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_channels),
                    summary = context.getString(R.string.iptv_settings_name_cleanup_hidden_channels_and_groups_your_groups),
                    icon = Icons.Default.LiveTv,
                    open = "channels" in open,
                    onToggle = { toggle("channels") }
                ) {
                    Text(
                        text = context.getString(R.string.iptv_settings_tip_long_press_a_channel_in_the_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    SubHeading(context.getString(R.string.iptv_settings_channel_names))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_channel_name_cleanup),
                        subtitle = context.getString(R.string.iptv_settings_words_to_take_off_the_start_or_end),
                        value = com.nuvio.tv.livetv.model.ChannelNameEditor.terms(s.nameRemovals).size.let { if (it == 0) context.getString(R.string.playback_afr_off) else context.resources.getQuantityString(R.plurals.iptv_settings_removed_count, it, it) },
                        onClick = { dialog = LiveDialog.NameEditor }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_also_clean_group_names),
                        context.getString(R.string.iptv_settings_take_the_same_words_off_group_names_too),
                        s.cleanGroupNames,
                        { update { it.copy(cleanGroupNames = !it.cleanGroupNames) } }
                    )
                    if (s.nameRemovals.isBlank()) {
                        SettingsActionRow(
                            context.getString(R.string.iptv_settings_add_common_prefixes_suffixes),
                            context.getString(R.string.iptv_settings_fills_in_1_s, com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON),
                            onClick = { update { it.copy(nameRemovals = com.nuvio.tv.livetv.model.ChannelNameEditor.COMMON) } }
                        )
                    }
                    SubHeading(context.getString(R.string.iptv_settings_hidden_your_groups))
                    SettingsActionRow(context.getString(R.string.iptv_settings_hidden_channels), context.getString(R.string.iptv_settings_channels_you_hid_select_one_to_show_it), "${user.hiddenChannels.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenChannels })
                    SettingsActionRow(context.getString(R.string.iptv_settings_hidden_groups), context.getString(R.string.iptv_settings_groups_you_hid_select_one_to_show_it), "${user.hiddenGroups.size}", leadingIcon = Icons.Default.VisibilityOff, onClick = { dialog = LiveDialog.HiddenGroups })
                    SettingsActionRow(context.getString(R.string.iptv_settings_my_groups), context.getString(R.string.iptv_settings_groups_you_created_select_one_to_delete_it), "${user.customGroups.size}", onClick = { dialog = LiveDialog.CustomGroups })
                    SubHeading(context.getString(R.string.iptv_settings_clear_reset))
                    SettingsActionRow(context.getString(R.string.iptv_settings_clear_favorites), context.getString(R.string.iptv_settings_removes_every_channel_from_favorites), "${user.favorites.size}", onClick = {
                        confirm(context.getString(R.string.iptv_settings_clear_favorites_2), context.getString(R.string.iptv_settings_every_channel_is_taken_out_of_favorites), context.getString(R.string.action_clear)) { viewModel.clearFavorites() }
                    })
                    SettingsActionRow(context.getString(R.string.iptv_settings_clear_recently_watched), context.getString(R.string.iptv_settings_empties_the_recently_watched_group), "${user.recent.size}", onClick = {
                        confirm(context.getString(R.string.iptv_settings_clear_recently_watched_2), context.getString(R.string.iptv_settings_the_recently_watched_group_is_emptied), context.getString(R.string.action_clear)) { viewModel.clearRecent() }
                    })
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_reset_channel_names_numbers), context.getString(R.string.iptv_settings_puts_back_the_names_and_numbers_from_your),
                        "${(user.channelNames.keys + user.channelNumbers.keys).size}",
                        onClick = {
                            confirm(context.getString(R.string.iptv_settings_reset_channel_names_and_numbers), context.getString(R.string.iptv_settings_every_channel_you_renamed_or_renumbered_goes_back), context.getString(R.string.iptv_settings_reset)) { viewModel.resetChannelEdits() }
                        }
                    )
                    SettingsActionRow(context.getString(R.string.iptv_settings_reset_group_names_order), context.getString(R.string.iptv_settings_puts_back_the_group_names_and_order_from), onClick = {
                        confirm(context.getString(R.string.iptv_settings_reset_group_names_and_order), context.getString(R.string.iptv_settings_renamed_groups_get_their_playlist_names_back_and), context.getString(R.string.iptv_settings_reset)) { viewModel.resetGroupEdits() }
                    })
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_reset_channel_order), context.getString(R.string.iptv_settings_undoes_reorder_channels_in_every_group),
                        "${user.channelOrder.size}", onClick = {
                            confirm(context.getString(R.string.iptv_settings_reset_channel_order_2), context.getString(R.string.iptv_settings_every_group_goes_back_to_its_normal_channel), context.getString(R.string.iptv_settings_reset)) { viewModel.resetChannelOrder() }
                        }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_remove_copied_channels), context.getString(R.string.iptv_settings_takes_out_every_channel_you_copied_into_another),
                        "${user.channelCopies.values.sumOf { it.size }}", onClick = {
                            confirm(context.getString(R.string.iptv_settings_remove_copied_channels_2), context.getString(R.string.iptv_settings_every_channel_you_copied_into_another_group_is), context.getString(R.string.iptv_settings_remove)) { viewModel.clearChannelCopies() }
                        }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_reset_epg_assignments),
                        context.getString(R.string.iptv_settings_channels_you_assigned_a_guide_to_go_back),
                        "${user.epgOverrides.size}",
                        onClick = {
                            confirm(context.getString(R.string.iptv_settings_reset_epg_assignments_2), context.getString(R.string.iptv_settings_every_channel_you_assigned_a_guide_to_or), context.getString(R.string.iptv_settings_reset)) { viewModel.resetEpgAssignments() }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Groups
            if (show(LiveTvPage.MAIN)) item(key = "groups") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_groups),
                    summary = context.getString(R.string.iptv_settings_the_group_list_on_the_left_of_the),
                    icon = Icons.Default.Folder,
                    open = "groups" in open,
                    onToggle = { toggle("groups") }
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_built_in_groups))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_favorites),
                        context.getString(R.string.iptv_settings_the_group_of_channels_you_ve_marked_as),
                        s.showFavoritesGroup,
                        { update { it.copy(showFavoritesGroup = !it.showFavoritesGroup) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_recently_watched),
                        context.getString(R.string.iptv_settings_the_group_of_channels_you_watched_last),
                        s.showRecentGroup,
                        { update { it.copy(showRecentGroup = !it.showRecentGroup) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_all_channels),
                        context.getString(R.string.iptv_settings_the_group_with_every_channel_in_it),
                        s.showAllChannelsGroup,
                        { update { it.copy(showAllChannelsGroup = !it.showAllChannelsGroup) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_the_list))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_group_by_playlist),
                        context.getString(R.string.iptv_settings_with_more_than_one_playlist_each_playlist_s),
                        s.groupPlaylistHeadings,
                        { update { it.copy(groupPlaylistHeadings = !it.groupPlaylistHeadings) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_channel_counts),
                        context.getString(R.string.iptv_settings_the_number_of_channels_next_to_each_group),
                        s.showGroupCounts,
                        { update { it.copy(showGroupCounts = !it.showGroupCounts) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_group_order),
                        subtitle = context.getString(R.string.iptv_settings_how_the_group_list_is_sorted_your_order),
                        value = when (s.groupSort) { "playlist" -> context.getString(R.string.iptv_settings_playlist_order); "name" -> context.getString(R.string.iptv_settings_name_a_z); else -> context.getString(R.string.iptv_settings_your_order) },
                        onClick = {
                            update { st -> st.copy(groupSort = when (st.groupSort) { "custom" -> "playlist"; "playlist" -> "name"; else -> "custom" }) }
                        }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_channels_in_a_group))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_channel_order),
                        subtitle = context.getString(R.string.iptv_settings_how_channels_are_sorted_in_every_group_favorites),
                        value = when (s.channelSort) {
                            ChannelSort.PLAYLIST -> context.getString(R.string.iptv_settings_playlist_order)
                            ChannelSort.NUMBER -> context.getString(R.string.iptv_settings_channel_number)
                            ChannelSort.NAME -> context.getString(R.string.iptv_settings_name)
                        },
                        onClick = { dialog = LiveDialog.SortChoice }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_number_channels_1_2_3_in_each_group),
                        context.getString(R.string.iptv_settings_replaces_the_playlist_s_numbers_with_each_channel),
                        s.sequentialNumbers,
                        { update { it.copy(sequentialNumbers = !it.sequentialNumbers) } }
                    )
                }
            }

            // ------------------------------------------------------------ On Demand
            if (show(LiveTvPage.ON_DEMAND)) item(key = "on_demand") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_on_demand),
                    summary = when {
                        onDemandStatus.loading -> onDemandStatus.message ?: context.getString(R.string.iptv_settings_importing_movies_and_series)
                        !onDemandHas -> context.getString(R.string.iptv_settings_nothing_imported_yet)
                        else -> context.getString(R.string.iptv_settings_movies_series_imported) + when {
                            !s.onDemandEnabled -> context.getString(R.string.iptv_settings_switched_off)
                            s.onDemandInSidebar -> context.getString(R.string.iptv_settings_in_the_side_menu)
                            else -> ""
                        }
                    },
                    icon = Icons.Default.Movie,
                    open = "on_demand" in open,
                    onToggle = { toggle("on_demand") },
                    focusFirst = firstFocus(LiveTvPage.ON_DEMAND)
                ) {
                    Text(
                        text = context.getString(R.string.iptv_settings_movies_and_series_from_xtream_logins_with_movies),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    if (onDemandStatus.loading) LoadingLine(onDemandStatus.message ?: context.getString(R.string.iptv_settings_importing_movies_and_series))
                    SubHeading(context.getString(R.string.iptv_settings_providers))
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_update_movies_series_now),
                        onDemandStatus.message ?: if (onDemandHas) context.getString(R.string.iptv_settings_imported_updates_once_a_day_by_itself) else context.getString(R.string.iptv_settings_nothing_imported_yet),
                        onClick = { viewModel.onDemand.refreshNow() }
                    )
                    // One switch per Xtream login (logins added before On Demand start switched off).
                    playlists.filter { it.hasCatalog }.forEach { pl ->
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_movies_series_from_1_s, pl.name),
                            if (pl.importVod) context.getString(R.string.iptv_settings_included) else context.getString(R.string.iptv_settings_not_included_switch_on_to_import_this_provider),
                            pl.importVod,
                            { viewModel.savePlaylist(pl.copy(importVod = !pl.importVod)) }
                        )
                    }
                    SubHeading(context.getString(R.string.iptv_settings_browsing))
                    var pickingSort by remember { mutableStateOf(false) }
                    val defaultSort = com.nuvio.tv.livetv.ondemand.VodSort.parse(s.vodDefaultSort)?.first
                        ?: com.nuvio.tv.livetv.ondemand.VodSort.DEFAULT
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_default_sort_order),
                        subtitle = context.getString(R.string.iptv_settings_how_each_group_is_sorted_unless_you_pick),
                        value = stringResource(com.nuvio.tv.livetv.ondemand.vodSortLabelResource(defaultSort)),
                        onClick = { pickingSort = true }
                    )
                    if (pickingSort) com.nuvio.tv.livetv.ondemand.VodSortPicker(
                        title = context.getString(R.string.iptv_settings_default_sort_order),
                        current = defaultSort,
                        onPick = { picked ->
                            pickingSort = false
                            val p = picked ?: com.nuvio.tv.livetv.ondemand.VodSort.DEFAULT
                            update { it.copy(vodDefaultSort = com.nuvio.tv.livetv.ondemand.VodSort.encode(p)) }
                        },
                        onDismiss = { pickingSort = false }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_merge_duplicates),
                        context.getString(R.string.iptv_settings_shows_each_movie_or_series_once_even_when),
                        s.vodMergeDuplicates,
                        { update { it.copy(vodMergeDuplicates = !it.vodMergeDuplicates) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_play_straight_from_my_provider),
                        context.getString(R.string.iptv_settings_play_on_a_title_opened_from_on_demand),
                        s.onDemandPlayDirect,
                        { update { it.copy(onDemandPlayDirect = !it.onDemandPlayDirect) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_use_posters_from_my_addons),
                        context.getString(R.string.iptv_settings_your_addons_posters_where_they_have_the_title),
                        s.onDemandAddonPosters,
                        { update { it.copy(onDemandAddonPosters = !it.onDemandAddonPosters) } }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_show_hidden_categories), context.getString(R.string.iptv_settings_brings_back_every_on_demand_category_you_hid),
                        "${user.vodHiddenCategories.size}", onClick = {
                            confirm(context.getString(R.string.iptv_settings_show_hidden_categories_2), context.getString(R.string.iptv_settings_every_on_demand_category_you_hid_comes_back), context.getString(R.string.iptv_settings_show)) { viewModel.clearVodHidden() }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Guide Layout
            if (show(LiveTvPage.GUIDE)) item(key = "layout") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_guide_layout),
                    summary = context.getString(R.string.iptv_settings_what_the_guide_shows_and_how_much_fits),
                    icon = Icons.Default.GridView,
                    open = "layout" in open,
                    onToggle = { toggle("layout") },
                    focusFirst = firstFocus(LiveTvPage.GUIDE)
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_top_of_the_guide))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_info_panel),
                        context.getString(R.string.iptv_settings_the_panel_with_the_highlighted_show_s_poster),
                        s.showProgramDetails,
                        { update { it.copy(showProgramDetails = !it.showProgramDetails) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_preview_window),
                        context.getString(R.string.iptv_settings_the_small_video_of_the_highlighted_channel_in),
                        s.showPreview,
                        { update { it.copy(showPreview = !it.showPreview) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_smaller_info_panel_preview),
                        context.getString(R.string.iptv_settings_shrinks_the_top_of_the_guide_to_about),
                        s.smallHeader,
                        { update { it.copy(smallHeader = !it.smallHeader) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_guide_darkness),
                        subtitle = context.getString(R.string.iptv_settings_how_dark_the_info_panel_at_the_top),
                        value = when (s.guideInfoBackground) { "darker" -> context.getString(R.string.iptv_settings_standard); "solid" -> context.getString(R.string.iptv_settings_darkest); else -> context.getString(R.string.iptv_settings_lightest) },
                        onClick = {
                            update {
                                it.copy(guideInfoBackground = when (it.guideInfoBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" })
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_posters),
                        context.getString(R.string.iptv_settings_posters_for_what_s_on_from_your_addons),
                        s.showPosters,
                        { update { it.copy(showPosters = !it.showPosters) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_playlist_name_in_info_panel),
                        context.getString(R.string.iptv_settings_which_playlist_the_highlighted_channel_comes_from),
                        s.showPlaylistInInfo,
                        { update { it.copy(showPlaylistInInfo = !it.showPlaylistInInfo) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_info_panel_parts))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_channel_name_logo),
                        context.getString(R.string.iptv_settings_the_channel_line_at_the_top_of_the),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.CHANNEL !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.CHANNEL
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_live_catch_up_tags),
                        context.getString(R.string.iptv_settings_the_small_tags_beside_the_channel_name),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TAGS !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TAGS
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_title),
                        context.getString(R.string.iptv_settings_the_big_show_title),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TITLE !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TITLE
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_start_end_time),
                        context.getString(R.string.iptv_settings_like_8_00_pm_9_00_pm),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.TIME !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.TIME
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_time_left),
                        context.getString(R.string.iptv_settings_like_25_min_left),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.LEFT !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.LEFT
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_category),
                        context.getString(R.string.iptv_settings_like_drama_or_sports),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.CATEGORY !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.CATEGORY
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_progress_bar),
                        context.getString(R.string.iptv_settings_how_far_into_the_show_you_are),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.PROGRESS !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.PROGRESS
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_description),
                        context.getString(R.string.iptv_settings_the_text_about_the_show),
                        com.nuvio.tv.livetv.ui.GuideInfoPart.DESCRIPTION !in com.nuvio.tv.livetv.ui.BarPart.parse(s.guideInfoHidden),
                        {
                            update {
                                val cur = com.nuvio.tv.livetv.ui.BarPart.parse(it.guideInfoHidden)
                                val key = com.nuvio.tv.livetv.ui.GuideInfoPart.DESCRIPTION
                                it.copy(guideInfoHidden = com.nuvio.tv.livetv.ui.BarPart.join(if (key in cur) cur - key else cur + key))
                            }
                        }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_channel_rows))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_channel_names),
                        context.getString(R.string.iptv_settings_off_just_numbers_and_logos_which_leaves_more),
                        s.showChannelNames,
                        { update { it.copy(showChannelNames = !it.showChannelNames) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_channel_numbers),
                        context.getString(R.string.iptv_settings_the_number_in_front_of_each_channel),
                        s.showChannelNumbers,
                        { update { it.copy(showChannelNumbers = !it.showChannelNumbers) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_channel_logos),
                        context.getString(R.string.iptv_settings_the_logo_in_front_of_each_channel_name),
                        s.showChannelLogos,
                        { update { it.copy(showChannelLogos = !it.showChannelLogos) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_outline_the_playing_channel),
                        context.getString(R.string.iptv_settings_a_red_outline_around_the_channel_you_re),
                        s.markPlayingChannel,
                        { update { it.copy(markPlayingChannel = !it.markPlayingChannel) } }
                    )
                    var pickingGuideSize by remember { mutableStateOf(false) }
                    val guideSizes = listOf(
                        Triple("COMPACT", context.getString(R.string.iptv_settings_compact), context.getString(R.string.iptv_settings_slimmer_rows_slightly_smaller_text_about_two_more)),
                        Triple("STANDARD", context.getString(R.string.iptv_settings_standard), context.getString(R.string.iptv_settings_the_usual_size)),
                        Triple("LARGE", context.getString(R.string.iptv_settings_large), context.getString(R.string.iptv_settings_bigger_text_and_rows_about_three_fewer_channels)),
                        Triple("XLARGE", context.getString(R.string.iptv_settings_extra_large), context.getString(R.string.iptv_settings_the_biggest_text_and_rows_about_five_fewer))
                    )
                    val currentSize = s.effectiveGuideSize
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_guide_size),
                        subtitle = context.getString(R.string.iptv_settings_how_big_the_text_and_rows_in_the),
                        value = guideSizes.firstOrNull { it.first == currentSize }?.second ?: context.getString(R.string.iptv_settings_standard),
                        onClick = { pickingGuideSize = true }
                    )
                    if (pickingGuideSize) ActionListDialog(
                        title = context.getString(R.string.iptv_settings_guide_size),
                        actions = guideSizes.map { (key, label, _) ->
                            (if (key == currentSize) "$label  ✓" else label) to {
                                pickingGuideSize = false
                                update { it.copy(guideSize = key, compactRows = key == "COMPACT") }
                            }
                        },
                        onDismiss = { pickingGuideSize = false }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_times_in_the_guide),
                        context.getString(R.string.iptv_settings_each_show_s_start_and_end_time_under),
                        s.showProgramTimes,
                        { update { it.copy(showProgramTimes = !it.showProgramTimes) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_episode_numbers),
                        context.getString(R.string.iptv_settings_episode_numbers_like_s2_e5_after_show_titles),
                        s.showEpisodeNumbers,
                        { update { it.copy(showEpisodeNumbers = !it.showEpisodeNumbers) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_show_the_now_line),
                        context.getString(R.string.iptv_settings_the_line_that_marks_the_current_time_across),
                        s.showNowLine,
                        { update { it.copy(showNowLine = !it.showNowLine) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_quality_badges),
                        context.getString(R.string.iptv_settings_4k_fhd_hd_or_sd_next_to_channels),
                        s.showQualityBadges,
                        { update { it.copy(showQualityBadges = !it.showQualityBadges) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_frame_rate_on_the_info_bar),
                        context.getString(R.string.iptv_settings_shows_how_smooth_the_channel_is_25_30),
                        s.showFrameRate,
                        { update { it.copy(showFrameRate = !it.showFrameRate) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_look))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_guide_sports_background),
                        subtitle = context.getString(R.string.iptv_settings_the_backdrop_behind_the_tv_guide_and_the),
                        value = guideBackdropLabel(context, s.guideBackdrop),
                        onClick = { update { it.copy(guideBackdrop = com.nuvio.tv.livetv.ui.GuideBackdrops.next(it.guideBackdrop)) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_highlight_style),
                        subtitle = context.getString(R.string.iptv_settings_how_the_selected_show_is_marked_an_outline),
                        value = if (s.solidHighlight) context.getString(R.string.iptv_settings_solid) else context.getString(R.string.iptv_settings_outline),
                        onClick = { dialog = LiveDialog.HighlightChoice }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_24_hour_clock),
                        context.getString(R.string.iptv_settings_times_like_20_30_instead_of_8_30),
                        s.use24HourClock,
                        { update { it.copy(use24HourClock = !it.use24HourClock) } }
                    )
                }
            }

            // ------------------------------------------------------------ Watching
            if (show(LiveTvPage.PLAYBACK)) item(key = "watching") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_opening_channel_surfing),
                    summary = context.getString(R.string.iptv_settings_opening_live_tv_changing_channels_the_info_bar),
                    icon = Icons.Default.PlayCircleFilled,
                    open = "watching" in open,
                    onToggle = { toggle("watching") },
                    focusFirst = firstFocus(LiveTvPage.PLAYBACK),
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_opening_live_tv))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_open_on_last_group),
                        context.getString(R.string.iptv_settings_live_tv_opens_on_the_group_you_last),
                        s.rememberLastGroup,
                        { update { it.copy(rememberLastGroup = !it.rememberLastGroup) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_play_last_channel_in_the_preview),
                        context.getString(R.string.iptv_settings_opening_live_tv_starts_the_channel_you_watched),
                        s.resumeLastInPreview,
                        { update { it.copy(resumeLastInPreview = !it.resumeLastInPreview) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_start_in_full_screen),
                        context.getString(R.string.iptv_settings_the_first_time_you_open_live_tv_after),
                        s.autoPlayLastChannel,
                        { update { it.copy(autoPlayLastChannel = !it.autoPlayLastChannel) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_moving_around))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_ok_opens_full_screen),
                        context.getString(R.string.iptv_settings_ok_on_a_show_goes_straight_to_full),
                        s.openFullscreenOnSelect,
                        { update { it.copy(openFullscreenOnSelect = !it.openFullscreenOnSelect) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_land_on_channel_names_like_a_cable_box),
                        context.getString(R.string.iptv_settings_picking_a_group_lands_on_the_channel_names),
                        s.browseByChannelName,
                        { update { it.copy(browseByChannelName = !it.browseByChannelName) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_overlay_mode),
                        context.getString(R.string.iptv_settings_left_while_watching_shows_channels_and_schedules_over),
                        s.overlayMode,
                        { update { it.copy(overlayMode = !it.overlayMode) } }
                    )
                    if (s.overlayMode) {
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_overlay_keep_list_open_when_changing_channel),
                            context.getString(R.string.iptv_settings_ok_on_another_channel_switches_to_it_and),
                            s.overlayKeepOpen,
                            { update { it.copy(overlayKeepOpen = !it.overlayKeepOpen) } }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_overlay_show_schedule_and_details_beside_the_list),
                            context.getString(R.string.iptv_settings_the_highlighted_channel_s_upcoming_shows_and_what),
                            s.overlayShowDetails,
                            { update { it.copy(overlayShowDetails = !it.overlayShowDetails) } }
                        )
                    }
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_channel_up_down_moves_within),
                        subtitle = context.getString(R.string.iptv_settings_which_channels_up_and_down_or_ch_and),
                        value = if (s.zapMode == ZapMode.GROUP) context.getString(R.string.iptv_settings_current_group) else context.getString(R.string.iptv_settings_all_channels),
                        onClick = { dialog = LiveDialog.ZapModeChoice }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_channel_surfing_browse_before_switching),
                        context.getString(R.string.iptv_settings_up_down_and_ch_ch_show_what_s),
                        s.channelSurf,
                        { update { it.copy(channelSurf = !it.channelSurf) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_reverse_channel_up_down),
                        context.getString(R.string.iptv_settings_up_goes_to_the_previous_channel_instead_of),
                        s.reverseZap,
                        { update { it.copy(reverseZap = !it.reverseZap) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_quick_channels),
                        subtitle = context.getString(R.string.iptv_settings_shortcuts_under_the_play_bar_press_ok_while),
                        value = if (!com.nuvio.tv.livetv.model.PanelRowLists.shown(s)) context.getString(R.string.playback_afr_off) else {
                            val n = com.nuvio.tv.livetv.model.PanelRowLists.parse(s).count { it.on }
                            if (n == 1) context.getString(R.string.iptv_settings_1_list) else context.getString(R.string.iptv_settings_1_s_lists, n)
                        },
                        onClick = { dialog = LiveDialog.ChannelRow }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_play_bar_background),
                        subtitle = context.getString(R.string.iptv_settings_how_dark_the_play_bar_is_standard_or),
                        value = when (s.panelBackground) { "darker" -> context.getString(R.string.iptv_settings_standard); "solid" -> context.getString(R.string.iptv_settings_darkest); else -> context.getString(R.string.iptv_settings_lightest) },
                        onClick = {
                            update {
                                it.copy(panelBackground = when (it.panelBackground) { "standard" -> "darker"; "darker" -> "solid"; else -> "standard" })
                            }
                        }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_info_bar_stays_for),
                        subtitle = context.getString(R.string.iptv_settings_how_long_the_show_info_stays_on_screen),
                        value = context.getString(R.string.iptv_settings_1_s_seconds, s.infoBannerSeconds),
                        onClick = { dialog = LiveDialog.BannerTime }
                    )
                }
            }

            // ------------------------------------------------------------ Picture, Sound & Clock
            if (show(LiveTvPage.PLAYBACK)) item(key = "picture") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_picture_sound_clock),
                    summary = context.getString(R.string.iptv_settings_the_clock_frame_rate_pausing_and_catch_up),
                    icon = Icons.Default.PlayCircleFilled,
                    open = "picture" in open,
                    onToggle = { toggle("picture") }
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_on_screen_clock))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_show_clock),
                        subtitle = context.getString(R.string.iptv_settings_a_clock_in_the_corner_while_you_watch),
                        value = clockModes(context).firstOrNull { it.first == s.clockMode }?.second ?: context.getString(R.string.iptv_settings_with_player_controls),
                        onClick = { dialog = LiveDialog.ClockMode }
                    )
                    if (s.clockMode == "always") {
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_clock_position),
                            subtitle = context.getString(R.string.iptv_settings_which_corner_of_the_screen),
                            value = clockPositions(context).firstOrNull { it.first == s.clockPosition }?.second ?: context.getString(R.string.iptv_settings_top_right),
                            onClick = { dialog = LiveDialog.ClockPosition }
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_clock_size),
                            subtitle = context.getString(R.string.iptv_settings_how_big_the_time_is),
                            value = clockSizes(context).firstOrNull { it.first == s.clockSize }?.second ?: context.getString(R.string.iptv_settings_medium),
                            onClick = { dialog = LiveDialog.ClockSize }
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_clock_opacity),
                            subtitle = context.getString(R.string.iptv_settings_lower_is_more_see_through_so_it_doesn),
                            value = "${s.clockOpacity}%",
                            onClick = { dialog = LiveDialog.ClockOpacity }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_clock_background),
                            context.getString(R.string.iptv_settings_a_soft_dark_box_behind_the_time_so),
                            s.clockBackground,
                            { update { it.copy(clockBackground = !it.clockBackground) } }
                        )
                    }
                    SubHeading(context.getString(R.string.iptv_settings_picture_sound))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_captions_on_by_default),
                        context.getString(R.string.iptv_settings_turns_captions_on_when_a_channel_has_them),
                        s.captionsByDefault,
                        { update { it.copy(captionsByDefault = !it.captionsByDefault) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_audio_passthrough),
                        context.getString(R.string.iptv_settings_sends_dolby_audio_untouched_to_your_tv_or),
                        s.audioPassthrough,
                        { update { it.copy(audioPassthrough = !it.audioPassthrough) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_match_frame_rate),
                        subtitle = context.getString(R.string.iptv_settings_switches_the_tv_to_the_video_s_frame),
                        value = frameRateModes(context).firstOrNull { it.first == s.frameRateMode }?.second ?: context.getString(R.string.playback_afr_off),
                        onClick = { dialog = LiveDialog.FrameRate }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_pause_catch_up))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_pause_and_rewind_live_tv),
                        context.getString(R.string.iptv_settings_on_channels_without_catch_up_pausing_records_the),
                        s.timeshiftEnabled,
                        { update { it.copy(timeshiftEnabled = !it.timeshiftEnabled) } }
                    )
                    if (s.timeshiftEnabled) SettingsToggleRow(
                        context.getString(R.string.iptv_settings_rewind_anytime),
                        context.getString(R.string.iptv_settings_full_screen_channels_keep_a_rolling_recording_so),
                        s.rewindAlways,
                        { update { it.copy(rewindAlways = !it.rewindAlways) } }
                    )
                    if (s.timeshiftEnabled) {
                        // Longer buffers when recordings go to a USB drive (far more room).
                        val onDrive = s.recordingStorage.isNotEmpty()
                        // 10 minutes by default: Fire TV Sticks and similar have little free space.
                        val lengths = if (onDrive) listOf(10, 15, 30, 60, 120, 240) else listOf(10, 15, 30, 60, 120)
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_keep_up_to),
                            subtitle = context.getString(R.string.iptv_settings_how_far_back_you_can_go_saved_to),
                            value = context.getString(R.string.iptv_settings_buffer_estimate, context.resources.getQuantityString(R.plurals.iptv_settings_minutes_short, s.timeshiftMinutes, s.timeshiftMinutes), (s.timeshiftMinutes * 35).let { mb -> if (mb >= 1000) context.getString(R.string.iptv_settings_1f_gb, mb / 1024f) else context.getString(R.string.iptv_settings_1_s_mb, mb) }),
                            onClick = {
                                update { st ->
                                    val i = lengths.indexOf(st.timeshiftMinutes)
                                    st.copy(timeshiftMinutes = lengths[if (i < 0) 1 else (i + 1) % lengths.size])
                                }
                            }
                        )
                    }
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_faster_catch_up_skipping),
                        context.getString(R.string.iptv_settings_saves_a_replay_to_the_device_while_you),
                        s.catchupCache,
                        { update { it.copy(catchupCache = !it.catchupCache) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_prefer_hls_for_catch_up),
                        context.getString(R.string.iptv_settings_asks_xtream_providers_for_replays_in_hls_m3u8),
                        s.catchupPreferHls,
                        { update { it.copy(catchupPreferHls = !it.catchupPreferHls) } }
                    )
                    SettingsNote(context.getString(R.string.iptv_settings_catch_up_time_shift_is_set_for_each))
                    SubHeading(context.getString(R.string.iptv_settings_connection))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_buffer_size),
                        subtitle = context.getString(R.string.iptv_settings_larger_rides_out_a_slow_connection_or_provider),
                        value = when (s.bufferSize) { "none" -> context.getString(R.string.iptv_settings_none); "small" -> context.getString(R.string.iptv_settings_small); "large" -> context.getString(R.string.iptv_settings_large); "xlarge" -> context.getString(R.string.iptv_settings_extra_large_2); else -> context.getString(R.string.iptv_settings_normal) },
                        onClick = {
                            update { st -> st.copy(bufferSize = when (st.bufferSize) { "none" -> "small"; "small" -> "normal"; "normal" -> "large"; "large" -> "xlarge"; else -> "none" }) }
                        }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_reconnect_automatically),
                        context.getString(R.string.iptv_settings_tries_the_stream_again_by_itself_if_it),
                        s.autoReconnect,
                        { update { it.copy(autoReconnect = !it.autoReconnect) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_switch_to_a_backup_channel),
                        context.getString(R.string.iptv_settings_backup_channel_description),
                        s.autoBackupChannel,
                        { update { it.copy(autoBackupChannel = !it.autoBackupChannel) } }
                    )
                }
            }

            // ------------------------------------------------------------ Guide Listings
            if (show(LiveTvPage.GUIDE)) item(key = "listings") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_guide_listings),
                    summary = context.getString(R.string.iptv_settings_how_far_the_guide_reaches),
                    icon = Icons.Default.Schedule,
                    open = "listings" in open,
                    onToggle = { toggle("listings") }
                ) {
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_days_ahead), context.getString(R.string.iptv_settings_how_far_into_the_future_the_guide_keeps),
                        if (s.epgFutureDays == 1) context.getString(R.string.iptv_settings_1_day) else context.getString(R.string.iptv_settings_1_s_days, s.epgFutureDays), onClick = { dialog = LiveDialog.FutureDays }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_past_shows_kept), context.getString(R.string.iptv_settings_how_far_back_the_guide_keeps_shows_for),
                        hoursLabel(context, s.epgPastHours), onClick = { dialog = LiveDialog.PastHours }
                    )
                    SettingsNote(context.getString(R.string.iptv_settings_guide_time_shift_is_set_for_each_guide))
                    val clearContext = androidx.compose.ui.platform.LocalContext.current
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_clear_posters_and_show_info),
                        context.getString(R.string.iptv_settings_forgets_the_posters_and_show_info_found_for),
                        onClick = {
                            confirm(
                                context.getString(R.string.iptv_settings_clear_posters_and_show_info_2),
                                context.getString(R.string.iptv_settings_live_tv_forgets_every_poster_and_show_description),
                                context.getString(R.string.action_clear)
                            ) {
                                viewModel.clearPosterData()
                                android.widget.Toast.makeText(clearContext, context.getString(R.string.iptv_settings_posters_and_show_info_cleared), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            // ------------------------------------------------------------ Home Screen & Nuvio
            if (show(LiveTvPage.INTEGRATION)) item(key = "nuvio") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_home_screen_nuvio),
                    summary = context.getString(R.string.iptv_settings_the_live_tv_row_inside_nuvio_s_home),
                    icon = Icons.Default.Home,
                    open = "nuvio" in open,
                    onToggle = { toggle("nuvio") },
                    focusFirst = firstFocus(LiveTvPage.INTEGRATION)
                ) {
                    // ---- Live TV row on the home screen
                    // On or off for the Nuvio profile in use (each profile has its own choice).
                    val homeProfileContext = androidx.compose.ui.platform.LocalContext.current
                    val homeProfileId by remember {
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                            homeProfileContext.applicationContext, com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint::class.java
                        ).profileManager()
                    }.activeProfileId.collectAsStateWithLifecycle()
                    val homeRowOn = homeProfileId !in s.homeRowHiddenProfiles
                    SubHeading(context.getString(R.string.iptv_settings_rows_on_your_tv_s_home_screen))
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_card_shape),
                        subtitle = context.getString(R.string.iptv_settings_match_your_launcher_s_row_wide_for_google),
                        value = when (s.launcherCardShape) { "TALL" -> context.getString(R.string.iptv_settings_tall); "SQUARE" -> context.getString(R.string.iptv_settings_square); else -> context.getString(R.string.iptv_settings_wide) },
                        onClick = {
                            update { st -> st.copy(launcherCardShape = when (st.launcherCardShape) { "WIDE" -> "TALL"; "TALL" -> "SQUARE"; else -> "WIDE" }) }
                            com.nuvio.tv.livetv.home.LiveTvLauncherRows.refresh(homeProfileContext, afterMs = 1_500)
                        }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_row_inside_nuvio_s_home_page))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_live_tv_row_on_the_home_screen),
                        context.getString(R.string.iptv_settings_for_this_profile_tucked_above_your_top_row),
                        homeRowOn,
                        {
                            update { st ->
                                st.copy(
                                    homeRowHiddenProfiles = if (homeProfileId in st.homeRowHiddenProfiles) st.homeRowHiddenProfiles - homeProfileId
                                    else st.homeRowHiddenProfiles + homeProfileId
                                )
                            }
                        }
                    )
                    if (homeRowOn) {
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_channels_in_the_row),
                            subtitle = context.getString(R.string.iptv_settings_which_channels_appear_favorites_recently_watched_or_one),
                            value = when (s.homeRowSource) {
                                "favorites", "" -> context.getString(R.string.iptv_settings_favorites)
                                "recent" -> context.getString(R.string.iptv_settings_recently_watched)
                                else -> user.customGroups.firstOrNull { it.id == s.homeRowSource }?.name ?: context.getString(R.string.iptv_settings_favorites)
                            },
                            onClick = { dialog = LiveDialog.HomeRowSource }
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_row_title),
                            subtitle = context.getString(R.string.iptv_settings_the_name_shown_above_the_row),
                            value = s.homeRowTitle.ifBlank { context.getString(R.string.iptv_settings_live_tv) },
                            onClick = { dialog = LiveDialog.HomeRowTitle }
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_row_order),
                            subtitle = context.getString(R.string.iptv_settings_your_order_channel_number_or_the_shows_ending),
                            value = when (s.homeRowSort) { "number" -> context.getString(R.string.iptv_settings_channel_number); "ending" -> context.getString(R.string.iptv_settings_ending_soonest); else -> context.getString(R.string.iptv_settings_your_order) },
                            onClick = {
                                update { st -> st.copy(homeRowSort = when (st.homeRowSort) { "yours" -> "number"; "number" -> "ending"; else -> "yours" }) }
                            }
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_settings_channels_in_the_row),
                            subtitle = context.getString(R.string.iptv_settings_how_many_channels_the_row_shows),
                            value = if (s.homeRowLimit == 0) context.getString(R.string.iptv_settings_all) else "${s.homeRowLimit}",
                            onClick = {
                                update { st -> st.copy(homeRowLimit = when (st.homeRowLimit) { 10 -> 20; 20 -> 40; 40 -> 0; else -> 10 }) }
                            }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_hide_channels_with_no_listings),
                            context.getString(R.string.iptv_settings_leaves_out_channels_showing_only_programming_or_no),
                            s.homeRowHidePlaceholders,
                            { update { it.copy(homeRowHidePlaceholders = !it.homeRowHidePlaceholders) } }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_ok_opens_the_guide),
                            context.getString(R.string.iptv_settings_off_ok_plays_the_channel_full_screen_on),
                            s.homeRowOpensGuide,
                            { update { it.copy(homeRowOpensGuide = !it.homeRowOpensGuide) } }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_show_what_s_next),
                            context.getString(R.string.iptv_settings_a_next_9_00_the_news_line_on),
                            s.homeRowShowNext,
                            { update { it.copy(homeRowShowNext = !it.homeRowShowNext) } }
                        )
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_compact_cards),
                            context.getString(R.string.iptv_settings_just_the_logo_and_the_show_name),
                            s.homeRowCompact,
                            { update { it.copy(homeRowCompact = !it.homeRowCompact) } }
                        )
                    }
                }
            }

            // ------------------------------------------------------------ Search & Streams
            if (show(LiveTvPage.INTEGRATION)) item(key = "search_streams") {
                var pickingStreamsPosition by remember { mutableStateOf(false) }
                LiveSection(
                    title = context.getString(R.string.iptv_settings_search_streams),
                    summary = context.getString(R.string.iptv_settings_search_order_on_demand_in_streams),
                    icon = Icons.Default.Search,
                    open = "search_streams" in open,
                    onToggle = { toggle("search_streams") }
                ) {
                    SubHeading(context.getString(R.string.iptv_settings_search_results_order))
                    SearchOrderList(
                        order = com.nuvio.tv.livetv.ui.parseSearchOrder(s.searchOrder),
                        onChange = { newOrder -> update { it.copy(searchOrder = newOrder.joinToString(",")) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_preview_channels_behind_search),
                        context.getString(R.string.iptv_settings_ok_on_a_channel_plays_it_behind_the),
                        s.searchPreview,
                        { update { it.copy(searchPreview = !it.searchPreview) } }
                    )
                    // Search from Home and the side menu always shows everything; these set what
                    // search shows when it's opened from the guide or from On Demand.
                    SubHeading(context.getString(R.string.iptv_settings_search_from_the_guide_shows))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_live_tv),
                        context.getString(R.string.iptv_settings_channels_and_shows_in_the_guide),
                        s.guideSearchLive,
                        { update { it.copy(guideSearchLive = !it.guideSearchLive) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_movies_series_2),
                        context.getString(R.string.iptv_settings_titles_from_your_nuvio_addons),
                        s.guideSearchCatalog,
                        { update { it.copy(guideSearchCatalog = !it.guideSearchCatalog) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_on_demand),
                        context.getString(R.string.iptv_settings_your_provider_s_own_movies_and_series),
                        s.guideSearchOnDemand,
                        { update { it.copy(guideSearchOnDemand = !it.guideSearchOnDemand) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_search_from_on_demand_shows))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_live_tv),
                        context.getString(R.string.iptv_settings_channels_and_shows_in_the_guide),
                        s.onDemandSearchLive,
                        { update { it.copy(onDemandSearchLive = !it.onDemandSearchLive) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_movies_series_2),
                        context.getString(R.string.iptv_settings_titles_from_your_nuvio_addons),
                        s.onDemandSearchCatalog,
                        { update { it.copy(onDemandSearchCatalog = !it.onDemandSearchCatalog) } }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_on_demand),
                        context.getString(R.string.iptv_settings_your_provider_s_own_movies_and_series),
                        s.onDemandSearchOnDemand,
                        { update { it.copy(onDemandSearchOnDemand = !it.onDemandSearchOnDemand) } }
                    )
                    SubHeading(context.getString(R.string.iptv_settings_streams_list))
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_on_demand_in_streams),
                        context.getString(R.string.iptv_settings_your_provider_s_copy_offered_with_your_addon),
                        s.onDemandInStreams,
                        { update { it.copy(onDemandInStreams = !it.onDemandInStreams) } }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_on_demand_tab_position),
                        subtitle = context.getString(R.string.iptv_settings_where_its_tab_goes_in_the_stream_list),
                        value = streamsPositionLabel(context, s.onDemandStreamsPosition),
                        onClick = { pickingStreamsPosition = true }
                    )
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_let_auto_play_pick_on_demand),
                        context.getString(R.string.iptv_settings_only_when_its_tab_is_first_and_auto),
                        s.onDemandAutoPlay,
                        { update { it.copy(onDemandAutoPlay = !it.onDemandAutoPlay) } }
                    )
                    if (pickingStreamsPosition) SettingsSingleChoiceDialog(
                        title = context.getString(R.string.iptv_settings_on_demand_tab_position_2),
                        options = listOf("FIRST", "AFTER_FIRST", "LAST").map { SettingsPickerOption(it, streamsPositionLabel(context, it)) },
                        selectedValue = s.onDemandStreamsPosition,
                        onOptionSelected = { v -> update { it.copy(onDemandStreamsPosition = v) }; pickingStreamsPosition = false },
                        onDismiss = { pickingStreamsPosition = false }
                    )
                }
            }

            // ------------------------------------------------------------ Parental Controls
            if (show(LiveTvPage.MAIN)) item(key = "parental") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_parental_controls),
                    summary = if (hasPin) context.getString(R.string.iptv_settings_on_1_s_locked, user.lockedGroups.size) else context.getString(R.string.playback_afr_off),
                    icon = Icons.Default.Lock,
                    open = "parental" in open,
                    onToggle = { toggle("parental") }
                ) {
                    Text(
                        text = context.getString(R.string.iptv_settings_locked_groups_and_categories_need_the_pin_to),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
                    )
                    SettingsActionRow(
                        if (hasPin) context.getString(R.string.iptv_settings_change_pin) else context.getString(R.string.iptv_settings_set_a_pin),
                        if (hasPin) context.getString(R.string.iptv_settings_parental_controls_are_on) else context.getString(R.string.iptv_settings_turns_parental_controls_on),
                        onClick = { pinWrong = false; dialog = if (hasPin) LiveDialog.PinCheck else LiveDialog.PinNew }
                    )
                    if (hasPin) {
                        SettingsToggleRow(
                            context.getString(R.string.iptv_settings_lock_adult_content_automatically),
                            context.getString(R.string.iptv_settings_locks_groups_and_categories_with_names_like_adult),
                            s.lockAdultContent,
                            { update { it.copy(lockAdultContent = !it.lockAdultContent) } }
                        )
                        SettingsActionRow(
                            context.getString(R.string.iptv_settings_turn_off_parental_controls), context.getString(R.string.iptv_settings_removes_the_pin_your_locked_groups_are_remembered),
                            context.getString(R.string.iptv_settings_1_s_locked, user.lockedGroups.size),
                            onClick = { pinWrong = false; dialog = LiveDialog.PinRemove }
                        )
                    }
                }
            }

            // ------------------------------------------------------------ Reminders
            if (show(LiveTvPage.GUIDE)) item(key = "reminders") {
                LiveSection(
                    title = context.getString(R.string.iptv_settings_reminders),
                    summary = if (user.reminders.isEmpty()) context.getString(R.string.iptv_settings_none_set) else context.getString(R.string.iptv_settings_1_s_upcoming, user.reminders.size),
                    icon = Icons.Default.Notifications,
                    open = "reminders" in open,
                    onToggle = { toggle("reminders") }
                ) {
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_upcoming_reminders),
                        context.getString(R.string.iptv_settings_set_from_a_show_s_info_in_the),
                        "${user.reminders.size}",
                        onClick = { dialog = LiveDialog.Reminders }
                    )
                }
            }

            // ------------------------------------------------------------ Recordings
            if (show(LiveTvPage.PLAYBACK)) item(key = "recordings") {
                val recContext = androidx.compose.ui.platform.LocalContext.current
                // Drives come and go: read again whenever the section is opened.
                val drives = remember("recordings" in open) { com.nuvio.tv.livetv.record.RecordingStorage.choices(recContext) }
                val driveProblems = remember("recordings" in open) { com.nuvio.tv.livetv.record.RecordingStorage.problems(recContext) }
                val chosen = drives.firstOrNull { it.id == s.recordingStorage }
                fun driveLabel(c: com.nuvio.tv.livetv.record.RecordingStorage.Choice) =
                    c.label + " · " + com.nuvio.tv.livetv.record.RecordingStorage.sizeLabel(c.freeBytes) + context.getString(R.string.iptv_settings_free)
                var pickingDrive by remember { mutableStateOf(false) }
                LiveSection(
                    title = context.getString(R.string.iptv_settings_recordings),
                    summary = context.getString(R.string.iptv_settings_saved_to) + (chosen?.label ?: if (s.recordingStorage.isEmpty()) context.getString(R.string.iptv_settings_this_device) else context.getString(R.string.iptv_settings_a_drive_that_isn_t_plugged_in)),
                    icon = Icons.Default.FiberManualRecord,
                    open = "recordings" in open,
                    onToggle = { toggle("recordings") }
                ) {
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_save_recordings_to),
                        subtitle = if (chosen == null && s.recordingStorage.isNotEmpty())
                            context.getString(R.string.iptv_settings_the_chosen_drive_isn_t_plugged_in_recordings)
                        else context.getString(R.string.iptv_settings_where_new_recordings_go_to_move_ones_already),
                        value = chosen?.label ?: context.getString(R.string.iptv_settings_this_device),
                        onClick = { pickingDrive = true }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_start_recording_early),
                        subtitle = context.getString(R.string.iptv_settings_starts_this_long_before_the_show_in_case),
                        value = padLabel(context, s.recordPreMinutes),
                        onClick = { dialog = LiveDialog.RecordPre }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_keep_recording_after),
                        subtitle = context.getString(R.string.iptv_settings_keeps_going_this_long_after_the_show_in),
                        value = padLabel(context, s.recordPostMinutes),
                        onClick = { dialog = LiveDialog.RecordPost }
                    )
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_settings_when_storage_runs_low),
                        subtitle = if (s.recordingSpaceRule == "DELETE_OLDEST") context.getString(R.string.iptv_settings_the_oldest_recordings_on_that_drive_are_deleted)
                        else context.getString(R.string.iptv_settings_recording_stops_nothing_is_deleted),
                        value = if (s.recordingSpaceRule == "DELETE_OLDEST") context.getString(R.string.iptv_settings_delete_oldest) else context.getString(R.string.iptv_settings_stop_recording),
                        onClick = {
                            if (s.recordingSpaceRule == "DELETE_OLDEST") update { it.copy(recordingSpaceRule = "STOP") }
                            else confirm(
                                context.getString(R.string.iptv_settings_delete_old_recordings),
                                context.getString(R.string.iptv_settings_when_space_runs_low_the_oldest_recordings_on),
                                context.getString(R.string.iptv_settings_turn_on)
                            ) { update { it.copy(recordingSpaceRule = "DELETE_OLDEST") } }
                        }
                    )
                    if (pickingDrive) ActionListDialog(
                        title = context.getString(R.string.iptv_settings_save_recordings_to),
                        actions = drives.map { c ->
                            (driveLabel(c) + if (c.id == (chosen?.id ?: "")) "  ✓" else "") to {
                                pickingDrive = false
                                update { it.copy(recordingStorage = c.id) }
                            }
                        } + driveProblems.map { p ->
                            // Plugged in but not usable: say why instead of leaving it out.
                            context.getString(R.string.iptv_settings_1_s_can_t_be_used, p.label) to {
                                pickingDrive = false
                                confirm(context.getString(R.string.iptv_settings_1_s_can_t_be_used_2, p.label), p.reason, "OK") { }
                            }
                        } + listOf(
                            context.getString(R.string.iptv_settings_drive_not_listed) to {
                                pickingDrive = false
                                confirm(
                                    context.getString(R.string.iptv_settings_drive_not_listed),
                                    context.getString(R.string.iptv_settings_missing_drive_help),
                                    "OK"
                                ) { }
                            }
                        ),
                        onDismiss = { pickingDrive = false }
                    )
                }
            }

            // ------------------------------------------------------------ Help
            if (show(LiveTvPage.MAIN)) item(key = "help") {
                val crashContext = androidx.compose.ui.platform.LocalContext.current
                LiveSection(
                    title = context.getString(R.string.iptv_settings_help),
                    summary = context.getString(R.string.iptv_settings_crash_report_start_up_report),
                    icon = Icons.AutoMirrored.Filled.Help,
                    open = "help" in open,
                    onToggle = { toggle("help") }
                ) {
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_crash_report),
                        context.getString(R.string.iptv_settings_the_last_time_the_app_crashed_froze_or),
                        onClick = { com.nuvio.tv.livetv.crash.CrashCatcher.openLastReport(crashContext) }
                    )
                    SettingsActionRow(
                        context.getString(R.string.iptv_settings_start_up_report),
                        context.getString(R.string.iptv_settings_what_live_tv_did_while_loading_this_time),
                        onClick = { dialog = LiveDialog.LoadReport }
                    )
                    // Hidden developer tools: only shown after typing the secret word into the
                    // channel name cleanup (see NameEditor below).
                    if (developerToolsOn) {
                        SubHeading(context.getString(R.string.iptv_settings_developer_tools))
                        SettingsActionRow(
                            context.getString(R.string.iptv_settings_test_poster_lookup),
                            context.getString(R.string.iptv_settings_what_your_addons_return_for_a_title),
                            onClick = { dialog = LiveDialog.PosterTest }
                        )
                        SettingsActionRow(
                            context.getString(R.string.iptv_settings_remote_button_tester),
                            context.getString(R.string.iptv_settings_shows_every_button_the_remote_sends),
                            onClick = { dialog = LiveDialog.ButtonTester }
                        )
                    }
                }
            }
        }
        } // LocalLiveFlat
        } // key(page)
    }

    drive.signIn?.let { prompt ->
        DriveSignInDialog(prompt = prompt, onCancel = { viewModel.driveSync.cancelSignIn() })
    }

    // ---------------------------------------------------------------- dialogs
    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        LiveDialog.AddM3u -> SourceFormDialog(
            title = context.getString(R.string.iptv_settings_add_m3u_playlist_2),
            fields = listOf(
                FormField(context.getString(R.string.iptv_settings_name), context.getString(R.string.iptv_settings_my_provider)),
                FormField(context.getString(R.string.iptv_settings_playlist_url), "http://…/playlist.m3u"),
                FormField(context.getString(R.string.iptv_settings_user_agent_optional), context.getString(R.string.iptv_settings_leave_empty_for_default))
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addPlaylist(v[0], v[1], v[2]); close() }
        )
        LiveDialog.AddXtream -> SourceFormDialog(
            title = context.getString(R.string.iptv_settings_add_xtream_codes_login_2),
            fields = listOf(
                FormField(context.getString(R.string.iptv_settings_name), context.getString(R.string.iptv_settings_my_provider)),
                FormField(context.getString(R.string.iptv_settings_server), "http://host:port"),
                FormField(context.getString(R.string.iptv_settings_username), ""),
                FormField(context.getString(R.string.iptv_settings_password), "")
            ),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3]); close() },
            toggleLive = context.getString(R.string.iptv_settings_include_tv_channels) to true,
            toggle2 = context.getString(R.string.iptv_settings_include_movies_series_on_demand) to true,
            onSaveAll = { v, _, vod, live ->
                if (v[1].isNotBlank()) viewModel.addXtream(v[0], v[1], v[2], v[3], importVod = vod, importLive = live)
                close()
            }
        )
        LiveDialog.AddStalker -> StalkerSourceDialog(
            onDismiss = close,
            onSave = { name, config, guide, vod, live ->
                viewModel.addStalker(name, config, vod, live, guide)
                close()
            }
        )
        LiveDialog.AddFromPhone -> AddFromPhoneDialog(viewModel = viewModel, onDismiss = close)
        LiveDialog.AddEpg -> SourceFormDialog(
            title = context.getString(R.string.iptv_settings_add_epg_source_2),
            fields = listOf(FormField(context.getString(R.string.iptv_settings_name), context.getString(R.string.iptv_settings_guide)), FormField(context.getString(R.string.iptv_settings_xmltv_url), "http://…/epg.xml.gz")),
            onDismiss = close,
            onSave = { v, _ -> if (v[1].isNotBlank()) viewModel.addEpg(v[0], v[1]); close() }
        )
        is LiveDialog.PlaylistActions -> ActionListDialog(
            title = d.source.name,
            actions = listOfNotNull(
                // The account, as the provider last reported it (nothing to do: shows the details).
                accounts[d.source.id]?.let { a ->
                    val parts = listOfNotNull(
                        a.status.takeIf { it.isNotBlank() },
                        expiryLabel(context, a)?.let { context.getString(R.string.iptv_settings_expires_1_s_2, it) } ?: context.getString(R.string.iptv_settings_no_expiry_date),
                        a.maxConnections.takeIf { it > 0 }?.let { context.getString(R.string.iptv_settings_1_s_of_2_s_connections_in_use, a.activeConnections, it) }
                    )
                    (context.getString(R.string.iptv_settings_account_details, parts.joinToString(" · "))) to { viewModel.updatePlaylistNow(d.source); close() }
                },
                context.getString(R.string.iptv_settings_edit) to { dialog = LiveDialog.EditPlaylist(d.source) },
                context.getString(R.string.iptv_settings_update_now) to { viewModel.updatePlaylistNow(d.source); close() },
                (if (d.source.enabled) context.getString(R.string.iptv_settings_disable) else context.getString(R.string.iptv_settings_enable)) to { viewModel.setPlaylistEnabled(d.source.id, !d.source.enabled); close() },
                context.getString(R.string.iptv_settings_delete_playlist) to { dialog = LiveDialog.ConfirmDeletePlaylist(d.source) },
                // Whose channel logos to show: the playlist's own or the guide's (each falls back
                // to the other when it has none).
                (if (d.source.logoSource == "epg") context.getString(R.string.iptv_settings_logos_prefer_logos_from_epg) else context.getString(R.string.iptv_settings_logos_prefer_logos_from_playlist)) to {
                    viewModel.setPlaylistLogoSource(d.source.id, if (d.source.logoSource == "epg") "playlist" else "epg")
                    close()
                },
                // Which stream format to ask for. Xtream: Auto follows what the account allows.
                // M3U links: Auto keeps the playlist's own links; TS / HLS switch links that can
                // be switched (Xtream-style links, Dispatcharr).
                if (!d.source.isStalker) (context.getString(R.string.iptv_settings_stream_format) + when (d.source.streamFormat) {
                    "ts" -> "TS"; "m3u8" -> "HLS (m3u8)"; else -> if (d.source.isXtream) context.getString(R.string.iptv_settings_auto) else context.getString(R.string.iptv_settings_as_in_playlist)
                }) to {
                    val next = when (d.source.streamFormat) { "auto" -> "ts"; "ts" -> "m3u8"; else -> "auto" }
                    viewModel.savePlaylist(d.source.copy(streamFormat = next))
                    close()
                } else null,
                // Which clock replay links are written in. Automatic: the one the provider's
                // server reports (or that the app worked out); pick one when that's wrong.
                if (!d.source.isStalker) (context.getString(R.string.iptv_settings_catchup_zone_value, catchupZoneLabel(context, d.source))) to { dialog = LiveDialog.CatchupZone(d.source) } else null,
                // Time shifts for this playlist: its built-in guide, and its catch-up.
                (context.getString(R.string.iptv_settings_guide_shift_value, offsetLabel(context, d.source.guideShiftMinutes))).takeIf { d.source.liveEnabled }?.let {
                    it to { dialog = LiveDialog.PlaylistGuideShift(d.source) }
                },
                (context.getString(R.string.iptv_settings_catchup_shift_value, offsetLabel(context, d.source.catchupShiftMinutes))).takeIf { d.source.liveEnabled && !d.source.isStalker }?.let {
                    it to { dialog = LiveDialog.PlaylistCatchupShift(d.source) }
                },
                context.getString(R.string.iptv_settings_move_up) to { viewModel.movePlaylist(d.source.id, -1); close() },
                context.getString(R.string.iptv_settings_move_down) to { viewModel.movePlaylist(d.source.id, 1); close() }
            ),
            onDismiss = close
        )
        is LiveDialog.CatchupZone -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_catch_up_time_zone_2),
            subtitle = context.getString(R.string.iptv_settings_catchup_zone_description, d.source.name),
            options = listOf(
                SettingsPickerOption(
                    "", context.getString(R.string.iptv_settings_automatic),
                    description = d.source.serverTimezone.takeIf { it.isNotBlank() }?.let { context.getString(R.string.iptv_settings_found_1_s, zoneName(context, it)) } ?: context.getString(R.string.iptv_settings_not_found_yet)
                )
            ) + catchupZones(context).map { (id, name) -> SettingsPickerOption(id, name, trailing = zoneClock(context, id)) },
            selectedValue = d.source.catchupTimezone,
            onOptionSelected = { v -> viewModel.setPlaylistCatchupZone(d.source.id, v); close() },
            onDismiss = close,
            maxHeight = 420.dp
        )
        is LiveDialog.EditPlaylist -> {
            val src = d.source
            if (src.isStalker) {
                StalkerSourceDialog(src, onDismiss = close, onSave = { name, config, guide, vod, live ->
                    viewModel.savePlaylist(src.copy(name = name, sourceType = "stalker", stalkerConfig = config,
                        useEmbeddedEpg = guide, importVod = vod, importLive = live))
                    close()
                })
            } else if (src.isXtream) {
                SourceFormDialog(
                    title = context.getString(R.string.iptv_settings_edit_xtream_login),
                    fields = listOf(
                        FormField(context.getString(R.string.iptv_settings_name), "", src.name),
                        FormField(context.getString(R.string.iptv_settings_server), "", src.xtreamServer),
                        FormField(context.getString(R.string.iptv_settings_username), "", src.xtreamUsername),
                        FormField(context.getString(R.string.iptv_settings_password), "", src.xtreamPassword),
                        FormField(context.getString(R.string.iptv_settings_user_agent_optional), "", src.userAgent)
                    ),
                    toggle = context.getString(R.string.iptv_settings_load_built_in_guide) to src.useEmbeddedEpg,
                    toggleLive = context.getString(R.string.iptv_settings_include_tv_channels) to src.importLive,
                    toggle2 = context.getString(R.string.iptv_settings_include_movies_series_on_demand) to src.importVod,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3], userAgent = v[4], useEmbeddedEpg = t))
                        close()
                    },
                    onSaveAll = { v, t, vod, live ->
                        viewModel.savePlaylist(
                            src.copy(
                                name = v[0], xtreamServer = v[1], xtreamUsername = v[2], xtreamPassword = v[3],
                                userAgent = v[4], useEmbeddedEpg = t, importVod = vod, importLive = live
                            )
                        )
                        close()
                    }
                )
            } else {
                SourceFormDialog(
                    title = context.getString(R.string.iptv_settings_edit_playlist),
                    fields = listOf(
                        FormField(context.getString(R.string.iptv_settings_name), "", src.name),
                        FormField(context.getString(R.string.iptv_settings_playlist_url), "", src.url),
                        FormField(context.getString(R.string.iptv_settings_user_agent_optional), "", src.userAgent)
                    ),
                    toggle = context.getString(R.string.iptv_settings_load_guide_linked_in_playlist) to src.useEmbeddedEpg,
                    onDismiss = close,
                    onSave = { v, t ->
                        viewModel.savePlaylist(src.copy(name = v[0], url = v[1], userAgent = v[2], useEmbeddedEpg = t))
                        close()
                    }
                )
            }
        }
        is LiveDialog.EpgActions -> ActionListDialog(
            title = d.source.name,
            actions = listOf(
                context.getString(R.string.iptv_settings_edit) to { dialog = LiveDialog.EditEpg(d.source) },
                context.getString(R.string.iptv_settings_update_now) to { viewModel.saveEpg(d.source); close() },
                (context.getString(R.string.iptv_settings_time_shift_value, offsetLabel(context, d.source.shiftMinutes))) to { dialog = LiveDialog.EpgSourceShift(d.source) },
                (if (d.source.enabled) context.getString(R.string.iptv_settings_disable) else context.getString(R.string.iptv_settings_enable)) to { viewModel.setEpgEnabled(d.source.id, !d.source.enabled); close() },
                context.getString(R.string.iptv_settings_delete_epg_source) to { dialog = LiveDialog.ConfirmDeleteEpg(d.source) }
            ),
            onDismiss = close
        )
        LiveDialog.PinCheck -> TextInputDialog(
            title = if (pinWrong) context.getString(R.string.iptv_settings_wrong_pin_try_again) else context.getString(R.string.iptv_settings_enter_your_current_pin),
            initial = "", hint = context.getString(R.string.iptv_settings_current_pin), confirmLabel = context.getString(R.string.iptv_settings_next), numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { pinWrong = false; dialog = LiveDialog.PinNew } else pinWrong = true }
        )
        LiveDialog.PinNew -> TextInputDialog(
            title = if (pinWrong) context.getString(R.string.iptv_settings_the_pin_needs_4_to_8_digits) else context.getString(R.string.iptv_settings_choose_a_pin_4_to_8_digits),
            initial = "", hint = context.getString(R.string.iptv_settings_new_pin), confirmLabel = context.getString(R.string.action_save), numeric = true,
            onDismiss = close,
            onConfirm = { pin ->
                val p = pin.trim()
                if (p.length in 4..8 && p.all { it.isDigit() }) { update { it.copy(parentalPin = p) }; close() } else pinWrong = true
            }
        )
        LiveDialog.PinRemove -> TextInputDialog(
            title = if (pinWrong) context.getString(R.string.iptv_settings_wrong_pin_try_again) else context.getString(R.string.iptv_settings_enter_your_pin_to_turn_parental_controls_off),
            initial = "", hint = "PIN", confirmLabel = context.getString(R.string.iptv_settings_turn_off), numeric = true,
            onDismiss = close,
            onConfirm = { if (it.trim() == s.parentalPin) { update { st -> st.copy(parentalPin = "") }; close() } else pinWrong = true }
        )
        LiveDialog.HomeRowSource -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_channels_in_the_row_2),
            options = buildList {
                add(SettingsPickerOption("favorites", context.getString(R.string.iptv_settings_favorites)))
                add(SettingsPickerOption("recent", context.getString(R.string.iptv_settings_recently_watched)))
                user.customGroups.forEach { add(SettingsPickerOption(it.id, it.name)) }
            },
            selectedValue = s.homeRowSource.ifBlank { "favorites" },
            onOptionSelected = { v -> update { it.copy(homeRowSource = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HomeRowTitle -> TextInputDialog(
            title = context.getString(R.string.iptv_settings_home_row_title),
            initial = s.homeRowTitle,
            hint = context.getString(R.string.iptv_settings_live_tv),
            confirmLabel = context.getString(R.string.action_save),
            onDismiss = close,
            onConfirm = { t -> update { it.copy(homeRowTitle = t.trim()) }; close() }
        )
        LiveDialog.ButtonTester -> com.nuvio.tv.livetv.ui.RemoteButtonTester(onClose = close)
        LiveDialog.LoadReport -> com.nuvio.tv.livetv.ui.LoadReportDialog(onDismiss = close)
        LiveDialog.PosterTest -> {
            val result by viewModel.posterTest.collectAsStateWithLifecycle()
            if (result == null) {
                TextInputDialog(
                    title = context.getString(R.string.iptv_settings_test_poster_lookup_2),
                    initial = "",
                    hint = context.getString(R.string.iptv_settings_e_g_family_guy),
                    confirmLabel = context.getString(R.string.iptv_settings_test),
                    onDismiss = close,
                    onConfirm = { viewModel.testPoster(it) }
                )
            } else {
                NuvioDialog(onDismiss = { viewModel.posterTest.value = null; close() }, title = context.getString(R.string.iptv_settings_poster_lookup), width = 720.dp) {
                    Text(
                        text = result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                    )
                    SettingsDialogActionRow {
                        SettingsDialogActionButton(text = context.getString(R.string.action_close), onClick = { viewModel.posterTest.value = null; close() }, primary = true)
                    }
                }
            }
        }
        LiveDialog.NameEditor -> TextInputDialog(
            title = context.getString(R.string.iptv_settings_prefixes_and_suffixes_to_remove),
            initial = s.nameRemovals,
            hint = context.getString(R.string.iptv_settings_e_g_usa_us_uk_24_7_fhd),
            confirmLabel = context.getString(R.string.action_save),
            onDismiss = close,
            onConfirm = { text ->
                // Secret word: turns the hidden developer tools on or off instead of saving.
                if (text.trim().equals(DEVELOPER_WORD, ignoreCase = true)) {
                    com.nuvio.tv.livetv.model.DeveloperTools.toggle() // until the app next starts
                } else {
                    update { it.copy(nameRemovals = text.trim()) }
                }
                close()
            }
        )
        LiveDialog.StartPage -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_open_the_app_on),
            // Live TV while it's in the menu; On Demand once something has been imported.
            options = buildList {
                add(SettingsPickerOption("HOME", context.getString(R.string.nav_home)))
                if (s.showInSidebar) add(SettingsPickerOption("LIVE_TV", context.getString(R.string.iptv_settings_live_tv)))
                if (onDemandHas) add(SettingsPickerOption("ON_DEMAND", context.getString(R.string.iptv_settings_on_demand)))
                add(SettingsPickerOption("SPORTS", context.getString(R.string.iptv_settings_sports)))
            },
            selectedValue = s.openOn,
            onOptionSelected = { v -> update { it.copy(openOn = v) }; close() },
            onDismiss = close
        )
        LiveDialog.Reminders -> ActionListDialog(
            title = if (user.reminders.isEmpty()) context.getString(R.string.iptv_settings_no_reminders) else context.getString(R.string.iptv_settings_reminders_select_to_remove),
            actions = user.reminders.map { r ->
                val whenText = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(context.settingsLocale, "EEEjm"), context.settingsLocale).format(java.util.Date(r.startMs))
                "$whenText · ${r.title} · ${r.channelName}" to { viewModel.removeReminder(r.channelKey, r.startMs); Unit }
            }.ifEmpty { listOf(context.getString(R.string.action_close) to close) },
            onDismiss = close
        )
        is LiveDialog.Confirm -> ConfirmDeleteDialog(
            title = d.title,
            message = d.message,
            onDismiss = close,
            onConfirm = { d.action(); close() },
            confirmLabel = d.label
        )
        LiveDialog.ConfirmRestore -> {
            // When the saved copy was made, and on which TV: restoring an old copy puts back old
            // playlists and logins (a provider's old address, an old password).
            var loaded by remember { mutableStateOf(false) }
            var info by remember { mutableStateOf<com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?>(null) }
            LaunchedEffect(Unit) {
                info = runCatching { viewModel.driveSync.backupInfo() }.getOrNull()
                loaded = true
            }
            ConfirmDeleteDialog(
                title = context.getString(R.string.iptv_settings_restore_from_google_drive),
                message = restoreMessage(context, loaded, info, viewModel.driveSync.localChangedAt()),
                onDismiss = close,
                onConfirm = { viewModel.driveSync.restoreNow(); close() },
                confirmLabel = context.getString(R.string.iptv_settings_restore)
            )
        }
        is LiveDialog.ConfirmDeletePlaylist -> ConfirmDeleteDialog(
            title = context.getString(R.string.iptv_settings_delete_1_s, d.source.name),
            message = context.getString(R.string.iptv_settings_its_1_s_channels_leave_the_guide_favorites, d.source.channelCount),
            onDismiss = close,
            onConfirm = { viewModel.removePlaylist(d.source.id); close() }
        )
        is LiveDialog.ConfirmDeleteEpg -> ConfirmDeleteDialog(
            title = context.getString(R.string.iptv_settings_delete_1_s, d.source.name),
            message = context.getString(R.string.iptv_settings_channels_using_this_guide_go_back_to_other),
            onDismiss = close,
            onConfirm = { viewModel.removeEpg(d.source.id); close() }
        )
        is LiveDialog.EditEpg -> SourceFormDialog(
            title = context.getString(R.string.iptv_settings_edit_epg_source),
            fields = listOf(FormField(context.getString(R.string.iptv_settings_name), "", d.source.name), FormField(context.getString(R.string.iptv_settings_xmltv_url), "", d.source.url)),
            onDismiss = close,
            onSave = { v, _ -> viewModel.saveEpg(d.source.copy(name = v[0], url = v[1])); close() }
        )
        LiveDialog.HiddenChannels -> UnhideDialog(
            title = context.getString(R.string.iptv_settings_hidden_channels_2),
            entries = user.hiddenChannels.sorted().map { it to viewModel.channelLabel(it) },
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = { viewModel.unhideAllChannels(); close() },
            onDismiss = close
        )
        LiveDialog.HiddenGroups -> UnhideDialog(
            title = context.getString(R.string.iptv_settings_hidden_groups_2),
            entries = user.hiddenGroups.sorted().map { it to viewModel.groupLabel(it) },
            onUnhide = { viewModel.unhideGroup(it) },
            onUnhideAll = { viewModel.unhideAllGroups(); close() },
            onDismiss = close
        )
        is LiveDialog.PlaylistGuideShift -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_guide_time_shift_1_s, d.source.name),
            subtitle = context.getString(R.string.iptv_settings_moves_this_playlist_s_built_in_guide_earlier),
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(context, it)) },
            selectedValue = d.source.guideShiftMinutes,
            onOptionSelected = { v -> viewModel.setPlaylistGuideShift(d.source.id, v); close() },
            onDismiss = close
        )
        is LiveDialog.EpgSourceShift -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_time_shift_1_s, d.source.name),
            subtitle = context.getString(R.string.iptv_settings_moves_this_guide_s_listings_earlier_or_later),
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(context, it)) },
            selectedValue = d.source.shiftMinutes,
            onOptionSelected = { v -> viewModel.setEpgShift(d.source.id, v); close() },
            onDismiss = close
        )
        LiveDialog.RecordPre -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_start_recording_early_2),
            options = listOf(0, 1, 2, 5, 10, 15).map { SettingsPickerOption(it, padLabel(context, it)) },
            selectedValue = s.recordPreMinutes,
            onOptionSelected = { v -> update { it.copy(recordPreMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.RecordPost -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_keep_recording_after_2),
            options = listOf(0, 2, 5, 10, 15, 30).map { SettingsPickerOption(it, padLabel(context, it)) },
            selectedValue = s.recordPostMinutes,
            onOptionSelected = { v -> update { it.copy(recordPostMinutes = v) }; close() },
            onDismiss = close
        )
        LiveDialog.FrameRate -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_match_frame_rate),
            options = frameRateModes(context).map { (v, t, d) -> SettingsPickerOption(v, t, description = d) },
            selectedValue = s.frameRateMode,
            onOptionSelected = { v -> update { it.copy(frameRateMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockMode -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_show_clock_2),
            options = clockModes(context).map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockMode,
            onOptionSelected = { v -> update { it.copy(clockMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockPosition -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_clock_position_2),
            options = clockPositions(context).map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockPosition,
            onOptionSelected = { v -> update { it.copy(clockPosition = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockSize -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_clock_size_2),
            options = clockSizes(context).map { SettingsPickerOption(it.first, it.second) },
            selectedValue = s.clockSize,
            onOptionSelected = { v -> update { it.copy(clockSize = v) }; close() },
            onDismiss = close
        )
        LiveDialog.ClockOpacity -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_clock_opacity_2),
            options = listOf(30, 50, 70, 100).map { SettingsPickerOption(it, "$it%") },
            selectedValue = s.clockOpacity,
            onOptionSelected = { v -> update { it.copy(clockOpacity = v) }; close() },
            onDismiss = close
        )
        is LiveDialog.PlaylistCatchupShift -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_catch_up_time_shift_1_s, d.source.name),
            subtitle = context.getString(R.string.iptv_settings_if_replays_start_the_show_before_or_after),
            options = EPG_OFFSETS.map { SettingsPickerOption(it, offsetLabel(context, it)) },
            selectedValue = d.source.catchupShiftMinutes,
            onOptionSelected = { v -> viewModel.setPlaylistCatchupShift(d.source.id, v); close() },
            onDismiss = close
        )
        LiveDialog.ZapModeChoice -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_channel_up_down_switches_within),
            options = listOf(
                SettingsPickerOption(ZapMode.GROUP, context.getString(R.string.iptv_settings_current_group)),
                SettingsPickerOption(ZapMode.ALL, context.getString(R.string.iptv_settings_all_channels))
            ),
            selectedValue = s.zapMode,
            onOptionSelected = { v -> update { it.copy(zapMode = v) }; close() },
            onDismiss = close
        )
        LiveDialog.BannerTime -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_info_banner_duration),
            options = BANNER_SECONDS.map { SettingsPickerOption(it, context.resources.getQuantityString(R.plurals.iptv_settings_seconds, it, it)) },
            selectedValue = s.infoBannerSeconds,
            onOptionSelected = { v -> update { it.copy(infoBannerSeconds = v) }; close() },
            onDismiss = close
        )
        LiveDialog.PlaylistRefresh -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_update_playlists_every),
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(context, it)) },
            selectedValue = s.playlistRefreshHours,
            onOptionSelected = { v -> update { it.copy(playlistRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.EpgRefresh -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_update_guides_every),
            options = REFRESH_HOURS.map { SettingsPickerOption(it, hoursLabel(context, it)) },
            selectedValue = s.epgRefreshHours,
            onOptionSelected = { v -> update { it.copy(epgRefreshHours = v) }; close() },
            onDismiss = close
        )
        LiveDialog.FutureDays -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_guide_days_ahead),
            options = FUTURE_DAYS.map { SettingsPickerOption(it, context.resources.getQuantityString(R.plurals.iptv_settings_days, it, it)) },
            selectedValue = s.epgFutureDays,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgFutureDays = v) }; close() },
            onDismiss = close
        )
        LiveDialog.SortChoice -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_channel_order_2),
            options = listOf(
                SettingsPickerOption(ChannelSort.PLAYLIST, context.getString(R.string.iptv_settings_playlist_order)),
                SettingsPickerOption(ChannelSort.NUMBER, context.getString(R.string.iptv_settings_channel_number)),
                SettingsPickerOption(ChannelSort.NAME, context.getString(R.string.iptv_settings_name_a_z))
            ),
            selectedValue = s.channelSort,
            onOptionSelected = { v -> update { it.copy(channelSort = v) }; close() },
            onDismiss = close
        )
        LiveDialog.HighlightChoice -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_highlight_style_2),
            options = listOf(
                SettingsPickerOption(false, context.getString(R.string.iptv_settings_outline), context.getString(R.string.iptv_settings_dark_tint_with_a_colored_border_logos_stay)),
                SettingsPickerOption(true, context.getString(R.string.iptv_settings_solid), context.getString(R.string.iptv_settings_fill_with_the_theme_s_accent_color))
            ),
            selectedValue = s.solidHighlight,
            onOptionSelected = { v -> update { it.copy(solidHighlight = v) }; close() },
            onDismiss = close
        )
        LiveDialog.CustomGroups -> UnhideDialog(
            title = context.getString(R.string.iptv_settings_my_groups_2),
            entries = user.customGroups.map { it.id to "${it.name} (${it.channelKeys.size})" },
            onUnhide = { viewModel.deleteCustomGroup(it) },
            onUnhideAll = { user.customGroups.forEach { g -> viewModel.deleteCustomGroup(g.id) }; close() },
            onDismiss = close,
            itemAction = context.getString(R.string.action_delete),
            allLabel = context.getString(R.string.iptv_settings_delete_all),
            emptyText = context.getString(R.string.iptv_settings_you_haven_t_made_any_groups_yet_long),
            hintText = context.getString(R.string.iptv_settings_select_a_group_to_delete_it_the_channels)
        )
        LiveDialog.ChannelRow -> ChannelRowDialog(
            settings = s,
            groups = remember(playlists, user.customGroups, user.groupNames) { viewModel.rowGroupOptions() },
            onUpdate = { t -> update(t) },
            onDismiss = close
        )
        LiveDialog.PastHours -> SettingsSingleChoiceDialog(
            title = context.getString(R.string.iptv_settings_past_guide_kept),
            options = PAST_HOURS.map { SettingsPickerOption(it, hoursLabel(context, it)) },
            selectedValue = s.epgPastHours,
            onOptionSelected = { v -> viewModel.updateAndReloadEpg { it.copy(epgPastHours = v) }; close() },
            onDismiss = close
        )
    }
}

// ==================================================================== loading line

/** A spinner and what's happening, while playlists, guides or On Demand are downloading. */
@Composable
private fun LoadingLine(message: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.nuvio.tv.livetv.ui.LoadingSpinner()
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
    }
}

// ==================================================================== Google Drive sync

/** Collapsed to one line until selected; then shows sign-in, backup and restore. */
@Composable
private fun DriveSyncCard(
    modifier: Modifier = Modifier,
    state: com.nuvio.tv.livetv.sync.DriveSyncState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onConnect: () -> Unit,
    onAutoSync: (Boolean) -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val status = when {
        !state.available -> context.getString(R.string.iptv_settings_not_available_in_this_build)
        !state.connected -> context.getString(R.string.playback_afr_off)
        state.busy -> context.getString(R.string.iptv_settings_syncing)
        state.lastSyncMs > 0 -> context.getString(R.string.iptv_settings_last_synced_at, DateFormat.getTimeInstance(DateFormat.SHORT, context.settingsLocale).format(Date(state.lastSyncMs)))
        else -> context.getString(R.string.iptv_settings_on)
    }
    LiveSection(
        title = context.getString(R.string.iptv_settings_backup_sync),
        summary = context.getString(R.string.iptv_settings_drive_status, status),
        icon = Icons.Default.Backup,
        open = expanded,
        onToggle = onToggleExpanded,
        modifier = modifier
    ) {
        run {
            when {
                !state.available -> Text(
                    text = context.getString(R.string.iptv_settings_google_drive_sync_needs_to_be_set_up),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                !state.connected -> {
                    Text(
                        text = context.getString(R.string.iptv_settings_your_playlists_guides_favorites_hidden_channels_and_live),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow(context.getString(R.string.iptv_settings_connect_google_drive), context.getString(R.string.iptv_settings_keeps_your_live_tv_setup_the_same_on), onClick = onConnect)
                }
                else -> {
                    SettingsActionRow(context.getString(R.string.iptv_settings_account_2), context.getString(R.string.iptv_settings_the_google_account_your_live_tv_setup_is), state.email ?: context.getString(R.string.iptv_settings_connected), onClick = {})
                    SettingsToggleRow(
                        context.getString(R.string.iptv_settings_sync_automatically),
                        context.getString(R.string.iptv_settings_back_up_changes_and_pick_up_changes_made),
                        state.autoSync,
                        { onAutoSync(!state.autoSync) }
                    )
                    SettingsActionRow(context.getString(R.string.iptv_settings_back_up_now_2), context.getString(R.string.iptv_settings_save_this_tv_s_setup_to_google_drive), onClick = onBackUp)
                    SettingsActionRow(context.getString(R.string.iptv_settings_restore_from_google_drive_2), context.getString(R.string.iptv_settings_replace_this_tv_s_setup_with_the_saved), onClick = onRestore)
                    SettingsActionRow(context.getString(R.string.iptv_settings_disconnect), context.getString(R.string.iptv_settings_stop_syncing_on_this_tv_your_backup_stays), onClick = onDisconnect)
                }
            }
            state.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun CloudSyncCard(
    modifier: Modifier = Modifier,
    state: com.nuvio.tv.livetv.sync.CloudSyncState,
    enabledParts: Set<com.nuvio.tv.livetv.sync.SyncPart>,
    onTogglePart: (com.nuvio.tv.livetv.sync.SyncPart) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTurnOn: () -> Unit,
    driveAvailable: Boolean,
    onImportDrive: () -> Unit,
    onSyncNow: () -> Unit,
    onRestorePrevious: () -> Unit,
    onTurnOff: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val status = when {
        !state.signedIn -> context.getString(R.string.iptv_settings_sign_in_to_nuvio_to_use_it)
        !state.enabled -> context.getString(R.string.playback_afr_off)
        state.busy -> context.getString(R.string.iptv_settings_syncing)
        state.lastSyncMs > 0 -> context.getString(R.string.iptv_settings_last_synced_at, DateFormat.getTimeInstance(DateFormat.SHORT, context.settingsLocale).format(Date(state.lastSyncMs)))
        else -> context.getString(R.string.iptv_settings_on)
    }
    LiveSection(
        title = context.getString(R.string.iptv_settings_setup_sync),
        summary = status,
        icon = Icons.Default.Cloud,
        open = expanded,
        onToggle = onToggleExpanded,
        modifier = modifier
    ) {
        run {
            when {
                !state.signedIn -> Text(
                    text = context.getString(R.string.iptv_settings_sign_in_to_your_nuvio_account_settings_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                !state.enabled -> {
                    Text(
                        text = context.getString(R.string.iptv_settings_your_playlists_including_their_logins_guides_favorites_sports),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow(context.getString(R.string.iptv_settings_turn_on), context.getString(R.string.iptv_settings_save_this_tv_s_setup_and_keep_every), onClick = onTurnOn)
                    if (driveAvailable) SettingsActionRow(context.getString(R.string.iptv_settings_import_from_google_drive_2), context.getString(R.string.iptv_settings_bring_your_google_drive_backup_over_to_this), onClick = onImportDrive)
                }
                else -> {
                    Text(
                        text = context.getString(R.string.iptv_settings_keeps_your_playlists_and_settings_the_same_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    SettingsActionRow(context.getString(R.string.iptv_settings_account_2), context.getString(R.string.iptv_settings_the_nuvio_sign_in_this_setup_follows), state.email ?: context.getString(R.string.iptv_settings_signed_in), onClick = {})
                    SubHeading(context.getString(R.string.iptv_settings_what_to_sync))
                    com.nuvio.tv.livetv.sync.SyncPart.values().forEach { part ->
                        val (title, description) = syncPartLabels(context, part)
                        SettingsToggleRow(title, description, part in enabledParts, { onTogglePart(part) })
                    }
                    SettingsActionRow(context.getString(R.string.iptv_settings_sync_now), context.getString(R.string.iptv_settings_changes_also_sync_by_themselves_a_few_seconds), onClick = onSyncNow)
                    SettingsActionRow(context.getString(R.string.iptv_settings_restore_earlier_copy), context.getString(R.string.iptv_settings_go_back_to_the_copy_saved_before_the), onClick = onRestorePrevious)
                    if (driveAvailable) SettingsActionRow(context.getString(R.string.iptv_settings_import_from_google_drive_2), context.getString(R.string.iptv_settings_bring_your_google_drive_backup_over_to_this), onClick = onImportDrive)
                    SettingsActionRow(context.getString(R.string.iptv_settings_turn_off_setup_sync), context.getString(R.string.iptv_settings_stop_syncing_on_this_tv_the_saved_copy), onClick = onTurnOff)
                    SettingsActionRow(context.getString(R.string.iptv_settings_delete_saved_setup), context.getString(R.string.iptv_settings_remove_the_saved_copy_and_stop_syncing), onClick = onDelete)
                }
            }
            state.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun DriveSignInDialog(prompt: com.nuvio.tv.livetv.sync.SignInPrompt, onCancel: () -> Unit) {
    val context = LocalContext.current
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    // Same layout as Nuvio's other QR sign-ins (debrid, Simkl): instructions, QR code, then the code.
    val qrBitmap = remember(prompt.qrUrl) {
        runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(prompt.qrUrl, 420, margin = 1) }.getOrNull()
    }
    val shortUrl = prompt.url.removePrefix("https://").removePrefix("http://")
    NuvioDialog(onDismiss = onCancel, title = context.getString(R.string.iptv_settings_connect_google_drive), width = 560.dp) {
        Text(
            text = context.getString(R.string.iptv_settings_scan_the_qr_code_with_your_phone_or, shortUrl),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (qrBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = context.getString(R.string.iptv_settings_qr_code),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(196.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
        Text(
            text = prompt.userCode,
            style = MaterialTheme.typography.displaySmall,
            color = NuvioTheme.colors.Secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = shortUrl,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = context.getString(R.string.iptv_settings_waiting_for_you_to_allow_access_this_screen),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = context.getString(R.string.action_cancel), onClick = onCancel)
            }
        }
    }
}

// ==================================================================== dialogs

/**
 * "Add from your phone": a QR code opens a page on the home network where a playlist link, an
 * Xtream login or an EPG link can be pasted on the phone. Whatever is sent is added right away
 * (the code in the address means only someone who can see this screen can use it).
 */
@Composable
private fun AddFromPhoneDialog(viewModel: LiveTvSettingsViewModel, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val closeFocus = remember { FocusRequester() }
    var handle by remember { mutableStateOf<com.nuvio.tv.livetv.crash.AddFromPhoneServer.Handle?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(listOf<String>()) }
    val logoRes = com.nuvio.tv.livetv.branding.LiveTvBranding.iptvWordmark(NuvioTheme.currentTheme.brandWordmarkResource)
    val accentArgb = NuvioTheme.colors.Secondary.toArgb()
    val phoneLocale = context.settingsLocale
    val tvName = remember(context) {
        runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
            ?: context.getString(R.string.iptv_settings_your_tv)
    }
    val phoneTexts = remember(context, tvName) {
        com.nuvio.tv.livetv.crash.AddFromPhoneServer.Texts.from(context.resources, tvName)
    }
    val logoBytes = remember(context, logoRes) {
        runCatching { context.resources.openRawResource(logoRes).use { it.readBytes() } }.getOrNull()
    }
    // The server holds immutable text/bytes only, so no Activity survives in its callbacks.
    val receiptTemplates = remember(context) {
        listOf(
            R.string.iptv_settings_1_s_guide,
            R.string.iptv_settings_added_1_s_it_s_loading_on_your,
            R.string.iptv_settings_added_playlist_guide,
            R.string.iptv_settings_playlist,
            R.string.iptv_settings_playlist_1_s,
            R.string.iptv_settings_tv_guide_1_s,
            R.string.iptv_settings_xtream_login_1_s
        ).associateWith { context.getString(it) }
    }
    androidx.compose.runtime.DisposableEffect(phoneTexts, phoneLocale, logoBytes, accentArgb, viewModel) {
        fun receipt(id: Int, vararg args: Any): String =
            String.format(phoneLocale, receiptTemplates.getValue(id), *args)
        val h = com.nuvio.tv.livetv.crash.AddFromPhoneServer.start(
            texts = phoneTexts,
            accent = String.format(java.util.Locale.US, "#%06X", accentArgb and 0xFFFFFF),
            logo = { logoBytes }
        ) { e ->
            val line = when (e.kind) {
                "xtream" -> {
                    val name = e.name.ifBlank { "Xtream" }
                    viewModel.addXtream(name, e.server, e.username, e.password, importVod = e.includeVod, importLive = e.includeLive)
                    receipt(R.string.iptv_settings_xtream_login_1_s, name)
                }
                "epg" -> {
                    val name = e.name.ifBlank { "EPG" }
                    viewModel.addEpg(name, e.url)
                    receipt(R.string.iptv_settings_tv_guide_1_s, name)
                }
                else -> {
                    val name = e.name.ifBlank { receipt(R.string.iptv_settings_playlist) }
                    viewModel.addPlaylist(name, e.url, e.userAgent)
                    if (e.epgUrl.isNotBlank()) viewModel.addEpg(receipt(R.string.iptv_settings_1_s_guide, name), e.epgUrl)
                    if (e.epgUrl.isNotBlank()) receipt(R.string.iptv_settings_added_playlist_guide, name) else receipt(R.string.iptv_settings_playlist_1_s, name)
                }
            }
            added = added + line
            receipt(R.string.iptv_settings_added_1_s_it_s_loading_on_your, line)
        }
        handle = h
        noNetwork = h == null
        onDispose { h?.stop() }
    }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { closeFocus.requestFocus() }
    }
    val qrBitmap = remember(handle?.url) {
        handle?.url?.let { runCatching { com.nuvio.tv.core.qr.QrCodeGenerator.generate(it, 420, margin = 1) }.getOrNull() }
    }
    NuvioDialog(onDismiss = onDismiss, title = context.getString(R.string.iptv_settings_add_from_your_phone), width = 620.dp) {
        if (noNetwork) {
            Text(
                text = context.getString(R.string.iptv_settings_connect_this_tv_to_your_home_wi_fi),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                qrBitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = context.getString(R.string.iptv_settings_qr_code),
                        modifier = Modifier.size(196.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    Text(
                        text = context.getString(R.string.iptv_settings_1_put_your_phone_on_the_same_wi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    handle?.url?.let {
                        Text(
                            text = context.getString(R.string.iptv_settings_phone_address, it.removePrefix("http://")),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
            Text(
                text = if (added.isEmpty()) context.getString(R.string.iptv_settings_waiting_for_your_phone_you_can_add_more)
                else context.getString(R.string.iptv_settings_added_items, added.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = if (added.isEmpty()) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Secondary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(closeFocus)) {
                SettingsDialogActionButton(text = if (added.isEmpty()) context.getString(R.string.action_close) else context.getString(R.string.iptv_settings_done), onClick = onDismiss)
            }
        }
    }
}

/** Provider identity is explicit; no MAC/device identifiers are generated or guessed. */
@Composable
private fun StalkerSourceDialog(
    source: PlaylistSource? = null,
    onDismiss: () -> Unit,
    onSave: (String, com.nuvio.stalker.StalkerConfig, Boolean, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    val config = source?.stalkerConfig
    SourceFormDialog(
        title = if (source == null) context.getString(R.string.iptv_settings_add_stalker_ministra_portal_2) else context.getString(R.string.iptv_settings_edit_stalker_ministra_portal),
        fields = listOf(
            FormField(context.getString(R.string.iptv_settings_name), context.getString(R.string.iptv_settings_my_provider), source?.name.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_portal_url), "https://…/stalker_portal/c/", config?.portalUrl.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_provider_issued_mac_address), "00:00:00:00:00:00", config?.mac.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_username_optional), "", config?.username.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_password_optional), "", config?.password.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_serial_number_optional), "", config?.serialNumber.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_device_id_optional), "", config?.deviceId.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_device_id_2_optional), "", config?.deviceId2.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_signature_optional), "", config?.signature.orEmpty()),
            FormField(context.getString(R.string.iptv_settings_provider_time_zone), "UTC", config?.timezone ?: "UTC"),
            FormField(context.getString(R.string.iptv_settings_language), "en", config?.language ?: "en")
        ),
        advancedFrom = 3,
        preserveWhitespaceAt = setOf(3, 4, 5, 6, 7, 8),
        validate = { values ->
            val address = runCatching { java.net.URI(values[1]) }.getOrNull()
            when {
                address?.scheme?.lowercase() !in listOf("http", "https") || address?.host.isNullOrBlank() || address?.rawUserInfo != null -> context.getString(R.string.iptv_settings_enter_the_full_http_or_https_portal_url)
                !Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$").matches(values[2]) -> context.getString(R.string.iptv_settings_enter_the_mac_address_supplied_by_your_provider)
                else -> null
            }
        },
        toggle = context.getString(R.string.iptv_settings_load_built_in_guide) to (source?.useEmbeddedEpg ?: true),
        toggleLive = context.getString(R.string.iptv_settings_include_tv_channels) to (source?.importLive ?: true),
        toggle2 = context.getString(R.string.iptv_settings_include_movies_series_on_demand) to (source?.importVod ?: true),
        onDismiss = onDismiss,
        onSave = { _, _ -> },
        onSaveAll = { values, guide, vod, live ->
            onSave(values[0], com.nuvio.stalker.StalkerConfig(values[1], values[2], values[3], values[4], values[5],
                values[6], values[7], values[8], values[9].ifBlank { "UTC" }, values[10].ifBlank { "en" }), guide, vod, live)
        }
    )
}

private data class FormField(val label: String, val hint: String, val initial: String = "")

@Composable
private fun SourceFormDialog(
    title: String,
    fields: List<FormField>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Boolean) -> Unit,
    toggle: Pair<String, Boolean>? = null,
    /** A second switch (Xtream: import movies & series); its value goes to [onSaveBoth]. */
    toggle2: Pair<String, Boolean>? = null,
    onSaveBoth: ((List<String>, Boolean, Boolean) -> Unit)? = null,
    /** Xtream: "Include TV channels", shown first. Its value goes to [onSaveAll]. */
    toggleLive: Pair<String, Boolean>? = null,
    onSaveAll: ((values: List<String>, guide: Boolean, vod: Boolean, live: Boolean) -> Unit)? = null,
    advancedFrom: Int? = null,
    validate: ((List<String>) -> String?)? = null,
    preserveWhitespaceAt: Set<Int> = emptySet()
) {
    val context = LocalContext.current
    val values = remember { fields.map { mutableStateOf(it.initial) } }
    var showAdvanced by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var toggleValue by remember { mutableStateOf(toggle?.second ?: true) }
    var toggle2Value by remember { mutableStateOf(toggle2?.second ?: true) }
    var toggleLiveValue by remember { mutableStateOf(toggleLive?.second ?: true) }
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstField.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 620.dp) {
        // The fields scroll inside the dialog, so Save and Cancel always stay on screen
        // (with a user agent field and the guide switch, they used to fall off the bottom).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
        fields.forEachIndexed { i, field ->
            if (advancedFrom != null && i == advancedFrom) SettingsToggleRow(
                title = context.getString(R.string.iptv_settings_advanced_provider_settings), subtitle = context.getString(R.string.iptv_settings_only_enter_values_supplied_by_your_provider),
                checked = showAdvanced, onToggle = { showAdvanced = !showAdvanced })
            if (advancedFrom == null || i < advancedFrom || showAdvanced) Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                NuvioTextField(
                    value = values[i].value,
                    onValueChange = { values[i].value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = field.hint,
                    focusRequester = if (i == 0) firstField else null
                )
            }
        }
        if (toggleLive != null) {
            SettingsToggleRow(
                title = toggleLive.first,
                subtitle = context.getString(R.string.iptv_settings_adds_the_provider_s_live_channels_to_the),
                checked = toggleLiveValue,
                onToggle = { toggleLiveValue = !toggleLiveValue }
            )
        }
        // The guide switch only matters when TV channels are included.
        if (toggle != null && (toggleLive == null || toggleLiveValue)) {
            SettingsToggleRow(
                title = toggle.first,
                subtitle = null,
                checked = toggleValue,
                onToggle = { toggleValue = !toggleValue }
            )
        }
        if (toggle2 != null) {
            SettingsToggleRow(
                title = toggle2.first,
                subtitle = context.getString(R.string.iptv_settings_adds_the_provider_s_movies_and_series_to),
                checked = toggle2Value,
                onToggle = { toggle2Value = !toggle2Value }
            )
        }
        }
        formError?.let { Text(it, color = NuvioTheme.colors.Error, style = MaterialTheme.typography.bodySmall) }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = context.getString(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = context.getString(R.string.action_save),
                onClick = {
                    val v = values.mapIndexed { i, value -> if (i in preserveWhitespaceAt) value.value else value.value.trim() }
                    formError = validate?.invoke(v)
                    if (formError == null) when {
                        onSaveAll != null -> onSaveAll(v, toggleValue, toggle2Value, toggleLiveValue)
                        onSaveBoth != null -> onSaveBoth(v, toggleValue, toggle2Value)
                        else -> onSave(v, toggleValue)
                    }
                },
                primary = true
            )
        }
    }
}

@Composable
private fun ActionListDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, width = 460.dp) {
        // Scrollable: on a 1080p TV not every option fits, and the last ones were unreachable.
        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            itemsIndexed(actions) { i, (label, action) ->
                SettingsActionRow(
                    title = label,
                    subtitle = null,
                    onClick = action,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = stringResource(R.string.action_delete)
) {
    val context = LocalContext.current
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { cancelFocus.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = message, width = 520.dp) {
        SettingsDialogActionRow {
            // Cancel is focused first, so a stray OK press can't delete anything.
            androidx.compose.foundation.layout.Box(Modifier.focusRequester(cancelFocus)) {
                SettingsDialogActionButton(text = context.getString(R.string.action_cancel), onClick = onDismiss)
            }
            SettingsDialogActionButton(text = confirmLabel, onClick = onConfirm, primary = true)
        }
    }
}

/** The restore prompt's text: what's replaced, when the saved copy was made and where, and a warning when it's old. */
private fun restoreMessage(context: Context, loaded: Boolean, info: com.nuvio.tv.livetv.sync.LiveTvDriveSync.BackupInfo?, localChangedAt: Long): String {
    val base = context.getString(R.string.iptv_settings_this_tv_s_live_tv_setup_playlists_guides)
    if (!loaded) return context.getString(R.string.iptv_settings_1_s_checking_when_it_was_saved, base)
    if (info == null || info.savedAtMs <= 0L) return base
    val now = System.currentTimeMillis()
    val days = ((now - info.savedAtMs) / (24 * 60 * 60_000L)).toInt()
    val ago = when {
        now - info.savedAtMs < 60 * 60_000L -> context.getString(R.string.iptv_settings_less_than_an_hour_ago)
        days < 1 -> context.getString(R.string.iptv_settings_today)
        days == 1 -> context.getString(R.string.iptv_settings_yesterday)
        days < 14 -> context.resources.getQuantityString(R.plurals.iptv_settings_days_ago, days, days)
        days < 60 -> context.resources.getQuantityString(R.plurals.iptv_settings_weeks_ago, days / 7, days / 7)
        else -> context.resources.getQuantityString(R.plurals.iptv_settings_months_ago, days / 30, days / 30)
    }
    val stamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, context.settingsLocale).format(java.util.Date(info.savedAtMs))
    val where = if (info.fromThisTv) context.getString(R.string.iptv_settings_this_tv) else info.device
    val saved = context.getString(R.string.iptv_settings_saved_1_s_on_2_s_3_s, stamp, where, ago)
    val warning = when {
        localChangedAt > info.savedAtMs + 60_000L ->
            context.getString(R.string.iptv_settings_this_tv_has_changes_made_after_that_copy)
        days >= 7 ->
            context.getString(R.string.iptv_settings_that_copy_is_1_s_if_your_playlists, ago)
        else -> null
    }
    return listOfNotNull(base, saved, warning).joinToString("\n\n")
}

@Composable
private fun UnhideDialog(
    title: String,
    entries: List<Pair<String, String>>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit,
    itemAction: String = stringResource(R.string.iptv_settings_show),
    allLabel: String = stringResource(R.string.iptv_settings_show_all),
    emptyText: String = stringResource(R.string.iptv_settings_nothing_is_hidden),
    hintText: String = stringResource(R.string.iptv_settings_select_an_item_to_show_it_again)
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = if (entries.isEmpty()) emptyText else hintText,
        width = 560.dp
    ) {
        if (entries.isEmpty()) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = context.getString(R.string.action_close), onClick = onDismiss, primary = true)
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                item(key = "__all__") {
                    SettingsActionRow(
                        title = allLabel,
                        subtitle = null,
                        value = "${entries.size}",
                        onClick = onUnhideAll,
                        modifier = Modifier.focusRequester(first)
                    )
                }
                items(entries, key = { it.first }) { (key, label) ->
                    SettingsActionRow(title = label, subtitle = null, value = itemAction, onClick = { onUnhide(key) })
                }
            }
        }
    }
}

/**
 * One folding section of Live TV settings, in Nuvio's own settings style: a header row with the
 * section's icon, name and a one-line summary; OK opens or closes it.
 */
@Composable
private fun LiveSection(
    title: String,
    summary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Flat tabs: the first section takes the focus coming in (its first row). */
    focusFirst: FocusRequester? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    if (LocalLiveFlat.current) {
        // A sub-tab: sections sit open under a plain heading (nothing to fold, only a few per tab).
        Column(
            if (focusFirst != null) Modifier.focusRequester(focusFirst).focusGroup() else Modifier
        ) {
            SettingsGroupCard {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xxs),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    androidx.tv.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                        Text(text = summary, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                    }
                }
                content()
            }
        }
        return
    }
    SettingsGroupCard {
        SettingsActionRow(
            title = title,
            subtitle = summary,
            leadingIcon = icon,
            trailingIcon = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            onClick = onToggle,
            modifier = modifier
        )
        if (open) content()
    }
}

/** A small label inside an open section ("Top of the Guide"), like Nuvio's group titles. */
@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun SubHeading(text: String) {
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextTertiary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.lg, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
    )
}

private fun streamsPositionLabel(context: Context, v: String): String = when (v) {
    "FIRST" -> context.getString(R.string.iptv_settings_first)
    "AFTER_FIRST" -> context.getString(R.string.iptv_settings_after_the_first_addon)
    else -> context.getString(R.string.iptv_settings_last)
}

private fun searchSectionLabel(context: Context, kind: String): String = when (kind) {
    "live" -> context.getString(R.string.iptv_settings_live_tv_channels)
    "ondemand" -> context.getString(R.string.iptv_settings_on_demand_your_provider)
    else -> context.getString(R.string.iptv_settings_nuvio_catalogs_addons)
}

/**
 * The search sections in order. OK picks one up (it's marked), Up and Down move it, OK again
 * puts it down: the same way Reorder groups works in the guide.
 */
@Composable
private fun SearchOrderList(order: List<String>, onChange: (List<String>) -> Unit) {
    val context = LocalContext.current
    var moving by remember { mutableStateOf<String?>(null) }
    val requesters = remember { order.associateWith { FocusRequester() } }
    order.forEachIndexed { i, kind -> androidx.compose.runtime.key(kind) {
        val isMoving = moving == kind
        SettingsActionRow(
            title = "${i + 1}   ${searchSectionLabel(context, kind)}",
            subtitle = if (isMoving) context.getString(R.string.iptv_settings_up_down_to_move_it_ok_to_put) else null,
            value = if (isMoving) context.getString(R.string.iptv_settings_moving) else null,
            onClick = { moving = if (isMoving) null else kind },
            modifier = Modifier
                .focusRequester(requesters.getValue(kind))
                .onPreviewKeyEvent { e ->
                    if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val to = i + step
                    if (to in order.indices) {
                        onChange(order.toMutableList().apply { add(to, removeAt(i)) })
                    }
                    true // stays on the row being moved
                }
        )
    } }
    // The moved row keeps the highlight in its new place.
    LaunchedEffect(order, moving) {
        moving?.let { runCatching { requesters.getValue(it).requestFocus() } }
    }
}



/** Translate display labels without changing stored theme or sync identifiers. */
private fun guideBackdropLabel(context: Context, id: String): String = context.getString(
    when (com.nuvio.tv.livetv.ui.GuideBackdrops.normalize(id)) {
        "onyx" -> R.string.iptv_settings_backdrop_onyx
        "original" -> R.string.iptv_settings_backdrop_original
        "arctic" -> R.string.iptv_settings_backdrop_arctic
        "emerald" -> R.string.iptv_settings_backdrop_emerald
        "rose" -> R.string.iptv_settings_backdrop_rose
        "copper" -> R.string.iptv_settings_backdrop_copper
        "graphite" -> R.string.iptv_settings_backdrop_graphite
        else -> R.string.iptv_settings_backdrop_match_theme
    }
)

private fun syncPartLabels(context: Context, part: com.nuvio.tv.livetv.sync.SyncPart): Pair<String, String> {
    val (title, description) = when (part) {
        com.nuvio.tv.livetv.sync.SyncPart.LOGINS -> R.string.iptv_settings_sync_logins to R.string.iptv_settings_sync_logins_description
        com.nuvio.tv.livetv.sync.SyncPart.VPN -> R.string.iptv_settings_sync_vpn to R.string.iptv_settings_sync_vpn_description
        com.nuvio.tv.livetv.sync.SyncPart.FAVORITES -> R.string.iptv_settings_sync_favorites to R.string.iptv_settings_sync_favorites_description
        com.nuvio.tv.livetv.sync.SyncPart.SPORTS -> R.string.iptv_settings_sports to R.string.iptv_settings_sync_sports_description
        com.nuvio.tv.livetv.sync.SyncPart.CALENDAR -> R.string.iptv_settings_sync_calendar to R.string.iptv_settings_sync_calendar_description
        com.nuvio.tv.livetv.sync.SyncPart.LOOK -> R.string.iptv_settings_sync_look to R.string.iptv_settings_sync_look_description
    }
    return context.getString(title) to context.getString(description)
}

private fun expiryLabel(context: Context, account: com.nuvio.tv.livetv.model.PlaylistAccount): String? =
    account.expiryLabel(locale = context.settingsLocale) { days, expired ->
        when {
            expired -> context.getString(R.string.iptv_settings_expiry_expired)
            days <= 0 -> context.getString(R.string.iptv_settings_today)
            else -> context.resources.getQuantityString(R.plurals.iptv_settings_days, days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), days)
        }
    }
