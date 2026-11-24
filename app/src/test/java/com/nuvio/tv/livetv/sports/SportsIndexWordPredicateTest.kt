package com.nuvio.tv.livetv.sports

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsIndexWordPredicateTest {
    private fun index(words: List<String>) = words.groupBy { it.substringBefore(' ') }

    @Test fun wholeWordsPhrasesAndFieldsKeepTheExistingMeaning() {
        val words = listOf("detroit", "lions", "foo bar", "pittsburgh steelers")
        val byFirst = index(words)
        assertTrue(hasAnySportsGuideWord(" detroit lions ", "", byFirst))
        assertTrue(hasAnySportsGuideWord(" ordinary bulletin ", " pittsburgh steelers ", byFirst))
        assertTrue(hasAnySportsGuideWord(" foo foo bar bar ", " foo bar ", byFirst))
        assertFalse(hasAnySportsGuideWord(" lionized undetroit ", " foo bars ", byFirst))
        assertFalse(hasAnySportsGuideWord(" foo ", " bar ", byFirst))
        assertFalse(hasAnySportsGuideWord("", "  ", byFirst))
        assertFalse(hasAnySportsGuideWord(" detroit ", " lions ", emptyMap()))
    }

    @Test fun normalizedUnicodeAndRepeatedTokensMatchTheBooleanFullSearch() {
        val rawWords = listOf("Pogačar", "São Paulo", "ᴸᴵⱽᴱ", "FOO & BAR", "lions", "new york jets")
        val words = rawWords.map { SportsCatalog.norm(it).trim() }.filter { it.length >= 3 }
        val samples = listOf("", "   ", "ordinary bulletin", "Pogačar at São Paulo", "POGAČAR Pogačar",
            "ᴸᴵⱽᴱ cycling", "foo&bar", "foo-bar", "lionized", "new york jetsetter", "New York Jets", "你好", "😀")
        for (title in samples) for (description in samples) assertEquivalent(title, description, words)

        val random = Random(20261123)
        val tokens = samples + listOf("foo", "bar", "and", "&", "new", "york", "jets", "\t", "\n", "\u0000")
        repeat(1500) {
            fun text() = List(random.nextInt(0, 14)) { tokens[random.nextInt(tokens.size)] }.joinToString(" ")
            assertEquivalent(text(), text(), words.shuffled(random).take(random.nextInt(words.size + 1)))
        }
    }

    private fun assertEquivalent(title: String, description: String, words: List<String>) {
        val nt = SportsCatalog.norm(title)
        val nd = if (description.isEmpty()) "" else SportsCatalog.norm(description)
        // A literal padded phrase is an independent oracle for the existing whole-word rule.
        val expected = words.any { nt.contains(" $it ") || nd.contains(" $it ") }
        assertEquals("$nt / $nd / $words", expected, hasAnySportsGuideWord(nt, nd, index(words)))
    }
}
