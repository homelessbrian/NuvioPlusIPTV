package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelDisplayChangesTest {
    @Test
    fun `playback history audio and list choices do not rebuild channel objects`() = runBlocking {
        val initial = LiveUserState(channelNames = mapOf("channel" to "My name"))
        val states = listOf(
            initial,
            initial.copy(lastChannelKey = "channel", previousChannelKey = "previous"),
            initial.copy(recent = listOf("channel"), favorites = listOf("channel")),
            initial.copy(channelAudio = mapOf("channel" to "en")),
            initial.copy(channelQuality = mapOf("channel" to "HD")),
            initial.copy(lastGroupId = "group", hiddenGroups = setOf("hidden")),
            initial.copy(hiddenChannels = setOf("channel"), lockedGroups = setOf("locked")),
            initial.copy(groupOrder = listOf("group"), channelOrder = mapOf("group" to listOf("channel"))),
            initial.copy(channelCopies = mapOf("group" to listOf("channel"))),
            initial.copy(vodSorts = mapOf("vod" to "name")),
        )
        val output = states.asFlow().displayChannelUserChanges().toList()
        assertEquals(1, output.size)
        assertSame(initial, output.single())
    }

    @Test
    fun `channel name changes are delivered`() = runBlocking {
        val initial = LiveUserState()
        val renamed = initial.copy(channelNames = mapOf("channel" to "Renamed"))
        assertEquals(listOf(initial, renamed), listOf(initial, renamed).asFlow().displayChannelUserChanges().toList())
    }

    @Test
    fun `channel number changes are delivered`() = runBlocking {
        val initial = LiveUserState()
        val renumbered = initial.copy(channelNumbers = mapOf("channel" to 500))
        assertEquals(listOf(initial, renumbered), listOf(initial, renumbered).asFlow().displayChannelUserChanges().toList())
    }

    @Test
    fun `group name changes are delivered`() = runBlocking {
        val initial = LiveUserState()
        val renamed = initial.copy(groupNames = mapOf("group" to "Renamed"))
        assertEquals(listOf(initial, renamed), listOf(initial, renamed).asFlow().displayChannelUserChanges().toList())
    }

    @Test
    fun `picked logo changes are delivered`() = runBlocking {
        val initial = LiveUserState()
        val changed = initial.copy(channelLogos = mapOf("channel" to "https://logo/new.png"))
        assertEquals(listOf(initial, changed), listOf(initial, changed).asFlow().displayChannelUserChanges().toList())
    }

    @Test
    fun `equal maps decoded into new objects do not rebuild channels`() = runBlocking {
        val initial = LiveUserState(
            channelNames = linkedMapOf("channel" to "Name"),
            channelNumbers = linkedMapOf("channel" to 1),
            groupNames = linkedMapOf("group" to "Group"),
            channelLogos = linkedMapOf("channel" to "https://logo/one.png"),
        )
        val decoded = initial.copy(
            lastChannelKey = "channel",
            channelNames = LinkedHashMap(initial.channelNames),
            channelNumbers = LinkedHashMap(initial.channelNumbers),
            groupNames = LinkedHashMap(initial.groupNames),
            channelLogos = LinkedHashMap(initial.channelLogos),
        )
        assertNotSame(initial.channelNames, decoded.channelNames)
        assertNotSame(initial.channelNumbers, decoded.channelNumbers)
        assertNotSame(initial.groupNames, decoded.groupNames)
        assertNotSame(initial.channelLogos, decoded.channelLogos)
        assertEquals(listOf(initial), listOf(initial, decoded).asFlow().displayChannelUserChanges().toList())
    }

    @Test
    fun `clearing overrides on a profile change is delivered`() = runBlocking {
        val initial = LiveUserState(
            channelNames = mapOf("channel" to "Name"),
            channelNumbers = mapOf("channel" to 500),
            groupNames = mapOf("group" to "Group"),
            channelLogos = mapOf("channel" to "https://logo/one.png"),
        )
        val cleared = LiveUserState()
        val output = listOf(initial, cleared, cleared.copy(lastGroupId = "other")).asFlow()
            .displayChannelUserChanges().toList()
        assertEquals(2, output.size)
        assertSame(initial, output[0])
        assertSame(cleared, output[1])
    }

    @Test
    fun `relevant changes after skipped metadata keep the latest channel overrides`() = runBlocking {
        val initial = LiveUserState()
        val renamed = initial.copy(channelNames = mapOf("channel" to "First"), recent = listOf("channel"))
        val renamedAgain = renamed.copy(channelNames = mapOf("channel" to "Second"))
        val output = listOf(initial, initial.copy(lastChannelKey = "channel"), renamed,
            renamed.copy(channelAudio = mapOf("channel" to "en")), renamedAgain).asFlow()
            .displayChannelUserChanges().toList()
        assertEquals(listOf(initial, renamed, renamedAgain), output)
        assertSame(renamed, output[1])
        assertSame(renamedAgain, output[2])
    }

    @Test
    fun `unrelated settings do not rebuild channel objects`() = runBlocking {
        val initial = LiveTvSettings(nameRemovals = "HD")
        val states = listOf(initial, initial.copy(infoBannerSeconds = 10),
            initial.copy(audioPassthrough = true), initial.copy(epgOffsetMinutes = 60),
            initial.copy(showChannelNumbers = false), initial.copy(homeRowEnabled = false))
        val output = states.asFlow().displayChannelSettingsChanges().toList()
        assertEquals(1, output.size)
        assertSame(initial, output.single())
    }

    @Test
    fun `changing or clearing name removal terms is delivered`() = runBlocking {
        val initial = LiveTvSettings(nameRemovals = "HD")
        val changed = initial.copy(nameRemovals = "HD, FHD", infoBannerSeconds = 10)
        val cleared = changed.copy(nameRemovals = "")
        val output = listOf(initial, initial.copy(showChannelNames = false), changed,
            changed.copy(audioPassthrough = true), cleared).asFlow().displayChannelSettingsChanges().toList()
        assertEquals(listOf(initial, changed, cleared), output)
        assertSame(changed, output[1])
        assertSame(cleared, output[2])
    }
}
