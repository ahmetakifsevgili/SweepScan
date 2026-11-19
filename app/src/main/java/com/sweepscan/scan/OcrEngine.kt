package com.sweepscan.scan

import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sweepscan.tracking.BarcodeRules
import com.sweepscan.tracking.Box
import java.io.Closeable

/**
 * Third reader: when neither ML Kit nor zxing-cpp can read the bars of a barcode they located, read
 * the number printed with it (ML Kit text recognition, on the device).
 *
 * Only an 8- or 13-digit number with a valid GS1 check digit is accepted, and only when the region
 * holds exactly one such number: a misread digit fails the check digit (one wrong digit always
 * does), and the model code or size printed on the same label are not barcode-shaped numbers. The
 * tracker still needs several agreeing reads before it counts.
 */
class OcrEngine : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** The number for the barcode at [uprightBox], or null. Blocking; analysis thread. */
    fun read(image: ImageProxy, uprightBox: Box): String? {
        val bitmap = LumaCrop.upright(image, region(uprightBox), MAX_SIDE) ?: return null
        return try {
            readBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** The accepted number in an upright crop (also used by the on-device OCR benchmark). */
    fun readBitmap(bitmap: android.graphics.Bitmap): String? {
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        } catch (e: Exception) {
            return null
        }
        return numbers(text.textBlocks.flatMap { b -> b.lines.map { it.text } })
            .filter { BarcodeRules.hasValidGs1CheckDigit(it) }
            .distinct()
            .singleOrNull()
    }

    /**
     * All text lines in [uprightRegion] (the location map reads the model line of a label with it).
     * Blocking; analysis thread.
     */
    fun lines(image: ImageProxy, uprightRegion: Box): List<String> {
        val bitmap = LumaCrop.upright(image, uprightRegion, MAX_SIDE) ?: return emptyList()
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).textBlocks.flatMap { b -> b.lines.map { it.text } }
        } catch (e: Exception) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        const val ENGINE = "ocr"
        private const val MAX_SIDE = 1024

        /**
         * Where the printed number can be. On the phone the located box is often only the scan line
         * it read, anywhere along the bars, so the region reaches a bar height above and below it
         * plus the digits under the bars (heights relative to the barcode's width).
         */
        /**
         * Where the model line ("0169 051  KAHVE  36") is: above the bars, over the whole label
         * width (the size sits in a box at the right end). Relative to the barcode's width.
         */
        fun modelRegion(box: Box): Box {
            val w = box.width
            return Box(box.left - 0.15f * w, box.centerY - 0.65f * w, box.right + 0.35f * w, box.centerY - 0.04f * w)
        }

        fun region(box: Box): Box {
            val w = box.width
            return Box(box.left - 0.12f * w, box.centerY - 0.45f * w, box.right + 0.12f * w, box.centerY + 0.65f * w)
        }

        /**
         * Candidate numbers in the recognised lines: 13-digit runs, also when the recogniser split
         * them into groups; 8-digit runs only when written in one piece, because the model code on
         * the same label ("0159 2041") becomes 8 digits once its space is dropped.
         */
        fun numbers(lines: List<String>): List<String> = lines.flatMap { line ->
            val joined = line.replace(Regex("[\\s.\\-]"), "")
            val thirteen = Regex("\\d+").findAll(joined).map { it.value }.filter { it.length == 13 }
            val eight = Regex("\\d+").findAll(line).map { it.value }.filter { it.length == 8 }
            (thirteen + eight).toList()
        }
    }
}
