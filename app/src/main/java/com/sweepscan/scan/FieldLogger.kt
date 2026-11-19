package com.sweepscan.scan

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.sweepscan.hid.HidEvent
import com.sweepscan.tracking.Detection
import com.sweepscan.tracking.FrameResult
import com.sweepscan.tracking.Motion
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything worth keeping about one analysed frame. */
class FrameRecord(
    val t: Long,
    /** Sensor timestamp of the frame (ns), for matching camera metadata and gyro samples. */
    val ts: Long,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotation: Int,
    val width: Int,
    val height: Int,
    val motion: Motion?,
    /** Raw detections as the decoders returned them (before read validation). */
    val detections: List<Detection>,
    val result: FrameResult,
    val msMlKit: Float,
    val msZxing: Float,
    val msFlow: Float,
    val msTracker: Float,
    val zxingTried: Int,
    val zxingOk: Int,
    val zxingAmbiguous: Int,
    /** Extra per-frame fields (camera metadata, gyro, …) merged into the line. */
    val extra: JSONObject? = null,
    /** Gradient sharpness across the bars per detection, index-aligned with [detections]. */
    val sharpness: List<Float>? = null,
    /** Shift predicted from the gyroscope's rotation since the previous frame (raw px). */
    val gyroShift: Pair<Float, Float>? = null,
)

/**
 * Field logs, schema 2. Every line is a JSON object with a `type`:
 *
 * - `meta`: first line of a recording (device, app version, settings, tracker config, camera caps)
 * - `frame`: one analysed frame — `t,w,h,motion,shift,dets,counted` keep the v0.2 meaning, plus
 *   sensor timestamp, buffer size/rotation, per-stage ms, zxing stats and per-detection verdicts;
 *   `tr` is what the overlay drew: one `[id, left, top, right, bottom, value, counted, visible,
 *   unread]` per track (flags 0/1; id -1 and value null for a located-but-unread label); `sus`
 *   (only when non-zero) is `[unread, uncounted]`: undercount warnings raised by this frame
 * - `hid`: keyboard events (job start/sent/uncertain, connection, protocol…)
 * - `event`: session events (reset, pause, count, undo, …), in order with the frames
 *
 * Recordings go to `Download/SweepScan/sweepscan-<date>-<tag>.jsonl` while the user records.
 * `hid` and `event` lines also go to an always-on `events-<date>.jsonl` (one per app run), so
 * sending to DİA is logged even without a recording. Replay: see `ReplayTest`.
 */
class FieldLogger(private val context: Context) {

    @Volatile private var recording: BufferedWriter? = null
    private var events: BufferedWriter? = null
    private var eventsFailed = false
    private var lastFlushMs = 0L

    @Volatile var fileName: String? = null
        private set
    @Volatile var framesWritten = 0
        private set
    val isActive: Boolean get() = recording != null

    /** Starts a recording; [meta] becomes its first line. False when the file can't be created. */
    @Synchronized
    fun start(tag: String, meta: JSONObject): Boolean {
        if (recording != null) return true
        val safeTag = tag.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').take(40)
        val name = "sweepscan-" + stamp() + (if (safeTag.isEmpty()) "" else "-$safeTag") + ".jsonl"
        val out = openDownload(name) ?: return false
        recording = out
        fileName = name
        framesWritten = 0
        meta.put("type", "meta").put("schema", SCHEMA).put("tag", tag).put("t0", SystemClock.elapsedRealtime())
        write(out, meta)
        out.flush()
        return true
    }

    /**
     * Writes everything buffered to disk. Called on Pause and when the app leaves the screen: the
     * periodic flush only runs when a new line arrives, so a paused or abandoned recording could
     * otherwise stay incomplete on disk (16 Nov `kontrol-2`: 4 s of a 30 s sweep).
     */
    @Synchronized
    fun flush() {
        runCatching { recording?.flush() }
        runCatching { events?.flush() }
    }

    @Synchronized
    fun stop() {
        recording?.flush()
        recording?.close()
        recording = null
        events?.flush()
    }

