package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A zoom step rescales every box about the image centre. The tracker must carry what it remembers
 * through it (applyZoom); forgetting would count a label in view, or one that comes back, again.
 */
class ZoomTrackingTest {

    private val w = 1000
    private val h = 1000
    private val cx = w / 2f
    private val cy = h / 2f
    private val label = Box(200f, 200f, 400f, 260f)
    private val value = "5095039545023"

    private fun frames(tracker: BarcodeTracker, fromMs: Long, n: Int, box: Box?): Pair<Long, Int> {
        var t = fromMs
        var counted = 0
        repeat(n) {
            val dets = listOfNotNull(box?.let { Detection(value, it) })
            counted += tracker.update(dets, t, null, w, h).newlyCounted.size
            t += 40
        }
        return t to counted
    }

    private fun run(zoomAware: Boolean, gapFrames: Int): Int {
        // Without zoom handling, also without the neighbour/drift allowance of 0.3.12, which would
        // otherwise mask the missing rescale in this synthetic case.
        val tracker = BarcodeTracker(if (zoomAware) TrackerConfig() else TrackerConfig(neighbourReid = false))
        var (t, counted) = frames(tracker, 0, 10, label)
        // Optionally out of sight (moved on, or too blurred to locate) while the zoom happens.
        if (gapFrames > 0) {
            val (t2, c2) = frames(tracker, t, gapFrames, null)
            t = t2
            counted += c2
        }
        if (zoomAware) tracker.applyZoom(1.5f, cx, cy)
        val (_, c3) = frames(tracker, t, 10, label.scaleAbout(1.5f, cx, cy))
        return counted + c3
    }

    @Test
    fun labelInViewAcrossAZoomIsCountedOnce() {
        assertEquals(1, run(zoomAware = true, gapFrames = 0))
    }

    @Test
    fun labelThatReappearsAfterAZoomIsReidentified() {
        // 30 frames = 1.2 s: longer than the 500 ms lost timeout, inside the re-identification window.
        assertEquals(1, run(zoomAware = true, gapFrames = 30))
    }

    @Test
    fun withoutRescalingTheSameLabelIsCountedTwice() {
        // Documents why applyZoom exists: the scaled box falls outside every gate.
        assertEquals(2, run(zoomAware = false, gapFrames = 30))
    }

    @Test
    fun scaleAboutKeepsTheCentreFixed() {
        val b = Box(400f, 450f, 600f, 550f).scaleAbout(2f, 500f, 500f)
        assertEquals(Box(300f, 400f, 700f, 600f), b)
    }
}
