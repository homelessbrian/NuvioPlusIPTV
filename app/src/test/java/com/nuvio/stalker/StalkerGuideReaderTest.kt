package com.nuvio.stalker

import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.Reader
import java.io.StringReader
import kotlin.test.*

class StalkerGuideReaderTest {
    private val programme = """{"ch_id":"10","id":"p1","name":"Équipe 東京","start_timestamp":1700000000,"stop_timestamp":"1700003600","descr":"Line one\nLine two","mark_archive":"1","unused":{"nested":[1,2,3]}}"""

    @Test fun supportsAllBulkEnvelopeShapesWithoutDroppingRows() {
        val forms = listOf("""{"js":[$programme]}""", """{"js":{"data":[$programme]}}""",
            """{"js":{"10":[$programme]}}""", """{"js":{"data":{"10":{"epg":[$programme]}}}}""")
        forms.forEach { input ->
            val rows = mutableListOf<StalkerGuideRow>()
            assertNull(StalkerGuideReader.read(StringReader(input)) { _, row -> rows += row })
            assertEquals(1, rows.size)
            assertEquals("Équipe 東京", rows.single().title)
            assertEquals("Line one\nLine two", rows.single().description)
            assertEquals(true, rows.single().archive)
        }
    }

    @Test fun aliasesKeepCanonicalPriorityRegardlessOfJsonOrder() {
        val input = """{"js":[{"ch_id":"preferred-channel","channel_id":"other","id":"preferred-id","program_id":"other","name":"Preferred","title":"Other","descr":"Preferred description","description":"Other","plot":"Other plot","mark_archive":0,"is_archive":1,"start_timestamp":1700000000,"stop_timestamp":1700003600,"end_timestamp":1700009999}]}"""
        var actual: StalkerGuideRow? = null
        assertNull(StalkerGuideReader.read(StringReader(input)) { _, row -> actual = row })
        assertEquals("preferred-channel", actual!!.channelId)
        assertEquals("preferred-id", actual!!.id)
        assertEquals("Preferred", actual!!.title)
        assertEquals("Preferred description", actual!!.description)
        assertEquals(false, actual!!.archive)
        assertEquals("1700003600", actual!!.stopTimestamp)
    }

    @Test fun emptyIsDifferentFromMalformedOrErrorResponse() {
        listOf("""{"js":[]}""", """{"js":{}}""", """{"js":{"data":[]}}""").forEach { input ->
            assertNull(StalkerGuideReader.read(StringReader(input)) { _, _ -> fail("Unexpected row") })
        }
        listOf("""{"js":false}""", """{"js":{"name":"No guide container"}}""", """{"data":[]}""", """{"js":[{"id":"x"}""", "<html>Not a portal</html>").forEach { input ->
            assertEquals(StalkerError.INVALID_RESPONSE, StalkerGuideReader.read(StringReader(input)) { _, _ -> })
        }
        assertEquals(StalkerError.AUTHENTICATION_FAILED, StalkerGuideReader.read(StringReader("Authorization failed.")) { _, _ -> })
        assertEquals(StalkerError.AUTHENTICATION_FAILED, StalkerGuideReader.read(StringReader("""{"js":{"error":"Token expired"}}""")) { _, _ -> })
        assertEquals(StalkerError.UNSUPPORTED, StalkerGuideReader.read(StringReader("""{"js":{"error":"Unknown action"}}""")) { _, _ -> })
    }

