package com.nuvio.tv.livetv.calendar

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** For screens that aren't given a ViewModel (settings, the details page's bell). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarEntryPoint {
    fun calendarRepository(): CalendarRepository
    fun liveTvPreferences(): com.nuvio.tv.livetv.data.LiveTvPreferences
    fun sportsRepository(): com.nuvio.tv.livetv.sports.SportsRepository
    fun layoutPreferences(): com.nuvio.tv.data.local.LayoutPreferenceDataStore
    fun onDemandRepository(): com.nuvio.tv.livetv.ondemand.OnDemandRepository
    fun liveTvPlayback(): com.nuvio.tv.livetv.player.LiveTvPlaybackController
}
