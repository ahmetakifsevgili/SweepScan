package com.sweepscan.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoZoomTest {

    /** Feeds one frame every 33 ms for [ms]; returns the first zoom change and its time. */
    private fun run(
        zoom: AutoZoom,
        fromMs: Long,
        ms: Long,
        current: Float,
        length: Float?,
        busy: (Long) -> Boolean = { false },
    ): Pair<Long, Float>? {
        var t = fromMs
        while (t < fromMs + ms) {
            val change = zoom.update(t, listOfNotNull(length), busy(t), current)
            if (change != null) return t to change
            t += 33
        }
        return null
    }

    @Test
    fun smallBarcodesZoomInOneStepAfterTheHoldTime() {
        val zoom = AutoZoom()
        val (at, to) = run(zoom, 0, 3000, current = 1f, length = 150f)!!
        assertEquals(1.5f, to)
        // 5 samples to start judging, then 800 ms below the threshold.
        assertEquals(true, at in 900L..1100L)
    }

    @Test
    fun largeBarcodesZoomOut() {
        assertEquals(2f, run(AutoZoom(), 0, 3000, current = 3f, length = 900f)?.second)
    }

    @Test
    fun goodSizeDoesNothing() {
        assertNull(run(AutoZoom(), 0, 10_000, current = 1.5f, length = 420f))
    }

    @Test
    fun noBarcodesDoNothing() {
        assertNull(run(AutoZoom(), 0, 10_000, current = 1f, length = null))
    }

    @Test
    fun staysWithinTheSteps() {
        assertNull(run(AutoZoom(), 0, 5000, current = 3f, length = 100f))
        assertNull(run(AutoZoom(), 0, 5000, current = 1f, length = 2000f))
    }

    @Test
    fun waitsWhileATrackIsOnScreen() {
        val zoom = AutoZoom()
        // Busy for the first 2.5 s: the step it wanted at ~1 s happens only once the screen is clear.
        val (at, to) = run(zoom, 0, 5000, current = 1f, length = 150f, busy = { it < 2500 })!!
        assertEquals(1.5f, to)
        assertEquals(true, at >= 2500)
    }

    @Test
    fun coolsDownAfterAChangeThenStepsAgain() {
        val zoom = AutoZoom()
        val (first, z1) = run(zoom, 0, 3000, current = 1f, length = 120f)!!
        assertEquals(1.5f, z1)
        // Still too small after the step: the next one comes no sooner than the cooldown.
        val (second, z2) = run(zoom, first + 33, 5000, current = z1, length = 180f)!!
        assertEquals(2f, z2)
        assertEquals(true, second - first >= 1500)
    }

    @Test
    fun briefSizeDipsAreIgnored() {
        val zoom = AutoZoom()
        var t = 0L
        var change: Float? = null
        // 400 px with a 300 ms dip to 150 px every second: the median never stays low long enough.
        while (t < 8000) {
            val length = if (t % 1000 < 300) 150f else 400f
            change = change ?: zoom.update(t, listOf(length), false, 1.5f)
            t += 33
        }
        assertNull(change)
    }
}
