package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram

/** Lightweight transformed views borrow the same charged underlying publication. */
internal interface GuideBrowsePublicationView {
    val underlyingGuidePrograms: Map<String, List<EpgProgram>>
}

/**
 * The full guide map may include the separately owned now/next overview. [lease] charges
 * only bounded browsing overrides. Publisher and each mounted consumer own a handle.
 */
internal class GuideBrowsePublication(
    private val snapshot: Map<String, List<EpgProgram>>,
    private val lease: GuideBrowseBudget.Lease,
    val publicationId: Long = 0L,
    private val onConsumerReleased: () -> Unit = {}
) : Map<String, List<EpgProgram>> by snapshot, AutoCloseable {
    init { lease.markPublication() }
    fun retain(): GuideBrowseBudget.Lease? = lease.retainPublication()
    fun retainTargetOwnership(): GuideTargetOwnership? = lease.tryRetainPublication()?.let {
        // Pass the original callback value, not a method reference bound to this map.
        DetachedGuideTargetOwnership(it, onConsumerReleased)
    }
    fun notifyConsumerReleased() = onConsumerReleased()
    override fun close() = lease.close()
    override fun equals(other: Any?): Boolean =
        snapshot == if (other is GuideBrowsePublication) other.snapshot else other
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString()
}

private class DetachedGuideTargetOwnership(
    private val lease: GuideBrowseBudget.Lease,
    private val released: () -> Unit
) : GuideTargetOwnership {
    private var open = true
    @Synchronized override fun retain(): GuideTargetOwnership? =
        if (open) lease.tryRetain()?.let { DetachedGuideTargetOwnership(it, released) } else null

    override fun close() {
        val release = synchronized(this) {
            if (!open) false else { open = false; true }
        }
        if (release) { lease.close(); released() }
    }
}

internal fun Map<String, List<EpgProgram>>.retainGuideTargetOwnership(): GuideTargetOwnership? =
    (underlyingBrowsePublication() as? GuideBrowsePublication)?.retainTargetOwnership()

internal fun Map<String, List<EpgProgram>>.retainGuideBrowsePublication(): GuideBrowseBudget.Lease? {
    return (underlyingBrowsePublication() as? GuideBrowsePublication)?.retain()
}

internal fun Map<String, List<EpgProgram>>.guideBrowsePublicationId(): Long =
    (underlyingBrowsePublication() as? GuideBrowsePublication)?.publicationId ?: 0L

internal fun Map<String, List<EpgProgram>>.notifyGuideBrowsePublicationRelease() {
    (underlyingBrowsePublication() as? GuideBrowsePublication)?.notifyConsumerReleased()
}

private fun Map<String, List<EpgProgram>>.underlyingBrowsePublication(): Map<String, List<EpgProgram>> {
    var source = this
    while (source is GuideBrowsePublicationView) {
        val next = source.underlyingGuidePrograms
        if (next === source) return source
        source = next
    }
    return source
}
