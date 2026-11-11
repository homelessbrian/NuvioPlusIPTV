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
    fun notifyConsumerReleased() = onConsumerReleased()
    override fun close() = lease.close()
    override fun equals(other: Any?): Boolean =
        snapshot == if (other is GuideBrowsePublication) other.snapshot else other
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString()
}

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
