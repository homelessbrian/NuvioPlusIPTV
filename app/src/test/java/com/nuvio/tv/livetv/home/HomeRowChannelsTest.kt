@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.nuvio.tv.livetv.home

import com.nuvio.tv.livetv.model.*
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.buildUi
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HomeRowChannelsTest {
    private fun channel(key: String, group: String = "g", number: Int = 1) =
        LiveChannel(key, "p", "Playlist", key, null, null, null, group, group, number, "https://example.test/$key")

    private class Counted(private val data: List<LiveChannel>) : AbstractList<LiveChannel>() {
        val reads = AtomicInteger()
        val thread = AtomicReference<Thread>()
        override val size get() = data.size
        override fun get(index: Int): LiveChannel {
            reads.incrementAndGet()
            thread.set(Thread.currentThread())
            return data[index]
        }
    }

    private class Fixture(test: TestScope, initial: List<LiveChannel> = listOf(
        LiveChannel("b", "p", "Playlist", "Beta", null, null, null, "g", "Group", 2, "https://example.test/b"),
        LiveChannel("a", "p", "Playlist", "Alpha", null, null, null, "g", "Group", 1, "https://example.test/a"),
        LiveChannel("c", "p", "Playlist", "Charlie", null, null, null, "other", "Other", 3, "https://example.test/c")
    )) {
        val counted = Counted(initial)
        val channels = MutableStateFlow<List<LiveChannel>>(counted)
        val user = MutableStateFlow(LiveUserState(favorites = listOf("b", "a"), recent = listOf("a", "b")))
        val settings = MutableStateFlow(LiveTvSettings(homeRowHidePlaceholders = false))
        var subscriptions = 0
        val row = homeRowChannelSelections(
            channels.onStart { subscriptions++ }.onCompletion { subscriptions-- },
            user, settings, test.backgroundScope, StandardTestDispatcher(test.testScheduler)
        )
        val seen = ArrayList<Pair<List<LiveChannel>, LiveTvSettings>>()
        val collector = test.backgroundScope.launch { row.collect { seen += it } }
        fun expected(): List<LiveChannel> = if (!settings.value.homeRowEnabled) emptyList() else
            buildUi(channels.value, user.value, settings.value, homeRowGroup(settings.value), "", true, exactGroup = true).channels
        fun current() = row.replayCache.single()
    }

    @Test
    fun `cards and prefetch share one selection and a late collector reuses it`() = runTest {
        val f = Fixture(this)
        runCurrent()
        val reads = f.counted.reads.get()
        assertTrue(reads > 0)
        var second: Pair<List<LiveChannel>, LiveTvSettings>? = null
        backgroundScope.launch { f.row.collect { second = it } }
        runCurrent()
        assertEquals(1, f.subscriptions)
        assertEquals(reads, f.counted.reads.get())
        assertSame(f.current(), second)
        f.user.value = f.user.value.copy(favorites = listOf("a"))
        runCurrent()
        assertEquals(listOf("a"), second!!.first.map { it.key })
        assertSame(f.current(), second)
    }

    @Test
    fun `unrelated playback reminder and on-demand saves do not scan the playlist`() = runTest {
        val f = Fixture(this)
        runCurrent(); f.counted.reads.set(0)
        val original = f.current()
        repeat(20) { i ->
            f.user.value = f.user.value.copy(
                lastChannelKey = "last-$i", previousChannelKey = "prev-$i", lastGroupId = "last-group-$i",
                channelQuality = mapOf("a" to "$i"), channelAudio = mapOf("a" to "$i"),
                reminders = listOf(Reminder("a", "A", "Reminder $i", i.toLong())),
                vodHiddenCategories = setOf("movie:$i"), vodGroupNames = mapOf("x" to "$i")
            )
            runCurrent()
        }
        assertEquals(0, f.counted.reads.get())
        assertEquals(1, f.seen.size)
        assertSame(original, f.current())
    }

    @Test
    fun `unrelated and independently observed presentation settings skip selection`() = runTest {
        val f = Fixture(this)
        runCurrent(); f.counted.reads.set(0)
        repeat(20) { i ->
            f.settings.value = f.settings.value.copy(
                infoBannerSeconds = i, homeRowTitle = "Title $i", homeRowCompact = i % 2 == 0,
                homeRowShowNext = i % 2 == 0, homeRowOpensGuide = i % 2 == 0,
                homeRowAboveContinueWatching = i % 2 == 0, homeRowHiddenProfiles = setOf(i),
                use24HourClock = i % 2 == 0
            )
            runCurrent()
        }
        assertEquals(0, f.counted.reads.get())
        assertEquals(1, f.seen.size)
    }

    @Test
    fun `every saved selection input still reaches buildUi`() = runTest {
        val changes: List<(LiveUserState) -> LiveUserState> = listOf(
            { it.copy(hiddenChannels = setOf("a")) }, { it.copy(hiddenGroups = setOf("g")) },
            { it.copy(favorites = listOf("c")) }, { it.copy(recent = listOf("c")) },
            { it.copy(lockedGroups = setOf("g")) },
            { it.copy(customGroups = listOf(CustomGroup("custom:test", "Custom", listOf("c")))) },
            { it.copy(channelOrder = mapOf("g" to listOf("a", "b"))) },
            { it.copy(channelCopies = mapOf("g" to listOf("c"))) },
            { it.copy(groupOrder = listOf("other", "g")) }
        )
        for (change in changes) {
            val f = Fixture(this)
            runCurrent(); f.counted.reads.set(0)
            f.user.value = change(f.user.value); runCurrent()
            assertTrue("A relevant user change must rebuild", f.counted.reads.get() > 0)
            assertEquals(f.expected(), f.current().first)
            f.collector.cancel()
        }
    }

    @Test
    fun `all selection and program-preparation settings remain current`() = runTest {
        val changes: List<(LiveTvSettings) -> LiveTvSettings> = listOf(
            { it.copy(homeRowEnabled = false) }, { it.copy(homeRowSource = "recent") },
            { it.copy(channelSort = ChannelSort.NAME) }, { it.copy(sequentialNumbers = true) },
            { it.copy(showFavoritesGroup = false) }, { it.copy(showRecentGroup = false) },
            { it.copy(showAllChannelsGroup = false) }, { it.copy(groupSort = "name") },
            { it.copy(groupPlaylistHeadings = false) }, { it.copy(parentalPin = "1234") },
            { it.copy(lockAdultContent = false) }, { it.copy(epgOffsetMinutes = 60) },
            { it.copy(homeRowHidePlaceholders = true) }, { it.copy(homeRowSort = "ending") },
            { it.copy(homeRowLimit = 1) }
        )
        for (change in changes) {
            val f = Fixture(this)
            runCurrent()
            f.settings.value = change(f.settings.value); runCurrent()
            assertEquals(f.settings.value, f.current().second)
            assertEquals(f.expected(), f.current().first)
            f.collector.cancel()
        }
    }

    @Test
    fun `favorites recent playlist custom and missing groups keep the same ordering`() = runTest {
        val f = Fixture(this)
        f.user.value = f.user.value.copy(
            customGroups = listOf(CustomGroup("custom:test", "Custom", listOf("c", "a"))),
            channelCopies = mapOf("g" to listOf("c")), channelOrder = mapOf("g" to listOf("c", "a"))
        )
        runCurrent()
        for (source in listOf("", "favorites", "recent", "g", "other", "custom:test", "missing", ChannelGroup.ALL)) {
            for (sort in ChannelSort.entries) {
                f.settings.value = f.settings.value.copy(homeRowSource = source, channelSort = sort)
                runCurrent()
                assertEquals(f.expected(), f.current().first)
            }
        }
        f.settings.value = f.settings.value.copy(homeRowSource = "missing")
        runCurrent()
        assertTrue(f.current().first.isEmpty())
    }

    @Test
    fun `hidden channels hidden playlist groups and parental locks keep their rules`() = runTest {
        val f = Fixture(this)
        f.user.value = f.user.value.copy(
            hiddenGroups = setOf("g"), hiddenChannels = setOf("a"),
            customGroups = listOf(CustomGroup("custom:test", "Custom", listOf("b", "a", "c"))),
            lockedGroups = setOf("other")
        )
        f.settings.value = f.settings.value.copy(homeRowSource = "custom:test", parentalPin = "1234")
        runCurrent()
        assertEquals(listOf("b"), f.current().first.map { it.key })
        assertEquals(f.expected(), f.current().first)
        f.settings.value = f.settings.value.copy(parentalPin = "")
        runCurrent()
        assertEquals(listOf("b", "c"), f.current().first.map { it.key })
    }

    @Test
    fun `parental unlock invalidates the shared selection without another saved change`() = runTest {
        val f = Fixture(this, listOf(channel("locked", "home-row-unlock-test")))
        f.user.value = LiveUserState(favorites = listOf("locked"), lockedGroups = setOf("home-row-unlock-test"))
        f.settings.value = f.settings.value.copy(parentalPin = "1234")
        runCurrent()
        assertTrue(f.current().first.isEmpty())
        assertTrue(ParentalControls.unlock("home-row-unlock-test", "1234", f.settings.value))
        runCurrent()
        assertEquals(listOf("locked"), f.current().first.map { it.key })
    }

    @Test
    fun `disabled empty and replacement-profile lists do not return old channels`() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.settings.value = f.settings.value.copy(homeRowEnabled = false)
        runCurrent(); assertTrue(f.current().first.isEmpty())
        f.channels.value = listOf(channel("profile-two"))
        f.user.value = LiveUserState(favorites = listOf("profile-two"))
        f.settings.value = f.settings.value.copy(homeRowEnabled = true)
        runCurrent()
        assertEquals(listOf("profile-two"), f.current().first.map { it.key })
        f.channels.value = emptyList()
        runCurrent(); assertTrue(f.current().first.isEmpty())
    }

    @Test
    fun `renames numbers logos replacements and sequential numbering stay current`() = runTest {
        val f = Fixture(this)
        runCurrent()
        val replacement = channel("a", number = 42).copy(name = "Renamed", logo = "https://example.test/logo")
        f.channels.value = listOf(replacement); runCurrent()
        assertSame(replacement, f.current().first.single())
        f.settings.value = f.settings.value.copy(sequentialNumbers = true); runCurrent()
        assertEquals(1, f.current().first.single().number)
        assertEquals(f.expected(), f.current().first)
    }

    @Test
    fun `randomized updates match the unfiltered selection`() = runTest {
        val f = Fixture(this)
        val random = Random(81)
        runCurrent()
        repeat(100) {
            f.user.value = f.user.value.copy(
                favorites = listOf("a", "b", "c").shuffled(random).take(random.nextInt(4)),
                hiddenChannels = if (random.nextBoolean()) setOf("a") else emptySet(),
                lastChannelKey = "metadata-$it"
            )
            f.settings.value = f.settings.value.copy(
                homeRowSource = listOf("favorites", "recent", "g", "missing")[random.nextInt(4)],
                channelSort = ChannelSort.entries[random.nextInt(3)], sequentialNumbers = random.nextBoolean()
            )
            runCurrent()
            assertEquals(f.expected(), f.current().first)
        }
    }

    @Test
    fun `upstream stays shared during the grace period and stops without consumers`() = runTest {
        val f = Fixture(this)
        runCurrent(); assertEquals(1, f.subscriptions)
        f.collector.cancel(); runCurrent()
        advanceTimeBy(4_999); runCurrent(); assertEquals(1, f.subscriptions)
        advanceTimeBy(1); runCurrent(); assertEquals(0, f.subscriptions)
        val reads = f.counted.reads.get()
        f.user.value = f.user.value.copy(favorites = listOf("c")); runCurrent()
        assertEquals(reads, f.counted.reads.get())
        backgroundScope.launch { f.row.collect {} }; runCurrent()
        assertEquals(1, f.subscriptions)
        assertEquals(listOf("c"), f.current().first.map { it.key })
    }

    @Test
    fun `owner cancellation stops selection`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val channels = MutableStateFlow<List<LiveChannel>>(listOf(channel("a")))
        val user = MutableStateFlow(LiveUserState(favorites = listOf("a")))
        val row = homeRowChannelSelections(channels, user, MutableStateFlow(LiveTvSettings()), owner, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { row.collect {} }; runCurrent()
        val before = row.replayCache.single()
        owner.cancel(); runCurrent()
        user.value = user.value.copy(favorites = emptyList()); runCurrent()
        assertSame(before, row.replayCache.single())
    }

    @Test
    fun `default selection runs off the caller thread`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob())
        val channels = Counted(listOf(channel("a")))
        val caller = Thread.currentThread()
        try {
            val row = homeRowChannelSelections(
                MutableStateFlow(channels), MutableStateFlow(LiveUserState(favorites = listOf("a"))),
                MutableStateFlow(LiveTvSettings()), owner
            )
            withTimeout(5_000) { row.first() }
            assertNotSame(caller, channels.thread.get())
        } finally { owner.cancel() }
    }
}
