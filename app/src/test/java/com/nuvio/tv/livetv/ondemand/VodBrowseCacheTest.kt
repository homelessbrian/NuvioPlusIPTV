package com.nuvio.tv.livetv.ondemand

import org.junit.Assert.*
import org.junit.Test

class VodBrowseCacheTest {
    @Test fun `large All prefix remains cached alongside several small category queries`() {
        val cache = VodBrowseCache()
        val revision = cache.revision()
        val all = VodBrowseCache.Rows(LongArray(VodBrowseCache.MAX_ENTRY_ROWS), IntArray(VodBrowseCache.MAX_ENTRY_ROWS), hasMore = true)
        cache.put("all", all, revision)
        repeat(3) { group ->
            cache.put("category-$group", VodBrowseCache.Rows(LongArray(833), IntArray(833)), revision)
        }
        assertSame(all, cache.get("all"))
        assertTrue(all.hasMore)
        repeat(3) { assertNotNull(cache.get("category-$it")) }
        assertTrue(all.ids.size + 3 * 833 <= VodBrowseCache.MAX_ROWS)
    }

    @Test fun `a single entry cannot consume the aggregate row budget`() {
        val cache = VodBrowseCache()
        val revision = cache.revision()
        val small = VodBrowseCache.Rows(LongArray(833), IntArray(833))
        cache.put("category", small, revision)
        cache.put("oversized-all", VodBrowseCache.Rows(LongArray(VodBrowseCache.MAX_ROWS), IntArray(VodBrowseCache.MAX_ROWS)), revision)
        assertNull(cache.get("oversized-all"))
        assertSame(small, cache.get("category"))
    }
}
