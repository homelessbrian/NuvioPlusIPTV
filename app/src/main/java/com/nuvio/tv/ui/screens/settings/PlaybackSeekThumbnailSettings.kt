package com.nuvio.tv.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.livetv.thumbs.SeekThumbSettings
import com.nuvio.tv.livetv.thumbs.SeekThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/** Settings > Playback > Seek Thumbnails. */
@Composable
internal fun PlaybackSeekThumbnailSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { SeekThumbSettings.load(context) }
    val s by SeekThumbSettings.values.collectAsState()
    val scope = rememberCoroutineScope()
    val lowMemoryBox = remember { SeekThumbSettings.isLowMemoryDevice(context) }
    var savedBytes by remember { mutableStateOf<Long?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, s.enabled) {
        savedBytes = withContext(Dispatchers.IO) { runCatching { SeekThumbs.savedBytes(context) }.getOrNull() }
    }
    fun update(change: (SeekThumbSettings.Values) -> SeekThumbSettings.Values) = SeekThumbSettings.update(context, change)

    SettingsToggleRow(
        title = "Seek Thumbnails",
        subtitle = "Shows a preview picture above the seek bar while you seek. Pictures are made on this TV from the video itself",
        checked = s.enabled,
        onToggle = { update { it.copy(enabled = !it.enabled) } }
    )
    SettingsNote(
        "Works on: movies and shows in the main player (including streamed HLS links), your recordings, sports replays and highlights, and catch-up.\n" +
            "Catch-up: pictures come from the part saved with Faster Catch-Up Skipping, or straight from the provider for streamed (HLS) catch-up.\n" +
            "Doesn't work on: Live TV, torrents, DASH links, or protected streams."
    )
    if (lowMemoryBox) {
        SettingsNote(
            "This device has limited memory. Thumbnails may cause stutter. If they do, they pause by themselves and the seek bar says so.",
            tone = SettingsNoteTone.Warning
        )
    }
    SettingsToggleRow(
        title = "Include 4K and HDR",
        subtitle = "Also makes thumbnails for 4K and HDR videos. Starts on for devices with plenty of memory and off for lower-end ones, but you can turn it on anywhere and try it. 4K thumbnails may stutter or not work on lower-end devices, and use more data. Pictures of HDR videos can look washed out. Streamed links use their smallest version, so this rarely matters for them",
        checked = s.include4k,
        enabled = s.enabled,
        onToggle = { update { it.copy(include4k = !it.include4k) } }
    )
    SettingsActionRow(
        title = "When to Make Thumbnails",
        subtitle = s.whenToMake.detail,
        value = s.whenToMake.label,
        enabled = s.enabled,
        leadingIcon = Icons.Default.Schedule,
        onClick = {
            val all = SeekThumbSettings.When.entries
            update { it.copy(whenToMake = all[(all.indexOf(it.whenToMake) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = "Storage Limit",
        subtitle = "Saved thumbnails take up to this much space; the videos watched longest ago are cleared first",
        value = "${s.storageMb} MB",
        enabled = s.enabled,
        leadingIcon = Icons.Default.SdStorage,
        onClick = {
            val all = SeekThumbSettings.STORAGE_CHOICES
            update { it.copy(storageMb = all[(all.indexOf(it.storageMb) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = "Clear Saved Thumbnails",
        subtitle = "Deletes every saved thumbnail. They're made again when needed",
        value = savedBytes?.let { formatSize(it) },
        leadingIcon = Icons.Default.DeleteSweep,
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { SeekThumbs.clearAll(context) } }
                refresh++
            }
        }
    )
    if (s.lastLowMemoryAt > 0L) {
        val date = Date(s.lastLowMemoryAt)
        SettingsNote(
            "Last paused for low memory: " + DateFormat.getMediumDateFormat(context).format(date) + ", " +
                DateFormat.getTimeFormat(context).format(date)
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
