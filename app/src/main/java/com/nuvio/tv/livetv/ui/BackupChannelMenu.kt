package com.nuvio.tv.livetv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * Long-press menu for a copy in the backup channels lists (the bar's Backups button and the
 * Right-twice flyout): the guide's usual channel options that make sense while watching —
 * switch to it, start the show over, record, favorites and groups, rename, renumber, hide.
 *
 * [onSwitch] plays this copy (and closes the lists); [onRestarted] is called after the show was
 * started over on it. [onDismiss] goes back to the list.
 */
@Composable
internal fun BackupChannelMenu(
    channel: LiveChannel,
    viewModel: LiveTvViewModel,
    playing: Boolean,
    onSwitch: () -> Unit,
    onRestarted: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf("menu") }
    val first = remember { FocusRequester() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val user by viewModel.userState.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val series by viewModel.seriesRules.collectAsStateWithLifecycle()
    val playlistNames by viewModel.playlistNames.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    when (step) {
        "rename" -> TextInputDialog(
            title = "Rename channel",
            initial = channel.name,
            hint = "Leave empty to use the playlist name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { viewModel.renameChannel(channel, it); onDismiss() }
        )
        "number" -> TextInputDialog(
            title = "Channel number",
            initial = channel.number.toString(),
            hint = "Leave empty to use the playlist number",
            confirmLabel = "Save",
            numeric = true,
            onDismiss = onDismiss,
            onConfirm = { viewModel.setChannelNumber(channel, it.trim().toIntOrNull()); onDismiss() }
        )
        "newgroup" -> TextInputDialog(
            title = "New group",
            initial = "",
            hint = "Group name",
            confirmLabel = "Save",
            onDismiss = onDismiss,
            onConfirm = { name -> viewModel.createGroupWith(name, channel); onDismiss() }
        )
        "group" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.uiState.value.customGroups,
            onDismiss = onDismiss,
            onPick = { g -> viewModel.addToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        "copy" -> GroupPickerDialog(
            channel = channel,
            groups = viewModel.copyTargets(channel),
            title = "Copy \"${channel.name}\" to…",
            onDismiss = onDismiss,
            onPick = { g -> viewModel.copyToGroup(g.id, channel); onDismiss() },
            onNewGroup = { step = "newgroup" }
        )
        "manual" -> com.nuvio.tv.livetv.record.ManualRecordingDialog(
            channel = channel,
            use24h = settings.use24HourClock,
            onSchedule = { title, start, stop ->
                LiveTvNotice.show(viewModel.scheduler.addManual(channel, title, start, stop))
                onDismiss()
            },
            onDismiss = onDismiss
        )
        else -> {
            val program = viewModel.currentProgram(channel.key, now)
            val isFavorite = channel.key in user.favorites
            val recordingNow = recordings.any { it.channelKey == channel.key && it.isRecording }
            val scheduled = program != null && schedules.any { it.channel.key == channel.key && it.startMs == program.startMs }
            val recordLabel = when {
                recordingNow -> "Stop recording"
                scheduled -> "Cancel recording"
                else -> "Record"
            }
            val seriesLabel = program?.let { p ->
                val n = com.nuvio.tv.livetv.data.LiveTvRepository.normalize(titleMarks(p.title).title)
                if (series.any { it.channel.key == channel.key && it.norm == n }) "Stop recording series" else "Record series"
            }
            val canRestart = viewModel.canWatchFromStart(channel)
            val bell = com.nuvio.tv.livetv.calendar.rememberLiveBell(program, channel)
            val from = playlistNames[channel.sourceId] ?: channel.sourceName
            LiveDialog(onDismiss = onDismiss, width = 460.dp) {
                LiveText("${channel.number}  ${channel.name}", size = 20.sp, weight = FontWeight.Bold)
                LiveText(
                    listOfNotNull(from.takeIf { it.isNotBlank() }, channel.group.takeIf { it.isNotBlank() }, if (playing) "Watching" else null)
                        .joinToString(" · "),
                    color = NuvioTheme.colors.TextSecondary, size = 13.sp
                )
                program?.let { LiveText("Now: ${it.title}", color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 1) }
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!playing || canRestart) item { MenuHeader("Watch", first = true) }
                    if (!playing) item { MenuItem("Switch to this copy", Modifier.focusRequester(first), onSwitch) }
                    if (canRestart) item {
                        MenuItem(viewModel.restartLabel(channel), if (playing) Modifier.focusRequester(first) else Modifier) {
                            if (viewModel.watchFromStart(channel)) onRestarted() else onDismiss()
                        }
                    }
                    if (bell != null) {
                        item { MenuHeader("This show") }
                        item { MenuItem(bell.label, onClick = { bell.toggle(); onDismiss() }) }
                    }
                    item { MenuHeader("Record", first = playing && !canRestart) }
                    item {
                        MenuItem(recordLabel, if (playing && !canRestart) Modifier.focusRequester(first) else Modifier) {
                            val msg = when {
                                recordingNow -> viewModel.toggleRecording(channel)
                                program != null -> viewModel.toggleScheduled(channel, program)
                                // No listings: record from now (an hour, as the play bar's Record does).
                                else -> viewModel.toggleRecording(channel)
                            }
                            LiveTvNotice.show(msg)
                            onDismiss()
                        }
                    }
                    if (program != null && seriesLabel != null) item {
                        MenuItem(seriesLabel) {
                            LiveTvNotice.show(viewModel.toggleSeries(channel, program))
                            onDismiss()
                        }
                    }
                    item { MenuItem("Schedule a recording…", onClick = { step = "manual" }) }
                    item { MenuHeader("Favorites & groups") }
                    item {
                        MenuItem(if (isFavorite) "Remove from favorites" else "Add to favorites") {
                            viewModel.toggleFavorite(channel)
                            onDismiss()
                        }
                    }
                    item { MenuItem("Add to group…", onClick = { step = "group" }) }
                    item { MenuItem("Copy channel…", onClick = { step = "copy" }) }
                    item { MenuHeader("Edit channel") }
                    item { MenuItem("Rename channel", onClick = { step = "rename" }) }
                    item { MenuItem("Change channel number", onClick = { step = "number" }) }
                    item {
                        MenuItem("Hide channel") {
                            viewModel.hideChannel(channel)
                            LiveTvNotice.show("${channel.name} hidden. It still shows in Backups.")
                            onDismiss()
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
        }
    }
}
