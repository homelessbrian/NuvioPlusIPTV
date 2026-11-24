package com.nuvio.tv.livetv.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuideContentChangesTest {
    @Test fun `real revision changes trigger with equal now next StateFlow while cache projections do not`() = runTest {
        val models = MutableStateFlow(mapOf("channel" to "now and next"))
        val revisions = MutableStateFlow<Long?>(1L)
        val received = ArrayList<Any>()
        backgroundScope.launch { models.guideContentChanges(revisions).collect { received += it } }
        runCurrent()
        models.value = mapOf("channel" to "viewport")
        runCurrent()
        models.value = mapOf("channel" to "now and next")
        runCurrent()
        for (revision in 2L..4L) {
            // Imports, future-only archive writes and remaps can leave now/next data-equal.
            models.value = mapOf("channel" to "now and next")
            revisions.value = revision
            runCurrent()
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), received)
    }

    @Test fun `legacy guides and clearing the stored mode still publish actual model changes`() = runTest {
        val models = MutableStateFlow(mapOf("channel" to "first"))
        val revisions = MutableStateFlow<Long?>(null)
        val received = ArrayList<Any>()
        backgroundScope.launch { models.guideContentChanges(revisions).collect { received += it } }
        runCurrent()
        models.value = mapOf("channel" to "first")
        runCurrent()
        models.value = mapOf("channel" to "changed")
        runCurrent()
        revisions.value = 9L
        runCurrent()
        revisions.value = null
        models.value = emptyMap()
        runCurrent()
        assertEquals(listOf(mapOf("channel" to "first"), mapOf("channel" to "changed"), 9L, emptyMap<String, String>()), received)
    }

    @Test fun `staged guide files coalesce until publication but later archive writes stay immediate`() = runTest {
        val events = MutableStateFlow(1L)
        val stored = MutableStateFlow(true)
        val building = MutableStateFlow(true)
        var revision = 1L
        val received = ArrayList<Long?>()
        backgroundScope.launch { events.storedGuideRevisions(stored, building) { revision }.collect { received += it } }
        runCurrent()
        for (next in 2L..4L) { revision = next; events.value = next; runCurrent() }
        assertEquals(emptyList<Long?>(), received)
        building.value = false
        runCurrent()
        assertEquals(listOf(4L), received)
        revision = 5L
        events.value = 5L
        runCurrent()
        assertEquals(listOf(4L, 5L), received)
    }
}
