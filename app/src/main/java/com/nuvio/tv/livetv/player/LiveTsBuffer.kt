package com.nuvio.tv.livetv.player

import java.io.IOException
import java.io.InterruptedIOException

/**
 * A single producer and two independent byte cursors. Only the playing reader can slow the
 * provider down, just as it would with a direct HTTP stream. Pausing or releasing that reader
 * lets recording continue. A disk reader that falls behind fails without holding up playback.
 */
internal class LiveTsBuffer(capacity: Int = 2 * 1024 * 1024) {
    private val data = ByteArray(capacity)
    private val monitor = Object()
    private var written = 0L
    private var liveEnd: Long? = null
    private val boundaries = java.util.ArrayDeque<Long>()
    private var recordingHistory = true
    private var reader: Any? = null
    private var consumed = 0L
    private var paused = false
    private var finished = false
    private var stopped = false

    fun attach(owner: Any, position: Long) = synchronized(monitor) {
        checkRunning()
        if (reader != null || position !in earliest()..written) throw IOException("Live position is unavailable")
        reader = owner
        consumed = position
        monitor.notifyAll()
    }

    fun detach(owner: Any) = synchronized(monitor) {
        if (reader === owner) reader = null
        monitor.notifyAll()
    }

    fun setPaused(value: Boolean) = synchronized(monitor) { paused = value; monitor.notifyAll() }

    val liveReaderViable: Boolean get() = synchronized(monitor) {
        !stopped && !finished && liveEnd == null &&
            (if (reader == null) written == 0L else consumed >= earliest())
    }

    fun append(bytes: ByteArray, length: Int) = synchronized(monitor) {
        var offset = 0
        while (offset < length) {
            checkRunning()
            val free = if (reader != null && !paused && liveEnd == null && consumed >= earliest()) {
                data.size - (written - consumed).toInt()
            } else data.size
            if (free == 0) {
                awaitChange()
                continue
            }
            val index = (written % data.size).toInt()
            val n = minOf(length - offset, free, data.size - index)
            bytes.copyInto(data, index, offset, offset + n)
            offset += n
            written += n
            while (boundaries.isNotEmpty() && boundaries.first < earliest()) boundaries.removeFirst()
            monitor.notifyAll()
        }
    }

    fun readLive(owner: Any, position: Long, dst: ByteArray, offset: Int, length: Int): Int =
        synchronized(monitor) {
            while (true) {
                checkRunning()
                if (reader !== owner) throw InterruptedIOException("Live reader closed")
                if (position < earliest()) throw IOException("Live position has left memory")
                if (liveEnd != null && position >= liveEnd!!) throw IOException("Live connection ended")
                val n = copy(position, dst, offset, length, liveEnd ?: written)
                if (n > 0 || length == 0) {
                    consumed = position + n
                    monitor.notifyAll()
                    return n
                }
                if (finished) return -1
                awaitChange()
            }
            @Suppress("UNREACHABLE_CODE") -1
        }

    /** A discontinuous new input must start a new disk segment and a fresh live extractor. */
    fun disconnected() = synchronized(monitor) {
        if (liveEnd == null) liveEnd = written
        if (recordingHistory && boundaries.peekLast() != written) boundaries.addLast(written)
        monitor.notifyAll()
    }

    fun releaseHistory() = synchronized(monitor) { recordingHistory = false; boundaries.clear() }

    fun readHistory(position: Long, dst: ByteArray, lastBoundary: Long): Int = synchronized(monitor) {
        while (true) {
            checkRunning()
            if (position < earliest()) throw IOException("Storage fell behind live playback")
            while (boundaries.isNotEmpty() && boundaries.first <= lastBoundary) boundaries.removeFirst()
            val next = boundaries.peekFirst()
            if (next == position) return DISCONTINUITY
            val n = copy(position, dst, 0, dst.size, next ?: written)
            if (n > 0) return n
            if (finished) return -1
            awaitChange()
        }
        @Suppress("UNREACHABLE_CODE") -1
    }

    fun finish() = synchronized(monitor) { finished = true; monitor.notifyAll() }
    fun stop() = synchronized(monitor) { stopped = true; monitor.notifyAll() }
    private fun earliest() = (written - data.size).coerceAtLeast(0L)
    private fun checkRunning() { if (stopped) throw InterruptedIOException("Live session closed") }
    private fun awaitChange() {
        try { monitor.wait() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Live reader interrupted")
        }
    }

    private fun copy(position: Long, dst: ByteArray, offset: Int, length: Int, end: Long): Int {
        val index = (position % data.size).toInt()
        val n = minOf(end - position, length.toLong(), (data.size - index).toLong()).toInt()
        if (n > 0) data.copyInto(dst, offset, index, index + n)
        return n
    }

    companion object { const val DISCONTINUITY = -2 }
}
