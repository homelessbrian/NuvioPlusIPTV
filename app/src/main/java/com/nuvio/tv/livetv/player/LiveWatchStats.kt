package com.nuvio.tv.livetv.player

import com.nuvio.tv.data.watchstats.WatchActivity
import com.nuvio.tv.data.watchstats.WatchActivityKind
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.sports.SportsAddonSource
import com.nuvio.tv.livetv.sports.SportsVideos

internal fun LiveChannel.watchStatsActivity(profileId: Int): WatchActivity? {
    // Add-on sports streams can be replays, and adult channels stay out of viewing history.
    if (sourceId == SportsVideos.SOURCE_ID || sourceId == SportsAddonSource.SOURCE_ID) return null
    if (ParentalControls.isAdult(name) || ParentalControls.isAdult(group)) return null
    return WatchActivity(profileId, WatchActivityKind.LIVE, key, name)
}
