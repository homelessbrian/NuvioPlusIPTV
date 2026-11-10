package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.util.AbstractMap.SimpleImmutableEntry
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GuideMapSnapshotTest {
    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Immutable map/view accepted mutation")
        } catch (_: UnsupportedOperationException) {
            // Java read-only collection views reject their mutators.
        } catch (_: ClassCastException) {
            // Kotlin read-only collections also reject a cast to a mutable interface.
        }
    }

    @Test fun `empty generation remains an immutable empty map`() {
        val snapshot = GuideMapSnapshot(GuideMapSnapshot.Base.capture(emptyMap<String?, String?>()), emptyMap())
        assertEquals(0, snapshot.size)
        assertTrue(snapshot.isEmpty())
        assertTrue(snapshot.keys.isEmpty())
        assertTrue(snapshot.values.isEmpty())
        assertTrue(snapshot.entries.isEmpty())
        assertEquals(emptyMap<String?, String?>(), snapshot)
        assertEquals(snapshot, emptyMap<String?, String?>())
        assertEquals(0, snapshot.hashCode())
        assertFalse(snapshot.containsKey(null))
        assertFalse(snapshot.entries.iterator().hasNext())
        assertThrows(NoSuchElementException::class.java) { snapshot.entries.iterator().next() }
    }

    @Test fun `base and overrides capture structure without copying programme identities`() {
        val full = listOf(EpgProgram(100, 200, "Game", "Details", "Sports", "S02E07", "icon", 2026, listOf("Actor")))
        val source = mutableMapOf("kept" to full, "hidden-by-empty" to full)
        val base = GuideMapSnapshot.Base.capture(source)
        val empty = emptyList<EpgProgram>()
        val changes = mutableMapOf("hidden-by-empty" to empty, "new" to full)
        val snapshot = GuideMapSnapshot(base, changes)
        source.clear()
        changes.clear()
        assertEquals(setOf("kept", "hidden-by-empty", "new"), snapshot.keys)
        assertSame(full, snapshot["kept"])
        assertSame(full, snapshot["new"])
        assertSame(empty, snapshot["hidden-by-empty"])
        assertEquals("Details", snapshot.getValue("kept").single().description)
        assertEquals(listOf("Actor"), snapshot.getValue("kept").single().people)
        assertEquals(3, snapshot.size)
        rejected { (base.snapshot as MutableMap)["other"] = full }
    }

    @Test fun `full schedule metadata wins while past future and overview hours remain present`() {
        val past = EpgProgram(0, 100, "Past tile")
        val light = EpgProgram(100, 200, "Light current")
        val future = EpgProgram(200, 300, "Future tile")
        val later = EpgProgram(300, 400, "Later overview")
        val full = light.copy(title = "Full current", description = "Details", category = "Sports",
            episode = "S02E07", icon = "full-icon", year = 2026, people = listOf("Actor", "Director"))
        val base = GuideMapSnapshot.Base.capture(mapOf("game" to listOf(light, later), "unbrowsed" to listOf(later)))
        val window = mergeGuideSchedules(listOf(past, light, future), base.snapshot.getValue("game"))
        val complete = mergeGuideSchedules(listOf(full), window)
        val snapshot = GuideMapSnapshot(base, mapOf("game" to complete))
        assertEquals(listOf(0L, 100L, 200L, 300L), snapshot.getValue("game").map { it.startMs })
        assertSame(full, snapshot.getValue("game")[1])
        assertEquals(full, snapshot.getValue("game")[1])
        assertSame(complete, snapshot["game"])
        assertSame(base.snapshot["unbrowsed"], snapshot["unbrowsed"])
        assertSame(window, mergeGuideSchedules(emptyList(), window))
    }

    @Test fun `386 window and schedule overrides remain complete with unseen base channels`() {
        val original = List(16_000) { index -> "channel-$index" to listOf(EpgProgram(100, 200, "Base $index")) }.toMap()
        val base = GuideMapSnapshot.Base.capture(original)
        val changes = (0 until 386).associate { index -> "channel-$index" to listOf(EpgProgram(200, 300, "Window or schedule $index")) }
        val snapshot = GuideMapSnapshot(base, changes)
        assertEquals(16_000, snapshot.size)
        changes.forEach { (key, value) -> assertSame(value, snapshot[key]) }
        assertSame(original["channel-15999"], snapshot["channel-15999"])
        val extraSchedules = changes + mapOf("outside-overview-1" to emptyList(), "outside-overview-2" to listOf(EpgProgram(0, 100, "Full schedule")))
        val union = GuideMapSnapshot(base, extraSchedules)
        assertEquals(16_002, union.size)
        assertTrue(union.containsKey("outside-overview-1"))
        assertTrue(union.getValue("outside-overview-1").isEmpty())
    }

    @Test fun `null entries shadow base and distinguish missing keys in every view`() {
        val base = GuideMapSnapshot.Base.capture(mapOf<String?, String?>("a" to "base", "b" to null, null to "old-null-key"))
        val changes = mapOf<String?, String?>("a" to null, "new" to null, null to "override-null-key")
        val snapshot = GuideMapSnapshot(base, changes)
        val expected = HashMap(base.snapshot).apply { putAll(changes) }
        assertEquals(expected, snapshot)
        assertEquals(snapshot, expected)
        assertEquals(expected.hashCode(), snapshot.hashCode())
        assertNull(snapshot["a"])
        assertTrue(snapshot.containsKey("a"))
        assertFalse(snapshot.containsKey("missing"))
        assertTrue(snapshot.entries.contains(SimpleImmutableEntry("new", null)))
        assertFalse(snapshot.entries.contains(SimpleImmutableEntry("missing", null)))
        assertFalse(snapshot.containsValue("base"))
        assertTrue(snapshot.containsValue(null))
        assertEquals(expected.keys, snapshot.keys)
        assertEquals(expected.entries, snapshot.entries)
        assertEquals(expected.values.groupingBy { it }.eachCount(), snapshot.values.groupingBy { it }.eachCount())
        assertEquals(snapshot.hashCode(), snapshot.entries.hashCode())
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    @Test fun `map views and entries reject structural mutation`() {
        val snapshot = GuideMapSnapshot(GuideMapSnapshot.Base.capture(mapOf("base" to 1)), mapOf("override" to 2))
        rejected { (snapshot as MutableMap<String, Int>)["base"] = 3 }
        rejected { (snapshot.keys as MutableSet).clear() }
        rejected { (snapshot.entries as MutableSet).clear() }
        rejected { (snapshot.values as MutableCollection).clear() }
        rejected { (snapshot.entries.first() as MutableMap.MutableEntry).setValue(5) }
        val iterator = snapshot.entries.iterator()
        iterator.next()
        rejected { (iterator as MutableIterator).remove() }
        assertEquals(mapOf("base" to 1, "override" to 2), snapshot)
    }

    @Test fun `redundant overrides and distinct bases obey symmetric map equality`() {
        val base = GuideMapSnapshot.Base.capture(mapOf("a" to 1, "b" to 2))
        val plain = GuideMapSnapshot(base, emptyMap())
        val redundant = GuideMapSnapshot(base, mapOf("a" to 1))
        val freshBase = GuideMapSnapshot(GuideMapSnapshot.Base.capture(mapOf("a" to 1, "b" to 2)), emptyMap())
        for (other in listOf<Map<String, Int>>(redundant, freshBase, mapOf("a" to 1, "b" to 2))) {
            assertTrue(plain == other)
            assertTrue(other == plain)
            assertEquals(plain.hashCode(), other.hashCode())
        }
        val extraA = GuideMapSnapshot(base, mapOf("extra-a" to 3))
        val extraB = GuideMapSnapshot(base, mapOf("extra-b" to 3))
        assertFalse(extraA == extraB)
        assertFalse(extraB == extraA)
        assertFalse(plain.equals(null))
        assertFalse(plain.equals("map"))
    }

    private class CountingKey(val id: Int) {
        override fun hashCode(): Int { hashes++; return id }
        override fun equals(other: Any?): Boolean = other is CountingKey && id == other.id
        companion object { var hashes = 0 }
    }

    @Test fun `StateFlow old equals new checks only small sibling overrides`() {
        val original = (0 until 16_000).associate { CountingKey(it) to "base-$it" }
        val base = GuideMapSnapshot.Base.capture(original)
        val changedKey = CountingKey(53)
        val first = GuideMapSnapshot(base, mapOf(changedKey to "changed"))
        val equivalent = GuideMapSnapshot(base, mapOf(changedKey to "changed", CountingKey(7) to "base-7"))
        val changed = GuideMapSnapshot(base, mapOf(changedKey to "new"))
        val flow = MutableStateFlow<Map<CountingKey, String>>(first)
        CountingKey.hashes = 0
        flow.value = equivalent
        assertSame(first, flow.value)
        assertTrue("Equal publication traversed overview", CountingKey.hashes < 100)
        CountingKey.hashes = 0
        flow.value = changed
        assertSame(changed, flow.value)
        assertTrue("Changed publication traversed overview", CountingKey.hashes < 100)
        assertFalse(changed == first)
        assertFalse(first == changed)
    }

    @Test fun `hash remains correct for shallowly shared value changes`() {
        val value = mutableListOf("before")
        val source = mapOf("key" to value)
        val snapshot = GuideMapSnapshot(GuideMapSnapshot.Base.capture(source), emptyMap())
        value += "after"
        assertEquals(source, snapshot)
        assertEquals(source.hashCode(), snapshot.hashCode())
        assertSame(value, snapshot["key"])
    }

    @Test fun `randomized overlays match HashMap oracle including snapshots and redundant values`() {
        val random = Random(0x6e757669)
        repeat(3_000) {
            val source = mutableMapOf<Int?, String?>()
            val changes = mutableMapOf<Int?, String?>()
            repeat(20) {
                if (random.nextBoolean()) source[random.nextInt(16)] = if (random.nextBoolean()) null else "v${random.nextInt(5)}"
                if (random.nextBoolean()) changes[random.nextInt(24)] = if (random.nextBoolean()) null else "v${random.nextInt(5)}"
            }
            source[null] = if (random.nextBoolean()) null else "null-key"
            val base = GuideMapSnapshot.Base.capture(source)
            val snapshot = GuideMapSnapshot(base, changes)
            val expected = HashMap(source).apply { putAll(changes) }
            val redundant = HashMap(changes).apply { source.forEach { (key, value) -> if (!containsKey(key) && random.nextBoolean()) put(key, value) } }
            val sibling = GuideMapSnapshot(base, redundant)
            assertEquals(expected, snapshot)
            assertEquals(snapshot, expected)
            assertEquals(expected.hashCode(), snapshot.hashCode())
            assertEquals(expected.size, snapshot.size)
            assertEquals(expected.keys, snapshot.keys)
            assertEquals(expected.entries, snapshot.entries)
            assertEquals(expected.values.groupingBy { it }.eachCount(), snapshot.values.groupingBy { it }.eachCount())
            assertEquals(snapshot, sibling)
            assertEquals(sibling, snapshot)
            assertEquals(snapshot.hashCode(), sibling.hashCode())
            source.clear()
            changes.clear()
            assertEquals(expected, snapshot)
        }
    }
}
