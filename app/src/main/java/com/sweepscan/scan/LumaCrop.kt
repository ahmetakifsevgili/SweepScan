package com.sweepscan.scan

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.sweepscan.tracking.Box
import kotlin.math.max
import kotlin.math.roundToInt

/** Grey crops of the camera frame's luma plane, turned upright, for the secondary models. */
object LumaCrop {

    /**
     * The region [upright] (upright image coordinates, clipped to the frame) as an upright grey
     * bitmap, sampled down so its longer side is at most [maxSide]. Null when too small.
     */
    fun upright(image: ImageProxy, upright: Box, maxSide: Int): Bitmap? {
        val rotation = image.imageInfo.rotationDegrees
        val (uw, uh) = ImageGeometry.uprightSize(image.width, image.height, rotation)
        val clipped = Box(
            upright.left.coerceIn(0f, uw.toFloat()), upright.top.coerceIn(0f, uh.toFloat()),
            upright.right.coerceIn(0f, uw.toFloat()), upright.bottom.coerceIn(0f, uh.toFloat()),
        )
        if (clipped.width < 16f || clipped.height < 16f) return null
        val b = ImageGeometry.boxToBuffer(clipped, rotation, image.width, image.height)
        val left = b.left.roundToInt().coerceIn(0, image.width - 1)
        val top = b.top.roundToInt().coerceIn(0, image.height - 1)
        val w = (b.right.roundToInt() - left).coerceIn(1, image.width - left)
        val h = (b.bottom.roundToInt() - top).coerceIn(1, image.height - top)
        val step = max(1, (max(w, h) + maxSide - 1) / maxSide)
        val ow = w / step
        val oh = h / step
        if (ow < 8 || oh < 8) return null
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val stride = plane.rowStride
        val pixels = IntArray(ow * oh)
        for (y in 0 until oh) {
            val row = (top + y * step) * stride + left
            for (x in 0 until ow) {
                val v = buffer.get(row + x * step).toInt() and 0xff
                pixels[y * ow + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val raw = Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation % 360 == 0) return raw
        val turned = Bitmap.createBitmap(raw, 0, 0, ow, oh, Matrix().apply { postRotate(rotation.toFloat()) }, false)
        if (turned !== raw) raw.recycle()
        return turned
    }
}
