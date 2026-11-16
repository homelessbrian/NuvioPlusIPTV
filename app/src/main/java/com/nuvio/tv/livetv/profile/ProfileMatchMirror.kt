package com.nuvio.tv.livetv.profile

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.livetv.data.LiveTvPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileMatchMirror"
private const val MIRROR_DEBOUNCE_MS = 4_000L

/**
 * Matched profiles: when Nuvio's own settings (theme, layout, player and the rest) change on the
 * profile in use, the profiles it is matched with get the same settings. Nuvio keeps those per
 * profile on its own servers (and the phone app and other TVs read each profile's own copy), so
 * each matched profile is given a copy rather than sharing one. They are a few kilobytes and are
 * only sent when something changes. Live TV's choices need none of this: a matched group already
 * shares one copy of them.
 */
@Singleton
class ProfileMatchMirror @Inject constructor(
    private val profileManager: ProfileManager,
    private val profileDataStoreFactory: ProfileDataStoreFactory,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val liveTvPreferences: LiveTvPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    val flows = profileSettingsSyncService.syncedFeatureNames.map { feature ->
                        profileDataStoreFactory.get(profileId, feature).data
                    }
                    combine(flows) { all -> profileId to all.toList() }
                        .distinctUntilChanged()
                        // The first value is what the profile already had, not a change.
                        .drop(1)
                }
                .debounce(MIRROR_DEBOUNCE_MS)
                .collect { (profileId, _) ->
                    try {
                        val group = liveTvPreferences.profileMatchGroups.first()
                            .firstOrNull { profileId in it } ?: return@collect
                        (group - profileId).forEach { other ->
                            profileSettingsSyncService.copyProfileSetup(
                                sourceProfileId = profileId,
                                targetProfileId = other,
                                copyProviderCredentials = false
                            ).onFailure { Log.w(TAG, "Could not match profile $other to $profileId", it) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Matching profiles failed", e)
                    }
                }
        }
    }
}
