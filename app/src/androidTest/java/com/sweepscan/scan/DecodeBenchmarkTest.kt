package com.sweepscan.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.sweepscan.tracking.BarcodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Decode benchmark on real shoe-box labels, run on a phone or emulator:
 *
 *     gradlew :app:connectedDebugAndroidTest      (results: adb logcat -s DecodeBench)
 *
 * The crops (`assets/labels`, made by `tools/make_label_corpus.py` from phone recordings) go
 * through the app's own ML Kit and zxing-cpp settings, clean and with the degradations a handheld
 * sweep adds: motion blur across the bars (long exposure), blur along the bars, defocus, distance
 * (downscaling) and sensor noise (high ISO). The "settings" rows model the exposure choices the
 * phone recordings compared: auto (~20 ms), 4 ms and 2 ms at the same hand speed.
 *
 * What the emulator's virtual camera cannot show (blur, focus, noise) is added here on purpose.
 */
@RunWith(AndroidJUnit4::class)
class DecodeBenchmarkTest {

    private class Label(val value: String, val kind: String, val name: String, val w: Int, val h: Int, val luma: FloatArray)

    private class Condition(val name: String, val transform: (Label) -> Label)

    private val scanner = BarcodeScanning.getClient(MlKitEngine.options())
    private val zxing = ZxingEngine.newReader()
    private var knownValues = emptySet<String>()
    private var realMisreads = 0

    @Test
    fun decodeRatesUnderHandheldDegradations() {
        val labels = loadLabels()
        assertTrue("no label crops in assets/labels", labels.isNotEmpty())
        log("labels: ${labels.size} (${labels.count { it.kind == "read" }} read by the app, " +
            "${labels.count { it.kind == "unread" }} located but unread), ${labels.map { it.value }.toSet().size} values")
        log(String.format(Locale.US, "%-26s %6s %6s %6s %6s %8s", "condition", "n", "mlkit", "zxing", "either", "misread"))

        knownValues = labels.map { it.value }.toSet()
        var cleanReadSet = 0.0
        for (c in conditions()) {
            for (kind in listOf("read", "unread")) {
                val set = labels.filter { it.kind == kind }
                if (set.isEmpty()) continue
                var ml = 0
                var zx = 0
                var either = 0
                var wrong = 0
                for (label in set) {
                    val img = c.transform(label)
                    val bmp = toBitmap(img)
                    val mlValues = mlkit(bmp)
                    val zxValues = zxing(bmp)
                    bmp.recycle()
                    val mlOk = label.value in mlValues
                    val zxOk = label.value in zxValues
                    if (mlOk) ml++
                    if (zxOk) zx++
                    if (mlOk || zxOk) either++
                    // A digit string of a plausible length that is not this label's value = misread.
                    val others = (mlValues + zxValues).filter { it != label.value && it.all(Char::isDigit) && it.length in 8..14 }
                    if (others.isNotEmpty()) {
                        wrong++
                        val unknown = others.filter { it !in knownValues }
                        // The app's read filter drops 13-digit values with a wrong GS1 check digit.
                        val passing = unknown.filter { it.length != 13 || BarcodeRules.hasValidGs1CheckDigit(it) }
                        log("  other value  ${c.name} [$kind] ${label.name}: truth ${label.value}, got $others" + when {
                            unknown.isEmpty() -> " (other labels of the corpus: a neighbour in the crop)"
                            passing.isEmpty() -> " (misread, stopped by the GS1 check digit)"
                            else -> " (MISREAD that passes the app's filter)"
                        })
                        if (passing.isNotEmpty() && c.name == "clean") realMisreads++
                    }
                }
                val n = set.size
                log(String.format(Locale.US, "%-26s %6d %5.0f%% %5.0f%% %5.0f%% %8d", "${c.name} [$kind]", n,
                    100.0 * ml / n, 100.0 * zx / n, 100.0 * either / n, wrong))
                if (c.name == "clean" && kind == "read") cleanReadSet = either.toDouble() / n
            }
        }
        // Guards. Single-frame misreads under heavy degradation do happen (the tracker's majority
        // vote keeps them out of the count) and are only logged; on the crops as captured there must
        // be none. The field crops are real frames, not ideal images: about half decode on their
        // own; the floor only catches a decoder or settings regression.
        assertEquals("misreads on the crops as captured", 0, realMisreads)
        assertTrue("field-read crops decoded: ${(cleanReadSet * 100).roundToInt()} %", cleanReadSet >= 0.4)
    }

    private fun conditions(): List<Condition> {
        val list = mutableListOf(Condition("clean") { it })
        for (px in intArrayOf(3, 6, 10, 15, 20)) list += Condition("blur across bars ${px}px") { blur(it, px, 0) }
        for (px in intArrayOf(20, 40)) list += Condition("blur along bars ${px}px") { blur(it, 0, px) }
        for (s in floatArrayOf(1.5f, 3f)) list += Condition("defocus sigma $s") { gauss(it, s) }
        for (f in floatArrayOf(0.7f, 0.5f, 0.35f)) list += Condition("distance x${(1 / f * 10).roundToInt() / 10.0}") { scale(it, f) }
        for (sd in intArrayOf(10, 20)) list += Condition("noise sd $sd") { noise(it, sd.toFloat()) }
        // Same hand speed, three exposures: blur shrinks with exposure time, noise grows with ISO.
        list += Condition("setting auto 20ms") { noise(blur(it, 10, 10), 4f) }
        list += Condition("setting 4ms") { noise(blur(it, 2, 2), 10f) }
        list += Condition("setting 2ms") { noise(blur(it, 1, 1), 16f) }
        return list
    }

