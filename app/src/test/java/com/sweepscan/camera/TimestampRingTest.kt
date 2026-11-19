package com.sweepscan.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimestampRingTest {

    @Test
    fun `exact and nearest lookups`() {
        val ring = TimestampRing<String>(4)
        ring.put(1_000_000_000L, "a")
        ring.put(1_033_000_000L, "b")
        assertEquals("a", ring.lookup(1_000_000_000L))
        assertEquals("b", ring.lookup(1_034_000_000L))
        assertNull(ring.lookup(1_100_000_000L))
    }

    @Test
    fun `old entries are evicted`() {
        val ring = TimestampRing<Int>(2)
        ring.put(10L, 1)
        ring.put(20_000_000L, 2)
        ring.put(40_000_000L, 3)
        assertNull(ring.lookup(10L, toleranceNs = 1L))
        assertEquals(3, ring.lookup(40_000_000L))
    }
}
