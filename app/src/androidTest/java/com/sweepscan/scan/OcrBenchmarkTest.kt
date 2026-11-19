package com.sweepscan.scan

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The OCR fallback on real phone frames: the region the app reads (barcode plus the digits under
 * it, `OcrEngine.region`) around labels whose bars ML Kit could read ("read") and could not
 * ("unread", the only case where the app runs OCR). A wrong number would be the dangerous outcome;
 * the check digit must keep it at zero. Results: adb logcat -s OcrBench
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkTest {

    @Test
    fun readsPrintedNumbers() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val ocr = OcrEngine()
        val stats = HashMap<String, IntArray>() // kind -> [n, right, wrong, none]
        var totalMs = 0.0
        val names = assets.list("ocr").orEmpty().filter { it.endsWith(".png") }
        // A number of another label in the corpus = a neighbour inside the region (the truth of
        // "unread" crops comes from the tracker and can point at the wrong one of two labels).
        val known = names.map { it.substringBefore("__") }.toSet()
        var neighbour = 0
        for (name in names) {
            val truth = name.substringBefore("__")
            val kind = name.split("__")[1]
            val bmp = assets.open("ocr/$name").use { BitmapFactory.decodeStream(it) } ?: continue
            val start = System.nanoTime()
            val got = ocr.readBitmap(bmp)
            totalMs += (System.nanoTime() - start) / 1e6
            bmp.recycle()
            val s = stats.getOrPut(kind) { IntArray(4) }
            s[0]++
            when (got) {
                null -> s[3]++
                truth -> s[1]++
                in known -> { neighbour++; s[3]++; log("  neighbour label in region: $name -> $got") }
                else -> { s[2]++; log("  WRONG $name -> $got") }
            }
        }
        ocr.close()
        for ((kind, s) in stats) {
            log(String.format(Locale.US, "%-7s n=%d  right %d (%.0f%%)  wrong %d  none %d", kind, s[0], s[1], 100.0 * s[1] / s[0], s[2], s[3]))
        }
        log(String.format(Locale.US, "mean %.0f ms per region; neighbour labels read: %d", totalMs / names.size.coerceAtLeast(1), neighbour))
        assertTrue(names.isNotEmpty())
        assertEquals("wrong numbers accepted", 0, stats.values.sumOf { it[2] })
    }

    private fun log(s: String) {
        Log.i("OcrBench", s)
        println(s)
    }
}
