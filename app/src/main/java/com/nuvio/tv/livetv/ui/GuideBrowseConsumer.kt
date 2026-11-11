package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.nuvio.tv.livetv.data.retainGuideBrowsePublication
import com.nuvio.tv.livetv.data.notifyGuideBrowsePublicationRelease
import com.nuvio.tv.livetv.data.retainGuideTargetOwnership
import com.nuvio.tv.livetv.data.GuideTargetOwnership
import com.nuvio.tv.livetv.data.UntrackedGuideTargetOwnership
import com.nuvio.tv.livetv.model.EpgProgram

/** Borrow the event's already-owned frame before any asynchronous projection read. */
internal fun Map<String, List<EpgProgram>>.retainGuideActionOwnership(program: EpgProgram?): GuideTargetOwnership? {
    retainGuideTargetOwnership()?.let { return it }
    if (program?.isDisplayProjection != true) return UntrackedGuideTargetOwnership
    com.nuvio.tv.livetv.record.LiveTvNotice.show("Programme details are unavailable. Please try again.")
    return null
}

/** Keep the preceding frame's display payload charged until composition replaces it. */
@Composable
internal fun RetainGuideBrowsePrograms(programs: Map<String, List<EpgProgram>>) {
    DisposableEffect(programs) {
        val lease = programs.retainGuideBrowsePublication()
        onDispose { lease?.close(); programs.notifyGuideBrowsePublicationRelease() }
    }
}

/** A flow projection owns the source payload while its latest derived value is mounted. */
internal class GuideProgramOwnership(private val acquired: (Map<String, List<EpgProgram>>) -> Unit = {}) : AutoCloseable {
    private var lease: AutoCloseable? = null
    private var source: Map<String, List<EpgProgram>>? = null
    @Synchronized fun update(programs: Map<String, List<EpgProgram>>) {
        val next = programs.retainGuideBrowsePublication()
        val previous = lease
        val previousSource = source
        lease = next
        source = programs
        acquired(programs)
        previous?.close()
        previousSource?.notifyGuideBrowsePublicationRelease()
    }
    @Synchronized override fun close() {
        lease?.close(); lease = null
        source?.notifyGuideBrowsePublicationRelease(); source = null
    }
}
