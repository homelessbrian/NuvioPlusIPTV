package com.nuvio.tv.livetv.ondemand

import android.content.Context
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class VodDownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED }

@Serializable
data class VodDownloadFolder(val id: String, val label: String)

@Serializable
data class VodDownload(
    val id: String,
    val title: String,
    val status: VodDownloadStatus = VodDownloadStatus.QUEUED,
    val bytes: Long = 0,
    val total: Long = -1,
    val error: String? = null,
    val folderLabel: String = "",
    internal val playlist: String = "",
    internal val category: String = "",
)

// Even a catalogue uid can contain a provider login (M3U movie ids include the original URL).
@Serializable
internal data class SavedVod(
    val view: VodDownload,
    val uid: String,
    val episodeId: String?,
    val season: Int?,
    val number: Int?,
    val destination: VodDownloadFolder,
    val extension: String = "",
    val publishingBytes: Long = 0,
) {
    fun matches(item: VodItem, episode: VodEpisode?) = uid == item.uid && episodeId == episode?.id &&
        season == episode?.season && number == episode?.episode
}

@Serializable
internal data class DownloadLibrary(
    val folder: VodDownloadFolder? = null,
    val items: List<SavedVod> = emptyList(),
)

@Singleton
class VodDownloadStore @Inject constructor(@ApplicationContext context: Context) : ProfileScopedCredentialStore {
    private val disk = ProtectedProfilePreferences(context, "playlist_vod_downloads", "playlist_vod_downloads")
    private val json = Json { ignoreUnknownKeys = true }
    private val libraries = mutableMapOf<Int, DownloadLibrary>()
    private val lists = mutableMapOf<Int, MutableStateFlow<List<VodDownload>>>()
    private val folders = mutableMapOf<Int, MutableStateFlow<VodDownloadFolder?>>()

    @Synchronized internal fun entries(id: Int): StateFlow<List<VodDownload>> =
        lists.getOrPut(id) { MutableStateFlow(emptyList()) }

    @Synchronized internal fun folder(id: Int): StateFlow<VodDownloadFolder?> =
        folders.getOrPut(id) { MutableStateFlow(null) }

    /** First load and all durable mutations run on IO. Progress updates only change the observable list. */
    @Synchronized internal fun read(id: Int): DownloadLibrary = libraries[id] ?: run {
        val library = disk.read(id)?.let { json.decodeFromString<DownloadLibrary>(it) } ?: DownloadLibrary()
        libraries[id] = library
        publish(id, library)
        library
    }

    @Synchronized internal fun edit(id: Int, change: (DownloadLibrary) -> DownloadLibrary): DownloadLibrary {
        val before = read(id)
        val after = change(before)
        if (after != before) {
            disk.write(id, json.encodeToString(DownloadLibrary.serializer(), after))
            libraries[id] = after
            publish(id, after)
        }
        return after
    }

    @Synchronized internal fun progress(profile: Int, id: String, bytes: Long, total: Long) {
        val flow = lists[profile] ?: return
        flow.value = flow.value.map {
            if (it.id == id && it.status == VodDownloadStatus.DOWNLOADING) it.copy(bytes = bytes, total = total) else it
        }
    }

    private fun publish(id: Int, library: DownloadLibrary) {
        lists.getOrPut(id) { MutableStateFlow(emptyList()) }.value = library.items.map { it.view }
        folders.getOrPut(id) { MutableStateFlow(null) }.value = library.folder
    }

    @Synchronized override fun removeProfile(profileId: Int) {
        disk.write(profileId, null)
        libraries.remove(profileId)
        publish(profileId, DownloadLibrary())
    }

    @Synchronized override fun clearAllProfiles() {
        disk.clear()
        (lists.keys + folders.keys).forEach { publish(it, DownloadLibrary()) }
        libraries.clear()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VodDownloadStoreModule {
    @Binds @IntoSet
    abstract fun profileStore(store: VodDownloadStore): ProfileScopedCredentialStore
}
