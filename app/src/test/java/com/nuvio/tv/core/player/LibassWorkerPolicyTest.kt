package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibassWorkerPolicyTest {
    private val twoGiB = 2L shl 30

    @Test
    fun `known devices up to two GiB request one worker without overwrite`() {
        for (ram in listOf(1L, 1L shl 30, twoGiB)) {
            val writes = ArrayList<Pair<String, Boolean>>()
            assertTrue(LibassWorkerPolicy.apply(
                physicalRamBytes = { ram },
                existingOverride = { null },
                setOverride = { value, overwrite -> writes += value to overwrite },
            ))
            assertEquals(listOf("1" to false), writes)
        }
    }

    @Test
    fun `unknown and larger devices keep automatic workers`() {
        for (ram in listOf(Long.MIN_VALUE, -1L, 0L, twoGiB + 1L, 4L shl 30, Long.MAX_VALUE)) {
            assertFalse(LibassWorkerPolicy.apply(
                physicalRamBytes = { ram },
                existingOverride = { null },
                setOverride = { _, _ -> throw AssertionError("Unexpected environment write for RAM $ram") },
            ))
        }
    }

    @Test
    fun `every explicit override is preserved without reading RAM`() {
        for (existing in listOf("", "0", "1", "2", "automatic")) {
            assertFalse(LibassWorkerPolicy.apply(
                physicalRamBytes = { throw AssertionError("RAM must not be queried for an explicit override") },
                existingOverride = { existing },
                setOverride = { _, _ -> throw AssertionError("Explicit override was replaced") },
            ))
        }
    }

    @Test
    fun `unavailable environment reads leave startup running`() {
        assertFalse(LibassWorkerPolicy.apply(
            physicalRamBytes = { throw AssertionError("RAM must not be queried after an environment failure") },
            existingOverride = { throw SecurityException("Environment unavailable") },
            setOverride = { _, _ -> throw AssertionError("Environment write must not be attempted") },
        ))
    }

    @Test
    fun `unavailable physical memory leaves startup running`() {
        assertFalse(LibassWorkerPolicy.apply(
            physicalRamBytes = { throw IllegalStateException("Memory service unavailable") },
            existingOverride = { null },
            setOverride = { _, _ -> throw AssertionError("Environment write must not be attempted") },
        ))
    }

    @Test
    fun `unavailable environment writes leave startup running`() {
        assertFalse(LibassWorkerPolicy.apply(
            physicalRamBytes = { twoGiB },
            existingOverride = { null },
            setOverride = { _, _ -> throw SecurityException("Environment write unavailable") },
        ))
    }
}
