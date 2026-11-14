@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nuvio.tv.livetv.multiview

import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.livetv.model.ChannelSort
import com.nuvio.tv.livetv.model.CustomGroup
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvSettings
import com.nuvio.tv.livetv.model.LiveUserState
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.ui.allowedLiveTvChannels
import com.nuvio.tv.livetv.ui.allowedLiveTvChannelsFlow
import com.nuvio.tv.livetv.ui.buildUi
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiview must preserve the guide's visibility rules without constructing its full ViewModel. */
class MultiviewChannelPolicyTest {
    @Test
    fun `picker matches guide all-visible policy for hidden locked adult and custom groups in every sort`() {
        val channels = listOf(
            channel("z", "sports", "Sports", "Zulu", 10),
            channel("a", "sports", "Sports", "Alpha", 20),
            channel("hidden-channel", "sports", "Sports"),
            channel("hidden-group", "hidden", "Hidden"),
            channel("locked", "locked", "Locked"),
            channel("adult", "adult", "Adults 18+"),
            channel("custom-lock", "custom:shared-id", "Plain playlist group"),
        )
        val user = LiveUserState(
            hiddenChannels = setOf("hidden-channel"), hiddenGroups = setOf("hidden"),
            lockedGroups = setOf("locked"),
            customGroups = listOf(CustomGroup("custom:shared-id", "Adults custom", listOf("z"))),
            channelCopies = mapOf("sports" to listOf("locked", "hidden-group")),
        )
        for (sort in ChannelSort.entries) {
            val settings = LiveTvSettings(parentalPin = "1234", lockAdultContent = true, channelSort = sort)
            val actual = allowedLiveTvChannels(channels, user, settings)
            val expected = if (sort == ChannelSort.NAME) listOf("a", "z") else listOf("z", "a")
            assertEquals(expected, actual.map { it.key })
            assertEquals(buildUi(channels, user, settings, ChannelGroup.ALL, "", true).allVisibleChannels, actual)
        }
    }

    @Test
    fun `hidden-group channels stay out of picker when custom groups or channel copies expose them elsewhere`() {
        val visible = channel("visible", "sports", "Sports")
        val hidden = channel("hidden", "hidden-group", "Hidden group")
        val user = LiveUserState(
            hiddenGroups = setOf("hidden-group"), favorites = listOf("hidden"), recent = listOf("hidden"),
            customGroups = listOf(CustomGroup("custom:pick", "My picks", listOf("hidden", "visible"))),
            channelCopies = mapOf("sports" to listOf("hidden")),
        )
        val settings = LiveTvSettings()
        val guide = buildUi(listOf(visible, hidden), user, settings, "custom:pick", "", true)
        assertEquals(listOf("hidden", "visible"), guide.channels.map { it.key })
        assertEquals(listOf(visible), allowedLiveTvChannels(listOf(visible, hidden), user, settings))
        assertEquals(guide.allVisibleChannels, allowedLiveTvChannels(listOf(visible, hidden), user, settings))
    }

    @Test
    fun `all-visible numbers and order do not inherit guide group reorder or sequential numbering`() {
        val channels = listOf(channel("first", number = 40), channel("second", number = 20))
        val user = LiveUserState(channelOrder = mapOf(ChannelGroup.ALL to listOf("second", "first")))
        val settings = LiveTvSettings(sequentialNumbers = true, channelSort = ChannelSort.PLAYLIST)
        val guide = buildUi(channels, user, settings, ChannelGroup.ALL, "", true)
        assertEquals(listOf("second", "first"), guide.channels.map { it.key })
        assertEquals(listOf(1, 2), guide.channels.map { it.number })
        val allowed = allowedLiveTvChannels(channels, user, settings)
        assertEquals(channels, allowed)
        assertSame(channels[0], allowed[0])
        assertEquals(guide.allVisibleChannels, allowed)
    }

