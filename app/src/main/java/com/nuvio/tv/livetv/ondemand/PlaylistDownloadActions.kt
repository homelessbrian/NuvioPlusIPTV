package com.nuvio.tv.livetv.ondemand

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/** An M3U playlist can use a URL as an item ID. Keep requests in memory only. */
internal data class PlaylistDownloadPick(
    val uid: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeId: String? = null,
    val chooseVersion: Boolean = false
)

internal class PlaylistDownloadActionState : ViewModel() {
    val pending = mutableStateOf<PlaylistDownloadPick?>(null)
    val choosingFolder = mutableStateOf(false)
}

@Composable
internal fun activeDownloadProfile(): Int? {
    val app = LocalContext.current.applicationContext
    val profiles = remember(app) {
        EntryPointAccessors.fromApplication(app, LiveTvStartupEntryPoint::class.java).profileManager()
    }
    val ready by profiles.activeProfileReady.collectAsStateWithLifecycle()
    val profile by profiles.activeProfileId.collectAsStateWithLifecycle()
    return if (ready) profile else null
}

@Composable
internal fun rememberMatchedDownloadAction(uid: String): (Int?, Int?) -> Unit {
    val download = rememberPlaylistDownloadAction()
    return { season, episode -> download(PlaylistDownloadPick(uid, season, episode, chooseVersion = true)) }
}

@Composable
internal fun rememberPlaylistDownloadAction(): (PlaylistDownloadPick) -> Unit {
    val context = LocalContext.current
    val profile = activeDownloadProfile() ?: return {}
    return key(profile) {
        val downloads = remember(context) { vodDownloads(context) }
        val repository = remember(context) { onDemandRepository(context) }
        val state = viewModel<PlaylistDownloadActionState>(key = "playlist-download-$profile")
        var pending by state.pending
        var choosingFolder by state.choosingFolder
        var versions by remember { mutableStateOf(emptyList<OnDemandRepository.Version>()) }

        fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        LaunchedEffect(pending, choosingFolder) {
            val request = pending ?: return@LaunchedEffect
            if (choosingFolder) return@LaunchedEffect
            try {
                val item = repository.itemByUid(request.uid)
                if (item == null || !downloads.canSave(item)) {
                    notify("This playlist title is unavailable for download.")
                } else {
                    if (request.chooseVersion) {
                        val copies = repository.versions(item).filter { downloads.canSave(it.item) }
                        if (copies.size > 1) {
                            versions = copies
                            return@LaunchedEffect
                        }
                    }
                    if (downloads.currentFolder(profile) == null) {
                        choosingFolder = true
                        return@LaunchedEffect
                    }
                    val episode = if (item.kind == VodKind.SERIES) {
                        repository.info(item)?.episodes?.firstOrNull {
                            it.season == request.season && it.episode == request.episode &&
                                (request.episodeId == null || it.id == request.episodeId)
                        }
                    } else null
                    if (item.kind == VodKind.SERIES && episode == null) {
                        notify("This playlist version doesn't include that episode.")
                    } else {
                        notify(downloads.enqueue(profile, item, episode) ?: "Added to Downloads")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notify("Couldn't open this playlist item for download.")
            }
            pending = null
        }
        if (versions.isNotEmpty()) {
            OnDemandVersionPicker(versions, onPick = {
                pending = pending?.copy(uid = it.item.uid, chooseVersion = false)
                versions = emptyList()
            }, onDismiss = { versions = emptyList(); pending = null })
        }
        if (choosingFolder) {
            PlaylistDownloadFolderPicker(
                profile = profile,
                onChosen = { choosingFolder = false },
                onDismiss = { pending = null; choosingFolder = false }
            )
        }
        val start: (PlaylistDownloadPick) -> Unit = { if (pending == null) pending = it }
        start
    }
}
