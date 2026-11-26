package com.nuvio.tv.livetv.player

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LiveTsBufferTest {
    @Test fun `an early pause before Media3 opens can resume from byte zero`() {
        val pipe = LiveTsBuffer(4)
        pipe.setPaused(true)
        assertTrue(pipe.liveReaderViable)
        pipe.setPaused(false)
        val owner = Any()
        pipe.attach(owner, 0)
        pipe.append(byteArrayOf(17), 1)
        val out = ByteArray(1)
        assertEquals(1, pipe.readLive(owner, 0, out, 0, 1))
        assertEquals(17, out[0].toInt())
    }

    @Test fun `a burst backpressures the provider while normal playback keeps every byte`() {
        val pipe = LiveTsBuffer(4)
        val reader = Any()
        pipe.attach(reader, 0)
        val worker = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val sent = worker.submit {
                pipe.append(byteArrayOf(1, 2, 3, 4), 4)
                started.countDown()
                pipe.append(byteArrayOf(5, 6, 7, 8), 4)
                pipe.finish()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(sent.isDone)
            val out = ByteArray(8)
            assertEquals(4, pipe.readLive(reader, 0, out, 0, 8))
            sent.get(2, TimeUnit.SECONDS)
            assertEquals(4, pipe.readLive(reader, 4, out, 4, 4))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out)
            assertEquals(-1, pipe.readLive(reader, 8, out, 0, 1))
            assertThrows(IOException::class.java) { pipe.readHistory(0, out, -1) }
        } finally { pipe.stop(); worker.shutdownNow() }
    }

    @Test fun `pausing releases upstream and storage keeps exact history across wrap`() {
        val pipe = LiveTsBuffer(4)
        val reader = Any()
        pipe.attach(reader, 0)
        val out = ByteArray(4)
        pipe.append(byteArrayOf(1, 2, 3, 4), 4)
        assertEquals(4, pipe.readHistory(0, out, -1))
        pipe.setPaused(true)
        assertTrue(pipe.liveReaderViable)
        pipe.append(byteArrayOf(5, 6, 7, 8), 4)
        assertEquals(4, pipe.readHistory(4, out, -1))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), out)
        assertFalse(pipe.liveReaderViable)
        pipe.setPaused(false)
        assertThrows(IOException::class.java) { pipe.readLive(reader, 0, out, 0, 1) }
        pipe.detach(reader)
        pipe.append(byteArrayOf(9), 1)
        assertEquals(1, pipe.readHistory(8, out, -1))
    }

    @Test fun `new connection ends the live extractor and marks the exact disk boundary`() {
        val pipe = LiveTsBuffer(12)
        val reader = Any()
        pipe.attach(reader, 0)
        pipe.append(byteArrayOf(1, 2, 3), 3)
        pipe.disconnected()
        pipe.disconnected()
        pipe.append(byteArrayOf(4, 5), 2)
        val bytes = ByteArray(12)
        assertEquals(3, pipe.readLive(reader, 0, bytes, 0, bytes.size))
        assertThrows(IOException::class.java) { pipe.readLive(reader, 3, bytes, 0, 1) }
        assertEquals(3, pipe.readHistory(0, bytes, -1))
        assertEquals(LiveTsBuffer.DISCONTINUITY, pipe.readHistory(3, bytes, -1))
        assertEquals(2, pipe.readHistory(3, bytes, 3))
        assertEquals(4, bytes[0].toInt())
    }

    @Test fun `stopping wakes waiting player and disk readers`() {
        val pipe = LiveTsBuffer(4)
        val reader = Any()
        pipe.attach(reader, 0)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val ready = CountDownLatch(2)
            val waiting = listOf(
                workers.submit { ready.countDown(); assertThrows(IOException::class.java) { pipe.readLive(reader, 0, ByteArray(1), 0, 1) } },
                workers.submit { ready.countDown(); assertThrows(IOException::class.java) { pipe.readHistory(0, ByteArray(1), -1) } },
            )
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            pipe.stop()
            waiting.forEach { it.get(2, TimeUnit.SECONDS) }
        } finally { pipe.stop(); workers.shutdownNow() }
    }
}
