package com.nuvio.tv.livetv.sports

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SportsGuideWordsTest {
    @Test fun `allocation light token lookup matches split oracle including collisions and unicode`() {
        val random = Random(911)
        val tokens = listOf("Aa", "BB", "tennis", "tour", "cup", "東京", "café", "naïve", "x\ty", "final")
        val words = tokens + listOf("tennis tour", "東京 cup", "café final", "", " final")
        val byFirst = words.groupBy { it.substringBefore(' ') }
        fun oracle(title: String, description: String): Set<String>? {
            var found: HashSet<String>? = null
            for (text in listOf(title, description)) for (token in text.split(' ')) {
                if (token.isEmpty()) continue
                for (word in byFirst[token].orEmpty()) if (SportsCatalog.hasWord(title, word) || SportsCatalog.hasWord(description, word)) {
                    (found ?: HashSet<String>().also { found = it }).add(word)
                }
            }
            return found
        }
        val index = SportsGuideWords(words)
        repeat(500) {
            fun text() = List(random.nextInt(15)) { if (random.nextBoolean()) tokens.random(random) else "" }.joinToString(" ")
            val title = text(); val description = text()
            assertEquals(oracle(title, description), index.matched(title, description))
        }
        assertEquals(oracle("Aa", ""), index.matched("Aa", ""))
        assertEquals(oracle("BB", ""), index.matched("BB", ""))
    }
}
