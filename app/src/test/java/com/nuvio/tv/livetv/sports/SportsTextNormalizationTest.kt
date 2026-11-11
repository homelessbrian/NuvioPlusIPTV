package com.nuvio.tv.livetv.sports

import java.text.Normalizer
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsTextNormalizationTest {
    private val marks = Regex("\\p{M}+")
    private val notWord = Regex("[^a-z0-9]+")
    private val spaces = Regex(" +")

    // Independent reference for the complete former Unicode pipeline, including its padding.
    private fun previous(text: String): String {
        val plain = Normalizer.normalize(SportsCatalog.plainSmall(text), Normalizer.Form.NFD).replace(marks, "")
        return " " + plain.lowercase(Locale.ROOT).replace("&", " and ")
            .replace(notWord, " ").trim().replace(spaces, " ") + " "
    }

    private fun check(text: String) = assertEquals(previous(text), SportsCatalog.norm(text))

    @Test fun `matching word boundaries ampersands and empty padding remain exact`() {
        val cases = mapOf(
            "" to "  ", "\u00a0🎾" to "  ", "&&" to " and and ",
            "&Équipe&& Ångström &" to " and equipe and and angstrom and ",
            "A\u0301B" to " ab ", "İSTANBUL & STRAẞE" to " istanbul and stra e ",
            "Tennis ᴸᴵⱽᴱ" to " tennis live ", "Ａ＆Ｂ" to "  "
        )
        for ((text, expected) in cases) {
            assertEquals(expected, previous(text))
            assertEquals(expected, SportsCatalog.norm(text))
        }
    }

    @Test fun `every UTF16 code unit retains the former pipeline result in word context`() {
        for (value in 0..0xffff) check("A${value.toChar()}B&É")
    }

    @Test fun `supplementary characters combining marks and malformed pairs retain word boundaries`() {
        // Covers every supplementary Unicode block and all surrogate combinations deterministically.
        for (value in 0x10000..0x10ffff step 31) check("É&" + String(Character.toChars(value)) + "B")
        for (value in 0xd800..0xdfff) {
            val c = value.toChar()
            check("A${c} B")
            check("É${c}${c}&Z")
        }
        for (value in listOf(0x1d165, 0x1d185, 0x1cf00, 0xe0100)) {
            check("A" + String(Character.toChars(value)) + "B")
        }
    }

    @Test fun `mixed casing raised marks punctuation and guide descriptions preserve full strings`() {
        val descriptions = listOf(
            "Live: Montréal Canadiens & New York Islanders — résumé, ᴴᴰ.",
            "İstanbul, I\u0307, ΣΟΣ/ΟΣ, ẞ/ß, Kelvin, Ångström; 100% _? &&",
            "Australian Open: ２０２６ 🎾 (men’s/women’s) — chaîne française",
            "A\u0000B\r\nC\tD\u00a0E\u0301F ᴺᴱᵂ ʟɪᴠᴇ ＆ τέλος"
        )
        descriptions.forEach { check(it); check(it.repeat(100)) }
        val random = Random(0x4e5556494fL)
        repeat(5000) {
            check(buildString { repeat(random.nextInt(120)) { append(random.nextInt(0x10000).toChar()) } })
        }
    }
}
