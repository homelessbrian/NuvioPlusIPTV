package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.livetv.record.DriveFolders
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Pick a folder on a drive for recordings: the drives first, then their folders. Back goes up a
 * level (and closes from the list of drives). Saving checks the folder can really be written to.
 */
@Composable
internal fun DriveFolderDialog(
    locale: Locale,
    onDismiss: () -> Unit,
    /** The folder was picked and saved: its storage id. */
    onChosen: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drive by remember { mutableStateOf<DriveFolders.Drive?>(null) }
    var dir by remember { mutableStateOf<File?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val drives by produceState(emptyList<DriveFolders.Drive>()) {
        value = withContext(Dispatchers.IO) { DriveFolders.drives(context) }
    }
    val here = dir
    val folders by produceState(emptyList<File>(), here) {
        value = if (here == null) emptyList() else withContext(Dispatchers.IO) { DriveFolders.subfolders(here) }
    }

    fun goTo(d: DriveFolders.Drive?, f: File?) { drive = d; dir = f; problem = null }
    fun up() {
        val d = drive
        val f = dir
        when {
            d == null || f == null -> onDismiss()
            f.absolutePath.trimEnd('/') == d.root.absolutePath.trimEnd('/') -> goTo(null, null)
            else -> goTo(d, f.parentFile)
        }
    }

    val suggested = context.getString(R.string.iptv_drive_folder_default_name)
    val actions: List<Pair<String, () -> Unit>> = if (drive == null || here == null) {
        drives.map { d ->
            val free = RecordingStorage.sizeLabel(runCatching { d.root.usableSpace }.getOrDefault(0L), locale)
            val name = if (d.primary) context.getString(R.string.iptv_drive_folder_shared_storage) else d.label
            context.getString(R.string.iptv_drive_folder_drive_row, name, free) to { goTo(d, d.root) }
        }
    } else buildList<Pair<String, () -> Unit>> {
        val d = drive!!
        add(context.getString(R.string.iptv_drive_folder_save_here) to {
            if (!busy) {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { DriveFolders.canWrite(here) }
                    busy = false
                    val path = DriveFolders.pathOn(d, here)
                    if (!ok || path == null) problem = context.getString(R.string.iptv_drive_folder_cant_write)
                    else {
                        val saved = DriveFolders.Saved(d.id, path)
                        DriveFolders.add(context, saved)
                        onChosen(saved.id)
                    }
                }
            }
        })
        if (folders.none { it.name.equals(suggested, ignoreCase = true) }) {
            add(context.getString(R.string.iptv_drive_folder_make, suggested) to {
                scope.launch {
                    val made = withContext(Dispatchers.IO) { DriveFolders.makeFolder(here, suggested) }
                    if (made != null) goTo(d, made) else problem = context.getString(R.string.iptv_drive_folder_cant_write)
                }
            })
        }
        add(context.getString(R.string.iptv_drive_folder_up) to { up() })
        folders.forEach { f -> add("📁  " + f.name to { goTo(d, f) }) }
    }

    val title = drive?.let { d -> if (d.primary) context.getString(R.string.iptv_drive_folder_shared_storage) else d.label }
        ?: context.getString(R.string.iptv_drive_folder_title)
    val subtitle = problem ?: when {
        drive == null && drives.isEmpty() -> context.getString(R.string.iptv_drive_folder_no_drives)
        drive == null -> context.getString(R.string.iptv_drive_folder_pick_drive)
        else -> {
            val path = here?.let { DriveFolders.pathOn(drive!!, it) }.orEmpty()
            context.getString(R.string.iptv_drive_folder_here, if (path.isEmpty()) "/" else "/$path")
        }
    }

    NuvioDialog(onDismiss = { up() }, title = title, subtitle = subtitle, width = 520.dp) {
        // A new list for each folder, so focus starts at its top.
        key(drive?.id, here?.absolutePath, drives.size) {
            val first = remember { FocusRequester() }
            LaunchedEffect(actions.isNotEmpty()) {
                delay(80)
                runCatching { first.requestFocus() }
            }
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
}
