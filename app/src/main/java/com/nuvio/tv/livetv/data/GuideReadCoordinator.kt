package com.nuvio.tv.livetv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure cancellation token: a database adapter installs its CancellationSignal callback. */
internal class GuideReadCancellation {
    private val lock = Any()
    private var cancelled = false
    private var handler: (() -> Unit)? = null

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    /** At most one SQL operation is active per request; finished readers clear the handler. */
    fun replaceCancellationHandler(callback: (() -> Unit)?) {
        val runNow = synchronized(lock) {
            handler = if (cancelled) null else callback
            cancelled && callback != null
        }
        if (runNow) callback!!()
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("Guide demand was superseded")
    }

    fun cancel() {
        val callback = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            handler.also { handler = null }
        }
        callback?.invoke()
    }
}

/**
 * One active read and one latest pending demand. Cancellation interrupts SQL, but the next
 * request still waits for the previous reader to return: blocking readers cannot overlap.
 */
internal class GuideReadCoordinator<T>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val work: suspend (T, GuideReadCancellation) -> Unit,
    private val onFailure: (Exception) -> Unit = {}
) : AutoCloseable {
    data class Stats(val active: Int, val pending: Int, val highWaterActive: Int,
                     val highWaterPending: Int, val accepted: Long, val superseded: Long)
    private data class Demand<T>(val value: T, val priority: Int)
    private data class Active<T>(val demand: Demand<T>, val cancellation: GuideReadCancellation, val job: Job)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pending: Demand<T>? = null
    private var active: Active<T>? = null
    private var closed = false
    private var highActive = 0
    private var highPending = 0
    private var accepted = 0L
    private var superseded = 0L

    private val worker = scope.launch(dispatcher) {
        for (ignored in wake) {
            while (true) {
                val request = synchronized(lock) { pending.also { pending = null } } ?: break
                val cancellation = GuideReadCancellation()
                val job = Job(currentCoroutineContext()[Job])
                val running = Active(request, cancellation, job)
                val alreadySuperseded = synchronized(lock) {
                    active = running
                    highActive = maxOf(highActive, 1)
                    closed || (pending?.priority ?: Int.MIN_VALUE) >= request.priority
                }
                if (alreadySuperseded) { cancellation.cancel(); job.cancel() }
                try {
                    withContext(job) {
                        cancellation.throwIfCancelled()
                        work(request.value, cancellation)
                    }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (failure: Exception) {
                    // SQLite reports OperationCanceledException rather than a coroutine type.
                    if (!cancellation.isCancelled) onFailure(failure)
                } finally {
                    cancellation.replaceCancellationHandler(null)
                    job.cancel()
                    synchronized(lock) { if (active === running) active = null }
                }
            }
        }
    }

    /** A lower-priority warm-up cannot replace visible/focused demand already in progress. */
    fun submit(value: T, priority: Int = 1): Boolean {
        val old = synchronized(lock) {
            if (closed || (pending?.priority ?: Int.MIN_VALUE) > priority ||
                (active?.demand?.priority ?: Int.MIN_VALUE) > priority) return false
            if (pending != null) superseded++
            pending = Demand(value, priority)
            accepted++
            highPending = maxOf(highPending, 1)
            active?.takeIf { priority >= it.demand.priority }
        }
        old?.cancellation?.cancel()
        old?.job?.cancel()
        wake.trySend(Unit)
        return true
    }

    fun cancel() {
        val old = synchronized(lock) { pending = null; active }
        old?.cancellation?.cancel()
        old?.job?.cancel()
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(if (active == null) 0 else 1, if (pending == null) 0 else 1,
            highActive, highPending, accepted, superseded)
    }

    override fun close() {
        synchronized(lock) { closed = true }
        cancel()
        wake.close()
        worker.cancel()
    }
}
