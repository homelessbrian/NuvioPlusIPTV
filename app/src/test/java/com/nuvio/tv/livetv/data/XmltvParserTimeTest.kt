package com.nuvio.tv.livetv.data

import java.time.Instant
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XmltvParserTimeTest {
    @Test
    fun timestampsKeepSecondsOffsetsAndWhitespace() {
        val utc = Instant.parse("2026-11-09T12:34:56Z").toEpochMilli()
        assertEquals(utc, XmltvParser.parseTime("20261109123456 +0000"))
        assertEquals(utc, XmltvParser.parseTime(" 20261109180456 +0530\t"))
        assertEquals(utc, XmltvParser.parseTime("20261109043456 -0800"))
        assertEquals(utc - 56_000, XmltvParser.parseTime("202611091234"))
        assertNull(XmltvParser.parseTime("20261309123456 +0000"))
        assertNull(XmltvParser.parseTime("20261100123456 +0000"))
        assertNull(XmltvParser.parseTime("20261109123"))
    }

    @Test
    fun directReaderMatchesExistingLeniencyAndUnicode() {
        val examples = listOf(null, "", " \u00a0", "2026110912345", "20261109123456789",
            "20260231999999", "20261109123456Z", "20261109123456 +ab30", "20261109123456 ++130",
            "20261109123456 +-130", "20261109123456 -0+03", "\u00a020261109123456\u00a0+0530\u00a0",
            "٢٠٢٦١١٠٩١٢٣٤٥٦ +٠٥٣٠")
        examples.forEach { assertEquals(it, previousReader(it), XmltvParser.parseTime(it)) }
        val random = Random(20261109)
        repeat(20_000) {
            val digits = buildString { repeat(random.nextInt(0, 25)) { append(('0'.code + random.nextInt(10)).toChar()) } }
            val offset = buildString { repeat(random.nextInt(0, 9)) { append("0123456789+-x \t"[random.nextInt(15)]) } }
            val value = " ${digits} $offset "
            assertEquals(value, previousReader(value), XmltvParser.parseTime(value))
        }
    }

    /** Compatibility oracle: the prior allocating reader, retained only in the test. */
    private fun previousReader(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        val digits = v.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31) return null
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        var ms = ((era * 146_097 + doe - 719_468) * 86_400L + hour * 3_600L + minute * 60L + second) * 1_000L
        val offset = v.substring(digits.length).trim()
        if (offset.length >= 5 && (offset[0] == '+' || offset[0] == '-')) {
            val sign = if (offset[0] == '-') -1 else 1
            val oh = offset.substring(1, 3).toIntOrNull() ?: 0
            val om = offset.substring(3, 5).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
