package com.nuvio.tv.livetv.ondemand

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nuvio.stalker.StalkerPlaybackLocator
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.livetv.data.ForkDns
import com.nuvio.tv.livetv.data.ForkHttp
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.stalker.StalkerGateway
import com.nuvio.vpn.android.VpnHttpClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VodDownloadsEntryPoint { fun vodDownloads(): VodDownloads }

fun vodDownloads(context: Context): VodDownloads =
    EntryPointAccessors.fromApplication(context.applicationContext, VodDownloadsEntryPoint::class.java).vodDownloads()

@Singleton
class VodDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: VodDownloadStore,
    private val repository: OnDemandRepository,
    private val prefs: LiveTvPreferences,
    private val profiles: ProfileManager,
    private val stalker: StalkerGateway,
) {
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val transferLock = Mutex()
    private val activeCalls = ConcurrentHashMap<String, Call>()
    private val visibleLists = ConcurrentHashMap<Int, StateFlow<List<VodDownload>>>()
    private val calls by lazy {
        VpnHttpClient.wrap(ForkHttp.builder().dns(ForkDns.provider)
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build())
    }

    fun entries(profileId: Int): StateFlow<List<VodDownload>> = visibleLists.getOrPut(profileId) {
        combine(store.entries(profileId), prefs.userState, prefs.settings, profiles.activeProfileId) { items, user, settings, active ->
            if (active == profileId) items.filter { visible(it.playlist, it.category, user, ParentalControls.enabled(settings)) }
            else emptyList()
        }.stateIn(io, SharingStarted.WhileSubscribed(5_000L, 0L), emptyList())
    }.also { load(profileId) }
    fun folder(profileId: Int): StateFlow<VodDownloadFolder?> = store.folder(profileId).also { load(profileId) }
    suspend fun currentFolder(profileId: Int): VodDownloadFolder? = withContext(Dispatchers.IO) {
        if (current(profileId)) store.read(profileId).folder else null
    }

    private fun load(profile: Int) {
        if (loaded.add(profile)) io.launch {
            try {
                val lib = store.read(profile)
                if (lib.items.any { it.view.status in pending } && current(profile)) start(profile)
            } catch (_: Exception) { loaded.remove(profile) }
        }
    }

    suspend fun chooseFolder(profileId: Int, id: String, label: String): Boolean = withContext(Dispatchers.IO) {
        if (!current(profileId)) return@withContext false
        try {
            VodDownloadFiles(context, id).checkWritable()
            store.edit(profileId) { it.copy(folder = VodDownloadFolder(id, label)) }
            true
        } catch (_: Exception) { false }
    }

    suspend fun enqueue(profileId: Int, item: VodItem, episode: VodEpisode? = null): String? = withContext(Dispatchers.IO) {
        try {
            if (!current(profileId) || !canSave(item)) return@withContext "This title is unavailable for download."
            if ((item.kind == VodKind.SERIES) != (episode != null)) return@withContext "Choose an episode to download."
            if (episode != null && episode.playlistId.isNotEmpty() && episode.playlistId != item.playlistId)
                return@withContext "Choose an episode from this playlist."
            val folder = store.read(profileId).folder ?: return@withContext "Choose a download folder first."
            var queued = false
            store.edit(profileId) { lib ->
                val prior = lib.items.firstOrNull { it.matches(item, episode) }
                if (prior != null && prior.view.status != VodDownloadStatus.FAILED) return@edit lib
                queued = true
                if (prior != null) return@edit lib.replace(prior.view.id) { it.queued() }
                val title = if (episode == null) item.name else "${item.name} · S${episode.season} E${episode.episode} · ${episode.title}"
                val view = VodDownload(UUID.randomUUID().toString(), title, folderLabel = folder.label,
                    playlist = item.playlistId, category = category(item))
                lib.copy(items = lib.items + SavedVod(view, item.uid, episode?.id, episode?.season, episode?.episode, folder))
            }
            if (queued) start(profileId)
            null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "Could not queue this download. Check the folder and try again." }
    }

    private fun category(item: VodItem) = "${item.kind.key}:${item.playlistId}:${item.categoryId}"
    private fun visible(playlist: String, category: String, user: LiveUserState, pinEnabled: Boolean) =
        playlist !in user.hiddenPlaylists && category !in user.vodHiddenCategories &&
            !(pinEnabled && "vod:$category" in user.lockedGroups)

    private suspend fun canSave(item: VodItem) =
        visible(item.playlistId, category(item), prefs.userState.first(), ParentalControls.enabled(prefs.settings.first())) &&
            !repository.isAdult(item)

    private fun start(profile: Int) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, VodDownloadService::class.java).putExtra("profile", profile))
        } catch (error: RuntimeException) {
            store.edit(profile) { lib -> lib.copy(items = lib.items.map { row ->
                if (row.view.status == VodDownloadStatus.QUEUED) row.failed("Open Downloads and retry to start the transfer.") else row
            }) }
            throw error
        }
    }

    suspend fun retry(profileId: Int, id: String): String? = withContext(Dispatchers.IO) {
        if (!current(profileId)) return@withContext "Switch back to the original profile."
        try {
            val lib = store.edit(profileId) { it.replace(id) { row ->
                if (row.view.status == VodDownloadStatus.FAILED) row.queued() else row
            } }
            if (lib.items.any { it.view.id == id && it.view.status == VodDownloadStatus.QUEUED }) start(profileId)
            null
        } catch (_: Exception) { "Could not restart the download." }
    }

    suspend fun playbackUri(profileId: Int, id: String): Uri? = withContext(Dispatchers.IO) {
        if (!current(profileId)) return@withContext null
        val row = store.read(profileId).items.firstOrNull { it.view.id == id && it.view.status == VodDownloadStatus.COMPLETE }
            ?: return@withContext null
        if (!visible(row.view.playlist, row.view.category, prefs.userState.first(), ParentalControls.enabled(prefs.settings.first())))
            return@withContext null
        runCatching { VodDownloadFiles(context, row.destination.id).saved(row) }.getOrNull()
    }

    suspend fun remove(profileId: Int, id: String): Boolean = withContext(Dispatchers.IO) {
        if (!current(profileId)) return@withContext false
        try {
            val row = store.edit(profileId) { lib -> lib.replace(id) {
                if (it.view.status in pending) it.failed("Download canceled.") else it
            } }.items.firstOrNull { it.view.id == id } ?: return@withContext true
            activeCalls[id]?.cancel()
            // An in-progress transfer owns its temporary file until it has finished closing it.
            // Never wait on an unrelated movie.
            val lock = fileLocks.getOrPut(id) { Mutex() }
            lock.withLock {
                if (row.extension.isNotEmpty()) VodDownloadFiles(context, row.destination.id).clean(row,
                    includeSaved = row.view.status == VodDownloadStatus.COMPLETE || row.publishingBytes > 0)
                store.edit(profileId) { lib -> lib.copy(items = lib.items.filterNot { it.view.id == id }) }
            }
            fileLocks.remove(id, lock)
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private fun current(profile: Int) = profiles.activeProfileReady.value && profiles.activeProfileId.value == profile

    internal suspend fun drain(profile: Int) = withContext(Dispatchers.IO) {
        profiles.activeProfileReady.first { it }
        transferLock.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val row = store.read(profile).items.firstOrNull { it.view.status in pending } ?: break
                try {
                    fileLocks.getOrPut(row.view.id) { Mutex() }.withLock {
                        if (store.read(profile).items.any { it.view.id == row.view.id && it.view.status in pending })
                            transfer(profile, row)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    store.edit(profile) { lib -> lib.replace(row.view.id) {
                        if (it.view.status in pending) it.failed((e as? VodDownloadError)?.displayMessage
                            ?: "Transfer interrupted. Check the connection, free space, and drive, then retry.") else it
                    } }
                }
            }
        }
    }

    private suspend fun transfer(profile: Int, row: SavedVod) {
        val id = row.view.id
        fun checkRunning() {
            if (!current(profile) || store.read(profile).items.none { it.view.id == id && it.view.status in pending })
                throw VodDownloadError("The profile or download changed. Retry from the original profile.")
        }
        checkRunning()
        val files = VodDownloadFiles(context, row.destination.id)
        // Android may kill the service between the filesystem rename and the durable library update.
        if (row.publishingBytes > 0) files.saved(row)?.let { uri ->
            if (files.size(uri) != row.publishingBytes) throw VodDownloadError("The saved file has changed on the drive.")
            store.edit(profile) { lib -> lib.replace(id) { if (it.view.status in pending) it.complete(row.publishingBytes) else it } }
            return
        }
        if (row.extension.isNotEmpty()) files.clean(row, includeSaved = false)
        store.edit(profile) { it.replace(id) { r -> if (r.view.status in pending)
            r.copy(publishingBytes = 0, view = r.view.copy(status = VodDownloadStatus.DOWNLOADING)) else r } }
        checkRunning()
        val item = repository.itemByUid(row.uid) ?: throw VodDownloadError("This title is no longer in the playlist.")
        if (!canSave(item)) throw VodDownloadError("This title is unavailable for download.")
        val source = prefs.currentPlaylists().firstOrNull { it.id == item.playlistId && it.enabled && it.importVod }
            ?: throw VodDownloadError("This playlist is no longer available.")
        val url = if (row.episodeId == null) repository.movieUrl(item) else {
            repository.info(item)?.episodes?.firstOrNull { it.id == row.episodeId && it.season == row.season && it.episode == row.number }
                ?.let { repository.episodeUrl(it) }
        } ?: throw VodDownloadError("The provider no longer lists this video.")
        val locator = StalkerPlaybackLocator.decode(url)
        val portal = locator?.let { stalker.prepare(it.sourceId, it.stream) }
        try {
            val target = portal?.url ?: url
            openVodFile(portal?.policy?.mediaCalls() ?: calls, target,
                source.effectiveUserAgent.ifBlank { LiveTvRepository.DEFAULT_UA }, LiveTvRepository.DEFAULT_UA) { call ->
                activeCalls[id] = call
                checkRunning()
            }.use { response ->
                if (response.code != 200) throw VodDownloadError("The provider returned HTTP ${response.code}.")
                val body = response.body
                val total = body.contentLength()
                val mime = response.header("Content-Type").orEmpty()
                if (vodIsManifest(response.request.url.encodedPath, mime)) throw VodDownloadError(DIRECT_ONLY)
                val sourceBytes = body.source()
                val prefix = sourceBytes.peek().use { peek -> peek.request(512); peek.readByteArray(minOf(512L, peek.buffer.size)) }
                val ext = vodFileExtension(prefix) ?: throw VodDownloadError(DIRECT_ONLY)
                if (total < 0 && (ext == "ts" || response.protocol == okhttp3.Protocol.HTTP_1_1 &&
                        !response.header("Transfer-Encoding").equals("chunked", true)))
                    throw VodDownloadError("The provider did not supply a verifiable end for this video file.")
                val staged = row.copy(extension = ext)
                checkRunning()
                store.edit(profile) { lib -> lib.replace(id) { it.copy(extension = ext) } }
                var published = false
                try {
                    val part = files.createPart(staged)
                    var lastUpdate = 0L
                    val bytes = files.output(part).use { output ->
                        copyVodVideo(body.byteStream(), output, total) { written ->
                            if (SystemClock.elapsedRealtime() - lastUpdate >= 500) {
                                checkRunning()
                                store.progress(profile, id, written, total)
                                lastUpdate = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    checkRunning()
                    files.verifyVideo(part)
                    checkRunning()
                    if (!canSave(item)) throw VodDownloadError("This title is unavailable for download.")
                    if (files.saved(staged) != null) throw VodDownloadError("A file with this name already exists in the folder.")
                    store.edit(profile) { lib -> lib.replace(id) { it.copy(publishingBytes = bytes) } }
                    files.publish(staged, part)
                    published = true
                    val lib = store.edit(profile) { it.replace(id) { r ->
                        if (r.view.status != VodDownloadStatus.DOWNLOADING || !current(profile)) r
                        else r.complete(bytes)
                    } }
                    if (lib.items.none { it.view.id == id && it.view.status == VodDownloadStatus.COMPLETE })
                        throw IOException("Download canceled before publication")
                } catch (e: Exception) {
                    runCatching { files.clean(staged, includeSaved = published) }
                    throw e
                }
            }
        } finally {
            activeCalls.remove(id)
            portal?.policy?.retire()
        }
    }

    internal fun interruptTransfers() { activeCalls.values.forEach { it.cancel() } }

    private fun SavedVod.queued() = copy(view = view.copy(status = VodDownloadStatus.QUEUED, bytes = 0, total = -1, error = null))
    private fun SavedVod.complete(bytes: Long) = copy(publishingBytes = 0,
        view = view.copy(status = VodDownloadStatus.COMPLETE, bytes = bytes, total = bytes, error = null))
    private fun SavedVod.failed(message: String) = copy(view = view.copy(status = VodDownloadStatus.FAILED, error = message))
    private fun DownloadLibrary.replace(id: String, change: (SavedVod) -> SavedVod) =
        copy(items = items.map { if (it.view.id == id) change(it) else it })

    private companion object {
        val pending = setOf(VodDownloadStatus.QUEUED, VodDownloadStatus.DOWNLOADING)
        const val DIRECT_ONLY = "This source is not a supported direct video file. HLS and DASH playlists cannot be downloaded."
    }
}
