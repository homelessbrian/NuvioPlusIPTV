package com.nuvio.tv.livetv.sports

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsWordBoundaryTest {
    @Test
    fun `only literal space boundaries count and later overlapping occurrences are checked`() {
        assertTrue(SportsCatalog.hasWord(" live new york jets football ", "new york jets"))
        assertFalse(SportsCatalog.hasWord(" new york jetstream ", "new york jets"))
        assertFalse(SportsCatalog.hasWord("football ", "football"))
        assertFalse(SportsCatalog.hasWord(" football", "football"))
        assertFalse(SportsCatalog.hasWord("\tfootball\t", "football"))
        assertFalse(SportsCatalog.hasWord("\u00A0football\u00A0", "football"))
        assertTrue(SportsCatalog.hasWord(" ababa aba ", "aba"))
        // The first occurrence has no left boundary; the overlapping later occurrence has both.
        assertTrue(SportsCatalog.hasWord("a a a ", "a a"))
        assertTrue(SportsCatalog.hasWord(" a   a  ", " a "))
        assertFalse(SportsCatalog.hasWord(" a ", " a "))
        assertFalse(SportsCatalog.hasWord(" anything ", ""))
        assertFalse(SportsCatalog.hasWord("    ", " "))
    }

    @Test
    fun `unicode whitespace and literal edge cases match the original expression`() {
        val cases = listOf(
            "", " ", "  ", " a ", " a a ", " aa ", " a  a ", "\t", "\n",
            "\u00A0", "\u2007", "\u202F", "\u0085", "\u200B", "é", "e\u0301",
            "中文", "\uD83D\uDE00", "\uD83D", "\uDE00", "a\u0000b",
        )
        var checked = 0
        for (text in cases) for (term in cases) {
            assertMatchesOriginal(text, term)
            assertMatchesOriginal(" $text ", term)
            assertMatchesOriginal("  $text  ", term)
            checked += 3
        }
        assertEquals(1_323, checked)
    }

    @Test
    fun `exhaustive short strings match the original expression`() {
        val texts = words(charArrayOf(' ', 'a', 'b', 'é'), 5)
        val terms = words(charArrayOf(' ', 'a', 'b', 'é'), 3)
        var checked = 0
        for (text in texts) for (term in terms) {
            assertMatchesOriginal(text, term)
            checked++
        }
        assertEquals(116_025, checked)
    }

    @Test
    fun `seeded arbitrary literal strings and substring terms match the original expression`() {
        val random = Random(0x5eed)
        val alphabet = charArrayOf(
            ' ', ' ', 'a', 'a', 'b', 'é', '中', '\t', '\n', '\u00A0', '\u2007', '\u202F',
            '\u0085', '\u200B', '\uD83D', '\uDE00', '\u0000',
        )
        var checked = 0
        repeat(250_000) { case ->
            val text = String(CharArray(random.nextInt(100)) { alphabet[random.nextInt(alphabet.size)] })
            val term = if (text.isNotEmpty() && (case and 1) == 0) {
                val from = random.nextInt(text.length)
                text.substring(from, from + random.nextInt(text.length - from + 1))
            } else {
                String(CharArray(random.nextInt(20)) { alphabet[random.nextInt(alphabet.size)] })
            }
            assertMatchesOriginal(text, term)
            checked++
            if ((case and 7) == 0) {
                assertMatchesOriginal(" $term ", term)
                checked++
            }
        }
        assertEquals(281_250, checked)
    }

    /** Keep the previous production expression as the oracle, independent of the new search loop. */
    private fun assertMatchesOriginal(text: String, term: String) {
        val expected = term.isNotBlank() && text.contains(" $term ")
        val actual = SportsCatalog.hasWord(text, term)
        if (actual != expected) {
            assertEquals(
                "UTF-16 text=${text.map { it.code.toString(16) }} term=${term.map { it.code.toString(16) }}",
                expected,
                actual,
            )
        }
    }

    private fun words(alphabet: CharArray, maxLength: Int): List<String> {
        val all = mutableListOf("")
        var level = listOf("")
        repeat(maxLength) {
            level = level.flatMap { prefix -> alphabet.map { prefix + it } }
            all += level
        }
        return all
    }
}
