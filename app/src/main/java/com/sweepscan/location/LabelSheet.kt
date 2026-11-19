package com.sweepscan.location

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Rack column labels as an A4 PDF, printed from the phone (Android print service, any Wi-Fi
 * printer) or shared as a file. Each label: the QR ("LOC:B-3-07", error correction Q, 4-module
 * quiet zone, drawn as vector squares so it stays sharp), the code in large letters and the depot
 * and row in words. Print at 100 % ("actual size"), never "fit to page".
 */
object LabelSheet {

    private const val PT_PER_MM = 72f / 25.4f
    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN_MM = 8f

    /** QR side including quiet zone, mm. 75 mm read from 2.5–3.5 m in the simulation. */
    const val DEFAULT_QR_MM = 75f

    /** Writes the labels for [codes] to [out]; returns the number of pages. */
    fun write(layout: SiteLayout, codes: List<LocationCode>, out: File, qrMm: Float = DEFAULT_QR_MM): Int {
        val doc = PdfDocument()
        val qrPt = qrMm * PT_PER_MM
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = qrPt * 0.2f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; color = Color.BLACK }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = qrPt * 0.065f; textAlign = Paint.Align.CENTER; color = Color.BLACK }
        val cut = Paint().apply { style = Paint.Style.STROKE; color = Color.LTGRAY; strokeWidth = 0.5f }
        val black = Paint().apply { color = Color.BLACK }

        val cellW = qrPt + 6 * PT_PER_MM
        val cellH = qrPt + big.textSize * 1.15f + small.textSize * 1.6f + 6 * PT_PER_MM
        val margin = MARGIN_MM * PT_PER_MM
        val perRow = ((A4_W - 2 * margin) / cellW).toInt().coerceAtLeast(1)
        val rowsPerPage = ((A4_H - 2 * margin) / cellH).toInt().coerceAtLeast(1)
        val perPage = perRow * rowsPerPage
        val left = (A4_W - perRow * cellW) / 2
        val top = (A4_H - rowsPerPage * cellH) / 2

        val pages = codes.chunked(perPage)
        pages.forEachIndexed { pageIndex, pageCodes ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageIndex + 1).create())
            val c = page.canvas
            pageCodes.forEachIndexed { i, code ->
                val x = left + (i % perRow) * cellW
                val y = top + (i / perRow) * cellH
                c.drawRect(x, y, x + cellW, y + cellH, cut)
                val qx = x + (cellW - qrPt) / 2
                val qy = y + 2 * PT_PER_MM
                drawQr(c, code.payload, qx, qy, qrPt, black)
                val textY = qy + qrPt + big.textSize * 0.95f
                c.drawText(layout.short(code), x + cellW / 2, textY, big)
                val where = if (layout.wholeRow(code)) layout.describe(code) + " · sol baş" else layout.describe(code)
                c.drawText(where, x + cellW / 2, textY + small.textSize * 1.5f, small)
            }
            doc.finishPage(page)
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return pages.size
    }

    private fun drawQr(c: android.graphics.Canvas, data: String, x: Float, y: Float, side: Float, paint: Paint) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.MARGIN to 4)
        val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = m.width
        val module = side / n
        for (j in 0 until n) for (i in 0 until n) {
            if (m.get(i, j)) c.drawRect(x + i * module, y + j * module, x + (i + 1) * module + 0.05f, y + (j + 1) * module + 0.05f, paint)
        }
    }

    /** Opens the system print dialog for [pdf] (A4, portrait). */
    fun print(context: Context, pdf: File, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        manager.print(jobName, PdfAdapter(pdf, jobName), attributes)
    }

    private class PdfAdapter(private val pdf: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(pdf).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
