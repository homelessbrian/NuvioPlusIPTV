package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.*
import org.junit.Test

class VodBrowsePolicyTest {
    @Test fun `mutation bookends reject preexisting readers and builds until all writers finish`() {
        val cache = VodBrowseCache()
        val rows = VodBrowseCache.Rows(longArrayOf(1), intArrayOf(2))
        val before = cache.revision()
        cache.put("unrelated", rows, before)
        cache.beginMutation()
        assertFalse(cache.readable(before))
        assertNull(cache.get("unrelated"))
        cache.beginMutation()
        val during = cache.revision()
        cache.endMutation(" AND genre LIKE ?")
        assertFalse(cache.readable(during))
        cache.put("stale", rows, during)
        cache.endMutation(" AND fl IS NULL")
        assertFalse(cache.readable(before))
        assertTrue(cache.readable(cache.revision()))
        assertSame(rows, cache.get("unrelated"))
        assertNull(cache.get("stale"))
    }

    @Test fun `browse id budget evicts least recently used query and rejects stale builds`() {
        val cache = VodBrowseCache(5)
        fun rows(n: Int) = VodBrowseCache.Rows(LongArray(n), IntArray(n))
        val revision = cache.revision()
        cache.put("all", rows(2), revision)
        cache.put("genre", rows(2), revision)
        cache.get("all")
        cache.put("next", rows(2), revision)
        assertNull(cache.get("genre"))
        assertNotNull(cache.get("all"))
        cache.invalidate()
        cache.put("old query", rows(2), revision)
        assertNull(cache.get("old query"))
        assertNull(cache.get("all"))
    }

    @Test fun `genre changes retain unrelated ordered representatives`() {
        val cache = VodBrowseCache()
        val rows = VodBrowseCache.Rows(longArrayOf(1), intArrayOf(2))
        cache.put("WHERE kind = ?", rows, cache.revision())
        cache.put("WHERE kind = ? AND genre LIKE ?", rows, cache.revision())
        cache.invalidate(" AND genre LIKE ?")
        assertSame(rows, cache.get("WHERE kind = ?"))
        assertNull(cache.get("WHERE kind = ? AND genre LIKE ?"))
    }


}
