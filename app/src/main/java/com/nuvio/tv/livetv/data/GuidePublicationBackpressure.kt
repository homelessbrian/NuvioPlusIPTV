package com.nuvio.tv.livetv.data

/**
 * At most one preceding publication awaits a consumer acknowledging its replacement.
 * Deferred demand stays dirty; no additional generation can strand an unleased flow value.
 */
internal class GuidePublicationBackpressure : AutoCloseable {
    private var preceding: AutoCloseable? = null
    private var currentId = 0L
    private var pending = false

    @get:Synchronized
    val dirty: Boolean get() = pending

    @Synchronized fun requestPublication(): Boolean {
        pending = true
        return preceding == null
    }

    @Synchronized fun unchanged() { pending = false }

    @Synchronized fun published(previous: AutoCloseable?, newId: Long) {
        check(preceding == null) { "Previous guide publication is not acknowledged" }
        require(newId > currentId) { "Guide publication IDs must increase" }
        preceding = previous
        currentId = newId
        pending = false
    }

    /** The consumer acquires its replacement lease before acknowledging this ID. */
    @Synchronized fun acknowledge(id: Long): Boolean {
        if (id < currentId) return false
        val retired = preceding
        preceding = null
        retired?.close()
        return pending
    }

    @Synchronized override fun close() {
        val retired = preceding
        preceding = null
        pending = false
        retired?.close()
    }
}
