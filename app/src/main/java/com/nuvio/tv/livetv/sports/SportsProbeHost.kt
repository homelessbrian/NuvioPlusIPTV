package com.nuvio.tv.livetv.sports

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.BackgroundWork
import com.nuvio.tv.livetv.data.ForkDns
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.record.LiveTvRecorder
import com.nuvio.tv.livetv.sports.probe.AndroidSportsChannelProbe
import com.nuvio.tv.livetv.sports.probe.SportsProbeQueue
import com.nuvio.tv.livetv.sports.probe.SportsProbeResult
import com.nuvio.tv.livetv.sports.probe.SportsProbeStatus
import com.nuvio.vpn.android.VpnPlaybackProtection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Source permissions and playback priority for one Sports page; network work stays in the engine. */
class SportsProbeHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: AndroidSportsChannelProbe,
    private val playback: LiveTvPlaybackController,
    private val recorder: LiveTvRecorder,
    private val preferences: LiveTvPreferences,
    private val channels: LiveTvChannelStore,
    private val addons: AddonRepository,
    private val profiles: ProfileManager,
    private val repository: SportsRepository,
) {
    private lateinit var scope: CoroutineScope
    private lateinit var queue: SportsProbeQueue
    private lateinit var user: StateFlow<LiveUserState?>
    private lateinit var live: StateFlow<LiveTvSettings?>
    private lateinit var sources: StateFlow<List<PlaylistSource>?>
    private lateinit var enabledAddons: StateFlow<Set<String>?>
    private var gameId: String? = null
    private var openingProfile = 0
    private var revision = 0L
    private var lastMatches: List<LiveChannel> = emptyList()
    private data class SourceIndex(val input: List<LiveChannel>, val values: Map<String, LiveChannel>)
    private var sourceIndex: SourceIndex? = null
    private var sourceKeys = emptySet<String>()
    private var indexJob: Job? = null
    private lateinit var indexDispatcher: CoroutineDispatcher
    private data class RuleCache(val user: LiveUserState, val live: LiveTvSettings, val sports: SportsSettings,
        val addons: Set<String>, val unlocked: Set<String>, val value: SportsWatchRules)
    private var ruleCache: RuleCache? = null
    private data class EventCache(val input: List<SportsEvent>, val id: String, val value: SportsEvent?)
    private var eventCache: EventCache? = null
    internal val results get() = queue.results
    internal val blockedByPlayback get() = queue.blockedByPlayback

    internal fun bind(owner: CoroutineScope, indexDispatcher: CoroutineDispatcher = Dispatchers.Default): SportsProbeHost {
        check(!this::queue.isInitialized)
        scope = owner
        this.indexDispatcher = indexDispatcher
        openingProfile = profiles.activeProfileId.value
        user = preferences.userState.stateIn(owner, SharingStarted.Eagerly, null)
        live = preferences.settings.stateIn(owner, SharingStarted.Eagerly, null)
        sources = preferences.playlists.stateIn(owner, SharingStarted.Eagerly, null)
        enabledAddons = addons.getInstalledAddons().map { list ->
            list.enabledAddons().mapTo(HashSet()) { "addon:${Integer.toHexString(it.baseUrl.hashCode())}:" } as Set<String>
        }.distinctUntilChanged().stateIn(owner, SharingStarted.Eagerly, null)
        queue = SportsProbeQueue(owner, engine, { allowed(it) }, ::busy, ::observed,
            epoch = { listOf(revision, VpnPlaybackProtection.settingsRevision(), VpnPlaybackProtection.networkRecoveryRevision(), ForkDns.choice.name) },
            publishable = { allowed(it) },
            playingChannelKey = { playback.state.value.channelKey })
        watch(playback.state) { queue.refresh() }
        watch(BackgroundWork.nuvioPlaying) { queue.refresh() }
        watch(BackgroundWork.onDemandPlaying) { queue.refresh() }
        watch(recorder.recordings) { queue.refresh() }
        watch(profiles.activeProfileId) { invalidate() }
        watch(profiles.activeProfileReady) { invalidate() }
        watch(sources) { invalidate() }
        watch(channels.displayChannels) { rebuildSourceIndex(); invalidate() }
        watch(user.distinctUntilChanged { a, b ->
            a?.hiddenChannels == b?.hiddenChannels && a?.hiddenGroups == b?.hiddenGroups &&
                a?.hiddenPlaylists == b?.hiddenPlaylists && a?.lockedGroups == b?.lockedGroups
        }) { invalidate() }
        watch(live.distinctUntilChanged { a, b ->
            a?.parentalPin == b?.parentalPin && a?.lockAdultContent == b?.lockAdultContent
        }) { invalidate() }
        watch(enabledAddons) { invalidate() }
        watch(ParentalControls.unlocked) { invalidate() }
        var automatic = repository.settings.value.autoCheckChannels
        watch(repository.settings) {
            val next = repository.settings.value.autoCheckChannels
            if (automatic && !next) queue.stop()
            automatic = next
            invalidate()
        }
        watch(repository.matches) {
            val fresh = gameId?.let { repository.matches.value[it] }.orEmpty().map { it.channel }
            if (fresh != lastMatches) { lastMatches = fresh; invalidate() }
        }
        val settingsLease = VpnPlaybackProtection.observeSettings { owner.launch { invalidate() } }
        val networkLease = VpnPlaybackProtection.observeNetworkRecovery { owner.launch { invalidate() } }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastLink: Pair<Network, List<String>>? = null
            override fun onAvailable(network: Network) { owner.launch { invalidate() } }
            override fun onLost(network: Network) { owner.launch { invalidate() } }
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                val key = network to link.dnsServers.map { it.hostAddress.orEmpty() }
                if (lastLink != key) { lastLink = key; owner.launch { invalidate() } }
            }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback); manager != null }.getOrDefault(false)
        owner.coroutineContext[Job]?.invokeOnCompletion {
            settingsLease.close(); networkLease.close()
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
        return this
    }

    private fun <T> watch(flow: Flow<T>, changed: () -> Unit) { scope.launch { flow.collect { changed() } } }

    internal fun open(id: String, candidates: List<LiveChannel>, automatic: Boolean) {
        gameId = id
        lastMatches = repository.matches.value[id].orEmpty().map { it.channel }
        val keys = candidates.filter { it.sourceId != SportsAddonSource.SOURCE_ID }.mapTo(HashSet()) { it.key }
        if (keys != sourceKeys || sourceIndex?.input !== channels.displayChannels.value || keys.any { it !in sourceIndex?.values.orEmpty() }) {
            sourceKeys = keys
            rebuildSourceIndex()
        }
        queue.open(id, candidates, automatic)
    }

    internal fun close(id: String) {
        if (gameId != id) return
        gameId = null
        lastMatches = emptyList()
        indexJob?.cancel()
        queue.close(id)
    }

    internal fun prioritize(keys: List<String>) = queue.prioritize(keys)
    internal fun stop() = queue.stop()
    internal suspend fun stopAndJoin() = queue.stopAndJoin()

    internal fun previewCheckResumption(): () -> Unit {
        val eventId = gameId ?: return {}
        return {
            if (gameId == eventId && repository.settings.value.autoCheckChannels) {
                queue.resume(eventId)
            }
        }
    }

    /** Capture the original selection before waiting for network cleanup. */
    internal fun playbackGuard(candidate: LiveChannel, replay: Boolean = false): () -> Boolean {
        val selectedProfile = profiles.activeProfileId.value
        val selectedGame = gameId
        return {
            selectedProfile == openingProfile && profiles.activeProfileReady.value && profiles.activeProfileId.value == selectedProfile &&
                (gameId == null || selectedGame == gameId) &&
                (if (replay) replayAllowed(candidate) else playbackSourceAllowed(candidate))
        }
    }

    /** Keep only this game's sources, and never scan the full playlist on each badge update. */
    private fun rebuildSourceIndex() {
        indexJob?.cancel()
        val input = channels.displayChannels.value
        val previous = sourceIndex?.takeIf { it.input === input }?.values.orEmpty()
        if (sourceIndex?.input !== input) sourceIndex = null
        if (gameId == null) return
        val wanted = sourceKeys
        indexJob = scope.launch {
            val found = withContext(indexDispatcher) {
                val out = LinkedHashMap(previous)
                for ((at, channel) in input.withIndex()) {
                    if (at % 1024 == 0) currentCoroutineContext().ensureActive()
                    if (channel.key in wanted) out[channel.key] = channel
                }
                val old = out.keys.filter { it !in wanted }.iterator()
                while (out.size > maxOf(128, wanted.size) && old.hasNext()) out.remove(old.next())
                out
            }
            if (channels.displayChannels.value === input && sourceKeys == wanted && gameId != null) {
                sourceIndex = SourceIndex(input, found)
                // Index readiness is not an authorization or network change; retain fresh health.
                queue.refresh()
            }
        }
    }

    private fun busy(): Boolean {
        if (BackgroundWork.nuvioPlaying.value || BackgroundWork.onDemandPlaying.value ||
            recorder.recordings.value.any { it.isRecording }) return true
        val state = playback.state.value
        if (state.channelKey == null) return false
        // Let the preview finish tuning before the check opens another provider connection.
        return !repository.settings.value.checkChannelsDuringPlayback ||
            state.catchupTitle != null || !state.hasRenderedVideoFrame || state.isBuffering ||
            !(state.isPlaying || state.userPaused)
    }

    private fun invalidate() {
        revision++
        queue.invalidate()
    }

    private fun observed(channel: LiveChannel): SportsProbeResult? {
        if (!allowed(channel)) return null
        val state = playback.state.value
        if (state.channelKey != channel.key || playback.playingChannel != channel ||
            !state.hasRenderedVideoFrame || state.videoWidth <= 0 || state.videoHeight <= 0) return null
        return when {
            state.isPlaying -> SportsProbeResult(SportsProbeStatus.WORKING, state.videoWidth, state.videoHeight,
                System.currentTimeMillis(), "Playing on this TV", playbackVerified = true,
                frameRate = playback.measuredFps.takeIf { it in 1f..240f } ?: 0f)
            state.userPaused -> SportsProbeResult(SportsProbeStatus.UNVERIFIED, state.videoWidth, state.videoHeight,
                System.currentTimeMillis(), "Playback paused")
            else -> null
        }
    }

    private fun replayAllowed(candidate: LiveChannel): Boolean {
        val sports = repository.settings.value
        if (!sports.showInSidebar) return false
        val published = (repository.vodMatches.value.keys + repository.videoMatches.value.keys).any { id ->
            val matches = SportsVideos.merge(
                repository.vodMatches.value[id].orEmpty(), repository.videoMatches.value[id].orEmpty(),
                sports.showHighlights, sports.showCondensed,
            )
            matches.any { it.channel == candidate } && event(id)?.let { it.leagueId !in sports.hiddenLeagues } == true
        }
        if (!published) return false
        val rules = currentRules() ?: return false
        return when (candidate.sourceId) {
            SportsAddonSource.SOURCE_ID -> rules.permitsAddon(candidate)
            SportsVideos.SOURCE_ID -> rules.permits(candidate)
            else -> sources.value.orEmpty().any { it.id == candidate.sourceId && it.enabled } && rules.permits(candidate)
        }
    }

    /** Eager snapshots make revocation apply to cached observations as well as new requests. */
    private fun allowed(candidate: LiveChannel, selectedGame: String? = gameId): Boolean {
        if (!profiles.activeProfileReady.value || profiles.activeProfileId.value != openingProfile) return false
        val id = selectedGame ?: return false
        val event = event(id) ?: return false
        val sports = repository.settings.value
        if (event.state == GameState.POST || !sports.showInSidebar || event.leagueId in sports.hiddenLeagues) return false
        val rules = currentRules() ?: return false
        val fresh = if (candidate.sourceId == SportsAddonSource.SOURCE_ID) {
            repository.matches.value[id].orEmpty().firstOrNull { it.channel.key == candidate.key }?.channel
                ?.takeIf { rules.permitsAddon(it) }
        } else {
            val source = sources.value?.firstOrNull { it.id == candidate.sourceId && it.enabled && it.liveEnabled }
            if (source == null) null else sourceIndex?.takeIf { it.input === channels.displayChannels.value }?.values?.get(candidate.key)
                ?.takeIf { rules.permits(it) }
        }
        return fresh == candidate && profiles.activeProfileReady.value && profiles.activeProfileId.value == openingProfile
    }

    /** Playback may target another game's channel; the probe page is not its source authority. */
    private fun playbackSourceAllowed(candidate: LiveChannel): Boolean {
        val rules = currentRules() ?: return false
        val fresh = if (candidate.sourceId == SportsAddonSource.SOURCE_ID) {
            repository.matches.value.entries.firstNotNullOfOrNull { (id, matches) ->
                matches.firstOrNull { sameTransport(it.channel, candidate) }?.channel
                    ?.takeIf { event(id)?.let { game -> game.leagueId !in repository.settings.value.hiddenLeagues } == true && rules.permitsAddon(it) }
            }
        } else {
            val source = sources.value?.firstOrNull { it.id == candidate.sourceId && it.enabled && it.liveEnabled }
            if (source == null) null else channels.displayChannels.value.firstOrNull { it.key == candidate.key }
                ?.takeIf { rules.permits(it) }
        }
        return fresh != null && sameTransport(fresh, candidate)
    }

    private fun sameTransport(a: LiveChannel, b: LiveChannel) = a.key == b.key && a.sourceId == b.sourceId &&
        a.url == b.url && a.headers == b.headers && a.stalkerRef == b.stalkerRef && a.drm == b.drm

    private fun currentRules(): SportsWatchRules? {
        val currentUser = user.value ?: return null
        val currentLive = live.value ?: return null
        val sports = repository.settings.value
        if (!sports.showInSidebar) return null
        val addons = enabledAddons.value.orEmpty()
        val unlocked = ParentalControls.unlocked.value
        val old = ruleCache
        if (old != null && old.user === currentUser && old.live === currentLive && old.sports === sports &&
            old.addons === addons && old.unlocked === unlocked) return old.value
        val rules = SportsWatchRules(currentUser, currentLive, sports, addons, unlocked)
        ruleCache = RuleCache(currentUser, currentLive, sports, addons, unlocked, rules)
        return rules
    }

    private fun event(id: String): SportsEvent? {
        val input = repository.known.value
        val old = eventCache
        if (old != null && old.input === input && old.id == id) return old.value
        val value = input.firstOrNull { it.id == id }
        eventCache = EventCache(input, id, value)
        return value
    }
}