    // ---- decoders --------------------------------------------------------------------------

    private fun mlkit(bmp: Bitmap): Set<String> =
        Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0))).mapNotNull { it.rawValue?.trim() }.toSet()

    private fun zxing(bmp: Bitmap): Set<String> =
        zxing.read(bmp).mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }.toSet()

    // ---- corpus ----------------------------------------------------------------------------

    private fun loadLabels(): List<Label> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.list("labels").orEmpty().filter { it.endsWith(".png") }.sorted().mapNotNull { name ->
            val parts = name.split("__")
            if (parts.size < 3) return@mapNotNull null
            val bmp = assets.open("labels/$name").use { BitmapFactory.decodeStream(it) } ?: return@mapNotNull null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val luma = FloatArray(px.size) { i -> ((px[i] shr 16 and 0xff) * 0.299f + (px[i] shr 8 and 0xff) * 0.587f + (px[i] and 0xff) * 0.114f) }
            Label(parts[0], parts[1], name, bmp.width, bmp.height, luma).also { bmp.recycle() }
        }
    }

    /** ML Kit rejects images under 32 px: small crops sit centred on a canvas of their mean grey. */
    private fun toBitmap(l: Label): Bitmap {
        val w = maxOf(l.w, MIN_SIDE)
        val h = maxOf(l.h, MIN_SIDE)
        val ox = (w - l.w) / 2
        val oy = (h - l.h) / 2
        val fill = l.luma.average().toFloat()
        val px = IntArray(w * h) { i ->
            val x = i % w - ox
            val y = i / w - oy
            val lum = if (x in 0 until l.w && y in 0 until l.h) l.luma[y * l.w + x] else fill
            val v = lum.roundToInt().coerceIn(0, 255)
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- degradations ----------------------------------------------------------------------

    /** Box blur of [dx] pixels across the bars (horizontal) and [dy] along them (vertical). */
    private fun blur(l: Label, dx: Int, dy: Int): Label {
        var out = l.luma
        if (dx > 1) out = box(out, l.w, l.h, dx, horizontal = true)
        if (dy > 1) out = box(out, l.w, l.h, dy, horizontal = false)
        return Label(l.value, l.kind, l.name, l.w, l.h, out)
    }

    private fun box(src: FloatArray, w: Int, h: Int, len: Int, horizontal: Boolean): FloatArray {
        val out = FloatArray(src.size)
        val half = len / 2
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var n = 0
            for (k in -half..(len - 1 - half)) {
                val xx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
                sum += src[yy * w + xx]
                n++
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    private fun gauss(l: Label, sigma: Float): Label {
        val r = (sigma * 3).roundToInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        fun pass(src: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(src.size)
            for (y in 0 until l.h) for (x in 0 until l.w) {
                var acc = 0f
                for (k in -r..r) {
                    val xx = if (horizontal) (x + k).coerceIn(0, l.w - 1) else x
                    val yy = if (horizontal) y else (y + k).coerceIn(0, l.h - 1)
                    acc += src[yy * l.w + xx] * kernel[k + r]
                }
                out[y * l.w + x] = acc
            }
            return out
        }
        return Label(l.value, l.kind, l.name, l.w, l.h, pass(pass(l.luma, true), false))
    }

    /** Farther away = fewer pixels per bar: area-average down by [f]. */
    private fun scale(l: Label, f: Float): Label {
        val w = (l.w * f).roundToInt().coerceAtLeast(8)
        val h = (l.h * f).roundToInt().coerceAtLeast(8)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = (x / f).toInt().coerceAtMost(l.w - 1)
            val x1 = ((x + 1) / f).toInt().coerceIn(x0 + 1, l.w)
            val y0 = (y / f).toInt().coerceAtMost(l.h - 1)
            val y1 = ((y + 1) / f).toInt().coerceIn(y0 + 1, l.h)
            var sum = 0f
            var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { sum += l.luma[yy * l.w + xx]; n++ }
            out[y * w + x] = sum / n
        }
        return Label(l.value, l.kind, l.name, w, h, out)
    }

    private fun noise(l: Label, sd: Float): Label {
        val rnd = Random(l.name.hashCode())
        val out = FloatArray(l.luma.size) { i ->
            // Sum of uniforms ~ normal; deterministic per crop so runs are comparable.
            val n = (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 2f) * 1.732f
            l.luma[i] + n * sd
        }
        return Label(l.value, l.kind, l.name, l.w, l.h, out)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private companion object {
        const val TAG = "DecodeBench"
        const val MIN_SIDE = 48
    }
}
