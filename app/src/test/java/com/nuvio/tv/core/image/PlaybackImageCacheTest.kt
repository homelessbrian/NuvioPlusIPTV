package com.nuvio.tv.core.image

import android.graphics.Bitmap
import coil3.asImage
import coil3.memory.MemoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PlaybackImageCacheTest {
    private fun cache(mebibytes: Long) = MemoryCache.Builder()
        .maxSizeBytes(mebibytes * MIB)
        .weakReferencesEnabled(false)
        .build()

    @Test fun `native playback trims oldest cached bitmaps but keeps recent artwork`() {
        val cache = cache(64)
        repeat(32) { index ->
            cache[MemoryCache.Key("poster-$index")] = MemoryCache.Value(
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).asImage()
            )
        }
        assertEquals(32 * MIB, cache.size)
        val budget = PlaybackImageCacheBudget()
        budget.attach(cache)

        budget.setOnDemandPlaying(true)

        assertEquals(16 * MIB, cache.maxSize)
        assertEquals(16 * MIB, cache.size)
        assertNull(cache[MemoryCache.Key("poster-0")])
        assertNotNull(cache[MemoryCache.Key("poster-31")])
        budget.setOnDemandPlaying(false)
        assertEquals(64 * MIB, cache.maxSize)
        // Restoring capacity does not reload discarded artwork behind the movie.
        assertEquals(16 * MIB, cache.size)
    }

    @Test fun `repeated playback notifications restore the original browsing budget`() {
        val cache = cache(48)
        val budget = PlaybackImageCacheBudget()
        budget.attach(cache)
        budget.setOnDemandPlaying(true)
        budget.setOnDemandPlaying(true)
        budget.attach(cache)
        budget.setOnDemandPlaying(false)
        budget.setOnDemandPlaying(false)

        assertEquals(48 * MIB, cache.maxSize)
    }

    @Test fun `cache created during playback starts with the playback budget`() {
        val budget = PlaybackImageCacheBudget()
        budget.setOnDemandPlaying(true)
        val cache = cache(64)

        budget.attach(cache)

        assertEquals(16 * MIB, cache.maxSize)
        budget.setOnDemandPlaying(false)
        assertEquals(64 * MIB, cache.maxSize)
    }

    @Test fun `playback does not grow an already smaller cache`() {
        val cache = cache(8)
        val budget = PlaybackImageCacheBudget()
        budget.attach(cache)

        budget.setOnDemandPlaying(true)
        assertEquals(8 * MIB, cache.maxSize)
        budget.setOnDemandPlaying(false)
        assertEquals(8 * MIB, cache.maxSize)
    }

    @Test fun `leaving playback preserves a limit changed by another memory policy`() {
        val cache = cache(64)
        val budget = PlaybackImageCacheBudget()
        budget.attach(cache)
        budget.setOnDemandPlaying(true)
        cache.maxSize = 4 * MIB

        budget.setOnDemandPlaying(false)

        assertEquals(4 * MIB, cache.maxSize)
    }

    @Test fun `replacing image loader restores the old cache and limits the new one`() {
        val first = cache(64)
        val second = cache(32)
        val budget = PlaybackImageCacheBudget()
        budget.attach(first)
        budget.setOnDemandPlaying(true)

        budget.attach(second)

        assertEquals(64 * MIB, first.maxSize)
        assertEquals(16 * MIB, second.maxSize)
        budget.setOnDemandPlaying(false)
        assertEquals(32 * MIB, second.maxSize)
    }

    private companion object { const val MIB = 1024L * 1024 }
}
