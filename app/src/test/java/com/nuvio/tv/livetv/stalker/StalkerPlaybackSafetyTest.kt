package com.nuvio.tv.livetv.stalker

import com.nuvio.stalker.StalkerConfig
import com.nuvio.stalker.StalkerContentKind
import com.nuvio.stalker.StalkerMediaAuthorization
import com.nuvio.stalker.StalkerStreamRef
import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.model.CatchupInfo
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.record.ChannelSnapshot
import com.nuvio.tv.livetv.record.RecordingSchedule
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.URI

class StalkerPlaybackSafetyTest {
    private val config = StalkerConfig("https://portal.invalid/custom/c/", "00:00:00:00:00:00")
    private val source = PlaylistSource("portal", "Portal", "", stalkerConfig = config, sourceType = "stalker")
    private val ref = StalkerStreamRef(StalkerContentKind.LIVE, "channel-7", "ffmpeg http://localhost/ch/7_",
        sourceIdentity = StalkerGateway.identity(source))
    private fun channel() = LiveChannel("portal|stalker|7", "portal", "Portal", "Sports", null, null,
        null, "sports", "Sports", 7, "stalker:portal:7", catchup = CatchupInfo("stalker", null, 7), stalkerRef = ref)

    @Test fun `control redirects preserve exact origin with default ports`() {
        val origin = URI("https://portal.invalid/custom/server/load.php")
        assertTrue(StalkerGateway.sameOrigin(origin, URI("https://portal.invalid:443/custom/portal.php")))
        for (url in listOf("http://portal.invalid/custom/portal.php", "https://cdn.invalid/portal.php",
            "https://portal.invalid:8443/portal.php", "https://user@portal.invalid/portal.php")) {
            assertFalse(StalkerGateway.sameOrigin(origin, URI(url)))
        }
    }

    @Test fun `portal and media routing must agree including loopback exemptions`() {
        assertTrue(StalkerGateway.consistentRoutes("HTTP_HTTPS:profile:1", "https://portal.invalid/c/", "http://cdn.invalid/live.ts"))
        assertTrue(StalkerGateway.consistentRoutes("OFF:null:1", "http://localhost/c/", "https://cdn.invalid/live.ts"))
        assertFalse(StalkerGateway.consistentRoutes("HTTP_ONLY:profile:1", "https://portal.invalid/c/", "http://cdn.invalid/live.ts"))
        assertFalse(StalkerGateway.consistentRoutes("HTTP_HTTPS:profile:1", "http://localhost/c/", "http://cdn.invalid/live.ts"))
        assertFalse(StalkerGateway.consistentRoutes("APP_WIDE:profile:1", "https://portal.invalid/c/", "rtsp://cdn.invalid/live"))
    }

    @Test fun `media credentials apply only to the authenticated portal origin`() {
        var checked = 0
        val authorization = StalkerMediaAuthorization("https://portal.invalid/custom/portal.php",
            mapOf("Authorization" to "Bearer synthetic-session", "Cookie" to "synthetic-cookie"))
        val policy = StalkerMediaPolicy(authorization, false) { checked++ }
        assertEquals("Bearer synthetic-session", policy.headersFor("https://portal.invalid:443/live/7.ts")["Authorization"])
        assertTrue(policy.headersFor("https://cdn.invalid/live/7.ts").isEmpty())
        assertTrue(authorization.headersFor("http://portal.invalid/live/7.ts").isEmpty())
        assertEquals(2, checked)
        assertFalse(policy.toString().contains("synthetic"))
    }

    @Test fun `secure media refuses insecure child URLs and retired playback refuses all requests`() {
        val authorization = StalkerMediaAuthorization("https://portal.invalid/portal.php", emptyMap())
        val policy = StalkerMediaPolicy(authorization, true) { }
        assertThrows(IOException::class.java) { policy.headersFor("http://cdn.invalid/segment.ts") }
        policy.retire()
        assertThrows(IOException::class.java) { policy.headersFor("https://portal.invalid/live.ts") }
    }

    @Test fun `redirect and child requests strip every portal controlled header before applying origin policy`() {
        val supplied = mapOf("authorization" to "synthetic", "Cookie" to "synthetic", "sn" to "synthetic",
            "X-User-Agent" to "synthetic", "Origin" to "synthetic", "Referer" to "synthetic",
            "User-Agent" to "synthetic", "Range" to "bytes=0-")
        assertEquals(mapOf("Range" to "bytes=0-"), StalkerMediaPolicy.withoutPortalHeaders(supplied))
    }

    @Test fun `source provenance changes for portal identity edits but not labels`() {
        assertEquals(StalkerGateway.identity(source), StalkerGateway.identity(source.copy(name = "Renamed")))
        assertNotEquals(StalkerGateway.identity(source), StalkerGateway.identity(source.copy(stalkerConfig = config.copy(deviceId = "synthetic-device"))))
        assertNotEquals(StalkerGateway.identity(source), StalkerGateway.identity(source.copy(stalkerConfig = config.copy(portalUrl = "https://other.invalid/c/"))))
    }

    @Test fun `recording snapshot keeps stable reference and never a temporary link or media headers`() {
        val snapshot = ChannelSnapshot.of(channel().copy(url = "https://cdn.invalid/live.ts?play_token=synthetic",
            headers = mapOf("Authorization" to "synthetic-secret")))
        val json = snapshot.toJson().toString()
        assertFalse(json.contains("play_token"))
        assertFalse(json.contains("synthetic-secret"))
        val restored = ChannelSnapshot.fromJson(snapshot.toJson()).toChannel()
        assertEquals(ref, restored.stalkerRef)
        assertEquals("", restored.url)
        assertTrue(restored.headers.isEmpty())
    }

    @Test fun `cold archive schedule retains portal programme identifier separately from times`() {
        val schedule = RecordingSchedule("schedule", ChannelSnapshot.of(channel()), "Match", null, null,
            1_000L, 5_000L, true, providerProgramId = "programme-archive-19", providerSourceIdentity = ref.sourceIdentity)
        val restored = RecordingSchedule.fromJson(schedule.toJson())
        assertEquals("programme-archive-19", restored.providerProgramId)
        assertEquals(ref.sourceIdentity, restored.providerSourceIdentity)
        assertEquals(ref, restored.channel.stalkerRef)
    }

    @Test fun `portal archive actions require an available provider programme identifier`() {
        val programme = EpgProgram(1_000L, 5_000L, "Match")
        assertFalse(CatchupUrlBuilder.isAvailable(channel(), programme, 10_000L))
        assertTrue(CatchupUrlBuilder.isAvailable(channel(), programme.copy(providerProgramId = "archive-19", providerSourceIdentity = ref.sourceIdentity), 10_000L))
        assertFalse(CatchupUrlBuilder.isAvailable(channel(), programme.copy(providerProgramId = "archive-19", providerSourceIdentity = "another-portal"), 10_000L))
        assertFalse(CatchupUrlBuilder.isAvailable(channel(), programme.copy(startMs = 20_000L, providerProgramId = "archive-19", providerSourceIdentity = ref.sourceIdentity), 10_000L))
    }

    @Test fun `unexpected exceptions never display or log server text or private locators`() {
        val secret = "https://private.invalid/?mac=synthetic&play_token=synthetic"
        assertFalse(StalkerGateway.failureMessage(IOException(secret)).contains(secret))
        assertFalse(StalkerGateway.failureMessage(IllegalStateException(secret)).contains(secret))
    }
}
