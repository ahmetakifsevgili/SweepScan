package com.sweepscan.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.sweepscan.tracking.Box
import com.sweepscan.tracking.Detection

/**
 * Primary detector. Besides decoded barcodes it also returns barcodes it *located* but could not
 * read (`enableAllPotentialBarcodes`); those keep tracks alive and are handed to [ZxingEngine].
 * Boxes are in upright image coordinates.
 */
class MlKitEngine {

    private val scanner = BarcodeScanning.getClient(options())

    /** Blocking; call from the analysis thread. */
    @OptIn(ExperimentalGetImage::class)
    fun detect(image: ImageProxy): List<Detection> {
        val media = image.image ?: return emptyList()
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        val barcodes = Tasks.await(scanner.process(input))
        return barcodes.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            val value = b.rawValue?.trim()?.takeIf { it.isNotEmpty() }
            val corners = b.cornerPoints?.takeIf { it.size == 4 }
                ?.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }?.toFloatArray()
            Detection(
                value,
                Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()),
                if (value != null) ENGINE else "",
                // A QR is marked even when unread, so the pipeline keeps location labels out of tracking.
                if (value != null || b.format == Barcode.FORMAT_QR_CODE) formatName(b.format) else "",
                corners,
            )
        }
    }

    fun close() = scanner.close()

    companion object {
        const val ENGINE = "mlkit"
        const val QR = "QR_CODE"

        /** Shared with the on-device decode benchmark, so it measures exactly what the app runs. */
        fun options(): BarcodeScannerOptions = BarcodeScannerOptions.Builder()
            // The shoe labels are Code 128; the others cover factory barcodes on other brands. QR is
            // only for the rack column labels of the location map ("LOC:B-3-07").
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE,
            )
            .enableAllPotentialBarcodes()
            .build()

        fun formatName(format: Int): String = when (format) {
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_QR_CODE -> QR
            else -> "F$format"
        }
    }
}
