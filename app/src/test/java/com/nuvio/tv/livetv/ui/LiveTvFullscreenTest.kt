package com.nuvio.tv.livetv.ui

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveTvFullscreenTest {
    @Before @After fun clearRequest() {
        LiveTvFullscreen.pending = false
        Snapshot.sendApplyNotifications()
    }

    @Test fun `fullscreen request invalidates drawing without waiting for a lifecycle collector`() {
        var invalidated = false
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { invalidated = true }) {
                assertFalse(LiveTvFullscreen.pending)
            }

            LiveTvFullscreen.request()
            Snapshot.sendApplyNotifications()

            assertTrue(LiveTvFullscreen.pending)
            assertTrue(invalidated)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
