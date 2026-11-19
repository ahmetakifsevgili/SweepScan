package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Down a column and straight back up, with an optical-flow estimate that drifts like the phone's
 * (a small bias every frame). By the time the camera is back at the top the remembered positions
 * are off by more than the strict gate; neighbours must carry the re-identification, without
 * merging identical boxes or a second column with the same sequence.
 */
class NeighbourReidTest {

    private val w = 1440
    private val h = 2560
    private val labelW = 300f
    private val labelH = 30f
    /** Box spacing in a column: about 2.5 barcode widths, as measured on the phone. */
    private val pitch = 750f

    private class Label(val value: String, val x: Float, val y: Float)

    private fun column(x: Float, values: List<String>) = values.mapIndexed { i, v -> Label(v, x, i * pitch) }

    /**
     * The camera centre moves through [path] (scene y per frame); x stays at [camX]. Flow reports the
     * true shift plus [biasPerFrame] px, so the integrated offset drifts.
     */
    private fun sweep(labels: List<Label>, camX: Float, path: List<Float>, config: TrackerConfig, biasPerFrame: Float): List<String> {
        val tracker = BarcodeTracker(config)
        val counted = ArrayList<String>()
        var t = 0L
        var prevY = path.first()
        for (camY in path) {
            val dets = labels.mapNotNull { l ->
                val cx = l.x - camX + w / 2f
                val cy = l.y - camY + h / 2f
                if (cx - labelW / 2 < 0 || cx + labelW / 2 > w || cy - labelH / 2 < 0 || cy + labelH / 2 > h) null
                else Detection(l.value, Box(cx - labelW / 2, cy - labelH / 2, cx + labelW / 2, cy + labelH / 2))
            }
            val trueShift = -(camY - prevY)
            val motion = Motion(0f, trueShift + biasPerFrame, 1f)
            counted += tracker.update(dets, t, motion, w, h).newlyCounted.map { it.value }
            prevY = camY
            t += 40
        }
        return counted
    }

    /** Top to bottom of [rows] boxes and back, at [step] px per frame. */
    private fun downAndUp(rows: Int, step: Float = 25f): List<Float> {
        val top = 0f
        val bottom = (rows - 1) * pitch
        val down = generateSequence(top) { it + step }.takeWhile { it <= bottom }.toList()
        return down + down.reversed()
    }

    private val sixDistinct = listOf("1000000000009", "2000000000008", "3000000000007", "4000000000006", "5000000000005", "6000000000004")
    private val withTwins = listOf("1000000000009", "7000000000003", "7000000000003", "2000000000008", "8000000000002", "8000000000002")

    @Test
    fun goingBackUpADriftingColumnCountsEachBoxOnce() {
        val counted = sweep(column(0f, sixDistinct), 0f, downAndUp(6), TrackerConfig(), biasPerFrame = 0.9f)
        assertEquals(sixDistinct.sorted(), counted.sorted())
    }

    @Test
    fun withoutNeighboursTheDriftCountsBoxesAgain() {
        // Control: the 0.3.11 behaviour on the same sweep.
        val counted = sweep(column(0f, sixDistinct), 0f, downAndUp(6), TrackerConfig(neighbourReid = false), biasPerFrame = 0.9f)
        assertEquals(true, counted.size > sixDistinct.size)
    }

    @Test
    fun identicalBoxesStackedTogetherStayTwoBoxes() {
        val counted = sweep(column(0f, withTwins), 0f, downAndUp(6), TrackerConfig(), biasPerFrame = 0.9f)
        assertEquals(withTwins.sorted(), counted.sorted())
    }

    @Test
    fun aSecondColumnWithTheSameSequenceIsCountedToo() {
        // Two columns, same products in the same order, 6 barcode widths apart: sweep down the first,
        // move across, sweep down the second. Nothing may be absorbed.
        val labels = column(0f, sixDistinct) + column(6 * labelW, sixDistinct)
        val tracker = BarcodeTracker(TrackerConfig())
        val counted = ArrayList<String>()
        var t = 0L
        fun frame(camX: Float, camY: Float, dx: Float, dy: Float) {
            val dets = labels.mapNotNull { l ->
                val cx = l.x - camX + w / 2f
                val cy = l.y - camY + h / 2f
                if (cx - labelW / 2 < 0 || cx + labelW / 2 > w || cy - labelH / 2 < 0 || cy + labelH / 2 > h) null
                else Detection(l.value, Box(cx - labelW / 2, cy - labelH / 2, cx + labelW / 2, cy + labelH / 2))
            }
            counted += tracker.update(dets, t, Motion(dx, dy + 0.9f, 1f), w, h).newlyCounted.map { it.value }
            t += 40
        }
        var x = 0f
        var y = 0f
        val bottom = 5 * pitch
        while (y < bottom) { frame(x, y, 0f, -25f); y += 25f }
        while (x < 6 * labelW) { frame(x, y, -25f, 0f); x += 25f }
        while (y > 0f) { frame(x, y, 0f, 25f); y -= 25f }
        assertEquals((sixDistinct + sixDistinct).sorted(), counted.sorted())
    }

    @Test
    fun gyroRotationIsIntegratedAcrossTheWholeInterval() {
        val window = com.sweepscan.sensors.GyroWindow()
        // 1 rad/s about x, samples every 5 ms from t = 0.
        for (i in 0..40) window.add(i * 5_000_000L, 1f, 0f, 0f)
        val rot = window.rotationBetween(12_000_000L, 37_000_000L)!!
        assertEquals(0.025f, rot[0], 1e-4f)
        assertEquals(0f, rot[1], 1e-6f)
    }
}
