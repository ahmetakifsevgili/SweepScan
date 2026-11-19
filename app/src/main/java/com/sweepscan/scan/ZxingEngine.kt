package com.sweepscan.scan

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.sweepscan.tracking.Box
import zxingcpp.BarcodeReader

/**
 * Result of one crop decode: [found] = number of distinct values in the crop (>1 = ambiguous),
 * [lines] = how many scan lines agreed on [value] (a confidence signal; 0 when nothing was read).
 */
data class ZxingOutcome(val value: String?, val format: String?, val found: Int, val lines: Int = 0)

/**
 * Second decoder (zxing-cpp), run only on the regions ML Kit located but could not decode.
 * Small, blurry or slightly damaged labels are where the two engines differ most.
 */
class ZxingEngine {

    private val reader = newReader()

    /**
     * Tries to decode the barcode inside [uprightBox] (upright image coordinates).
     * [ZxingOutcome.value] is null when nothing, or more than one different value, is found in the crop.
     */
    fun decodeRegion(image: ImageProxy, uprightBox: Box): ZxingOutcome {
        val rotation = image.imageInfo.rotationDegrees
        val (uw, uh) = ImageGeometry.uprightSize(image.width, image.height, rotation)
        // Generous padding along the bars: quiet zones matter for 1D decoders.
        val padded = ImageGeometry.pad(uprightBox, 0.25f, 0.6f, uw, uh)
        val b = ImageGeometry.boxToBuffer(padded, rotation, image.width, image.height)
        val crop = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        if (crop.width() < 16 || crop.height() < 16) return ZxingOutcome(null, null, 0)

        val previous = image.cropRect
        image.setCropRect(crop)
        val results = try {
            reader.read(image).filter { !it.text?.trim().isNullOrEmpty() }
        } finally {
            image.setCropRect(previous)
        }
        val values = results.map { it.text!!.trim() }.toSet()
        val single = values.singleOrNull()
        val best = results.filter { it.text?.trim() == single }.maxByOrNull { it.lineCount }
        return ZxingOutcome(single, best?.format?.name, values.size, best?.lineCount ?: 0)
    }

    companion object {
        const val ENGINE = "zxing"

        /** Shared with the on-device decode benchmark, so it measures exactly what the app runs. */
        fun newReader() = BarcodeReader().apply {
            options.formats = setOf(
                BarcodeReader.Format.CODE_128, BarcodeReader.Format.EAN_13,
                BarcodeReader.Format.EAN_8, BarcodeReader.Format.ITF,
            )
            options.tryHarder = true
            options.tryRotate = false
            options.tryDownscale = false
        }
    }
}
