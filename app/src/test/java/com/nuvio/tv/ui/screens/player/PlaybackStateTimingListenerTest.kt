package com.nuvio.tv.ui.screens.player

import android.app.Application
import androidx.media3.common.Player
import com.nuvio.tv.core.player.MovieStartupTrace
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class PlaybackStateTimingListenerTest {
    private var clock = 100L
    private val attempt = MovieStartupTrace.Recorder { clock }.begin()

    @Test fun reportsAnUnfinishedSessionCallbackAndItsCompletedDurationEvenIfItThrows() {
        val failure = IllegalStateException("simulated listener failure")
        val listener = PlaybackStateTimingListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                clock += 8_000
                val inFlight = attempt.sessionStateTiming.summary(clock, includeInFlightAge = true)!!
                assertTrue(inFlight.contains("BUFFERING entered +500ms, still in callback after 8000ms"))
                assertFalse(attempt.sessionStateTiming.summary(clock)!!.contains("after 8000ms"))
                throw failure
            }
        }, attempt)
        clock += 500
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        })
        assertTrue(attempt.sessionStateTiming.summary(clock)!!.endsWith("1 completed, longest BUFFERING at +500ms took 8000ms"))
    }

    @Test fun retiredAttemptStillDispatchesWithoutRecordingMoreTimings() {
        attempt.retire()
        var calls = 0
        PlaybackStateTimingListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { calls++ }
        }, attempt).onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, calls)
        assertNull(attempt.sessionStateTiming.summary(clock))
    }

    @Test fun forwardsEveryListenerCallbackInTheBundledMedia3WithItsOriginalArguments() {
        val type = Player.Listener::class.java
        val received = mutableListOf<Pair<Method, List<Any?>>>()
        val target = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            received += method to args.orEmpty().toList()
            null
        } as Player.Listener
        val listener = PlaybackStateTimingListener(target, attempt)
        for (method in type.declaredMethods.filter { it.name.startsWith("on") }) {
            val args = method.parameterTypes.map { parameter ->
                when (parameter) {
                    Integer.TYPE -> 2
                    java.lang.Long.TYPE -> 7L
                    java.lang.Float.TYPE -> 0.5f
                    java.lang.Boolean.TYPE -> true
                    List::class.java -> emptyList<Any>()
                    else -> mockkClass(parameter.kotlin)
                }
            }
            method.invoke(listener, *args.toTypedArray())
            assertEquals("Forwarding ${method.name}", listOf(method to args), received)
            received.clear()
        }
        listener.onMediaItemTransition(null, 0)
        listener.onPlayerErrorChanged(null)
        assertEquals(listOf(null, null), received.map { it.second.first() })
    }

    @Test fun sessionRegistrationPreservesDuplicatesRemovalAndTheAdvertisedPlayerIdentity() {
        val underlying = mockk<Player>()
        val registered = linkedSetOf<Player.Listener>()
        every { underlying.addListener(any()) } answers { registered.add(firstArg()); Unit }
        every { underlying.removeListener(any()) } answers { registered.remove(firstArg()); Unit }
        val sessionPlayer = SafeMediaSessionPlayer(underlying, attempt)
        var eventPlayer: Player? = null
        val sessionListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { eventPlayer = player }
        }
        sessionPlayer.addListener(sessionListener)
        sessionPlayer.addListener(sessionListener)
        registered.single().onEvents(underlying, mockk())
        assertSame(sessionPlayer, eventPlayer)
        sessionPlayer.removeListener(sessionListener)
        assertTrue(registered.isEmpty())
    }
}
