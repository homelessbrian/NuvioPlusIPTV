package com.nuvio.tv.livetv.sports.probe

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.multiview.LiveTvMediaSourceBuilder
import com.nuvio.tv.livetv.player.LiveDns
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Media3 periods, HTTP and cancellation; no decoder or provider-account claim. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SportsProbeRuntimeTest {
    @Test fun continuousTsAndHlsRequireVideoSamplesAndUseTheirActualDimensions() = runBlocking {
        val ts = check("/continuous.ts")
        val extensionless = check("/extensionless")
        val hls = check("/master.m3u8")
        listOf(ts, extensionless, hls).forEach {
            assertEquals(SportsProbeStatus.WORKING, it.status)
            assertTrue(it.width > 0 && it.height > 0)
            assertFalse(it.playbackVerified)
        }
        assertEquals(ts.width, extensionless.width)
        assertEquals(ts.height, extensionless.height)
        assertEquals(ts.width, hls.width)
        assertEquals(ts.height, hls.height)
        assertNotEquals("Advertised maximum was mistaken for an extracted video format", 7680, hls.width)
        writeEvidence("media", JSONObject().put("ts", describe(ts)).put("extensionless", describe(extensionless))
            .put("hls", describe(hls)))
    }

    @Test fun cancellingStalledHttpBodyReleasesItBeforeTheNextProbe() = runBlocking {
        val calls = CopyOnWriteArrayList<Call>()
        val active = ConcurrentHashMap.newKeySet<Call>()
        val reading = ConcurrentHashMap.newKeySet<String>()
        val client = LiveDns.client.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
            .readTimeout(30, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { active += call }
                override fun responseBodyStart(call: Call) { reading += call.request().url.encodedPath }
                override fun callEnd(call: Call) { active -= call }
                override fun callFailed(call: Call, ioe: IOException) { active -= call }
            }).build()
        try {
            val factory = Call.Factory { request: Request -> client.newCall(request).also { calls += it } }
            val pending = async { check("/slow.ts", SportsProbeHttpSession(factory)) }
            withTimeout(3_000) { while ("/slow.ts" !in reading) delay(10) }
            val started = System.nanoTime()
            withTimeout(3_000) { pending.cancelAndJoin() }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Cancellation waited for the socket timeout", elapsedMs < 3_000)
            assertTrue("No owned HTTP call was cancelled", calls.isNotEmpty() && calls.all { it.isCanceled() })
            assertTrue("HTTP body still active after cancellation completed", active.isEmpty())
            val next = check("/continuous.ts", SportsProbeHttpSession(factory))
            assertEquals(SportsProbeStatus.WORKING, next.status)
            assertTrue(active.isEmpty())
            writeEvidence("cancellation", JSONObject().put("cancelMs", elapsedMs)
                .put("ownedCalls", calls.size).put("activeAfter", active.size).put("next", describe(next)))
        } finally {
            calls.forEach(Call::cancel)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private suspend fun check(path: String, session: SportsProbeHttpSession = SportsProbeHttpSession(LiveDns.client)): SportsProbeResult {
        val base = requireNotNull(InstrumentationRegistry.getArguments().getString("probeBaseUrl")) {
            "Pass -e probeBaseUrl http://10.0.2.2:<fixture-port> with the owned loopback fixture running"
        }.trimEnd('/')
        // Inject only the loopback transport boundary: production still uses its VPN adapter.
        return try {
            probeSportsMediaSource(beforeClose = session::close) { decorate ->
                LiveTvMediaSourceBuilder(observeCaptions = false, requireHttpSources = true,
                    continuousTs = true, requireMediaPreparation = true,
                    httpDecorator = decorate, httpFactoryOverride = session.factory(emptyMap()))
                    .build(base + path, emptyMap(), isLive = true)
            }
        } finally { session.close() }
    }

    private fun describe(result: SportsProbeResult) = JSONObject().put("status", result.status.name)
        .put("width", result.width).put("height", result.height).put("playbackVerified", result.playbackVerified)

    private fun writeEvidence(name: String, evidence: JSONObject) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "sports-probe-runtime").apply { mkdirs() }
        File(folder, "$name.json").writeText(evidence.put("scope", "Android decoder-free MediaSource engine; loopback fixture")
            .put("recordedAtMs", System.currentTimeMillis()).toString(2))
    }
}
