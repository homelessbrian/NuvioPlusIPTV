package com.nuvio.tv.remote

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration

fun interface RemoteInputScheduler {
    fun after(delayMillis: Long, task: () -> Unit): AutoCloseable
}

private class MainRemoteInputScheduler : RemoteInputScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun after(delayMillis: Long, task: () -> Unit): AutoCloseable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMillis)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }
}

/**
 * A per-Activity, main-thread dispatcher. Unchanged input goes straight to the host.
 * Only explicitly customized gestures are buffered. Their other gesture retains native behavior.
 */
class RemoteInputRouter(
    private val configuration: () -> RemoteConfiguration,
    private val replay: (KeyEvent) -> Boolean,
    private val longPressTimeoutMillis: Long = ViewConfiguration.getLongPressTimeout().toLong(),
    private val scheduler: RemoteInputScheduler = MainRemoteInputScheduler()
) : AutoCloseable {
    private class Route(
        val screen: RemoteScreen,
        val active: () -> Boolean,
        val action: (RemoteAction) -> Boolean,
        val defaults: () -> List<RemoteDefaultBinding>,
        val nativeCancel: () -> Unit,
        val canHandleAction: (RemoteAction) -> Boolean,
        val canRemapButton: (RemoteButton) -> Boolean
    )
    private data class PhysicalKey(val device: Int, val code: Int, val scan: Int, val downAt: Long) {
        companion object { fun of(event: KeyEvent) = PhysicalKey(event.deviceId, event.keyCode, event.scanCode, event.downTime) }
    }
    private class Pending(
        val physical: PhysicalKey,
        val button: RemoteButton,
        val route: Route,
        val configuration: RemoteConfiguration,
        val down: KeyEvent,
        val once: RemoteAction?,
        val hold: RemoteAction?,
        val defaultHold: RemoteAction?,
        val thresholdMillis: Long
    ) {
        var longReached = false
        var nativeHolding = false
        var timer: AutoCloseable? = null
    }

    private val routes = mutableListOf<Route>()
    private val draining = linkedSetOf<PhysicalKey>()
    private var pending: Pending? = null
    private var suspensions = 0
    private var replaying = false
    private var closed = false

    fun register(
        screen: RemoteScreen,
        active: () -> Boolean,
        onAction: (RemoteAction) -> Boolean,
        defaults: () -> List<RemoteDefaultBinding> = { RemoteCatalog.defaults(screen) },
        onNativeCancel: () -> Unit = { },
        canHandleAction: (RemoteAction) -> Boolean = { true },
        canRemapButton: (RemoteButton) -> Boolean = { true }
    ): AutoCloseable {
        if (closed) return AutoCloseable { }
        val route = Route(screen, active, onAction, defaults, onNativeCancel, canHandleAction, canRemapButton)
        routes += route
        return AutoCloseable {
            routes.remove(route)
            if (pending?.route === route) cancelPending()
        }
    }

    fun hasActiveHost(screen: RemoteScreen): Boolean = activeRoute()?.screen == screen

    fun defaultsFor(screen: RemoteScreen, fallback: List<RemoteDefaultBinding> = RemoteCatalog.defaults(screen)): List<RemoteDefaultBinding> {
        val route = activeRoute()?.takeIf { it.screen == screen } ?: return fallback
        return try { route.defaults().toList() } catch (_: Exception) { fallback }
    }

    fun suspendRemapping(): AutoCloseable {
        cancelPending()
        suspensions++
        var released = false
        return AutoCloseable { if (!released) { released = true; suspensions = (suspensions - 1).coerceAtLeast(0) } }
    }

    fun dispatch(event: KeyEvent): Boolean {
        if (closed || replaying || event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (pending == null && draining.isEmpty()) {
            if (suspensions > 0 || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || configuration().bindings.isEmpty()) return false
        }
        val physical = PhysicalKey.of(event)
        if (consumeDrained(event)) return true
        var current = pending
        if (current != null && !valid(current)) { cancelPending(); current = null }
        if (current != null) {
            if (physical != current.physical) {
                // Some remotes emit two keys for one press. Don't fire two customized actions.
                cancelPending()
                if (event.action == KeyEvent.ACTION_DOWN) drain(physical)
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                if (!event.isCanceled) {
                    if (!current.longReached && event.eventTime - current.down.downTime >= current.thresholdMillis) reachLong(current, event)
                    if (pending === current && valid(current)) {
                        if (current.nativeHolding) native(event)
                        else if (!current.longReached) {
                            if (current.once != null) perform(current, current.once)
                            else { native(current.down); native(event) }
                        }
                    }
                } else if (current.nativeHolding) cancelNative(current)
                finish(current)
                return true
            }
            val nativeRepeat = current.hold == null && current.defaultHold == null && event.repeatCount > 0
            if (!current.longReached && (nativeRepeat || current.hold != null && event.isLongPress || event.eventTime - current.down.downTime >= current.thresholdMillis)) reachLong(current, event)
            else if (current.nativeHolding) native(event)
            return true
        }
        if (physical in draining) {
            if (event.action == KeyEvent.ACTION_UP) draining.remove(physical)
            return true
        }
        if (suspensions > 0 || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val route = activeRoute() ?: return false
        val button = RemoteButton.from(event) ?: return false
        if (RemoteCatalog.isProtected(route.screen, button) || !canRemap(route, button)) return false
        val config = configuration()
        val once = config.bindings.firstOrNull { it.screen == route.screen && it.press == RemotePress.ONCE && it.button.canonical() == button }?.action?.takeIf { canHandle(route, it) }
        val hold = config.bindings.firstOrNull { it.screen == route.screen && it.press == RemotePress.HOLD && it.button.canonical() == button }?.action?.takeIf { canHandle(route, it) }
        if (once == null && hold == null) return false
        val nativeHold = if (hold == null) try { route.defaults().firstOrNull { it.button.canonical() == button && it.press == RemotePress.HOLD } }
            catch (_: Exception) { null } else null
        val threshold = if (hold == null && nativeHold != null && nativeHold.holdMillis > 0)
            nativeHold.holdMillis.coerceIn(1, 5000) else longPressTimeoutMillis
        val semanticHold = nativeHold?.takeIf { !it.repeatable && canHandle(route, it.action) }?.action
        val next = Pending(physical, button, route, config, KeyEvent(event),
            once, hold, semanticHold, threshold)
        pending = next
        next.timer = scheduler.after(threshold) { if (pending === next && valid(next)) reachLong(next) else if (pending === next) cancelPending() }
        return true
    }

    /** Cancels on navigation, lifecycle loss, editing, or configuration replacement; trailing releases are consumed. */
    fun cancelPending() {
        val current = pending ?: return
        drain(current.physical)
        pending = null
        current.timer?.close()
        cancelNative(current)
    }

    /** Also usable by a dialog window: a release abandoned by learning must not activate its focused row. */
    fun consumeDrained(event: KeyEvent): Boolean {
        val physical = PhysicalKey.of(event)
        if (physical !in draining) return false
        if (event.action == KeyEvent.ACTION_UP) draining.remove(physical)
        return true
    }

    internal fun blockUntilRelease(device: Int, code: Int, scan: Int, downAt: Long) = drain(PhysicalKey(device, code, scan, downAt))

    override fun close() { cancelPending(); routes.clear(); closed = true }

    private fun reachLong(current: Pending, trigger: KeyEvent? = null) {
        if (pending !== current || current.longReached || !valid(current)) return
        current.longReached = true
        current.timer?.close()
        if (current.hold != null) perform(current, current.hold)
        else if (current.defaultHold != null) perform(current, current.defaultHold)
        else {
            // Re-enter the unchanged host handler with a complete native down/long sequence.
            // Once native holding begins, repeats and the release follow that same host route.
            current.nativeHolding = true
            native(current.down)
            if (pending === current && valid(current)) {
                if (trigger != null && trigger.action == KeyEvent.ACTION_DOWN && (trigger.repeatCount > 0 || trigger.isLongPress)) native(trigger)
                else {
                    val at = trigger?.eventTime ?: maxOf(SystemClock.uptimeMillis(), current.down.eventTime + current.thresholdMillis)
                    val repeated = KeyEvent(current.down.downTime, at, KeyEvent.ACTION_DOWN, current.down.keyCode, 1,
                        current.down.metaState, current.down.deviceId, current.down.scanCode,
                        current.down.flags or KeyEvent.FLAG_LONG_PRESS, current.down.source)
                    native(repeated)
                }
            }
        }
    }

    private fun perform(current: Pending, action: RemoteAction) {
        if (!valid(current) || action == RemoteAction.NONE) return
        try { current.route.action(action) }
        catch (_: Exception) { Log.w("RemoteControls", "Remote action unavailable: ${current.route.screen.name}/${action.name}") }
    }

    private fun native(event: KeyEvent) {
        replaying = true
        try { replay(event) } finally { replaying = false }
    }

    private fun finish(current: Pending) {
        if (pending !== current) return
        pending = null
        current.timer?.close()
    }

    private fun valid(current: Pending): Boolean = !closed && suspensions == 0 &&
        routes.contains(current.route) && activeRoute() === current.route && configuration() == current.configuration &&
        canRemap(current.route, current.button) &&
        (current.once == null || canHandle(current.route, current.once)) &&
        (current.hold == null || canHandle(current.route, current.hold))

    private fun cancelNative(current: Pending) {
        if (!current.nativeHolding) return
        current.nativeHolding = false
        try { current.route.nativeCancel() }
        catch (_: Exception) { Log.w("RemoteControls", "Native remote gesture cleanup failed: ${current.route.screen.name}") }
    }

    private fun canHandle(route: Route, action: RemoteAction): Boolean = try { route.canHandleAction(action) } catch (_: Exception) { false }
    private fun canRemap(route: Route, button: RemoteButton): Boolean = try { route.canRemapButton(button) } catch (_: Exception) { false }

    private fun activeRoute(): Route? {
        for (index in routes.indices.reversed()) {
            val route = routes[index]
            if (try { route.active() } catch (_: Exception) { false }) return route
        }
        return null
    }

    private fun drain(key: PhysicalKey) { if (draining.size >= 16) draining.remove(draining.first()); draining += key }
}

/** One-key discovery; completes only when every key in the captured physical sequence has been released. */
class RemoteButtonCapture(
    private val onCaptured: (RemoteButton) -> Unit,
    private val onCancelled: () -> Unit,
    private val settleMillis: Long = 100,
    private val scheduler: RemoteInputScheduler = MainRemoteInputScheduler(),
    private val onIssue: (RemoteCaptureIssue) -> Unit = { },
    private val drainTo: RemoteInputRouter? = null
) : AutoCloseable {
    private data class Key(val device: Int, val code: Int, val scan: Int, val downAt: Long)
    private val down = mutableSetOf<Key>()
    private var selected: RemoteButton? = null
    private var cancel = false
    private var finished = false
    private var settled: AutoCloseable? = null
    private var firstKey: Key? = null
    private var ambiguous = false
    private var unsupported = false

    fun dispatch(event: KeyEvent): Boolean {
        if (finished) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val key = Key(event.deviceId, event.keyCode, event.scanCode, event.downTime)
        if (event.action == KeyEvent.ACTION_DOWN) {
            settled?.close(); settled = null
            if (event.repeatCount > 0 && firstKey == null) { down += key; return true }
            if (event.repeatCount == 0) down += key
            if (firstKey == null) firstKey = key else if (firstKey != key) ambiguous = true
            if (RemoteCatalog.isBack(event.keyCode)) cancel = true
            else if (selected == null) {
                selected = RemoteButton.from(event)
                if (selected == null) unsupported = true
            }
        } else {
            down -= key
            if (event.isCanceled) cancel = true
            if (cancel && RemoteCatalog.isBack(event.keyCode) && drainTo != null) { cancel(drainTo); return true }
            if (down.isEmpty() && (selected != null || cancel || unsupported)) {
                settled?.close()
                settled = scheduler.after(settleMillis) {
                    if (!finished && down.isEmpty()) {
                        when {
                            cancel -> { finished = true; onCancelled() }
                            ambiguous || unsupported -> {
                                val issue = if (ambiguous) RemoteCaptureIssue.AMBIGUOUS else RemoteCaptureIssue.UNSUPPORTED
                                selected = null; firstKey = null; ambiguous = false; unsupported = false
                                onIssue(issue)
                            }
                            else -> { finished = true; selected?.let(onCaptured) }
                        }
                    }
                }
            }
        }
        return true
    }

    fun cancel() {
        if (finished) return
        if (drainTo != null) { cancel(drainTo); return }
        cancel = true
        if (down.isEmpty()) { finished = true; settled?.close(); onCancelled() }
    }

    /** Force cancellation is safe when releases can be handed to the Activity/dialog drain. */
    fun cancel(router: RemoteInputRouter) {
        if (finished) return
        close(router)
        onCancelled()
    }

    override fun close() = close(drainTo)

    /** Silent disposal. The Activity/dialog can consume any physical releases that arrive later. */
    fun close(router: RemoteInputRouter?) {
        settled?.close()
        down.forEach { router?.blockUntilRelease(it.device, it.code, it.scan, it.downAt) }
        finished = true
        down.clear()
    }
}
