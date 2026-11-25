package com.nuvio.tv.core.usenet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsenetConfigurationBackupTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `older serialized configurations inherit defaults and future fields are ignored`() {
        val decoded = json.decodeFromString<UsenetConfiguration>("""{"profile":"low-memory","futureChoice":true}""")
        assertEquals(UsenetConfiguration(profile = "low-memory"), decoded)
        assertEquals(decoded, json.decodeFromString<UsenetConfiguration>(json.encodeToString(decoded)))
    }

    @Test fun `backup validation matches edit normalization without changing the input`() {
        val original = UsenetConfiguration(searchUserAgent = " $NUVIO_USER_AGENT ", downloadUserAgent = " Downloader/1 ")
        val normalized = original.normalizedAndValidated()
        assertEquals("", normalized.searchUserAgent)
        assertEquals("Downloader/1", normalized.downloadUserAgent)
        assertEquals(" Downloader/1 ", original.downloadUserAgent)
        for (invalid in listOf(
            original.copy(profile = "unknown"), original.copy(readAhead = 513),
            original.copy(maxConnections = 4097), original.copy(fallbackMaxAttempts = 0),
            original.copy(maxMissingArticles = 1, maxConsecutiveMissing = 2),
            original.copy(downloadUserAgent = "one\r\ntwo")
        )) assertThrows(IllegalArgumentException::class.java) { invalid.normalizedAndValidated() }
    }
}
