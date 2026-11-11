package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.data.GuideTargetOwnership
import com.nuvio.tv.livetv.data.UntrackedGuideTargetOwnership
import java.util.IdentityHashMap
import kotlinx.coroutines.Job

/** Only explicit selections are retained; equal replacement values are distinct targets. */
internal class GuideTargetRegistry<T : Any> : AutoCloseable {
    private val targets = IdentityHashMap<T, GuideTargetOwnership>()
    private val committed = IdentityHashMap<T, Boolean>()
    private var closed = false

    /** Called synchronously before installing a target, while its source frame is owned. */
    @Synchronized fun capture(target: T, active: List<T?>, requiresOwner: Boolean,
                              acquire: () -> GuideTargetOwnership?): Boolean {
        prune(active, includeCommitted = true)
        if (closed) return false
        if (targets.containsKey(target)) return true
        val ownership = acquire()
        if (ownership == null && requiresOwner) return false
        targets[target] = ownership ?: UntrackedGuideTargetOwnership
        return true
    }

    @Synchronized fun fork(target: T): GuideTargetOwnership? = targets[target]?.retain()

    /** SideEffect observes the final state, including menu-to-info transfers in one event. */
    @Synchronized fun commit(active: List<T?>) {
        committed.clear()
        active.filterNotNull().forEach { committed[it] = true }
        prune(active, includeCommitted = false)
    }

    private fun prune(active: List<T?>, includeCommitted: Boolean) {
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (active.none { it === entry.key } && (!includeCommitted || !committed.containsKey(entry.key))) {
                iterator.remove()
                entry.value.close()
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        targets.values.forEach { it.close() }
        targets.clear()
        committed.clear()
    }

}

/** Completion also runs when a cancelled scope prevents the coroutine body from starting. */
internal fun Job.releaseGuideOwnershipOnCompletion(ownership: AutoCloseable?): Job = apply {
    if (ownership != null) invokeOnCompletion { ownership.close() }
}

internal inline fun <T> withGuideOwnership(ownership: AutoCloseable?, block: () -> T): T =
    try { block() } finally { ownership?.close() }
