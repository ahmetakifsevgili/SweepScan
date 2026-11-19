package com.sweepscan.scan

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.sweepscan.location.LocationCode
import com.sweepscan.tracking.BarcodeTracker
import com.sweepscan.tracking.Detection
import com.sweepscan.tracking.FrameResult
import com.sweepscan.tracking.Motion
import com.sweepscan.tracking.Suspects
import com.sweepscan.tracking.TrackerConfig
import org.json.JSONObject

/** Rolling numbers for the on-screen debug line. */
data class PipelineStats(
    val fps: Float,
    val msMlKit: Float,
    val msZxing: Float,
    val msFlow: Float,
    val msTracker: Float,
    val decoded: Int,
    val located: Int,
    val rescued: Int,
    val motion: Motion?,
)

/** A location label (QR) read in this frame; [box] in upright image coordinates. */
class LabelRead(val value: String, val box: com.sweepscan.tracking.Box)

class PipelineResult(
    val frame: FrameResult,
    /** Upright analysis image size; the overlay maps from this to the screen. */
    val imageWidth: Int,
    val imageHeight: Int,
    val stats: PipelineStats,
    /** Location labels read in this frame (never tracked or counted). */
    val labels: List<LabelRead> = emptyList(),
)

/**
 * The per-frame image-processing chain, run on the camera analysis thread:
 *
 * 1. ML Kit finds every barcode in the frame (decoded or only located),
 * 2. zxing-cpp retries the located-but-unreadable ones on a crop,
 * 3. optical flow measures how far the scene moved,
 * 4. the tracker turns all of it into counted boxes.
 */
