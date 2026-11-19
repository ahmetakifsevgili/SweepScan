package com.sweepscan.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.atan

class ShelfLevelTest {

    private fun deg(rad: Double) = Math.toDegrees(rad).toFloat()

    /** Elevation of a point at [h] m seen from the default eye and reach. */
    private fun seen(h: Float) = deg(atan(((h - ShelfLevel.EYE_M) / ShelfLevel.REACH_M).toDouble()))

    @Test
    fun cameraTiltFromRotationMatrix() {
        // Lying flat, screen up: the back camera looks at the floor.
        assertEquals(-90f, ShelfLevel.cameraElevationDeg(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), 0.01f)
        // Upright in portrait, looking at the horizon: device z is horizontal.
        assertEquals(0f, ShelfLevel.cameraElevationDeg(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)), 0.01f)
    }

    @Test
    fun labelAbovePictureCentreIsHigher() {
        val centre = ShelfLevel.pointElevationDeg(-10f, 1000f, 2000, 1500f)
        val above = ShelfLevel.pointElevationDeg(-10f, 400f, 2000, 1500f)
        assertEquals(-10f, centre, 0.001f)
        assertEquals(-10f + deg(atan(600.0 / 1500.0)), above, 0.01f)
    }

    @Test
    fun heightsBecomeShelves() {
        val l = ShelfLevel.LEVEL_M
        assertEquals(1, ShelfLevel.level(seen(0.2f), 4))
        assertEquals(2, ShelfLevel.level(seen(0.05f + 1.5f * l), 4))
        assertEquals(4, ShelfLevel.level(seen(0.05f + 3.5f * l), 4))
        // Above the rack or below the floor: clamped.
        assertEquals(4, ShelfLevel.level(seen(3f), 4))
        assertEquals(1, ShelfLevel.level(-80f, 4))
        // A one-shelf rack has only shelf 1.
        assertEquals(1, ShelfLevel.level(seen(1.6f), 1))
    }

    @Test
    fun calibrationFromBottomAndTopReads() {
        // A rack of 5 shelves of 40 cm read from 1.2 m away with the phone at 1.3 m.
        val reach = 1.2f
        val level = 0.4f
        fun at(h: Float) = deg(atan(((h - 1.3f) / reach).toDouble()))
        val c = ShelfLevel.Calibration.fromTwoReads(at(0.05f + 0.5f * level), at(0.05f + 4.5f * level), 5, eye = 1.3f)
        assertNotNull(c)
        assertEquals(reach, c!!.reach, 0.01f)
        assertEquals(level, c.levelM, 0.01f)
        assertEquals(3, ShelfLevel.level(at(0.05f + 2.5f * level), 5, c))
        assertNull(ShelfLevel.Calibration.fromTwoReads(10f, -10f, 5))
    }
}
