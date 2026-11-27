package com.nuvio.tv.remote

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RemoteInputRouterTest {
    private class Clock : RemoteInputScheduler {
        private data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Task>()
        private var now = 0L
        override fun after(delayMillis: Long, task: () -> Unit): AutoCloseable {
            val scheduled = Task(now + delayMillis, task); tasks += scheduled
            return AutoCloseable { scheduled.cancelled = true }
        }
        fun advance(to: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
                now = next.at; next.cancelled = true; next.run()
            }
            now = to
        }
    }
    private data class Native(val action: Int, val code: Int, val repeats: Int, val cancelled: Boolean)
    private val left = RemoteButton(KeyEvent.KEYCODE_DPAD_LEFT)
    private fun event(action: Int, time: Long, code: Int = KeyEvent.KEYCODE_DPAD_LEFT, repeat: Int = 0, flags: Int = 0, downAt: Long = 0): KeyEvent =
        KeyEvent(downAt, time, action, code, repeat, 0, 7, 0, flags)

    @Test fun shortAndHeldCustomActionsAreExclusiveEvenWithoutHardwareRepeats() {
        val clock = Clock(); val actions = mutableListOf<RemoteAction>(); val native = mutableListOf<Int>()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.HOLD, left, RemoteAction.OPTIONS)
        val router = RemoteInputRouter({ config }, { native += it.action; true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true })
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 0)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 100)))
        assertEquals(listOf(RemoteAction.OPEN_GUIDE), actions)
        actions.clear()
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 0)))
        clock.advance(500)
        assertEquals(listOf(RemoteAction.OPTIONS), actions)
        router.dispatch(event(KeyEvent.ACTION_DOWN, 700, repeat = 2))
        router.dispatch(event(KeyEvent.ACTION_UP, 900))
        assertEquals(listOf(RemoteAction.OPTIONS), actions)
        assertTrue(native.isEmpty())
    }

    @Test fun customizingOnlyHoldReplaysTheOriginalShortPressAsCompletePair() {
        val clock = Clock(); val native = mutableListOf<Native>(); val actions = mutableListOf<RemoteAction>()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.HOLD, left, RemoteAction.OPTIONS)
        val router = RemoteInputRouter({ config }, { native += Native(it.action, it.keyCode, it.repeatCount, it.isCanceled); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0)); router.dispatch(event(KeyEvent.ACTION_UP, 100))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), native.map { it.action })
        assertTrue(actions.isEmpty())
    }

    @Test fun originalRightHoldKeepsNative600msThresholdInsteadOfRestartingLegacyWallClock() {
        val clock = Clock(); val actions = mutableListOf<RemoteAction>(); var nativeDowns = 0
        val right = RemoteButton(KeyEvent.KEYCODE_DPAD_RIGHT)
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, right, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { if (it.action == KeyEvent.ACTION_DOWN) nativeDowns++; true }, 1000, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true }, {
            listOf(RemoteDefaultBinding(right, RemotePress.HOLD, RemoteAction.LAST_CHANNEL, 600))
        })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0, right.keyCode))
        clock.advance(500); assertTrue(actions.isEmpty())
        clock.advance(599); assertTrue(actions.isEmpty())
        clock.advance(600); assertEquals(listOf(RemoteAction.LAST_CHANNEL), actions)
        router.dispatch(event(KeyEvent.ACTION_UP, 800, right.keyCode))
        assertEquals(0, nativeDowns)
        assertEquals(listOf(RemoteAction.LAST_CHANNEL), actions)
    }

    @Test fun unknownOriginalHoldKeepsNativeRepeatsButCancellationCleansUpWithoutCommittingRelease() {
        val clock = Clock(); val native = mutableListOf<Native>(); val actions = mutableListOf<RemoteAction>(); var cancelled = 0
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { native += Native(it.action, it.keyCode, it.repeatCount, it.isCanceled); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true }, { emptyList() }, onNativeCancel = { cancelled++ })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0)); clock.advance(500)
        router.dispatch(event(KeyEvent.ACTION_DOWN, 700, repeat = 3))
        router.dispatch(event(KeyEvent.ACTION_UP, 800, flags = KeyEvent.FLAG_CANCELED))
        assertEquals(listOf(0, 0, 0), native.map { it.action })
        assertEquals(listOf(0, 1, 3), native.map { it.repeats })
        assertEquals(1, cancelled); assertTrue(actions.isEmpty())
    }

    @Test fun routeOrConfigurationChangeCancelsPendingGestureAndSwallowsRelease() {
        val clock = Clock(); val actions = mutableListOf<RemoteAction>(); var active = true
        var config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { fail("No native event should escape"); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { active }, { actions += it; true })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0)); active = false; clock.advance(500)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 700))); assertTrue(actions.isEmpty())
        active = true
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        config = config.withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.LAST_CHANNEL)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 100))); assertTrue(actions.isEmpty())
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0)); router.dispatch(event(KeyEvent.ACTION_UP, 100))
        assertEquals(listOf(RemoteAction.LAST_CHANNEL), actions)
    }

    @Test fun dialogSuspensionAndMixedKeysNeverLeakAnOrphanReleaseOrDoubleAction() {
        val clock = Clock(); val actions = mutableListOf<RemoteAction>()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { fail("Unexpected replay"); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        val suspended = router.suspendRemapping()
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 50, repeat = 0)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 100)))
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0)))
        suspended.close()
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 10, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 20)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 30, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertTrue(actions.isEmpty())
    }

    @Test fun enterAliasUsesTheOkAssignmentWhileProtectedMenuNavigationStaysNative() {
        val actions = mutableListOf<RemoteAction>(); val clock = Clock()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE,
            RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER), RemoteAction.OPTIONS)
        val router = RemoteInputRouter({ config }, { true }, 500, clock)
        val live = router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_ENTER))
        router.dispatch(event(KeyEvent.ACTION_UP, 100, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf(RemoteAction.OPTIONS), actions)
        live.close()
        router.register(RemoteScreen.TV_GUIDE, { true }, { fail("Menu selection must stay native"); true })
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_BACK)))
    }

    @Test fun unchangedInputNeverReplaysOrStartsAMappingFromARepeat() {
        val router = RemoteInputRouter({ RemoteConfiguration() }, { fail("Unchanged input must pass through"); true }, 500, Clock())
        router.register(RemoteScreen.LIVE_TV, { true }, { fail("No override"); true })
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0)))
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 700, repeat = 4)))
        assertFalse(router.dispatch(event(KeyEvent.ACTION_UP, 800)))
    }

    @Test fun earlyNativeRepeatsRetainOriginalHeldBehaviorRatherThanFiringCustomShortPress() {
        val clock = Clock(); val native = mutableListOf<Int>(); val actions = mutableListOf<RemoteAction>()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { native += it.action; true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true }, { emptyList() })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        router.dispatch(event(KeyEvent.ACTION_DOWN, 350, repeat = 1))
        router.dispatch(event(KeyEvent.ACTION_UP, 450))
        assertEquals(listOf(0, 0, 1), native); assertTrue(actions.isEmpty())
    }

    @Test fun anOldAbandonedReleaseCannotCancelANewPhysicalPressAfterFocusReturns() {
        val clock = Clock(); val actions = mutableListOf<RemoteAction>()
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, left, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { fail("Unexpected native replay"); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0)); router.cancelPending()
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 1000, downAt = 1000)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 1050)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 1100, downAt = 1000)))
        assertEquals(listOf(RemoteAction.OPEN_GUIDE), actions)
    }

    @Test fun repeatableDefaultHoldUsesNativeSeekAndCleansOnlyTheOriginalRouteOnContextLoss() {
        val clock = Clock(); val native = mutableListOf<Int>(); val actions = mutableListOf<RemoteAction>()
        var active = true; var oldCleanups = 0; var newCleanups = 0
        val right = RemoteButton(KeyEvent.KEYCODE_DPAD_RIGHT)
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, right, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, { native += it.action; true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { active }, { actions += it; true }, {
            listOf(RemoteDefaultBinding(right, RemotePress.HOLD, RemoteAction.SEEK_FORWARD, repeatable = true))
        }, onNativeCancel = { oldCleanups++ })
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0, right.keyCode)); clock.advance(500)
        router.dispatch(event(KeyEvent.ACTION_DOWN, 700, right.keyCode, repeat = 3))
        assertEquals(listOf(0, 0, 0), native); assertTrue(actions.isEmpty())
        active = false
        router.register(RemoteScreen.MOVIE, { true }, { fail("Old release must not act on a new screen"); true }, onNativeCancel = { newCleanups++ })
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 800, right.keyCode)))
        assertEquals(1, oldCleanups); assertEquals(0, newCleanups)
        assertEquals(listOf(0, 0, 0), native)
    }

    @Test fun browseEligibilityKeepsCustomBrowseWorkingWithoutStealingNativeOkOrOtherActions() {
        val clock = Clock(); var browsing = false; val actions = mutableListOf<RemoteAction>()
        val red = RemoteButton(KeyEvent.KEYCODE_PROG_RED)
        val guide = RemoteButton(KeyEvent.KEYCODE_GUIDE)
        val ok = RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER)
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, red, RemoteAction.BROWSE_NEXT_CHANNEL)
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, guide, RemoteAction.OPTIONS)
            .withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, ok, RemoteAction.BROWSE_NEXT_CHANNEL)
        val router = RemoteInputRouter({ config }, { fail("Unexpected native replay"); true }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; browsing = true; true },
            canHandleAction = { !browsing || it == RemoteAction.BROWSE_NEXT_CHANNEL },
            canRemapButton = { !browsing || it != ok })
        repeat(2) {
            router.dispatch(event(KeyEvent.ACTION_DOWN, 0, red.keyCode)); router.dispatch(event(KeyEvent.ACTION_UP, 100, red.keyCode))
        }
        assertEquals(listOf(RemoteAction.BROWSE_NEXT_CHANNEL, RemoteAction.BROWSE_NEXT_CHANNEL), actions)
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0, ok.keyCode)))
        assertFalse(router.dispatch(event(KeyEvent.ACTION_DOWN, 0, guide.keyCode)))
        browsing = false
        router.dispatch(event(KeyEvent.ACTION_DOWN, 0, guide.keyCode)); browsing = true
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 100, guide.keyCode)))
        assertEquals(2, actions.size)
    }

    @Test fun originalHeldChannelSurfContinuesWhenItsUnusedCustomShortActionBecomesIneligible() {
        val clock = Clock(); var browsing = false; var cancelled = 0
        val native = mutableListOf<Int>(); val actions = mutableListOf<RemoteAction>()
        val up = RemoteButton(KeyEvent.KEYCODE_DPAD_UP)
        val config = RemoteConfiguration().withBinding(RemoteScreen.LIVE_TV, RemotePress.ONCE, up, RemoteAction.OPEN_GUIDE)
        val router = RemoteInputRouter({ config }, {
            native += it.action
            if (it.action == KeyEvent.ACTION_DOWN) browsing = true
            true
        }, 500, clock)
        router.register(RemoteScreen.LIVE_TV, { true }, { actions += it; true },
            onNativeCancel = { cancelled++ },
            canHandleAction = { !browsing || it == RemoteAction.BROWSE_NEXT_CHANNEL || it == RemoteAction.BROWSE_PREVIOUS_CHANNEL },
            canRemapButton = { !browsing || it.keyCode != KeyEvent.KEYCODE_DPAD_CENTER })
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 0, up.keyCode)))
        // The original DOWN starts channel surfing. OPEN_GUIDE is no longer allowed in that
        // state, but it is not the action being executed: this is the unchanged held gesture.
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 350, up.keyCode, repeat = 1)))
        assertTrue(browsing)
        assertTrue(router.dispatch(event(KeyEvent.ACTION_DOWN, 700, up.keyCode, repeat = 3)))
        assertTrue(router.dispatch(event(KeyEvent.ACTION_UP, 800, up.keyCode)))
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), native)
        assertTrue(actions.isEmpty())
        assertEquals(0, cancelled)
    }

    @Test fun captureRejectsMixedKeyBurstThenAcceptsCleanPressWithoutRepeatsOrOrphanUp() {
        val clock = Clock(); val added = mutableListOf<RemoteButton>(); val issues = mutableListOf<RemoteCaptureIssue>()
        val capture = RemoteButtonCapture({ added += it }, { fail("Not cancelled") }, 100, clock, { issues += it })
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        capture.dispatch(event(KeyEvent.ACTION_UP, 10))
        // A second key from the same physical remote can arrive after the first key's release.
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 20, KeyEvent.KEYCODE_DPAD_CENTER))
        capture.dispatch(event(KeyEvent.ACTION_UP, 30, KeyEvent.KEYCODE_DPAD_CENTER))
        clock.advance(100)
        assertEquals(listOf(RemoteCaptureIssue.AMBIGUOUS), issues); assertTrue(added.isEmpty())
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 200, KeyEvent.KEYCODE_PROG_YELLOW))
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 230, KeyEvent.KEYCODE_PROG_YELLOW, repeat = 2))
        assertTrue(added.isEmpty())
        capture.dispatch(event(KeyEvent.ACTION_UP, 240, KeyEvent.KEYCODE_PROG_YELLOW))
        clock.advance(200)
        assertEquals(listOf(RemoteButton(KeyEvent.KEYCODE_PROG_YELLOW)), added)
    }

    @Test fun captureCancelTransfersHeldReleaseToDialogAndNeverCallsDisposedState() {
        val clock = Clock(); var cancelled = 0; val config = RemoteConfiguration()
        val router = RemoteInputRouter({ config }, { true }, 500, clock)
        val capture = RemoteButtonCapture({ fail("Cancelled capture cannot add a key") }, { cancelled++ }, 100, clock, drainTo = router)
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 0))
        capture.cancel()
        assertEquals(1, cancelled)
        assertTrue(router.consumeDrained(event(KeyEvent.ACTION_UP, 200)))
        val disposed = RemoteButtonCapture({ fail("Disposed capture") }, { fail("Dispose must be silent") }, 100, clock)
        disposed.dispatch(event(KeyEvent.ACTION_DOWN, 0)); disposed.close(router)
        assertTrue(router.consumeDrained(event(KeyEvent.ACTION_UP, 200)))
        clock.advance(1000)
    }

    @Test fun captureDoesNotEnrollAButtonAlreadyHeldBeforeLearningStarted() {
        val clock = Clock(); val added = mutableListOf<RemoteButton>()
        val capture = RemoteButtonCapture({ added += it }, { }, 100, clock)
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 800, repeat = 5))
        capture.dispatch(event(KeyEvent.ACTION_UP, 900)); clock.advance(1000)
        assertTrue(added.isEmpty())
        capture.dispatch(event(KeyEvent.ACTION_DOWN, 0, KeyEvent.KEYCODE_ENTER))
        capture.dispatch(event(KeyEvent.ACTION_UP, 100, KeyEvent.KEYCODE_ENTER)); clock.advance(1100)
        assertEquals(listOf(RemoteButton(KeyEvent.KEYCODE_DPAD_CENTER)), added)
    }
}