    @Test
    fun `parental PIN and adult-lock settings keep the guide policy`() {
        val channels = listOf(channel("locked", "explicit", "Plain"), channel("adult", "adult", "Adults"))
        val user = LiveUserState(lockedGroups = setOf("explicit"))
        assertTrue(allowedLiveTvChannels(channels, user, LiveTvSettings(parentalPin = "1234")).isEmpty())
        assertEquals(listOf("adult"), allowedLiveTvChannels(channels, user,
            LiveTvSettings(parentalPin = "1234", lockAdultContent = false)).map { it.key })
        assertEquals(channels, allowedLiveTvChannels(channels, user, LiveTvSettings(parentalPin = "")))
    }

    @Test
    fun `flow replaces stale source objects and current profile hidden choices without retaining old channels`() = runTest {
        val original = channel("shared")
        val removed = channel("removed")
        val channels = MutableStateFlow(listOf(original, removed))
        val user = MutableStateFlow(LiveUserState())
        val settings = MutableStateFlow(LiveTvSettings())
        var latest = emptyList<LiveChannel>()
        backgroundScope.launch { allowedLiveTvChannelsFlow(channels, user, settings).collect { latest = it } }
        runCurrent()
        assertEquals(listOf(original, removed), latest)

        val replacement = original.copy(name = "Fresh name", url = "https://example.invalid/replaced", headers = mapOf("X-Test" to "fresh"))
        channels.value = listOf(replacement, channel("profile-two"))
        user.value = LiveUserState(hiddenChannels = setOf("profile-two"))
        runCurrent()
        assertEquals(listOf(replacement), latest)
        assertSame(replacement, latest.single())

        user.value = LiveUserState(hiddenChannels = setOf("shared"))
        runCurrent()
        assertEquals(listOf("profile-two"), latest.map { it.key })
        channels.value = emptyList()
        runCurrent()
        assertTrue(latest.isEmpty())
    }

    @Test
    fun `parental unlock invalidates the slim flow without a settings or playlist save`() = runTest {
        val group = "multiview-policy-unlock-${System.nanoTime()}"
        val locked = channel("locked", group, "Locked")
        val channels = MutableStateFlow(listOf(locked))
        val user = MutableStateFlow(LiveUserState(lockedGroups = setOf(group)))
        val settings = MutableStateFlow(LiveTvSettings(parentalPin = "1234"))
        var latest = emptyList<LiveChannel>()
        backgroundScope.launch { allowedLiveTvChannelsFlow(channels, user, settings).collect { latest = it } }
        runCurrent()
        assertTrue(latest.isEmpty())
        assertTrue(ParentalControls.unlock(group, "1234", settings.value))
        runCurrent()
        assertEquals(listOf(locked), latest)
    }

    @Test
    fun `unrelated playback and guide preference writes do not rescan a large picker list`() = runTest {
        val reads = AtomicInteger()
        val values = List(16_000) { channel("$it", number = it) }
        val counted = object : AbstractList<LiveChannel>() {
            override val size: Int get() = values.size
            override fun get(index: Int): LiveChannel = values[index].also { reads.incrementAndGet() }
        }
        val channels = MutableStateFlow<List<LiveChannel>>(counted)
        val user = MutableStateFlow(LiveUserState())
        val settings = MutableStateFlow(LiveTvSettings())
        var emissions = 0
        backgroundScope.launch { allowedLiveTvChannelsFlow(channels, user, settings).collect { emissions++ } }
        runCurrent()
        assertTrue(reads.get() > 0)
        reads.set(0)
        repeat(5) { index ->
            user.value = user.value.copy(lastChannelKey = "last-$index", channelAudio = mapOf("one" to "$index"))
            settings.value = settings.value.copy(use24HourClock = index % 2 == 0, infoBannerSeconds = index)
            runCurrent()
        }
        assertEquals(0, reads.get())
        assertEquals(1, emissions)
    }

    private fun channel(
        key: String,
        groupId: String = "sports",
        group: String = "Sports",
        name: String = key,
        number: Int = 1,
    ) = LiveChannel(key, "fixture", "Fixture", name, null, null, null, groupId, group, number, "https://example.invalid/$key")
}
