package com.sweepscan.scan

import androidx.camera.core.ImageProxy
import com.sweepscan.tracking.Box
import kotlin.math.abs

/** Cheap statistics on the Y (luma) plane, sampled on a sparse grid. */
object LumaStats {

    /** Mean luma inside [uprightBoxes], or in a centre band when there are none. */
    fun meanLuma(image: ImageProxy, uprightBoxes: List<Box>): Float {
        val rotation = image.imageInfo.rotationDegrees
        val w = image.width
        val h = image.height
        val regions = if (uprightBoxes.isNotEmpty()) {
            uprightBoxes.map { ImageGeometry.boxToBuffer(it, rotation, w, h) }
        } else {
            listOf(Box(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f))
        }
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        var sum = 0L
        var n = 0
        for (r in regions) {
            val x0 = r.left.toInt().coerceIn(0, w - 1)
            val x1 = r.right.toInt().coerceIn(0, w - 1)
            val y0 = r.top.toInt().coerceIn(0, h - 1)
            val y1 = r.bottom.toInt().coerceIn(0, h - 1)
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    sum += buffer.get(y * stride + x).toInt() and 0xFF
                    n++
                    x += STEP
                }
                y += STEP
            }
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    /**
     * Mean absolute luma gradient across the bars inside an upright box: a sharpness score for
     * comparing exposure modes and sweep directions (bars are vertical in the upright image, so
     * "across" is upright x).
     */
    fun sharpness(image: ImageProxy, uprightBox: Box): Float {
        val rotation = image.imageInfo.rotationDegrees
        val w = image.width
        val h = image.height
        val b = ImageGeometry.boxToBuffer(uprightBox, rotation, w, h)
        // Upright x maps to buffer y for 90/270 rotations.
        val alongY = rotation % 180 != 0
        val plane = image.planes[0]
        val buffer = plane.buffer
        val stride = plane.rowStride
        val x0 = b.left.toInt().coerceIn(0, w - 2)
        val x1 = b.right.toInt().coerceIn(0, w - 2)
        val y0 = b.top.toInt().coerceIn(0, h - 2)
        val y1 = b.bottom.toInt().coerceIn(0, h - 2)
        var sum = 0L
        var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val a = buffer.get(y * stride + x).toInt() and 0xFF
                val c = if (alongY) buffer.get((y + 1) * stride + x).toInt() and 0xFF else buffer.get(y * stride + x + 1).toInt() and 0xFF
                sum += abs(a - c)
                n++
                x += if (alongY) 2 else 1
            }
            y += if (alongY) 1 else 2
        }
        return if (n == 0) 0f else sum.toFloat() / n
    }

    private const val STEP = 4
}