    @Synchronized
    fun logFrame(r: FrameRecord, image: String? = null) {
        val out = recording ?: return
        val line = frameJson(r)
        image?.let { line.put("img", it) }
        write(out, line)
        framesWritten++
        maybeFlush()
    }

    fun frameJson(r: FrameRecord): JSONObject {
        val line = JSONObject()
            .put("type", "frame")
            .put("t", r.t)
            .put("ts", r.ts)
            .put("bw", r.bufferWidth).put("bh", r.bufferHeight).put("rot", r.rotation)
            .put("w", r.width).put("h", r.height)
            .put("motion", r.motion?.let(::motionJson) ?: JSONObject.NULL)
            .put(
                "shift",
                JSONObject().put("dx", r.result.shiftX.toDouble()).put("dy", r.result.shiftY.toDouble()).put("src", r.result.motionSource.name),
            )
            .put("dets", JSONArray().apply {
                r.detections.forEachIndexed { i, d ->
                    val o = JSONObject()
                        .put("v", d.value ?: JSONObject.NULL)
                        .put("b", JSONArray(listOf(d.box.left, d.box.top, d.box.right, d.box.bottom).map { round1(it) }))
                        .put("e", d.engine)
                    if (d.format.isNotEmpty()) o.put("f", d.format)
                    d.corners?.let { c -> o.put("c", JSONArray(c.map { round1(it) })) }
                    if (d.lines > 0) o.put("lc", d.lines)
                    r.result.verdicts.getOrNull(i)?.let { o.put("r", it.name) }
                    r.sharpness?.getOrNull(i)?.let { o.put("s", round1(it)) }
                    put(o)
                }
            })
            .put("counted", JSONArray(r.result.newlyCounted.map { it.value }))
            .put("tr", JSONArray().apply {
                r.result.tracks.forEach { t ->
                    put(
                        JSONArray()
                            .put(t.id)
                            .put(round1(t.box.left)).put(round1(t.box.top)).put(round1(t.box.right)).put(round1(t.box.bottom))
                            .put(t.value ?: JSONObject.NULL)
                            .put(if (t.counted) 1 else 0).put(if (t.visible) 1 else 0).put(if (t.unreadable) 1 else 0),
                    )
                }
            })
            .put(
                "ms",
                JSONObject().put("ml", round1(r.msMlKit)).put("zx", round1(r.msZxing))
                    .put("of", round1(r.msFlow)).put("tr", round1(r.msTracker)),
            )
            .put("zx", JSONObject().put("try", r.zxingTried).put("ok", r.zxingOk).put("multi", r.zxingAmbiguous))
        r.result.suspects.let { s ->
            if (s.unreadable > 0 || s.uncounted > 0) line.put("sus", JSONArray().put(s.unreadable).put(s.uncounted))
        }
        r.gyroShift?.let { (gx, gy) -> line.put("gs", JSONArray().put(round1(gx)).put(round1(gy))) }
        if (r.result.reid.isNotEmpty()) {
            // [value, lostId, nx, ny, neighbourMatch, neighbourConflict, similarity, accepted]
            line.put("reid", JSONArray().apply {
                r.result.reid.forEach { a ->
                    put(
                        JSONArray().put(a.value).put(a.lostId).put(round2(a.nx)).put(round2(a.ny))
                            .put(if (a.neighbourMatch) 1 else 0).put(if (a.neighbourConflict) 1 else 0)
                            .put(a.similarity?.let { round2(it) } ?: JSONObject.NULL).put(if (a.accepted) 1 else 0),
                    )
                }
            })
        }
        r.extra?.let { extra -> extra.keys().forEach { k -> line.put(k, extra.get(k)) } }
        return line
    }

