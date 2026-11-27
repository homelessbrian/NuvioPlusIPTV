package com.nuvio.tv.livetv.ondemand

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistDownloadsScreen(onPlay: (Uri, String) -> Unit) {
    val profile = activeDownloadProfile() ?: return
    key(profile) {
        val context = LocalContext.current
        val downloads = remember(context) { vodDownloads(context) }
        val entries by remember(downloads, profile) { downloads.entries(profile) }.collectAsStateWithLifecycle()
        val folder by remember(downloads, profile) { downloads.folder(profile) }.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var pickFolder by rememberSaveable { mutableStateOf(false) }
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmRemoval by rememberSaveable { mutableStateOf(false) }
        var unavailable by remember { mutableStateOf(false) }
        val folderFocus = remember { FocusRequester() }
        fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

        Column(Modifier.fillMaxSize().background(NuvioTheme.colors.Background).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveText("Downloads", size = 28.sp, weight = FontWeight.Bold)
            SettingsActionRow(if (folder == null) "Choose download folder" else "Change download folder",
                subtitle = folder?.label, modifier = Modifier.focusRequester(folderFocus), onClick = { pickFolder = true })
            if (entries.isEmpty()) {
                LiveText("Movies and episodes you download from On Demand appear here.", size = 16.sp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.id }) { entry ->
                    SettingsActionRow(title = entry.title, subtitle = entry.downloadStatusLabel(), onClick = {
                        unavailable = false
                        confirmRemoval = false
                        selectedId = entry.id
                    })
                }
            }
        }
        LaunchedEffect(Unit) { folderFocus.requestFocus() }
        if (pickFolder) {
            PlaylistDownloadFolderPicker(profile, onChosen = { pickFolder = false }, onDismiss = { pickFolder = false })
        }

        entries.firstOrNull { it.id == selectedId }?.let { entry ->
            val dismiss = { selectedId = null; confirmRemoval = false; unavailable = false }
            val complete = entry.status == VodDownloadStatus.COMPLETE
            val active = entry.status == VodDownloadStatus.QUEUED || entry.status == VodDownloadStatus.DOWNLOADING
            if (confirmRemoval) {
                NuvioDialog(onDismiss = { confirmRemoval = false },
                    title = if (active) "Cancel download?" else "Remove download?",
                    subtitle = entry.title) {
                    SettingsActionRow("Keep", subtitle = null, onClick = { confirmRemoval = false })
                    SettingsActionRow(if (active) "Cancel download" else "Remove download", subtitle = null, onClick = {
                        scope.launch {
                            if (downloads.remove(profile, entry.id)) dismiss()
                            else notify("Reconnect ${entry.folderLabel} and try again.")
                        }
                    })
                }
            } else {
                NuvioDialog(onDismiss = dismiss, title = entry.title,
                    subtitle = if (unavailable) "Reconnect ${entry.folderLabel} and check that the saved file is still there."
                        else entry.downloadStatusLabel()) {
                    if (complete) {
                        SettingsActionRow("Play saved file", subtitle = null, onClick = {
                            scope.launch {
                                val uri = downloads.playbackUri(profile, entry.id)
                                if (uri == null) unavailable = true
                                else { dismiss(); onPlay(uri, entry.title) }
                            }
                        })
                    }
                    if (entry.status == VodDownloadStatus.FAILED) {
                        SettingsActionRow("Retry download", subtitle = null, onClick = {
                            scope.launch {
                                val problem = downloads.retry(profile, entry.id)
                                if (problem == null) dismiss() else notify(problem)
                            }
                        })
                    }
                    SettingsActionRow(if (active) "Cancel download" else "Remove download",
                        subtitle = null, onClick = { confirmRemoval = true })
                }
            }
        }
    }
}

private fun VodDownload.downloadStatusLabel(): String = when (status) {
    VodDownloadStatus.QUEUED -> "Queued"
    VodDownloadStatus.DOWNLOADING -> if (total > 0) {
        "Downloading · ${(bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)}%"
    } else "Downloading · ${RecordingStorage.sizeLabel(bytes)}"
    VodDownloadStatus.COMPLETE -> "Saved · ${RecordingStorage.sizeLabel(bytes)}"
    VodDownloadStatus.FAILED -> error ?: "Download failed"
}
