package com.nuvio.tv.livetv.player

/** IPTV download-ahead shares memory with guide models, artwork and native codec surfaces. */
internal object LiveTvBufferPolicy {
    data class Budget(val targetBytes: Int, val prioritizeTime: Boolean)

    fun forDevice(effectiveHeapMb: Long, physicalRamBytes: Long): Budget {
        val heapMb = effectiveHeapMb.takeIf { it > 0L } ?: 512L
        val bytes = ((heapMb shl 20) * 3 / 10).coerceIn(32L shl 20, 160L shl 20).toInt()
        // A 2 GiB box may advertise the same large Java heap as a much larger device.
        // Keep its existing cushion budget, but do not exceed it just to reach a time minimum.
        val constrainedDevice = physicalRamBytes in 1..(2L shl 30)
        return Budget(bytes, heapMb >= 512L && !constrainedDevice)
    }
}
