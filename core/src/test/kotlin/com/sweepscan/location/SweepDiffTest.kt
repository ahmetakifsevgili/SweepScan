package com.sweepscan.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepDiffTest {

    @Test fun firstSweepHasEverythingNew() {
        val d = SweepDiff.of(null, mapOf("A" to 2, "B" to 1))
        assertTrue(d.firstSweep)
        assertEquals(setOf("A", "B"), d.added.keys)
        assertTrue(d.removed.isEmpty())
    }

    @Test fun newGoneAndChangedBoxes() {
        val d = SweepDiff.of(mapOf("A" to 2, "B" to 1, "C" to 3), mapOf("A" to 2, "B" to 2, "D" to 1))
        assertFalse(d.firstSweep)
        assertEquals(mapOf("D" to 1), d.added)
        assertEquals(mapOf("C" to 3), d.removed)
        assertEquals(mapOf("B" to (1 to 2)), d.changed)
        assertEquals(1, d.unchanged)
        assertFalse(d.isEmpty)
    }

    @Test fun sameContentsIsEmpty() {
        val d = SweepDiff.of(mapOf("A" to 2), mapOf("A" to 2))
        assertTrue(d.isEmpty)
        assertEquals(1, d.unchanged)
    }
}
