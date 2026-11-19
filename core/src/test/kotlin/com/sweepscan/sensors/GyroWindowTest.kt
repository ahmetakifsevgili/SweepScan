package com.sweepscan.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GyroWindowTest {

    @Test
    fun `mean, max, yaw and integrated rotation over a window`() {
        val w = GyroWindow(16)
        val rad = (Math.PI / 180).toFloat()
        // 10 samples, 10 ms apart, turning about Y at 30 °/s.
        for (i in 0 until 10) w.add(i * 10_000_000L, 0f, 30f * rad, 0f)
        val s = w.summary(0, 90_000_000L)!!
        assertEquals(10, s.samples)
        assertEquals(30f, s.meanDps, 0.01f)
        assertEquals(30f, s.meanYawDps, 0.01f)
        assertEquals(2.7f, s.rotationDeg[1], 0.01f) // 9 intervals × 10 ms × 30 °/s
    }

    @Test
    fun `window outside the samples is empty`() {
        val w = GyroWindow(4)
        w.add(1_000L, 1f, 0f, 0f)
        assertNull(w.summary(2_000L, 3_000L))
    }
}
