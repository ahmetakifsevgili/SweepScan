package com.sweepscan.tracking

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in screen (view) pixels. Kept free of android.* so the tracker runs in plain JVM tests. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val diagonal: Float get() = hypot(width, height)

    fun translate(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    /** The box as seen after zooming by [s] about ([cx], [cy]). */
    fun scaleAbout(s: Float, cx: Float, cy: Float) =
        Box(cx + (left - cx) * s, cy + (top - cy) * s, cx + (right - cx) * s, cy + (bottom - cy) * s)

    fun centerDistance(other: Box): Float = hypot(centerX - other.centerX, centerY - other.centerY)

    fun iou(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = width * height + other.width * other.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}
