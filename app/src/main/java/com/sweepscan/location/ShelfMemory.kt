package com.sweepscan.location

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.sweepscan.tracking.Box
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * What the shelf looked like: while a row is swept, the first read of each label keeps a small
 * photo of the shelf around it (the label framed in amber) and the shelf it was on
 * ([ShelfLevel]). A search shows the latest photo, so the answer is "here, this is how it looked
 * two days ago" rather than only a row name.
 *
 * One JPEG (≈ 30 KB) and one JSON per barcode, the latest only, in files/shelf/; the oldest are
 * dropped beyond [KEEP]. The index lives in memory (read once from disk in the background), so
 * looking a barcode up never touches the disk on the caller's thread.
 */
class ShelfMemory(context: Context) {

    data class Shot(val barcode: String, val code: LocationCode, val level: Int?, val time: Long, val file: File)

    private val dir = File(context.filesDir, "shelf").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val index = ConcurrentHashMap<String, Shot>()

    init {
        io.execute { load() }
    }

    private fun load() {
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { json ->
            runCatching {
                val o = JSONObject(json.readText())
                val barcode = o.getString("barcode")
                val code = LocationCode.parseText(o.getString("code")) ?: return@runCatching
                val jpg = File(dir, json.name.removeSuffix(".json") + ".jpg")
                if (jpg.exists()) index.putIfAbsent(barcode, Shot(barcode, code, if (o.has("level")) o.getInt("level") else null, o.getLong("time"), jpg))
            }
        }
    }

    /**
     * Keeps [region] — the shelf around the label, upright, already cut from the frame (see
     * [region]) — as [barcode]'s latest photo, with the label at [box] (region pixels) framed.
     * Scales, marks and writes on a background thread; [region] is recycled afterwards.
     */
    fun save(barcode: String, code: LocationCode, level: Int?, region: Bitmap, box: Box, time: Long = System.currentTimeMillis()) {
        io.execute {
            runCatching {
                val shot = frame(region, box)
                region.recycle()
                val name = safe(barcode)
                val jpg = File(dir, "$name.jpg")
                jpg.outputStream().use { shot.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                shot.recycle()
                File(dir, "$name.json").writeText(
                    JSONObject().put("barcode", barcode).put("code", code.text).put("time", time)
                        .also { o -> level?.let { o.put("level", it) } }.toString(),
                )
                index[barcode] = Shot(barcode, code, level, time, jpg)
                if (index.size > KEEP) prune()
            }
        }
    }

    fun get(barcode: String): Shot? = index[barcode]

    /** The newest photo of any of [barcodes] taken in [code]'s row (any row when null). */
    fun latest(barcodes: Collection<String>, code: LocationCode? = null): Shot? =
        barcodes.mapNotNull { index[it] }.filter { code == null || sameRow(it.code, code) }.maxByOrNull { it.time }

    /** The shelf most of [barcodes] were last read on in [code]'s row, or null if none is known. */
    fun level(barcodes: Collection<String>, code: LocationCode): Int? =
        barcodes.mapNotNull { index[it] }.filter { sameRow(it.code, code) }.mapNotNull { it.level }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun count(): Int = index.size

    fun clear() {
        index.clear()
        io.execute { dir.listFiles()?.forEach { it.delete() } }
    }

    /** Decodes [shot]'s photo off the main thread; [done] runs on the main thread (null if gone). */
    fun decode(shot: Shot, done: (Bitmap?) -> Unit) {
        io.execute {
            val b = runCatching { BitmapFactory.decodeFile(shot.file.path) }.getOrNull()
            main.post { done(b) }
        }
    }

    private fun prune() {
        index.values.sortedBy { it.time }.take(index.size - KEEP).forEach { s ->
            index.remove(s.barcode)
            s.file.delete()
            File(dir, s.file.name.removeSuffix(".jpg") + ".json").delete()
        }
    }

    companion object {
        const val KEEP = 4000
        private const val MAX_SIDE = 560

        private fun sameRow(a: LocationCode, b: LocationCode) = a.depot == b.depot && a.row.equals(b.row, ignoreCase = true)

        private fun safe(s: String) = s.filter { it.isLetterOrDigit() }.ifEmpty { "x" }.take(40)

        /**
         * The part of a [width]×[height] upright frame kept around a label at [box]: a few labels
         * wide, a box-stack high. Cut it out before anything else (it is a fraction of the frame).
         */
        fun region(box: Box, width: Int, height: Int): Box {
            val cw = max(box.width * 3.4f, width * 0.42f).coerceAtMost(width.toFloat())
            val ch = max(box.height * 6f, cw * 0.62f).coerceAtMost(height.toFloat())
            val left = (box.centerX - cw / 2).coerceIn(0f, width - cw)
            val top = (box.centerY - ch / 2).coerceIn(0f, height - ch)
            return Box(left, top, left + cw, top + ch)
        }

        /** [region] scaled down, the label at [box] (region pixels) framed in amber. */
        fun frame(region: Bitmap, box: Box): Bitmap {
            val scale = min(1f, MAX_SIDE / max(region.width, region.height).toFloat())
            val out = Bitmap.createBitmap((region.width * scale).toInt().coerceAtLeast(1), (region.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.scale(scale, scale)
            c.drawBitmap(region, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            val pad = max(6f, box.height * 0.25f)
            val ring = RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
            val stroke = 3.5f / scale
            c.drawRoundRect(ring, 10f / scale, 10f / scale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke + 3f / scale
                color = 0x99000000.toInt()
            })
            c.drawRoundRect(ring, 10f / scale, 10f / scale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = 0xFFFFB300.toInt()
            })
            return out
        }

        /** Cuts [region] out of a whole upright [frame] (tests, and frames already upright). */
        fun cut(frame: Bitmap, region: Box): Bitmap =
            Bitmap.createBitmap(frame, region.left.toInt(), region.top.toInt(), region.width.toInt().coerceAtLeast(1), region.height.toInt().coerceAtLeast(1))
    }
}
