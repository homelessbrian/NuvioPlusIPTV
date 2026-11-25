package com.nuvio.tv.livetv.player

import android.content.Context
import android.net.Uri
import io.mockk.*
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveTvTimeshiftSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var recorder: LiveTvTimeshift
    private val connections = ConcurrentHashMap<String, Connection>()
    private val streams = ArrayList<GateStream>()
    private val jobs = ArrayList<Job>()

    @Before fun createRecorder() {
        val cache = temporary.newFolder("cache")
        recorder = LiveTvTimeshift(mockk<Context> { every { cacheDir } returns cache })
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers {
            val file = firstArg<File>()
            mockk<Uri>(relaxed = true) { every { path } returns file.absolutePath }
        }
        // Keep the actual recorder and its IO coroutine; only the provider boundary is local.
        mockkObject(LiveHttp)
        every { LiveHttp.open(any(), any(), any(), any()) } answers {
            connections[firstArg<String>()] ?: error("Unexpected provider request")
        }
    }

    @After fun closeRecorder() {
        if (::recorder.isInitialized) recorder.stop()
        streams.forEach { it.release.countDown(); it.finish.countDown() }
        if (::recorder.isInitialized) {
            val scope = field(recorder, "scope") as CoroutineScope
            scope.cancel()
            runBlocking { withTimeout(10_000) { scope.coroutineContext[Job]?.join() } }
        }
        unmockkObject(LiveHttp)
        unmockkStatic(Uri::class)
    }

    @Test fun `a blocked TS read returning after stop cannot restore cleared counters`() {
        val stream = gate(late = packet(256))
        start("old", stream)
        stream.awaitBlocked()
        assertEquals(2_000L, recorder.savedMs)
        recorder.stop()
        stream.release.countDown()
        stream.awaitOutcome()
        stream.finish.countDown()
        join(jobs.first())

        assertNull(recorder.session)
        assertFalse(recorder.isRecording)
        assertEquals("A canceled read must not restore stopped-session progress", 0L, recorder.savedMs)
    }

    @Test fun `late TS bytes cannot update the replacement counter or issue another provider read`() {
        val old = gate(late = packet(256))
        start("old", old)
        old.awaitBlocked()
        val next = start("new", gate())
        assertEquals(0L, recorder.savedMs)
        old.release.countDown()
        old.awaitOutcome()

        evidence("late-ts-read", JSONObject().put("savedMs", recorder.savedMs)
            .put("oldReadCalls", old.reads.get()).put("replacementStillSelected", recorder.session == next))
        assertSame(next, recorder.session)
        assertEquals("Old buffered bytes belong to the canceled recording", 0L, recorder.savedMs)
        assertEquals("Cancellation must be observed before the next blocking read", 2, old.reads.get())
        assertTrue(recorder.isRecording)
    }

    @Test fun `an old TS finalizer cannot publish into a retained directory or stop its replacement`() {
        val old = gate()
        val previous = start("old", old)
        old.awaitBlocked()
        val before = File(previous.dir, "index.m3u8").readBytes()
        val next = start("new", gate())
        // A busy drive can retain an abandoned directory. Finish asynchronous deletion first,
        // then model that retained copy so filesystem unlink behavior cannot hide publication.
        joinCleanupJobs()
        previous.dir.mkdirs()
        File(previous.dir, "index.m3u8").writeBytes(before)
        old.release.countDown()
        join(jobs.first())
        val after = File(previous.dir, "index.m3u8").readBytes()

        evidence("old-ts-finalizer", JSONObject().put("replacementStillRecording", recorder.isRecording)
            .put("oldPlaylistChanged", !before.contentEquals(after)))
        assertArrayEquals("Canceling an old writer must not finish its abandoned playlist", before, after)
        assertSame(next, recorder.session)
        assertTrue("An old finalizer must not end the replacement session", recorder.isRecording)
        assertEquals(0L, recorder.savedMs)
    }

    @Test fun `a current finite TS recording still publishes its end and final duration`() {
        val stream = gate()
        val current = start("current", stream, reconnect = false)
        stream.awaitBlocked()
        stream.release.countDown()
        join(jobs.single())

        assertSame(current, recorder.session)
        assertFalse(recorder.isRecording)
        assertTrue(recorder.savedMs >= 2_000L)
        assertTrue(File(current.dir, "index.m3u8").readText().contains("#EXT-X-ENDLIST"))
        assertTrue(stream.closed.get())
    }

    @Test fun `a finite TS input error preserves saved pieces and closes the current recording`() {
        val stream = gate(failure = true)
        val current = start("failed-ts", stream, reconnect = false)
        stream.awaitBlocked()
        stream.release.countDown()
        join(jobs.single())

        assertSame(current, recorder.session)
        assertFalse(recorder.isRecording)
        assertTrue(recorder.savedMs >= 2_000L)
        assertTrue(File(current.dir, "index.m3u8").readText().contains("#EXT-X-ENDLIST"))
        assertTrue(stream.closed.get())
    }

    @Test fun `a current HLS input error ends only its own recording and closes its input`() {
        val stream = gate(initial = byteArrayOf(), failure = true)
        val current = start("failed-hls", stream, hls = true)
        stream.awaitBlocked()
        stream.release.countDown()
        join(jobs.single())

        assertSame(current, recorder.session)
        assertFalse(recorder.isRecording)
        assertEquals(0L, recorder.savedMs)
        assertTrue(stream.closed.get())
    }

    @Test fun `stopping a writer does not cancel the reusable recorder scope`() {
        val old = gate()
        start("old", old)
        old.awaitBlocked()
        recorder.stop()
        old.release.countDown()
        join(jobs.first())
        val nextStream = gate()
        val next = start("new", nextStream)
        nextStream.awaitBlocked()

        assertSame(next, recorder.session)
        assertTrue(recorder.isRecording)
        assertEquals(2_000L, recorder.savedMs)
        assertTrue((field(recorder, "scope") as CoroutineScope).coroutineContext[Job]!!.isActive)
    }

    @Test fun `a canceled HLS fetch returning later cannot end the new TS recording`() {
        val manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-ENDLIST\n".toByteArray()
        val old = gate(initial = byteArrayOf(), late = manifest)
        start("old-hls", old, hls = true)
        old.awaitBlocked()
        val next = start("new", gate())
        old.release.countDown()
        // HLS readBytes asks once more for EOF. No actual provider or media is accessed.
        old.finish.countDown()
        join(jobs.first())

        evidence("old-hls-finalizer", JSONObject().put("replacementStillRecording", recorder.isRecording))
        assertSame(next, recorder.session)
        assertTrue("The replaced HLS job must not clear the new session's recording flag", recorder.isRecording)
    }

    private fun start(name: String, stream: GateStream, reconnect: Boolean = true, hls: Boolean = false): LiveTvTimeshift.Session {
        recorder.baseDir = temporary.newFolder("base-$name")
        val url = "http://fixture.invalid/$name.${if (hls) "m3u8" else "ts"}"
        connections[url] = Connection(URL(url), stream)
        val result = requireNotNull(recorder.start(url, emptyMap(), 10, reconnect = reconnect))
        jobs += field(recorder, "job") as Job
        return result
    }

    private fun gate(initial: ByteArray = primer(), late: ByteArray? = null, failure: Boolean = false) =
        GateStream(initial, late, failure).also { streams += it }
    private fun join(job: Job) = runBlocking { withTimeout(10_000) { job.join() } }
    private fun joinCleanupJobs() {
        val scope = field(recorder, "scope") as CoroutineScope
        val cleanup = scope.coroutineContext[Job]!!.children.filter { it !in jobs }.toList()
        runBlocking { withTimeout(10_000) { cleanup.forEach { it.join() } } }
    }

    private class Connection(url: URL, private val stream: InputStream) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        override fun getInputStream(): InputStream = stream
    }

    private class GateStream(private val initial: ByteArray, private val late: ByteArray?, private val failure: Boolean) : InputStream() {
        val release = CountDownLatch(1)
        val finish = CountDownLatch(1)
        private val blocked = CountDownLatch(1)
        private val outcome = CountDownLatch(1)
        val reads = AtomicInteger()
        val closed = AtomicBoolean()
        private var stage = if (initial.isEmpty()) 1 else 0
        fun awaitBlocked() = assertTrue("Recorder did not reach the controlled read", blocked.await(10, TimeUnit.SECONDS))
        fun awaitOutcome() = assertTrue("Canceled read did not return or close", outcome.await(10, TimeUnit.SECONDS))
        override fun read(): Int = error("The recorder uses bulk reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            if (stage == 0) { stage = 1; return copy(initial, buffer, offset, length) }
            if (stage == 1) {
                stage = 2
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Controlled read was not released" }
                if (failure) throw IOException("Controlled provider failure")
                return late?.let { copy(it, buffer, offset, length) } ?: -1
            }
            outcome.countDown()
            check(finish.await(10, TimeUnit.SECONDS)) { "Unexpected orphan read was not released" }
            return -1
        }
        override fun close() { closed.set(true); outcome.countDown() }
        private fun copy(bytes: ByteArray, buffer: ByteArray, offset: Int, length: Int): Int {
            check(bytes.size <= length)
            bytes.copyInto(buffer, offset)
            return bytes.size
        }
    }

    private fun evidence(name: String, value: JSONObject) {
        System.getProperty("timeshift.proof.output")?.let { File(it, "$name.json").writeText(value.toString(2)) }
    }
    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)!!
    private fun primer() = clock(0) + pat() + pmt() + clock(180_000, clean = true)
    private fun packet(pid: Int, start: Boolean = false) = ByteArray(188) { 0xff.toByte() }.also {
        it[0] = 0x47; it[1] = ((pid shr 8) or if (start) 0x40 else 0).toByte(); it[2] = pid.toByte(); it[3] = 0x10
    }
    private fun pat() = packet(0, true).also {
        it[4] = 0; it[5] = 0; it[6] = 0xb0.toByte(); it[7] = 13
        it[13] = 0; it[14] = 1; it[15] = 0xe0.toByte(); it[16] = 100
    }
    private fun pmt() = packet(100, true).also { it[4] = 0; it[5] = 2; it[13] = 0xe1.toByte(); it[14] = 0 }
    private fun clock(ticks: Long, clean: Boolean = false) = packet(256).also {
        it[3] = 0x30; it[4] = 7; it[5] = (0x10 or if (clean) 0x40 else 0).toByte()
        it[6] = (ticks shr 25).toByte(); it[7] = (ticks shr 17).toByte(); it[8] = (ticks shr 9).toByte()
        it[9] = (ticks shr 1).toByte(); it[10] = (((ticks and 1) shl 7) or 0x7e).toByte(); it[11] = 0
    }
}
