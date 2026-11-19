package com.sweepscan.scan

import com.sweepscan.tracking.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Conversions between the camera buffer (as delivered by the sensor) and the *upright* image
 * (buffer rotated clockwise by `rotationDegrees`, which is what ML Kit reports boxes in and what
 * the tracker works in).
 */
object ImageGeometry {

    fun uprightSize(bufferWidth: Int, bufferHeight: Int, rotation: Int): Pair<Int, Int> =
        if (rotation % 180 == 0) Pair(bufferWidth, bufferHeight) else Pair(bufferHeight, bufferWidth)

    /** Upright point → buffer point. */
    fun toBuffer(u: Float, v: Float, rotation: Int, bufferWidth: Int, bufferHeight: Int): Pair<Float, Float> =
        when (normalize(rotation)) {
            90 -> Pair(v, bufferHeight - u)
            180 -> Pair(bufferWidth - u, bufferHeight - v)
            270 -> Pair(bufferWidth - v, u)
            else -> Pair(u, v)
        }

    /** Buffer point → upright point (inverse of [toBuffer]). */
    fun toUpright(x: Float, y: Float, rotation: Int, bufferWidth: Int, bufferHeight: Int): Pair<Float, Float> =
        when (normalize(rotation)) {
            90 -> Pair(bufferHeight - y, x)
            180 -> Pair(bufferWidth - x, bufferHeight - y)
            270 -> Pair(y, bufferWidth - x)
            else -> Pair(x, y)
        }

    fun boxToBuffer(box: Box, rotation: Int, bufferWidth: Int, bufferHeight: Int): Box {
        val (x1, y1) = toBuffer(box.left, box.top, rotation, bufferWidth, bufferHeight)
        val (x2, y2) = toBuffer(box.right, box.bottom, rotation, bufferWidth, bufferHeight)
        return Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** A displacement measured in the buffer, expressed in upright coordinates. */
    fun vectorToUpright(dx: Float, dy: Float, rotation: Int): Pair<Float, Float> =
        when (normalize(rotation)) {
            90 -> Pair(-dy, dx)
            180 -> Pair(-dx, -dy)
            270 -> Pair(dy, -dx)
            else -> Pair(dx, dy)
        }

    /** Grows [box] by the given fractions of its size and clamps it to the image. */
    fun pad(box: Box, fracX: Float, fracY: Float, width: Int, height: Int): Box = Box(
        (box.left - box.width * fracX).coerceAtLeast(0f),
        (box.top - box.height * fracY).coerceAtLeast(0f),
        (box.right + box.width * fracX).coerceAtMost(width.toFloat()),
        (box.bottom + box.height * fracY).coerceAtMost(height.toFloat()),
    )

    private fun normalize(rotation: Int) = ((rotation % 360) + 360) % 360
}
