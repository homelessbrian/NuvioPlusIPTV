package com.nuvio.tv.livetv.data

import org.junit.Assert.*
import org.junit.Test

class ProgrammeIdentityPoolTest {
    @Test fun `identical provenance shares one immutable string within a guide read`() {
        val pool = ProgrammeIdentityPool()
        val first = "a".repeat(64).toCharArray().concatToString()
        val second = first.toCharArray().concatToString()
        assertNotSame(first, second)
        assertSame(pool.share(first), pool.share(second))
        assertNull(pool.share(null))
    }

    @Test fun `different guide reads have no retained shared table`() {
        val first = "a".repeat(64).toCharArray().concatToString()
        val second = first.toCharArray().concatToString()
        assertNotSame(ProgrammeIdentityPool().share(first), ProgrammeIdentityPool().share(second))
    }

    @Test fun `unbounded source identities preserve their value without growing the table`() {
        val pool = ProgrammeIdentityPool()
        repeat(128) { pool.share("identity-$it") }
        val first = "outside-the-bound".toCharArray().concatToString()
        val second = first.toCharArray().concatToString()
        assertEquals(first, pool.share(first))
        assertNotSame(pool.share(first), pool.share(second))
        val longFirst = "x".repeat(512).toCharArray().concatToString()
        val longSecond = longFirst.toCharArray().concatToString()
        assertEquals(longFirst, pool.share(longFirst))
        assertNotSame(pool.share(longFirst), pool.share(longSecond))
    }
}
