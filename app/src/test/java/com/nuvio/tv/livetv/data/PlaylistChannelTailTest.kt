package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.LiveChannel
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistChannelTailTest {
    private data class Row(val id: Int, val name: String, val tvgId: String?, val explicit: Int?, val unmarked: Boolean)

    @Test fun `compacted rows match filtering before numbering with duplicate keys and explicit numbers`() {
        val random = Random(0x5441494cL)
        repeat(1_000) {
            val prefix = List(random.nextInt(8)) { n -> Row(-n - 1, "News ${n % 3}", "${n % 2}", null, false) }
            val rows = List(random.nextInt(80)) { n ->
                Row(n, if (random.nextInt(4) == 0) "Episode ${n % 4}" else "News ${n % 3}",
                    if (random.nextBoolean()) null else "${n % 2}",
                    if (random.nextInt(4) == 0) random.nextInt(600) else null, random.nextBoolean())
            }
            val expected = ArrayList<LiveChannel>()
            val expectedKeys = HashSet<String>()
            var expectedNumber = 1
            for (row in prefix) expectedNumber = append(expected, expectedKeys, row, expectedNumber)
            val firstNumber = expectedNumber
            for (row in rows) {
                if (row.unmarked && row.name.startsWith("Episode")) continue
                expectedNumber = append(expected, expectedKeys, row, expectedNumber)
            }

            val actual = ArrayList<LiveChannel>()
            val actualKeys = HashSet<String>()
            var actualNumber = 1
            for (row in prefix) actualNumber = append(actual, actualKeys, row, actualNumber)
            val tail = PlaylistChannelTail(actual, actualKeys, firstNumber)
            for (row in rows) {
                if (row.unmarked) tail.unmarkedEntry()
                actualNumber = append(actual, actualKeys, row, actualNumber)
            }
            actualNumber = tail.removeStrays(actualNumber) { it.startsWith("Episode") }
            assertEquals(expected, actual)
            assertEquals(expectedKeys, actualKeys)
            assertEquals(expectedNumber, actualNumber)
            assertEquals(rows.count { it.unmarked && it.name.startsWith("Episode") }, tail.removed)
        }
    }

    private fun append(channels: MutableList<LiveChannel>, keys: MutableSet<String>, row: Row, counter: Int): Int {
        val base = "fixture|${row.tvgId ?: ""}|${row.name}"
        var key = base
        var suffix = 2
        while (!keys.add(key)) key = "$base#${suffix++}"
        val number = row.explicit ?: counter
        channels += LiveChannel(key, "fixture", "Fixture", row.name, row.tvgId, null, null, "News", "News", number,
            "http://fixture.test/${row.id}", autoNumber = row.explicit == null)
        return maxOf(counter, number) + 1
    }
}
