package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumTest {
    private val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun readsGitHubDigest() {
        assertEquals(hex, Checksum.fromDigest("sha256:$hex"))
        assertEquals(hex, Checksum.fromDigest("SHA256:${hex.uppercase()}"))
        assertEquals(hex, Checksum.fromDigest(hex))
    }

    @Test
    fun ignoresMissingOrOtherDigests() {
        assertNull(Checksum.fromDigest(null))
        assertNull(Checksum.fromDigest(""))
        assertNull(Checksum.fromDigest("sha512:abcd"))
        assertNull(Checksum.fromDigest("sha256:zz"))
    }

    @Test
    fun hexOfEmptyInput() {
        assertEquals(hex, Checksum.toHex(java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(0))))
    }

    @Test
    fun matchesOnlyWhenEqualOrNothingExpected() {
        assertTrue(Checksum.matches(null, hex))
        assertTrue(Checksum.matches(hex.uppercase(), hex))
        assertFalse(Checksum.matches("0".repeat(64), hex))
    }
}
