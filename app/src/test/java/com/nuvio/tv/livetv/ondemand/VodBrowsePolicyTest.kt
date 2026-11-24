package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.*
import org.junit.Test

class VodBrowsePolicyTest {
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
