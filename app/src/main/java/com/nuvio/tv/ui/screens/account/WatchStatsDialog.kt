package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.watchstats.WatchStatsBreakdown
import com.nuvio.tv.data.watchstats.WatchStatsRange
import com.nuvio.tv.data.watchstats.WatchStatsSummary
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.profile.ProfileUnlockDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsChoiceChip
import com.nuvio.tv.ui.screens.settings.SettingsDialogActionButton
import com.nuvio.tv.ui.screens.settings.SettingsPickerOption
import com.nuvio.tv.ui.screens.settings.SettingsSingleChoiceDialog
import com.nuvio.tv.ui.screens.settings.SettingsToggleRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface StatsLoad<out T> {
    data object Loading : StatsLoad<Nothing>
    data object Failed : StatsLoad<Nothing>
    data class Ready<T>(val result: T) : StatsLoad<T>
}

@Composable
internal fun WatchStatsDialog(onDismiss: () -> Unit, viewModel: WatchStatsViewModel = hiltViewModel()) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val profileReady by viewModel.profileReady.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    key(authState, activeId) {
        WatchStatsForAccount(onDismiss, viewModel, profiles, activeId.takeIf { profileReady })
    }
}

private data class ProfileGrant(val profile: UserProfile, val pinVerified: Boolean = false)

