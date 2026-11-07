package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Keep in step with the saved overrides read by LiveTvChannelStore's channel transform. */
internal fun Flow<LiveUserState>.displayChannelUserChanges(): Flow<LiveUserState> =
    distinctUntilChanged { a, b ->
        a.channelNames == b.channelNames && a.channelNumbers == b.channelNumbers &&
            a.groupNames == b.groupNames && a.channelLogos == b.channelLogos
    }

/** Other settings are consumed separately by the guide and player. */
internal fun Flow<LiveTvSettings>.displayChannelSettingsChanges(): Flow<LiveTvSettings> =
    distinctUntilChanged { a, b -> a.nameRemovals == b.nameRemovals }