class FramePipeline(
    private val settings: ScanSettings,
    private val logger: FieldLogger,
    private val flowAvailable: Boolean,
    private val onResult: (PipelineResult) -> Unit,
) : ImageAnalysis.Analyzer {

    val trackerConfig = TrackerConfig()

    /** Adds per-frame fields to the log (camera metadata, gyro, …); runs only while recording. */
    @Volatile var frameExtras: ((ImageProxy) -> JSONObject?)? = null

    /** Runs on every analysed frame after decoding, before tracking (exposure loop). */
    @Volatile var frameHook: ((ImageProxy, List<Detection>) -> Unit)? = null

    /** Told, on this thread with the frame still open, when labels were counted in it. */
    @Volatile var countedHook: ((ImageProxy, FrameResult) -> Unit)? = null

    /** Shift predicted from the gyroscope since the previous frame (raw px); null when unknown. */
    @Volatile var gyroShiftOf: ((ImageProxy) -> Pair<Float, Float>?)? = null

    /** The zoom ratio this frame was captured at (camera metadata); null when unknown. */
    @Volatile var zoomOf: ((ImageProxy) -> Float?)? = null

    /**
     * Location map: true for a counted barcode whose model is still unknown. Its label's model line
     * is then read now and then (text recognition, only while the view is fairly still).
     */
    @Volatile var modelWanted: ((String) -> Boolean)? = null

    /** Text lines read above a counted barcode (barcode, lines); runs on the analysis thread. */
    @Volatile var onModelLines: ((String, List<String>) -> Unit)? = null

    /** Warnings still open when tracking is reset (pause / finish); runs on the analysis thread. */
    @Volatile var onSuspects: ((Suspects) -> Unit)? = null

    private val mlKit = MlKitEngine()
    private val zxing = ZxingEngine()
    private val ocr = OcrEngine()
    private var ocrFrame = 0
    private var modelFrame = 0
    /** Scene motion of the previous frame (px): OCR is skipped while the view moves fast. */
    private var lastMotionPx = 0f
    private val motionEstimator = MotionEstimator()
    private val tracker = BarcodeTracker(trackerConfig)
    private val sampler = FrameSampler(logger)

    /** Analysed frames saved during the current recording. */
    val sampledFrames: Int get() = sampler.count

    @Volatile var scanning = true
    @Volatile private var resetRequested = false
    @Volatile private var discardSuspects = false

    private var lastFrameMs = 0L
    private var lastZoom: Float? = null
    private var fps = 0f
    private var msMlKit = 0f
    private var msZxing = 0f
    private var msFlow = 0f
    private var msTracker = 0f

    /**
     * Forget all tracks (e.g. after a pause, when the camera may point anywhere).
     * [reportSuspects] = false drops the open warnings too (the sweep they belong to was deleted).
     */
    fun requestReset(reportSuspects: Boolean = true) {
        discardSuspects = !reportSuspects
        resetRequested = true
    }

    override fun analyze(image: ImageProxy) {
        image.use { process(it) }
    }

    fun close() {
        mlKit.close()
        ocr.close()
    }

    private fun process(image: ImageProxy) {
        if (resetRequested) {
            val open = tracker.reset()
            val reported = !discardSuspects
            if (reported) onSuspects?.invoke(open)
            discardSuspects = false
            motionEstimator.reset()
            resetRequested = false
            // Written on this thread so replay sees the reset at the right place between frames.
            logger.logEvent("reset", mapOf("unread" to open.unreadable, "uncounted" to open.uncounted, "reported" to reported))
        }
        if (!scanning) return

        val now = SystemClock.elapsedRealtime()
        val rotation = image.imageInfo.rotationDegrees
        val (width, height) = ImageGeometry.uprightSize(image.width, image.height, rotation)

        // A zoom step (by hand or automatic) shows up in the frames a few frames after it is asked
        // for: rescale the tracker on the first frame captured at the new zoom.
        zoomOf?.invoke(image)?.let { zoom ->
            val previous = lastZoom
            if (previous != null && kotlin.math.abs(zoom / previous - 1f) > ZOOM_TOLERANCE) {
                val scale = zoom / previous
                tracker.applyZoom(scale, width / 2f, height / 2f)
                // Optical flow across a zoom step would read the scale change as motion.
                motionEstimator.reset()
                logger.logEvent("zoom", mapOf("scale" to scale, "cx" to width / 2f, "cy" to height / 2f, "from" to previous, "to" to zoom))
            }
            lastZoom = zoom
        }

        val t0 = SystemClock.elapsedRealtimeNanos()
        val found = mlKit.detect(image)
        val t1 = SystemClock.elapsedRealtimeNanos()
        // Location labels (QR) never enter tracking: they would show as unread boxes.
        val (labelDetections, boxDetections) = found.partition { it.format == MlKitEngine.QR || LocationCode.isLocation(it.value) }
        val rescue = if (settings.zxing) rescue(image, boxDetections) else Rescue(boxDetections, 0, 0, 0)
        val labels = labelDetections.mapNotNull { d -> d.value?.let { LabelRead(it, d.box) } } +
            rescue.detections.filter { LocationCode.isLocation(it.value) }.map { LabelRead(it.value!!, it.box) }
        val ocred = if (settings.ocr) readPrinted(image, rescue.detections) else null
        val detections = (ocred?.first ?: rescue.detections).filter { !LocationCode.isLocation(it.value) }
        frameHook?.invoke(image, detections)
        val t2 = SystemClock.elapsedRealtimeNanos()
        val motion = if (settings.flow && flowAvailable) motionEstimator.estimate(image) else null
        val t3 = SystemClock.elapsedRealtimeNanos()
        val gyroShift = gyroShiftOf?.invoke(image)
        // The tracker's appearance hook stays unused: the MediaPipe embedder (0.4–0.6) could not tell
        // labels apart on real crops and was removed in 0.7.0 to keep the APK shareable.
        val frame = tracker.update(detections, now, motion, width, height, gyroShift)
        val t4 = SystemClock.elapsedRealtimeNanos()
        lastMotionPx = kotlin.math.hypot(frame.shiftX, frame.shiftY)
        readModelLine(image, frame)
        if (frame.newlyCounted.isNotEmpty()) countedHook?.let { hook -> runCatching { hook(image, frame) } }

        if (logger.isActive) {
            val record = FrameRecord(
                t = now, ts = image.imageInfo.timestamp,
                bufferWidth = image.width, bufferHeight = image.height, rotation = rotation,
                width = width, height = height, motion = motion, detections = detections, result = frame,
                msMlKit = (t1 - t0) / 1e6f, msZxing = (t2 - t1) / 1e6f, msFlow = (t3 - t2) / 1e6f, msTracker = (t4 - t3) / 1e6f,
                zxingTried = rescue.tried, zxingOk = rescue.ok + (ocred?.second ?: 0), zxingAmbiguous = rescue.ambiguous,
                extra = frameExtras?.invoke(image),
                sharpness = detections.map { LumaStats.sharpness(image, it.box) },
                gyroShift = gyroShift,
            )
            val saved = if (settings.sampleFrames) {
                sampler.maybeSample(image, frame.verdicts, now) { logger.frameJson(record) }
            } else {
                null
            }
            logger.logFrame(record, saved)
        }

        if (lastFrameMs > 0) fps = ema(fps, 1000f / (now - lastFrameMs).coerceAtLeast(1))
        lastFrameMs = now
        msMlKit = ema(msMlKit, (t1 - t0) / 1e6f)
        msZxing = ema(msZxing, (t2 - t1) / 1e6f)
        msFlow = ema(msFlow, (t3 - t2) / 1e6f)
        msTracker = ema(msTracker, (t4 - t3) / 1e6f)
        val stats = PipelineStats(
            fps, msMlKit, msZxing, msFlow, msTracker,
            decoded = detections.count { it.decoded },
            located = detections.count { !it.decoded },
            rescued = rescue.ok + (ocred?.second ?: 0),
            motion = motion,
        )
        onResult(PipelineResult(frame, width, height, stats, labels))
    }

    private class Rescue(val detections: List<Detection>, val tried: Int, val ok: Int, val ambiguous: Int)

    /** Runs zxing-cpp on the largest located-but-unreadable barcodes (bounded per frame for speed). */
    private fun rescue(image: ImageProxy, detections: List<Detection>): Rescue {
        val candidates = detections.withIndex().filter { !it.value.decoded }
            .sortedByDescending { it.value.box.width * it.value.box.height }
            .take(MAX_RESCUES_PER_FRAME)
            .map { it.index }
            .toSet()
        if (candidates.isEmpty()) return Rescue(detections, 0, 0, 0)
        var ok = 0
        var ambiguous = 0
        val out = detections.mapIndexed { i, d ->
            if (i !in candidates) return@mapIndexed d
            val outcome = zxing.decodeRegion(image, d.box)
            if (outcome.found > 1) ambiguous++
            val value = outcome.value ?: return@mapIndexed d
            ok++
            d.copy(value = value, engine = ZxingEngine.ENGINE, format = outcome.format ?: "", lines = outcome.lines)
        }
        return Rescue(out, candidates.size, ok, ambiguous)
    }

    /**
     * Reads the printed number of the largest barcode still unread after both decoders (one per
     * frame, every [OCR_EVERY]th frame: text recognition takes tens of ms). Returns the detections
     * with that one filled in, and 1 when a number was read.
     */
    private fun readPrinted(image: ImageProxy, detections: List<Detection>): Pair<List<Detection>, Int>? {
        // Printed digits blur long before the bars stop decoding; text recognition costs ~120 ms
        // per region on the S23 Ultra, so only on a fairly still view and every few frames.
        if (++ocrFrame % OCR_EVERY != 0 || lastMotionPx > OCR_MAX_MOTION_PX) return null
        val target = detections.withIndex().filter { !it.value.decoded }
            .maxByOrNull { it.value.box.width * it.value.box.height } ?: return null
        val value = ocr.read(image, target.value.box) ?: return detections to 0
        val out = detections.toMutableList()
        out[target.index] = target.value.copy(value = value, engine = OcrEngine.ENGINE, format = "TEXT")
        return out to 1
    }

    /**
     * Location map: reads the model line ("0169 051  KAHVE  36") above the largest counted barcode
     * whose model is unknown; every [MODEL_EVERY]th frame and only while the view is fairly still.
     */
    private fun readModelLine(image: ImageProxy, frame: FrameResult) {
        val wanted = modelWanted ?: return
        val sink = onModelLines ?: return
        if (++modelFrame % MODEL_EVERY != 0 || lastMotionPx > OCR_MAX_MOTION_PX) return
        val target = frame.tracks
            .filter { t -> t.visible && t.counted && t.value?.let(wanted) == true }
            .maxByOrNull { it.box.width } ?: return
        val lines = ocr.lines(image, OcrEngine.modelRegion(target.box))
        if (lines.isNotEmpty()) sink(target.value!!, lines)
    }

    private fun ema(old: Float, new: Float) = if (old == 0f) new else old * 0.9f + new * 0.1f

    private companion object {
        const val MAX_RESCUES_PER_FRAME = 3
        const val OCR_EVERY = 3
        const val MODEL_EVERY = 2
        const val OCR_MAX_MOTION_PX = 8f
        const val ZOOM_TOLERANCE = 0.02f
    }
}