@Composable
private fun WatchStatsForAccount(
    onDismiss: () -> Unit,
    viewModel: WatchStatsViewModel,
    profiles: List<UserProfile>,
    activeId: Int?,
) {
    val locks by viewModel.profileLocks.collectAsStateWithLifecycle(initialValue = emptyMap())
    val tracking by viewModel.trackingEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val trackingFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }
    var profilePicker by remember { mutableStateOf(false) }
    var profileRequest by remember { mutableStateOf<UserProfile?>(null) }
    var pinProfile by remember { mutableStateOf<UserProfile?>(null) }
    var grant by remember { mutableStateOf<ProfileGrant?>(null) }
    var range by remember { mutableStateOf(WatchStatsRange.ALL_TIME) }
    var status by remember { mutableStateOf<Int?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var savingTracking by remember { mutableStateOf(false) }

    val profile = grant?.takeIf { selection ->
        selection.profile in profiles && (selection.pinVerified || locks[selection.profile.id] != true)
    }?.profile ?: profiles.firstOrNull { it.id == activeId }
    val canSelectProfile = activeId != null && profiles.size > 1
    val openPin = pinProfile?.takeIf { it in profiles }

    LaunchedEffect(profileRequest) {
        val requested = profileRequest ?: return@LaunchedEffect
        if (requested.id == activeId) {
            grant = null
        } else {
            when (try {
                viewModel.profileAccess(requested)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                WatchStatsProfileAccess.UNAVAILABLE
            }) {
                WatchStatsProfileAccess.ALLOWED -> grant = ProfileGrant(requested)
                WatchStatsProfileAccess.PIN_REQUIRED -> pinProfile = requested
                WatchStatsProfileAccess.UNAVAILABLE -> status = R.string.watch_stats_profile_unavailable
            }
        }
        profileRequest = null
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.watch_stats_title),
        width = 720.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = 8.dp,
        contentPadding = 18.dp,
    ) {
        SettingsToggleRow(
            title = stringResource(R.string.watch_stats_track),
            subtitle = stringResource(R.string.watch_stats_track_detail),
            checked = tracking,
            modifier = Modifier.focusRequester(trackingFocus),
            onToggle = {
                if (!savingTracking) {
                    savingTracking = true
                    status = null
                    scope.launch {
                        try {
                            viewModel.setTrackingEnabled(!tracking)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = R.string.watch_stats_tracking_failed
                        } finally {
                            savingTracking = false
                        }
                    }
                }
            },
        )
        if (canSelectProfile) {
            SettingsActionRow(
                title = stringResource(R.string.watch_stats_profile),
                subtitle = if (profileRequest != null) stringResource(R.string.watch_stats_check_access) else null,
                value = profile?.name,
                modifier = Modifier.focusRequester(profileFocus),
                onClick = {
                    if (profileRequest == null) {
                        status = null
                        profilePicker = true
                    }
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchStatsRange.entries.forEach { item ->
                SettingsChoiceChip(
                    label = stringResource(item.labelRes),
                    selected = range == item,
                    onClick = { range = item },
                )
            }
        }
        status?.let { StatsNote(stringResource(it), error = true) }
        val modifier = Modifier.weight(1f, fill = false)
        if (profile == null) {
            StatsNote(stringResource(R.string.watch_stats_loading))
        } else {
            WatchStatsReport(viewModel, profile.id, range, tracking, modifier)
        }
        SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onDismiss)

        LaunchedEffect(Unit) { trackingFocus.requestFocusAfterFrames() }
    }

    if (profilePicker) {
        fun closePicker() {
            profilePicker = false
            scope.launch { profileFocus.requestFocusAfterFrames() }
        }
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.watch_stats_profile),
            options = profiles.map { candidate ->
                SettingsPickerOption(
                    value = candidate.id,
                    title = candidate.name,
                    description = if (candidate.id != activeId && locks[candidate.id] == true) {
                        stringResource(R.string.watch_stats_pin_required)
                    } else null,
                )
            },
            selectedValue = profile?.id ?: activeId ?: 1,
            onOptionSelected = { id ->
                profileRequest = profiles.firstOrNull { it.id == id }
                closePicker()
            },
            onDismiss = ::closePicker,
        )
    }

    if (openPin != null) {
        fun closePin() {
            if (verifying) return
            pinProfile = null
            pinError = null
            scope.launch { profileFocus.requestFocusAfterFrames() }
        }
        ProfileUnlockDialog(
            profile = openPin,
            isWorking = verifying,
            errorMessage = pinError,
            onClearError = { pinError = null },
            onDismiss = ::closePin,
            onSubmit = { pin ->
                if (!verifying) {
                    verifying = true
                    scope.launch {
                        try {
                            when (val result = viewModel.verifyPin(openPin, pin)) {
                                ProfilePinCheck.Unlocked -> {
                                    grant = ProfileGrant(openPin, pinVerified = true)
                                    pinProfile = null
                                    pinError = null
                                    profileFocus.requestFocusAfterFrames()
                                }
                                ProfilePinCheck.Unavailable -> pinError = context.getString(R.string.profile_pin_verify_error)
                                is ProfilePinCheck.Rejected -> pinError = if (result.retryAfterSeconds > 0) {
                                    context.getString(R.string.profile_pin_locked, result.retryAfterSeconds)
                                } else context.getString(R.string.profile_pin_invalid)
                            }
                        } finally {
                            verifying = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun WatchStatsReport(
    viewModel: WatchStatsViewModel,
    profileId: Int,
    range: WatchStatsRange,
    tracking: Boolean,
    modifier: Modifier,
) {
    val lifetime = key(profileId) {
        val counts = remember(profileId) {
            viewModel.lifetimeWatched(profileId)
                .map<LifetimeWatchedTotals, StatsLoad<LifetimeWatchedTotals>> { StatsLoad.Ready(it) }
                .catch { emit(StatsLoad.Failed) }
        }
        counts.collectAsStateWithLifecycle(initialValue = StatsLoad.Loading).value
    }
    val activity = key(profileId, range, tracking) {
        produceState<StatsLoad<WatchStatsSummary>>(initialValue = StatsLoad.Loading) {
            value = try {
                StatsLoad.Ready(viewModel.read(profileId, range))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                StatsLoad.Failed
            }
        }.value
    }

    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 72.dp.roundToPx() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(profileId, range) { scroll.scrollTo(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, RoundedCornerShape(8.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionUp -> -step
                    Key.DirectionDown -> step
                    else -> return@onPreviewKeyEvent false
                }
                val target = (scroll.value + delta).coerceIn(0, scroll.maxValue)
                if (target == scroll.value) return@onPreviewKeyEvent false
                scope.launch { scroll.scrollTo(target) }
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .verticalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsHeading(stringResource(R.string.watch_stats_watched_totals))
        StatsNote(stringResource(R.string.watch_stats_watched_detail))
        when (lifetime) {
            StatsLoad.Loading -> StatsNote(stringResource(R.string.watch_stats_loading))
            StatsLoad.Failed -> StatsNote(stringResource(R.string.watch_stats_read_failed), error = true)
            is StatsLoad.Ready -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val totals = lifetime.result
                WatchMetric(stringResource(R.string.watch_stats_movies), totals.movies.toString(), Modifier.weight(1f))
                WatchMetric(stringResource(R.string.watch_stats_episodes), totals.episodes.toString(), Modifier.weight(1f))
                WatchMetric(stringResource(R.string.watch_stats_shows), totals.shows.toString(), Modifier.weight(1f))
            }
        }
        StatsHeading(stringResource(R.string.watch_stats_activity))
        if (!tracking) StatsNote(stringResource(R.string.watch_stats_paused))
        when (activity) {
            StatsLoad.Loading -> StatsNote(stringResource(R.string.watch_stats_loading))
            StatsLoad.Failed -> StatsNote(stringResource(R.string.watch_stats_read_failed), error = true)
            is StatsLoad.Ready -> ActivityStats(activity.result)
        }
    }
}

@Composable
private fun ActivityStats(summary: WatchStatsSummary) {
    if (summary.liveMillis == 0L && summary.onDemandMillis == 0L &&
        summary.moviePlays == 0L && summary.episodePlays == 0L
    ) {
        StatsNote(stringResource(R.string.watch_stats_no_activity))
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchMetric(stringResource(R.string.watch_stats_live_time), watchDuration(summary.liveMillis), Modifier.weight(1f))
        WatchMetric(stringResource(R.string.watch_stats_on_demand_time), watchDuration(summary.onDemandMillis), Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchMetric(stringResource(R.string.watch_stats_movie_plays), summary.moviePlays.toString(), Modifier.weight(1f))
        WatchMetric(stringResource(R.string.watch_stats_episode_plays), summary.episodePlays.toString(), Modifier.weight(1f))
    }
    WatchBreakdown(
        stringResource(R.string.watch_stats_top_channels),
        stringResource(R.string.watch_stats_no_channels),
        summary.channels,
    ) { watchDuration(it.durationMillis) }
    val genresByPlays = remember(summary.genres) {
        summary.genres.sortedWith(compareByDescending<WatchStatsBreakdown> { it.moviePlays + it.episodePlays }
            .thenByDescending { it.durationMillis })
    }
    WatchBreakdown(
        stringResource(R.string.watch_stats_top_genres),
        stringResource(R.string.watch_stats_no_genres),
        genresByPlays,
    ) { stringResource(R.string.watch_stats_genre_plays, it.moviePlays, it.episodePlays) }
}

@Composable
private fun WatchBreakdown(
    title: String,
    empty: String,
    entries: List<WatchStatsBreakdown>,
    value: @Composable (WatchStatsBreakdown) -> String,
) {
    StatsHeading(title)
    if (entries.isEmpty()) StatsNote(empty)
    entries.take(5).forEach { entry ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StatsNote(value(entry))
        }
    }
}

@Composable
private fun WatchMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.Bold)
        StatsNote(label)
    }
}

@Composable
private fun StatsHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextPrimary, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatsNote(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary)
}

private val WatchStatsRange.labelRes: Int
    get() = when (this) {
        WatchStatsRange.TODAY -> R.string.watch_stats_today
        WatchStatsRange.THIS_WEEK -> R.string.watch_stats_this_week
        WatchStatsRange.ALL_TIME -> R.string.watch_stats_all_time
    }

@Composable
private fun watchDuration(durationMs: Long): String {
    val minutes = durationMs / 60_000
    return when {
        minutes >= 60 -> stringResource(R.string.watch_stats_hours_minutes, minutes / 60, minutes % 60)
        minutes == 0L && durationMs > 0L -> stringResource(R.string.watch_stats_under_minute)
        else -> stringResource(R.string.watch_stats_minutes, minutes)
    }
}