    @Test fun streamingAndBufferedDecodeMatchForBlankTimestampFallbackAndDuplicateKeys() = runBlocking {
        val body = """{"js":{"10":[{"id":"original","id":"p1","name":"Original","name":"Final title","title":"Lower-priority alias","start_timestamp":"","stop_timestamp":" ","time":"2024-01-01T01:00:00Z","time_to":"2024-01-01T02:00:00Z","descr":"Original description","descr":"Final description","description":"Lower-priority alias"},{"id":"original","id":"","program_id":"p2","name":"Original","name":null,"title":"Fallback title","start_timestamp":"1704070800","start_timestamp":" ","stop_timestamp":"1704074400","start":"2024-01-01T01:00:00Z","descr":"Original description","descr":"","description":"Fallback description","mark_archive":true,"mark_archive":null,"is_archive":false}]}}"""
        val config = StalkerConfig("https://portal.example.test/c/", "02:00:00:00:00:01")
        fun response(url: String): StalkerHttpResponse = StalkerHttpResponse(200, when (Url(url).parameters["action"]) {
            "handshake" -> """{"js":{"token":"fixture-token"}}"""
            "get_profile" -> """{"js":{"status":0}}"""
            else -> body
        })
        val buffered = StalkerClient(config, StalkerTransport { url, _ -> response(url) }, { 1_800_000_000_000 })
        val streamed = StalkerClient(config, object : StalkerStreamingGuideTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = response(url)
            override suspend fun getGuide(url: String, headers: Map<String, String>, onRow: (String?, StalkerGuideRow) -> Unit) =
                StalkerGuideHttpResponse(200, StalkerGuideReader.read(StringReader(body), onRow))
        }, { 1_800_000_000_000 })
        val actual = mutableListOf<StalkerProgramme>()
        streamed.forEachProgramme { actual += it }
        assertEquals(buffered.guide(), actual)
        assertEquals("p1", actual.first().id)
        assertEquals("Final title", actual.first().title)
        assertEquals("Final description", actual.first().description)
        assertEquals(1_704_070_800_000L, actual.first().startMillis)
        assertEquals("p2", actual.last().id)
        assertEquals("Fallback title", actual.last().title)
        assertEquals("Fallback description", actual.last().description)
        assertFalse(actual.last().archiveAvailable)
    }

    @Test fun cancellationFromConsumerIsNotTurnedIntoMalformedJson() {
        assertFailsWith<CancellationException> {
            StalkerGuideReader.read(StringReader("""{"js":[$programme]}""")) { _, _ -> throw CancellationException("fixture cancellation") }
        }
    }

    @Test fun streamingClientRenewsBeforeRowsAndNeverBuffersGuideBody() = runBlocking {
        var handshakes = 0; var guides = 0
        val transport = object : StalkerStreamingGuideTransport {
            override suspend fun get(url: String, headers: Map<String, String>): StalkerHttpResponse = when (Url(url).parameters["action"]) {
                "handshake" -> { handshakes++; StalkerHttpResponse(200, """{"js":{"token":"fixture-$handshakes"}}""") }
                "get_profile" -> StalkerHttpResponse(200, """{"js":{"status":0,"id":"fixture"}}""")
                else -> fail("Guide must use streaming transport")
            }
            override suspend fun getGuide(url: String, headers: Map<String, String>, onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse {
                guides++
                if (guides == 1) return StalkerGuideHttpResponse(401)
                assertEquals("Bearer fixture-2", headers["Authorization"])
                val error = StalkerGuideReader.read(StringReader("""{"js":[$programme]}"""), onRow)
                return StalkerGuideHttpResponse(200, error)
            }
        }
        val client = StalkerClient(StalkerConfig("https://portal.example.test/c/", "02:00:00:00:00:01"), transport, { 1_800_000_000_000 })
        var count = 0
        client.forEachProgramme { count++; assertTrue(it.archiveAvailable) }
        assertEquals(1, count); assertEquals(2, guides); assertEquals(2, handshakes)
    }

    @Test fun lateStreamingFailureNeverReplaysIntoPartialStagingOutput() = runBlocking {
        var guides = 0
        val transport = object : StalkerStreamingGuideTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = StalkerHttpResponse(200,
                if (Url(url).parameters["action"] == "handshake") """{"js":{"token":"fixture"}}""" else """{"js":{"status":0}}""")
            override suspend fun getGuide(url: String, headers: Map<String, String>, onRow: (String?, StalkerGuideRow) -> Unit): StalkerGuideHttpResponse {
                guides++
                onRow("10", StalkerGuideRow(id = "p1", startTimestamp = "1700000000", stopTimestamp = "1700003600"))
                return StalkerGuideHttpResponse(200, StalkerError.AUTHENTICATION_FAILED)
            }
        }
        val client = StalkerClient(StalkerConfig("https://portal.example.test/c/", "02:00:00:00:00:01"), transport, { 1_800_000_000_000 })
        var rows = 0
        val failure = assertFailsWith<StalkerException> { client.forEachProgramme { rows++ } }
        assertEquals(StalkerError.AUTHENTICATION_FAILED, failure.error)
        assertEquals(1, guides); assertEquals(1, rows)
    }

    @Test fun realScaleSyntheticGuideIsReadSeriallyFromAnUnmaterializedReader() {
        var rows = 0; var channelSum = 0L
        val error = SyntheticStalkerGuideReader(16_000, 48).use { input ->
            StalkerGuideReader.read(input) { _, row -> rows++; channelSum += row.channelId!!.toLong() }
        }
        assertNull(error)
        assertEquals(768_000, rows)
        assertEquals(48L * 15_999 * 16_000 / 2, channelSum)
    }
}

/** Generates public synthetic rows on demand; no fixture URL, account or full guide string. */
internal class SyntheticStalkerGuideReader(private val channels: Int, private val perChannel: Int) : Reader() {
    private var row = -1
    private var fragment = "{\"js\":{\"data\":["
    private var offset = 0
    private var finished = false
    override fun read(buffer: CharArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        var written = 0
        while (written < length) {
            if (offset == fragment.length) {
                if (finished) return if (written == 0) -1 else written
                row++
                if (row == channels * perChannel) { fragment = "]}}"; finished = true }
                else {
                    val channel = row / perChannel; val slot = row % perChannel
                    val timestamp = 1_700_000_000L + slot * 1800L
                    fragment = (if (row == 0) "" else ",") + "{\"ch_id\":\"$channel\",\"id\":\"p$row\",\"name\":\"Programme $slot Équipe 東京\",\"start_timestamp\":$timestamp,\"stop_timestamp\":${timestamp + 1800},\"descr\":\"Synthetic programme description\",\"mark_archive\":1}"
                }
                offset = 0
            }
            val count = minOf(length - written, fragment.length - offset)
            fragment.toCharArray(buffer, start + written, offset, offset + count)
            offset += count; written += count
        }
        return written
    }
    override fun close() {}
}
