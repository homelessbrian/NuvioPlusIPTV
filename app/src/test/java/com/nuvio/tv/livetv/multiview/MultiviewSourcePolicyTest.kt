package com.nuvio.tv.livetv.multiview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewSourcePolicyTest {
    @Test
    fun `HTTP manifests media and remote licenses use the protected source policy`() {
        listOf(
            "https://example.invalid/channel.m3u8?token=fixture",
            "http://example.invalid/channel.ts",
            "HTTPS://example.invalid/channel.mpd",
            "https://example.invalid/license",
        ).forEach { assertTrue(MultiviewSourcePolicy.allows(it)) }
    }

    @Test
    fun `socket and local schemes cannot bypass protected HTTP transport`() {
        listOf(
            "rtsp://example.invalid/channel", "rtsps://example.invalid/channel",
            "udp://example.invalid:1234", "file:///channel.ts", "content://channel",
            "data:video/mp2t;base64,fixture", "channel.ts", "", "http://invalid host",
        ).forEach { assertFalse(MultiviewSourcePolicy.allows(it)) }
    }

    @Test
    fun `strict DRM permits only explicitly supported schemes`() {
        listOf("widevine", "playready", "clearkey", " Widevine ", "CLEARKEY").forEach {
            assertTrue(MultiviewSourcePolicy.supportsDrmScheme(it))
        }
        listOf("", "unknown", "none", "unprotected", "http").forEach {
            assertFalse(MultiviewSourcePolicy.supportsDrmScheme(it))
        }
    }

    @Test
    fun `license transport excludes stream credentials and custom provider headers`() {
        val headers = MultiviewSourcePolicy.licenseHeaders(
            mapOf(
                "User-Agent" to "Fixture agent", "referer" to "https://example.invalid/player",
                "Origin" to "https://example.invalid", "Authorization" to "Fixture stream credential",
                "Cookie" to "Fixture stream cookie", "X-Provider-Token" to "Fixture stream token",
            ),
            emptyMap(),
        )
        assertEquals(setOf("User-Agent", "referer", "Origin"), headers.keys)
        assertFalse(headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })
        assertFalse(headers.containsKey("X-Provider-Token"))
    }

    @Test
    fun `explicit license credentials and context overrides remain available to the license host`() {
        val headers = MultiviewSourcePolicy.licenseHeaders(
            mapOf("User-Agent" to "Stream agent", "Authorization" to "Stream credential", "Cookie" to "Stream cookie"),
            mapOf("User-Agent" to "License agent", "Authorization" to "License credential", "Cookie" to "License cookie"),
        )
        assertEquals(
            mapOf("User-Agent" to "License agent", "Authorization" to "License credential", "Cookie" to "License cookie"),
            headers,
        )
    }

    @Test
    fun `strict license context overrides are case insensitive while single player defaults stay unchanged`() {
        val stream = mapOf("User-Agent" to "Stream agent", "Referer" to "https://example.invalid/stream")
        val license = mapOf("user-agent" to "License agent", "REFERER" to "https://example.invalid/license")
        assertEquals(4, MultiviewSourcePolicy.licenseHeaders(stream, license).size)
        assertEquals(
            license,
            MultiviewSourcePolicy.licenseHeaders(stream, license, caseInsensitiveOverrides = true),
        )
    }
}
