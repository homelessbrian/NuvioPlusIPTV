package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A successful portal hand-off must carry through to playback, not just catalogue access. */
class StalkerPortalHandoffTest {
    private val config = StalkerConfig(
        "https://portal.example.test/tenant/c/", "02:00:00:00:00:01"
    )

    @Test fun mediaOnTheAuthenticatedHandoffOriginKeepsPortalCredentials() = runBlocking {
        val media = "https://lb2.example.test/stalker_portal/streams/fixture.ts?token=fixture-play"
        val client = handedOffClient("ffmpeg $media")

        val resolved = client.resolve(reference())
        assertEquals(media, resolved.url)
        val authorization = client.mediaAuthorization()
        val headers = authorization.headersFor(resolved.url)

        assertEquals("Bearer fixture-session", headers["Authorization"],
            "The server that authenticated the portal must receive its playback authorization")
        assertTrue(headers["Cookie"].orEmpty().contains("mac=02%3A00%3A00%3A00%3A00%3A01"))
        assertTrue(headers["User-Agent"].orEmpty().contains("MAG250"))
        assertEquals("Model: MAG250; Link: Ethernet", headers["X-User-Agent"])
        assertTrue(authorization.headersFor("https://cdn.example.test/fixture.ts").isEmpty(),
            "An unrelated media origin must not inherit the portal token or identity")
        assertTrue(authorization.headersFor("https://lb2.example.test:8443/fixture.ts").isEmpty(),
            "Another port is a different origin, even when the hostname is the same")
    }

    @Test fun relativeMediaUsesTheAuthenticatedHandoffInstallation() = runBlocking {
        val client = handedOffClient("ffmpeg streams/fixture.ts?token=fixture-play")

        val resolved = client.resolve(reference())

        assertEquals(
            "https://lb2.example.test/stalker_portal/streams/fixture.ts?token=fixture-play",
            resolved.url,
            "Relative media returned by the final portal must not resolve against the old host or path"
        )
    }

    private fun reference() = StalkerStreamRef(
        StalkerContentKind.LIVE, "fixture-channel", "ffmpeg http://localhost/ch/fixture_",
        useHttpTmpLink = true
    )

    private fun handedOffClient(command: String) = StalkerClient(config,
        StalkerTransport { url, headers ->
            val request = Url(url)
            val action = request.parameters["action"]
            if (action != "handshake") assertEquals("Bearer fixture-session", headers["Authorization"])
            val body = when (action) {
                "handshake" -> """{"js":{"token":"fixture-session"}}"""
                "get_profile" -> """{"js":{"id":"fixture-account","status":0}}"""
                "create_link" -> """{"js":{"cmd":"$command"}}"""
                else -> error("Unexpected synthetic portal operation: $action")
            }
            StalkerHttpResponse(200, body, finalUrl =
                "https://lb2.example.test/stalker_portal/server/load.php?${request.encodedQuery}")
        }, { 1_800_000_000_000L })
}
