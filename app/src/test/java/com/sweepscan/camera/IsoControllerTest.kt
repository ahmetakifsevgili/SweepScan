package com.sweepscan.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IsoControllerTest {

    /** Luma proportional to ISO, saturating at 255. */
    private fun sensor(iso: Int, scene: Float) = (iso * scene).coerceAtMost(255f)

    @Test
    fun `converges on a linear sensor`() {
        val c = IsoController(100, 6400, start = 800)
        val scene = 0.03f // luma per ISO unit: needs ~3700 for target 110
        repeat(20) { c.update(sensor(c.iso, scene), IsoController.TARGET_LABELS, c.iso) }
        assertTrue("iso=${c.iso}", abs(sensor(c.iso, scene) - IsoController.TARGET_LABELS) < 0.2f * IsoController.TARGET_LABELS)
    }

    @Test
    fun `dead band keeps the iso`() {
        val c = IsoController(100, 6400, start = 1600)
        assertNull(c.update(105f, 110f, 1600))
    }

    @Test
    fun `saturation halves iso and limits are respected`() {
        val c = IsoController(100, 12800, start = 1600)
        assertEquals(800, c.update(255f, 110f, 1600))
        val high = IsoController(100, 12800, start = 6400)
        assertNull(high.update(5f, 110f, 6400)) // already at the 6400 cap
    }

    @Test
    fun `stale frames are ignored`() {
        val c = IsoController(100, 6400, start = 1600)
        assertNull(c.update(20f, 110f, appliedIso = 800))
    }

    @Test
    fun `a camera that never reports the commanded iso does not freeze the loop`() {
        val c = IsoController(100, 6400, start = 1600)
        repeat(3) { assertNull(c.update(20f, 110f, appliedIso = 1250)) }
        val next = c.update(20f, 110f, appliedIso = 1250)
        assertTrue("next=$next", next != null && next > 1600)
    }
}
