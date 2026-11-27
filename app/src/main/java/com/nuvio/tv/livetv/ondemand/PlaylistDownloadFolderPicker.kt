package com.nuvio.tv.livetv.ondemand

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.livetv.record.DriveFolders
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.DriveFolderDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import kotlinx.coroutines.launch
import java.util.Locale

/** Android folder picker where available; Fire TV can instead use the existing TV drive browser. */
@Composable
internal fun PlaylistDownloadFolderPicker(profile: Int, onChosen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var browse by rememberSaveable { mutableStateOf(false) }
    var waitingForAccess by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save(id: String, label: String) {
        if (saving) return
        saving = true
        scope.launch {
            val saved = vodDownloads(context).chooseFolder(profile, id, label)
            saving = false
            if (saved) onChosen() else {
                browse = false
                problem = "Couldn't write to this folder. Reconnect the drive or choose another folder."
            }
        }
    }

    val tree = rememberLauncherForActivityResult(remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                val label = DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "Selected folder" }
                save(uri.toString(), label)
            } catch (_: RuntimeException) {
                problem = "The device didn't grant access. Choose a writable folder."
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val listener = LifecycleEventObserver { _, event ->
            if (waitingForAccess && event == Lifecycle.Event.ON_RESUME) {
                waitingForAccess = false
                if (DriveFolders.hasAccess()) browse = true
                else problem = "Allow file access to browse attached drives."
            }
        }
        lifecycle.addObserver(listener)
        onDispose { lifecycle.removeObserver(listener) }
    }

    if (browse) {
        DriveFolderDialog(locale = Locale.getDefault(), forDownloads = true, onDismiss = { browse = false }, onChosen = { id ->
            val folder = DriveFolders.parse(id)
            if (folder != null) save(id, folder.drive + "/" + folder.path)
        })
    } else {
        NuvioDialog(onDismiss = onDismiss, title = "Download folder",
            subtitle = problem ?: "Choose where to save new movies and episodes.") {
            SettingsActionRow("Choose folder", subtitle = null, enabled = !saving, onClick = {
                try {
                    tree.launch(null)
                } catch (_: ActivityNotFoundException) {
                    problem = if (DriveFolders.supported()) "Use Browse drives on this TV."
                    else "This TV does not provide a folder picker."
                }
            })
            if (DriveFolders.supported()) {
                SettingsActionRow("Browse drives", subtitle = "Internal storage, SD card or USB drive", enabled = !saving, onClick = {
                    if (DriveFolders.hasAccess()) browse = true
                    else if (DriveFolders.openAccessSettings(context)) waitingForAccess = true
                    else problem = "This TV didn't open file access settings."
                })
            }
        }
    }
}
