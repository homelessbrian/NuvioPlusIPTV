package com.nuvio.tv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideWindowLeaseTest {
    @Test fun `settings continuation after overlay release cannot pin a closed viewport`() {
        val lease = GuideWindowLease()
        val pendingSettings = lease.begin()
        lease.clear()
        assertFalse(lease.select(pendingSettings, "closed", GuideRangeCache.Window(0, 10)))
        assertNull(lease.current())
        assertFalse(lease.owns(pendingSettings))
    }

    @Test fun `late earlier settings or query completion cannot replace the current selection`() {
        val lease = GuideWindowLease()
        val old = lease.begin()
        val latest = lease.begin()
        assertTrue(lease.select(latest, "current", GuideRangeCache.Window(20, 30)))
        assertFalse(lease.select(old, "old", GuideRangeCache.Window(0, 10)))
        assertEquals("current", lease.current()?.channel)
        assertTrue(lease.owns(latest))
        assertFalse(lease.owns(old))
    }
}
