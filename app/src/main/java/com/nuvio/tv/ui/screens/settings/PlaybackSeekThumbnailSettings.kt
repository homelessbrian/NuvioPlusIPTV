package com.nuvio.tv.ui.screens.settings

import android.content.Context
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
import com.nuvio.tv.R
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
        title = context.getString(R.string.iptv_thumbs_title),
        subtitle = context.getString(R.string.iptv_thumbs_subtitle),
        checked = s.enabled,
        onToggle = { update { it.copy(enabled = !it.enabled) } }
    )
    SettingsNote(context.getString(R.string.iptv_thumbs_works_on))
    if (lowMemoryBox) {
        SettingsNote(
            context.getString(R.string.iptv_thumbs_low_memory_note),
            tone = SettingsNoteTone.Warning
        )
    }
    SettingsToggleRow(
        title = context.getString(R.string.iptv_thumbs_include_4k),
        subtitle = context.getString(R.string.iptv_thumbs_include_4k_desc),
        checked = s.include4k,
        enabled = s.enabled,
        onToggle = { update { it.copy(include4k = !it.include4k) } }
    )
    SettingsActionRow(
        title = context.getString(R.string.iptv_thumbs_when_to_make),
        subtitle = context.getString(whenDetailRes(s.whenToMake)),
        value = context.getString(whenLabelRes(s.whenToMake)),
        enabled = s.enabled,
        leadingIcon = Icons.Default.Schedule,
        onClick = {
            val all = SeekThumbSettings.When.entries
            update { it.copy(whenToMake = all[(all.indexOf(it.whenToMake) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = context.getString(R.string.iptv_thumbs_storage_limit),
        subtitle = context.getString(R.string.iptv_thumbs_storage_limit_desc),
        value = context.getString(R.string.iptv_thumbs_size_mb, s.storageMb.toString()),
        enabled = s.enabled,
        leadingIcon = Icons.Default.SdStorage,
        onClick = {
            val all = SeekThumbSettings.STORAGE_CHOICES
            update { it.copy(storageMb = all[(all.indexOf(it.storageMb) + 1) % all.size]) }
        }
    )
    SettingsActionRow(
        title = context.getString(R.string.iptv_thumbs_clear),
        subtitle = context.getString(R.string.iptv_thumbs_clear_desc),
        value = savedBytes?.let { formatSize(context, it) },
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
            context.getString(
                R.string.iptv_thumbs_last_paused,
                DateFormat.getMediumDateFormat(context).format(date),
                DateFormat.getTimeFormat(context).format(date)
            )
        )
    }
}

private fun formatSize(context: Context, bytes: Long): String = when {
    bytes < 1024L * 1024L -> context.getString(R.string.iptv_thumbs_size_kb, (bytes / 1024L).toString())
    else -> context.getString(R.string.iptv_thumbs_size_mb, "%.1f".format(bytes / (1024.0 * 1024.0)))
}

private fun whenLabelRes(w: SeekThumbSettings.When): Int = when (w) {
    SeekThumbSettings.When.SEEKING -> R.string.iptv_thumbs_when_seeking
    SeekThumbSettings.When.WATCHING -> R.string.iptv_thumbs_when_watching
    SeekThumbSettings.When.RIGHT_AWAY -> R.string.iptv_thumbs_when_right_away
}

private fun whenDetailRes(w: SeekThumbSettings.When): Int = when (w) {
    SeekThumbSettings.When.SEEKING -> R.string.iptv_thumbs_when_seeking_desc
    SeekThumbSettings.When.WATCHING -> R.string.iptv_thumbs_when_watching_desc
    SeekThumbSettings.When.RIGHT_AWAY -> R.string.iptv_thumbs_when_right_away_desc
}
