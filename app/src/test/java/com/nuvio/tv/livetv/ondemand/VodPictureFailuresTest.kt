package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.*
import org.junit.Test

class VodPictureFailuresTest {
    @Test fun `repeated access does not extend retry pause and failures are bounded`() {
        var time = 0L
        val cache = VodPictureFailures(2, 30, now = { time })
        cache += "a"; cache += "b"
        time = 10
        assertTrue("a" in cache)
        cache += "c"
        assertFalse("b" in cache)
        assertEquals(2, cache.size)
        time = 29
        assertTrue("a" in cache)
        time = 30
        assertFalse("a" in cache)
        cache.remove("c")
        assertEquals(0, cache.size)
    }

    @Test fun `disabled cache and reversed clocks retry rather than retaining failures forever`() {
        var time = 10L
        val disabled = VodPictureFailures(0, now = { time })
        disabled += "url"
        assertFalse("url" in disabled)
        val cache = VodPictureFailures(2, 30, now = { time })
        cache += "url"
        time = 5
        assertFalse("url" in cache)
    }
}
