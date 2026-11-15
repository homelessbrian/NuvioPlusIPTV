package com.nuvio.tv.core.image

import android.graphics.Bitmap
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import coil3.asImage
import coil3.memory.MemoryCache
import org.json.JSONObject
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** Headless Android component experiment; does not launch Nuvio or touch its data. */
object CacheRamBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.single()
        require(mode == "baseline" || mode == "candidate")
        val output = PrintStream(FileOutputStream(FileDescriptor.out), true)
        output.println("cache-component-started " + mode)
        val started = SystemClock.elapsedRealtime()
        val cache = MemoryCache.Builder().maxSizeBytes(64L * 1024 * 1024).build()
        val budget = PlaybackImageCacheBudget()
        budget.attach(cache)
        repeat(64) { index ->
            cache[MemoryCache.Key("poster-$index")] = MemoryCache.Value(
                Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                    // A decoded poster writes its pixels; touch every page instead of measuring
                    // potentially lazy, shared zero pages from an empty bitmap allocation.
                    eraseColor(0xff223344.toInt() + index)
                }.asImage()
            )
        }
        // Model the player owning its current artwork independently of the cache.
        val currentArtwork = (checkNotNull(cache[MemoryCache.Key("poster-63")]).image as coil3.BitmapImage).bitmap

        fun sample(stage: String, forcedGc: Boolean) {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val runtime = Runtime.getRuntime()
            output.println(JSONObject().apply {
                put("mode", mode)
                put("stage", stage)
                put("pid", Process.myPid())
                put("elapsedMs", SystemClock.elapsedRealtime() - started)
                put("strongCacheBytes", cache.size)
                put("cacheMaxBytes", cache.maxSize)
                put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                put("totalPssKb", info.totalPss)
                put("nativePssKb", info.nativePss)
                put("dalvikPssKb", info.dalvikPss)
                put("totalPrivateDirtyKb", info.totalPrivateDirty)
                put("currentArtworkAllocationBytes", currentArtwork.allocationByteCount)
                put("currentArtworkRecycled", currentArtwork.isRecycled)
                put("forcedGc", forcedGc)
            }.toString())
        }

        Thread.sleep(3_000)
        sample("full-cache", false)
        if (mode == "candidate") budget.setOnDemandPlaying(true)
        Thread.sleep(3_000)
        sample("natural-settle", false)
        // Diagnostic stage only. Production does not force GC or recycle bitmaps.
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(1_000)
        }
        sample("controlled-gc", true)
        if (mode == "candidate") budget.setOnDemandPlaying(false)
        sample("restored-capacity", true)
        check(!currentArtwork.isRecycled)
        // app_process may start framework worker threads; this experiment owns the whole process.
        kotlin.system.exitProcess(0)
    }
}
