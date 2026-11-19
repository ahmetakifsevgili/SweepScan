package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A whole delivery at phone speed: 300 boxes in 30 stacks, swept at 25 frames per second with one
 * box across the picture (how the app is used), with the decoder behaving badly on purpose.
 *
 * The emulator recordings (see ReplayTest) exercise the real camera and decoders but only at a few
 * frames per second; this covers the frame rate of the real phone, where the tracker's timeouts
 * span a dozen frames rather than one.
 */
class WarehouseSimulationTest {

    private class Box3(val value: String, val x: Float, val y: Float)

    private val imageW = 1080
    private val imageH = 1920
    private val faceW = 1000f          // one box end fills the width of the picture
    private val faceH = 600f
    private val barW = 300f            // the barcode is about 30 % of the box end, 6:1
    private val barH = 50f
    private val stacks = 30
    private val perStack = 10

    /** Stacks of boxes; about a third of the boxes carry the same barcode as another box. */
    private fun warehouse(rng: Random): List<Box3> {
        val values = ArrayList<String>()
        while (values.size < stacks * perStack) {
            val v = (1..13).joinToString("") { rng.nextInt(10).toString() }
            repeat(listOf(1, 1, 1, 1, 2, 2, 3)[rng.nextInt(7)]) { values += v }
        }
        // Most twins stay stacked together; some end up in another stack.
        repeat(40) { values[rng.nextInt(values.size)] = values[rng.nextInt(values.size)] }
        return (0 until stacks).flatMap { s ->
            (0 until perStack).map { r ->
                val jitter = (rng.nextFloat() - 0.5f) * 60f                  // stacks are not straight
                Box3(values[s * perStack + r], s * (faceW + 80f) + 680f + jitter, r * faceH + 380f)
            }
        }
    }

    private class Noise(val miss: Float, val locatedOnly: Float, val misread: Float, val hiccup: Float)

    /** Sweeps every stack top to bottom. [resetBetweenStacks] = the user pauses between stacks. */
    private fun sweep(boxes: List<Box3>, noise: Noise, seed: Int, resetBetweenStacks: Boolean, withFlow: Boolean): List<String> {
        val rng = Random(seed)
        val tracker = BarcodeTracker()
        val counted = ArrayList<String>()
        var t = 0L
        var camX = 0f
        var camY = -imageH.toFloat()
        var prevX = camX
        var prevY = camY

        fun frame() {
            val speed = hypot(camX - prevX, camY - prevY)
            // Moving this fast the picture is smeared: nothing is found, and above a point optical flow gives up too.
            val detections = if (speed > BLUR_PX_PER_FRAME) emptyList() else boxes.mapNotNull { b ->
                val left = b.x - barW / 2 - camX
                val top = b.y - barH / 2 - camY
                val box = Box(left, top, left + barW, top + barH)
                val visible = Box(box.left.coerceAtLeast(0f), box.top.coerceAtLeast(0f), box.right.coerceAtMost(imageW.toFloat()), box.bottom.coerceAtMost(imageH.toFloat()))
                when {
                    visible.width <= 0f || visible.height <= 0f -> null
                    // Cut by the edge of the picture: found, and sometimes "read" as a shorter number.
                    visible.width < barW || visible.height < barH ->
                        if (visible.width > barW / 3) Detection(if (rng.nextFloat() < 0.3f) b.value.take(8 + rng.nextInt(4)) else null, visible) else null
                    rng.nextFloat() < noise.miss -> null
                    rng.nextFloat() < noise.locatedOnly -> Detection(null, box)
                    rng.nextFloat() < noise.misread -> Detection(b.value.replaceRange(5, 6, ((b.value[5] - '0' + 1) % 10).toString()), box)
                    else -> Detection(b.value, box.translate((rng.nextFloat() - 0.5f) * 4f, (rng.nextFloat() - 0.5f) * 4f))
                }
            }
            val flow = if (withFlow && speed <= FLOW_LIMIT_PX_PER_FRAME) {
                Motion(prevX - camX + (rng.nextFloat() - 0.5f), prevY - camY + (rng.nextFloat() - 0.5f), 0.9f)
            } else {
                null
            }
            prevX = camX
            prevY = camY
            counted += tracker.update(detections, t, flow, imageW, imageH).newlyCounted.map { it.value }
            t += if (rng.nextFloat() < noise.hiccup) 120 else 40            // 25 fps with the odd dropped frame
        }

        for (s in 0 until stacks) {
            val column = boxes.subList(s * perStack, (s + 1) * perStack)
            val targetX = column.map { it.x }.average().toFloat() - imageW / 2f
            val top = column.first().y - imageH * 0.75f
            val bottom = column.last().y - imageH * 0.25f
            // Swing the phone from the foot of the last stack to the top of this one in a second and a half.
            val steps = 38
            val fromX = camX
            val fromY = camY
            for (i in 1..steps) {
                camX = fromX + (targetX - fromX) * i / steps
                camY = fromY + (top - imageH - fromY) * i / steps
                frame()
            }
            repeat(10) { frame() }                                            // settle
            if (resetBetweenStacks) tracker.reset()
            // Down the stack at about one box per second.
            while (camY < bottom + imageH) {
                camY += 24f
                camX = targetX + (rng.nextFloat() - 0.5f) * 6f                // hand shake
                frame()
            }
        }
        repeat(150) { frame() }
        return counted
    }

    private fun check(noise: Noise, resetBetweenStacks: Boolean, withFlow: Boolean) {
        val boxes = warehouse(Random(11))
        val expected = boxes.groupingBy { it.value }.eachCount()
        val counted = sweep(boxes, noise, seed = 5, resetBetweenStacks, withFlow).groupingBy { it }.eachCount()
        val over = counted.entries.sumOf { (k, n) -> (n - (expected[k] ?: 0)).coerceAtLeast(0) }
        val under = expected.entries.sumOf { (k, n) -> (n - (counted[k] ?: 0)).coerceAtLeast(0) }
        if (over + under > 0) {
            val wrong = (counted.keys + expected.keys).filter { counted[it] != expected[it] }
            println("over=$over under=$under: " + wrong.take(12).joinToString { "$it counted=${counted[it] ?: 0} expected=${expected[it] ?: 0}" })
        }
        assertEquals("boxes counted twice or misread", 0, over)
        assertEquals("boxes not counted", 0, under)
        assertEquals(boxes.size, counted.values.sum())
    }

    private val clean = Noise(miss = 0f, locatedOnly = 0f, misread = 0f, hiccup = 0f)
    private val rough = Noise(miss = 0.15f, locatedOnly = 0.10f, misread = 0.01f, hiccup = 0.05f)

    @Test
    fun `300 boxes, every frame read`() = check(clean, resetBetweenStacks = false, withFlow = true)

    @Test
    fun `300 boxes, a quarter of the frames missed or unreadable, some misread`() = check(rough, resetBetweenStacks = false, withFlow = true)

    @Test
    fun `300 boxes, pausing between stacks`() = check(rough, resetBetweenStacks = true, withFlow = true)

    @Test
    fun `300 boxes, without optical flow`() = check(rough, resetBetweenStacks = false, withFlow = false)

    @Test
    fun `the warehouse really has identical boxes`() {
        val expected = warehouse(Random(11)).groupingBy { it.value }.eachCount()
        val twins = expected.values.filter { it > 1 }.sum()
        assertEquals("twins=$twins", true, twins in 120..260)
    }

    private companion object {
        const val BLUR_PX_PER_FRAME = 60f
        const val FLOW_LIMIT_PX_PER_FRAME = 150f
    }
}
