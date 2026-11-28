package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A byte target for one VOD allocator, updated from ExoPlayer's playback thread. */
@UnstableApi
class AdaptiveVodBufferLimit internal constructor(
    private val nowMs: () -> Long,
    private val sampleMemory: (() -> Long) -> MemorySample
) {
    constructor(context: Context, scope: CoroutineScope, nativeAllocator: Boolean) :
        this(SystemClock::elapsedRealtime, AndroidMemorySampler(context, scope, nativeAllocator)::read)

    internal data class MemorySample(
        val maxHeapBytes: Long,
        val usedHeapBytes: Long,
        val availableRamBytes: Long?,
        val lowMemoryThresholdBytes: Long,
        val nativeAllocator: Boolean,
        val ramSampleAllocatorBytes: Long? = null
    )

    private var lastSampleMs: Long? = null
    private var safeBytes: Long = Long.MAX_VALUE
    private var targetBytes: Int? = null
    private var nextGrowthMs = 0L

    internal fun targetBytes(baseBytes: Int, allocatorFootprintBytes: () -> Long): Int {
        val now = nowMs()
        val lastSample = lastSampleMs
        val sampled = lastSample == null || now - lastSample >= SAMPLE_INTERVAL_MS
        if (sampled) {
            val memory = sampleMemory(allocatorFootprintBytes)
            val footprint = allocatorFootprintBytes()
            val reserve = (memory.maxHeapBytes / 5).coerceIn(MIN_HEAP_RESERVE_BYTES, MAX_HEAP_RESERVE_BYTES)
            val heapHeadroom = memory.maxHeapBytes - memory.usedHeapBytes - reserve
            // Native sample buffers normally consume RAM outside the Java heap. If the heap
            // still runs low, stop growing too: Android's direct-buffer fallback can use it.
            // The allocator can reuse its idle blocks. Existing allocations count against total
            // process memory already, so add them back before calculating the target.
            val heapLimit = if (memory.nativeAllocator && heapHeadroom > 0L) Long.MAX_VALUE
                else footprint + heapHeadroom
            val ramLimit = memory.availableRamBytes?.let {
                // Use the allocator size from the RAM sample. It may have grown since then.
                (memory.ramSampleAllocatorBytes ?: footprint) + it -
                    memory.lowMemoryThresholdBytes - SYSTEM_RESERVE_BYTES
            } ?: Long.MAX_VALUE
            safeBytes = minOf(heapLimit, ramLimit).coerceAtLeast(MIN_TARGET_BYTES) / MIB * MIB
            lastSampleMs = now
        }
        val allowedBytes = minOf(safeBytes, baseBytes.toLong()).toInt()
        val previous = targetBytes
        val target = when {
            previous == null -> allowedBytes
            allowedBytes < previous -> {
                nextGrowthMs = now + GROWTH_INTERVAL_MS
                allowedBytes
            }
            sampled && allowedBytes > previous && now >= nextGrowthMs &&
                safeBytes >= previous.toLong() + GROWTH_MARGIN_BYTES -> {
                nextGrowthMs = now + GROWTH_INTERVAL_MS
                minOf(allowedBytes.toLong(), previous.toLong() + GROWTH_STEP_BYTES).toInt()
            }
            else -> previous
        }
        if (previous == null && target < baseBytes) nextGrowthMs = now + GROWTH_INTERVAL_MS
        targetBytes = target
        return target
    }

    internal fun reset() {
        lastSampleMs = null
        safeBytes = Long.MAX_VALUE
        targetBytes = null
        nextGrowthMs = 0L
    }

    private class AndroidMemorySampler(
        context: Context,
        private val scope: CoroutineScope,
        private val nativeAllocator: Boolean
    ) {
        private data class Ram(
            val availableBytes: Long,
            val lowMemoryThresholdBytes: Long,
            val allocatorBytes: Long
        )

        private val manager = context.applicationContext.getSystemService(ActivityManager::class.java)
        private val info = ActivityManager.MemoryInfo()
        private val runtime = Runtime.getRuntime()
        private val refreshing = AtomicBoolean()
        @Volatile private var ram: Ram? = null

        init {
            // Start while the player is being built. No allocator has been created yet.
            refreshRam { 0L }
        }

        fun read(allocatorFootprintBytes: () -> Long): MemorySample {
            val latestRam = ram
            refreshRam(allocatorFootprintBytes)
            return MemorySample(
                maxHeapBytes = runtime.maxMemory(),
                usedHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
                availableRamBytes = latestRam?.availableBytes,
                lowMemoryThresholdBytes = latestRam?.lowMemoryThresholdBytes ?: 0L,
                nativeAllocator = nativeAllocator || NuvioEngineConfig.get().isNativeAllocationEnabled,
                ramSampleAllocatorBytes = latestRam?.allocatorBytes
            )
        }

        private fun refreshRam(allocatorFootprintBytes: () -> Long) {
            if (manager == null || !refreshing.compareAndSet(false, true)) return
            // getMemoryInfo crosses a system-process boundary. Never wait for it on the
            // playback thread, and keep at most one refresh in flight.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    manager.getMemoryInfo(info)
                    if (info.totalMem > 0) ram = Ram(info.availMem, info.threshold, allocatorFootprintBytes())
                }
            }.invokeOnCompletion { refreshing.set(false) }
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val GROWTH_INTERVAL_MS = 4_000L
        const val GROWTH_MARGIN_BYTES = 8 * MIB
        const val GROWTH_STEP_BYTES = 8 * MIB
        const val MIN_TARGET_BYTES = 12 * MIB
        const val MIN_HEAP_RESERVE_BYTES = 24 * MIB
        const val MAX_HEAP_RESERVE_BYTES = 96 * MIB
        const val SYSTEM_RESERVE_BYTES = 32 * MIB
    }
}