    /**
     * `dx,dy,c`: median translation and its confidence (what the tracker uses). `a`: the same
     * motion as a similarity `[a, b, tx, c, d, ty]` in upright pixels, `in`: its inlier share.
     */
    private fun motionJson(m: Motion): JSONObject {
        val o = JSONObject().put("dx", m.dx.toDouble()).put("dy", m.dy.toDouble()).put("c", m.confidence.toDouble())
        m.similarity?.let { s ->
            // Rotation/scale terms need more digits than pixel offsets: 1e-5 is 0.02 px over a 1920 px frame.
            val digits = intArrayOf(5, 5, 2, 5, 5, 2)
            o.put("a", JSONArray(s.mapIndexed { i, v -> round(v, digits[i]) }))
            o.put("in", round(m.inliers, 3))
        }
        return o
    }

    @Synchronized
    fun logHid(event: HidEvent) {
        val line = JSONObject().put("type", "hid").put("t", SystemClock.elapsedRealtime()).put("ev", event.ev)
        event.id?.let { line.put("id", it) }
        event.len?.let { line.put("len", it) }
        event.typed?.let { line.put("typed", it) }
        event.enter?.let { line.put("enter", it) }
        event.reason?.let { line.put("reason", it) }
        event.typeMs?.let { line.put("typeMs", it) }
        event.gapMs?.let { line.put("gapMs", it) }
        event.detail?.let { line.put("detail", it) }
        writeEverywhere(line)
    }

    @Synchronized
    fun logEvent(name: String, fields: Map<String, Any?> = emptyMap()) {
        val line = JSONObject().put("type", "event").put("t", SystemClock.elapsedRealtime()).put("ev", name)
        fields.forEach { (k, v) -> line.put(k, v ?: JSONObject.NULL) }
        writeEverywhere(line)
    }

    /** Writes a whole file to `Download/SweepScan` (capability dumps, CSV backups). */
    fun writeFile(name: String, content: String): Boolean {
        val out = openDownload(name) ?: return false
        return runCatching { out.use { it.write(content) } }.isSuccess
    }

    /** Writes a file to `Download/SweepScan/<folder>` (sampled frames and their sidecars). */
    fun writeBinary(folder: String, name: String, block: (OutputStream) -> Unit): Boolean {
        val out = openStream(name, folder) ?: return false
        return runCatching { out.buffered().use(block) }.isSuccess
    }

    fun writeText(folder: String, name: String, text: String): Boolean =
        writeBinary(folder, name) { it.write(text.toByteArray()) }

    private fun writeEverywhere(line: JSONObject) {
        recording?.let { write(it, line) }
        eventsWriter()?.let { write(it, line) }
        maybeFlush()
    }

    private fun eventsWriter(): BufferedWriter? {
        if (events == null && !eventsFailed) {
            events = openDownload("events-" + stamp() + ".jsonl")
            eventsFailed = events == null
        }
        return events
    }

    private fun maybeFlush() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlushMs < FLUSH_MS) return
        lastFlushMs = now
        recording?.flush()
        events?.flush()
    }

    private fun write(out: BufferedWriter, line: JSONObject) {
        runCatching {
            out.write(line.toString())
            out.newLine()
        }
    }

    /** A new text file in `Download/SweepScan` (Android 10+), or null. */
    private fun openDownload(name: String): BufferedWriter? = openStream(name, null)?.bufferedWriter()

    private fun openStream(name: String, folder: String?): OutputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val mime = mimeFor(name)
        val path = Environment.DIRECTORY_DOWNLOADS + "/SweepScan" + (folder?.let { "/$it" } ?: "")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, path)
        }
        val resolver = context.contentResolver
        return runCatching {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)
        }.getOrNull()
    }

    /**
     * MediaStore renames a file whose extension doesn't belong to its MIME type ("x.jsonl" as
     * text/plain becomes "x.jsonl.txt"), so the type must follow the extension; unknown
     * extensions are kept as they are with the generic type.
     */
    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "json" -> "application/json"
        "csv" -> "text/csv"
        "png" -> "image/png"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun round2(v: Float): Double = Math.round(v * 100.0) / 100.0


    private fun round1(v: Float) = Math.round(v * 10.0) / 10.0

    private fun round(v: Float, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }

    companion object {
        const val SCHEMA = 2
        private const val FLUSH_MS = 2000L
    }
}
