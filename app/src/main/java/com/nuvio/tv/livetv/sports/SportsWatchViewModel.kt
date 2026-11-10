package com.nuvio.tv.livetv.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.parental.ParentalControls
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A small Sports source/permission adapter shared by the single-player strip and pane screen. */
@HiltViewModel
class SportsWatchViewModel @Inject constructor(
    private val repo: SportsRepository,
    private val channels: LiveTvChannelStore,
    preferences: LiveTvPreferences,
    addons: AddonRepository,
    private val profiles: ProfileManager,
    selection: SportsWatchSelection,
) : ViewModel() {
    val settings = repo.settings
    val currentProfile: StateFlow<Int> = profiles.activeProfileId
    private val openingProfile = currentProfile.value
    private val leases = ConcurrentHashMap.newKeySet<SportsViewingLease>()

    private data class PermissionSnapshot(
        val playlist: List<LiveChannel>,
        val matches: Map<String, List<ChannelMatch>>,
        val rules: SportsWatchRules,
        val allowed: Map<String, LiveChannel>,
    )

    // Keep the small revocation inputs separate from the background channel projection.
    // Native permission checks read this eager value even while a large list is rebuilding.
    private val rules: StateFlow<SportsWatchRules?> = combine(
        preferences.userState.distinctUntilChanged { a, b ->
            a.hiddenChannels == b.hiddenChannels && a.hiddenGroups == b.hiddenGroups &&
                a.lockedGroups == b.lockedGroups
        },
        preferences.settings.distinctUntilChanged { a, b ->
            a.parentalPin == b.parentalPin && a.lockAdultContent == b.lockAdultContent
        },
        settings.distinctUntilChanged { a, b ->
            a.showInSidebar == b.showInSidebar && a.hiddenLeagues == b.hiddenLeagues &&
                a.eventChannels == b.eventChannels && a.networkChannels == b.networkChannels &&
                a.teamChannels == b.teamChannels && a.leagueChannels == b.leagueChannels
        },
        addons.getInstalledAddons().map { installed ->
            installed.enabledAddons().mapTo(HashSet()) {
                "addon:${Integer.toHexString(it.baseUrl.hashCode())}:"
            } as Set<String>
        }.distinctUntilChanged(),
        ParentalControls.unlocked,
    ) { user, live, sports, enabled, unlocked -> SportsWatchRules(user, live, sports, enabled, unlocked) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val permissions: StateFlow<PermissionSnapshot?> = combine(
        channels.displayChannels, repo.matches, rules, currentProfile, profiles.activeProfileReady,
    ) { playlist, matched, currentRules, profile, profileReady ->
        if (!profileReady || currentRules == null || !SportsWatchPolicy.sameProfile(openingProfile, profile)) null
        else PermissionSnapshot(playlist, matched, currentRules,
            SportsWatchPolicy.allowedChannels(playlist, matched, currentRules))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Includes manual hidden-group Sports selections and installed Sports-addon sources. */
    val allowedChannels: StateFlow<List<LiveChannel>> = combine(permissions, currentProfile) { snapshot, profile ->
        if (snapshot == null || !SportsWatchPolicy.sameProfile(openingProfile, profile)) emptyList()
        else snapshot.allowed.values.toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val clock = flow {
        while (true) { emit(System.currentTimeMillis()); delay(15_000L) }
    }

    internal val games: StateFlow<List<SportsWatchGame>> = combine(
        repo.events, permissions, clock, currentProfile,
    ) { events, snapshot, now, profile ->
        if (snapshot == null || !SportsWatchPolicy.sameProfile(openingProfile, profile)) emptyList()
        else SportsWatchPolicy.games(events, snapshot.matches, snapshot.allowed, snapshot.rules.sports, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class PageInputs(
        val events: List<SportsEvent>, val known: List<SportsEvent>, val follows: SportsFollows,
        val settings: SportsSettings, val selection: SportsWatchPageSelection?,
    )

    private val pageInputs = combine(repo.events, repo.known, repo.follows, settings, selection.current) {
        events, known, follows, sports, selected -> PageInputs(events, known, follows, sports, selected)
    }

    /** Preserve the page's selected filter and arranged rows, dropping results and distant games. */
    internal val rows: StateFlow<List<SportsWatchRow>> = combine(
        pageInputs, permissions, clock, currentProfile,
    ) { input, snapshot, now, profile ->
        if (snapshot == null || !SportsWatchPolicy.sameProfile(openingProfile, profile)) emptyList()
        else {
            val query = SportsWatchPolicy.pageQuery(input.selection, profile, input.follows)
            val page = SportsPageLists.build(repo, query, input.events, input.known, snapshot.matches,
                input.follows, input.settings, snapshot.playlist.isNotEmpty(), now)
            SportsWatchPolicy.rows(page, input.events, snapshot.matches, snapshot.allowed, input.settings, now)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Resolve the requested broadcast at the press, or Sports' best allowed source when omitted. */
    fun resolve(eventId: String, channelKey: String? = null): ChannelMatch? {
        if (!profileStillCurrent()) return null
        val event = repo.events.value.firstOrNull { it.id == eventId } ?: return null
        val currentSettings = settings.value
        if (!currentSettings.showInSidebar || event.leagueId in currentSettings.hiddenLeagues ||
            !SportsWatchPolicy.liveEvent(event, System.currentTimeMillis())) return null
        val snapshot = permissions.value ?: return null
        val latestRules = (rules.value ?: return null).copy(
            sports = currentSettings, unlockedGroups = ParentalControls.unlocked.value)
        val match = SportsWatchPolicy.resolveMatch(repo.matches.value[eventId].orEmpty(), channelKey) {
            freshSource(it, snapshot, latestRules)
        } ?: return null
        return match.takeIf {
            profileStillCurrent() && repo.matches.value[eventId].orEmpty()
                .any { current -> current.channel.key == match.channel.key } && mayPlay(match.channel)
        }
    }

    /** Also used inside native focus/resume callbacks, before the next Compose snapshot. */
    fun mayPlay(channel: LiveChannel): Boolean {
        if (!profileStillCurrent()) return false
        val snapshot = permissions.value ?: return false
        val latestRules = (rules.value ?: return false).copy(
            sports = settings.value, unlockedGroups = ParentalControls.unlocked.value)
        return freshSource(channel, snapshot, latestRules) == channel && profileStillCurrent()
    }

    private fun profileStillCurrent(): Boolean = profiles.activeProfileReady.value &&
        SportsWatchPolicy.sameProfile(openingProfile, currentProfile.value)

    private fun freshSource(
        candidate: LiveChannel,
        snapshot: PermissionSnapshot,
        currentRules: SportsWatchRules,
    ): LiveChannel? {
        if (candidate.sourceId == SportsAddonSource.SOURCE_ID) {
            // Synthetic addon channels are absent from the playlist store. Public Sports matches
            // are their authority; removal/replacement must not keep an older URL or headers alive.
            val published = repo.matches.value.values.asSequence().flatMap { it.asSequence() }
                .firstOrNull { it.channel.key == candidate.key }?.channel ?: return null
            return published.takeIf { currentRules.permitsAddon(it) }
        }
        val current = if (snapshot.playlist === channels.displayChannels.value) snapshot.allowed[candidate.key]
        else channels.displayChannels.value.firstOrNull { it.key == candidate.key }
        return current?.takeIf { currentRules.permits(it) }
    }

    /** Register only while the watching surface exists; ordinary VM/back-stack retention is idle. */
    fun acquireViewing(): AutoCloseable {
        val lease = SportsViewingLease({ repo.start(); repo.setViewing(true) }, { repo.setViewing(false) })
        leases += lease
        return AutoCloseable { lease.close(); leases -= lease }
    }

    override fun onCleared() {
        leases.forEach { it.close() }
        leases.clear()
        super.onCleared()
    }
}
