package com.nuvio.tv.ui.screens.account

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.data.local.ProfileLockStateDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.watchstats.WatchStatsRange
import com.nuvio.tv.data.watchstats.WatchStatsRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal data class LifetimeWatchedTotals(val movies: Int, val episodes: Int, val shows: Int)

internal enum class WatchStatsProfileAccess { ALLOWED, PIN_REQUIRED, UNAVAILABLE }

@HiltViewModel
internal class WatchStatsViewModel @Inject constructor(
    private val stats: WatchStatsRepository,
    private val watched: WatchedItemsPreferences,
    private val profileManager: ProfileManager,
    private val lockStore: ProfileLockStateDataStore,
    private val profileSync: ProfileSyncService,
    private val authManager: AuthManager,
) : ViewModel() {
    val profiles = profileManager.profiles
    val activeProfileId = profileManager.activeProfileId
    val profileReady = profileManager.activeProfileReady
    val authState = authManager.authState
    val profileLocks = lockStore.pinEnabled
    val trackingEnabled = stats.trackingEnabled

    fun lifetimeWatched(profileId: Int) = watched.observeAllItems(profileId).map { items ->
        val episodes = items.filter { it.season != null && it.episode != null }
        LifetimeWatchedTotals(
            movies = items.count { it.contentType.equals("movie", ignoreCase = true) && it.episode == null },
            episodes = episodes.size,
            shows = episodes.mapTo(HashSet()) { it.contentId }.size,
        )
    }.flowOn(Dispatchers.Default)

    suspend fun read(profileId: Int, range: WatchStatsRange) = stats.read(profileId, range)

    suspend fun setTrackingEnabled(enabled: Boolean) = stats.setTrackingEnabled(enabled)

    suspend fun profileAccess(profile: UserProfile): WatchStatsProfileAccess {
        if (!profileReady.value || profile !in profiles.value) return WatchStatsProfileAccess.UNAVAILABLE
        val viewer = activeProfileId.value
        if (profile.id == viewer) return WatchStatsProfileAccess.ALLOWED
        val auth = authState.value
        val locked = when (auth) {
            is AuthState.FullAccount -> {
                // Reading another profile must not trust a possibly stale cached unlock state.
                val locks = profileSync.pullProfileLockStates().getOrNull() ?: return WatchStatsProfileAccess.UNAVAILABLE
                currentCoroutineContext().ensureActive()
                if (auth != authState.value) return WatchStatsProfileAccess.UNAVAILABLE
                lockStore.replaceAll(locks)
                locks[profile.id] == true
            }
            AuthState.SignedOut -> {
                if (lockStore.pinEnabled.first()[profile.id] == true) return WatchStatsProfileAccess.UNAVAILABLE
                false
            }
            AuthState.Loading -> return WatchStatsProfileAccess.UNAVAILABLE
        }
        if (auth != authState.value || activeProfileId.value != viewer || profile !in profiles.value) {
            return WatchStatsProfileAccess.UNAVAILABLE
        }
        return if (locked) WatchStatsProfileAccess.PIN_REQUIRED else WatchStatsProfileAccess.ALLOWED
    }

    suspend fun verifyPin(profile: UserProfile, pin: String): ProfilePinCheck {
        val auth = authState.value
        val viewer = activeProfileId.value
        if (auth !is AuthState.FullAccount || profile !in profiles.value) return ProfilePinCheck.Unavailable
        val result = profileSync.verifyProfilePin(profile.id, pin).getOrNull()
        currentCoroutineContext().ensureActive()
        if (auth != authState.value || activeProfileId.value != viewer || profile !in profiles.value || result == null) {
            return ProfilePinCheck.Unavailable
        }
        return if (result.unlocked) ProfilePinCheck.Unlocked else ProfilePinCheck.Rejected(result.retryAfterSeconds)
    }
}

internal sealed interface ProfilePinCheck {
    data object Unlocked : ProfilePinCheck
    data object Unavailable : ProfilePinCheck
    data class Rejected(val retryAfterSeconds: Int) : ProfilePinCheck
}
