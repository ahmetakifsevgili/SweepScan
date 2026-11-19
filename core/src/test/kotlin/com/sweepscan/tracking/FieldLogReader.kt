package com.sweepscan.tracking

import org.json.JSONObject
import kotlin.math.max
import java.io.File

/**
 * Reads a field log (schema 2, or v0.2 lines without a `type`) into what the tracker needs.
 * Only `frame` lines and `reset` events matter for replay; everything else is skipped.
 */
class FieldLog(
    val meta: JSONObject?,
    val steps: List<Step>,
) {
    sealed class Step {
        class Frame(
            val t: Long,
            val width: Int,
            val height: Int,
            val motion: Motion?,
            val detections: List<Detection>,
            val liveCounted: List<String>,
            /** What the phone's overlay drew (`tr`); empty for logs older than v0.3.3. */
            val liveTracks: List<TrackView> = emptyList(),
            /** Gyro-predicted shift (raw px): logged (`gs`, v0.3.12+) or approximated from `gyro.r`. */
            val gyroShift: Pair<Float, Float>? = null,
        ) : Step()

        /** [afterAutoZoom]: the reset v0.3.9 did right after an automatic zoom step. */
        class Reset(val afterAutoZoom: Boolean = false) : Step()

        /** The camera zoomed by [scale] about ([cx], [cy]) before the next frame. */
        class Zoom(val scale: Float, val cx: Float, val cy: Float) : Step()
    }

    val frames: List<Step.Frame> get() = steps.filterIsInstance<Step.Frame>()

    /** True when the phone logged its zoom handling (v0.3.10+); older logs get zoom steps derived from camera metadata. */
    var zoomLogged = false
        private set

    /** The tracker settings the phone used, as far as the meta line records them. */
    fun recordedConfig(): TrackerConfig {
        val t = meta?.optJSONObject("tracker") ?: return TrackerConfig()
        val lengths = t.optJSONArray("lengths")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
        val d = TrackerConfig()
        return TrackerConfig(
            minHits = t.optInt("minHits", d.minHits),
            minAgreement = t.optDouble("minAgreement", d.minAgreement.toDouble()).toFloat(),
            lostTimeoutMs = t.optLong("lostTimeoutMs", d.lostTimeoutMs),
            reidWindowMs = t.optLong("reidWindowMs", d.reidWindowMs),
            matchGate = t.optDouble("matchGate", d.matchGate.toDouble()).toFloat(),
            reidGate = t.optDouble("reidGate", d.reidGate.toDouble()).toFloat(),
            velocitySmoothing = t.optDouble("velocitySmoothing", d.velocitySmoothing.toDouble()).toFloat(),
            minMotionConfidence = t.optDouble("minMotionConfidence", d.minMotionConfidence.toDouble()).toFloat(),
            validateReads = t.optBoolean("validateReads", d.validateReads),
            lengths = lengths,
            edgeMarginPx = t.optDouble("edgeMarginPx", d.edgeMarginPx.toDouble()).toFloat(),
            dedupeIou = t.optDouble("dedupeIou", d.dedupeIou.toDouble()).toFloat(),
            requireGs1For13 = t.optBoolean("requireGs1For13", d.requireGs1For13),
            absorbTwins = t.optBoolean("absorbTwins", d.absorbTwins),
            reidentify = t.optBoolean("reidentify", d.reidentify),
            // Logs from before this option existed were recorded with time-only expiry.
            reidHoldTravel = t.optDouble("reidHoldTravel", 0.0).toFloat(),
            reidHoldMaxMs = t.optLong("reidHoldMaxMs", d.reidHoldMaxMs),
            priorFirst = t.optBoolean("priorFirst", false),
            // Options newer than the log were off on the phone.
            flowShiftFirst = t.optBoolean("flowShiftFirst", false),
            gyroFallback = t.optBoolean("gyroFallback", false),
            neighbourReid = t.optBoolean("neighbourReid", false),
            reidAlongNoConflict = t.optDouble("reidAlongNoConflict", d.reidAlongNoConflict.toDouble()).toFloat(),
            reidAlongWithNeighbour = t.optDouble("reidAlongWithNeighbour", d.reidAlongWithNeighbour.toDouble()).toFloat(),
            uncountedWarnMs = t.optLong("uncountedWarnMs", d.uncountedWarnMs),
        )
    }

    /**
     * Replays the log through a fresh tracker; returns the counted values in order.
     * Zoom steps rescale the tracker (current behaviour). With [asRecorded], resets that followed an
     * automatic zoom are kept and zoom steps that the phone did not apply are skipped, as on the phone.
     */
    fun replay(config: TrackerConfig, asRecorded: Boolean = false): List<String> {
        val tracker = BarcodeTracker(config)
        val counted = ArrayList<String>()
        for (step in steps) {
            when (step) {
                is Step.Reset -> if (asRecorded || !step.afterAutoZoom) tracker.reset()
                is Step.Zoom -> if (!asRecorded || zoomLogged) tracker.applyZoom(step.scale, step.cx, step.cy)
                is Step.Frame -> counted += tracker.update(step.detections, step.t, step.motion, step.width, step.height, step.gyroShift)
                    .newlyCounted.map { it.value }
            }
        }
        return counted
    }

    companion object {
        private const val AUTO_ZOOM_RESET_MS = 500L

        fun read(file: File): FieldLog = parse(file.readLines())

        fun parse(lines: List<String>): FieldLog {
            var meta: JSONObject? = null
            val steps = ArrayList<Step>()
            val logged = lines.any { it.contains("\"ev\":\"zoom\"") }
            // v0.3.9 asked for a reset right after an automatic zoom; the pipeline applied it on its
            // next frame, so a frame line or two can sit between the two events.
            var autoZoomAt = Long.MIN_VALUE
            var lastZoom: Double? = null
            var lastFrameT = -1L
            var focalPerPx = 0.0 // focal length / sensor width (mm / mm), from the meta line
            for (line in lines) {
                if (line.isBlank()) continue
                val o = JSONObject(line)
                when (o.optString("type", "frame")) {
                    "meta" -> {
                        meta = o
                        focalPerPx = focalRatio(o)
                    }
                    "event" -> when (o.optString("ev")) {
                        "reset" -> {
                            val t = o.optLong("t")
                            steps += Step.Reset(autoZoomAt != Long.MIN_VALUE && t - autoZoomAt in 0..AUTO_ZOOM_RESET_MS)
                            autoZoomAt = Long.MIN_VALUE
                        }
                        "auto_zoom" -> autoZoomAt = o.optLong("t")
                        "zoom" -> steps += Step.Zoom(
                            o.getDouble("scale").toFloat(), o.getDouble("cx").toFloat(), o.getDouble("cy").toFloat(),
                        )
                    }
                    "frame" -> {
                        // Older logs: the frame's camera zoom tells where a zoom step took effect.
                        val zoom = o.optJSONObject("cap")?.let { c -> if (c.isNull("zoom")) null else c.optDouble("zoom") }
                        if (!logged && zoom != null && !zoom.isNaN()) {
                            val previous = lastZoom
                            if (previous != null && kotlin.math.abs(zoom / previous - 1) > 0.02) {
                                steps += Step.Zoom((zoom / previous).toFloat(), o.optInt("w") / 2f, o.optInt("h") / 2f)
                            }
                            lastZoom = zoom
                        }
                        val frame = frame(o)
                        val gs = gyroShift(o, if (lastFrameT >= 0) o.getLong("t") - lastFrameT else 0L, focalPerPx, zoom ?: lastZoom ?: 1.0)
                        steps += if (gs != null) Step.Frame(frame.t, frame.width, frame.height, frame.motion, frame.detections, frame.liveCounted, frame.liveTracks, gs) else frame
                        lastFrameT = o.getLong("t")
                    }
                }
            }
            return FieldLog(meta, steps).also { it.zoomLogged = logged }
        }

        /** focal length / sensor width from `camera.caps` (focalMm, sensorMm "WxH"); 0 when unknown. */
        private fun focalRatio(meta: JSONObject): Double {
            val caps = meta.optJSONObject("camera")?.optJSONObject("caps") ?: return 0.0
            val focal = caps.optJSONArray("focalMm")?.optDouble(0) ?: return 0.0
            val sensor = caps.optString("sensorMm").substringBefore('x').toDoubleOrNull() ?: return 0.0
            return if (sensor > 0) focal / sensor else 0.0
        }

        /**
         * Logged `gs` when present. Older logs carry `gyro.r`, the rotation (deg) over the 100 ms before
         * the frame: rate x frame interval x focal length in px gives an approximate shift. In the upright
         * portrait image, rotation about the device x axis moves the scene vertically, about y sideways.
         */
        private fun gyroShift(o: JSONObject, dtMs: Long, focalPerPx: Double, zoom: Double): Pair<Float, Float>? {
            o.optJSONArray("gs")?.let { return it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
            val r = o.optJSONObject("gyro")?.optJSONArray("r") ?: return null
            if (dtMs <= 0 || focalPerPx <= 0.0) return null
            val fPx = focalPerPx * max(o.optInt("bw"), o.optInt("bh")) * zoom
            val k = dtMs / 100.0 * Math.PI / 180.0 * fPx
            return (r.getDouble(1) * k).toFloat() to (r.getDouble(0) * k).toFloat()
        }

        private fun frame(o: JSONObject): Step.Frame {
            val dets = o.getJSONArray("dets")
            val detections = (0 until dets.length()).map { i ->
                val d = dets.getJSONObject(i)
                val b = d.getJSONArray("b")
                Detection(
                    if (d.isNull("v")) null else d.getString("v"),
                    Box(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
                    d.optString("e"),
                    d.optString("f"),
                    lines = d.optInt("lc"),
                )
            }
            val motion = o.optJSONObject("motion")?.let {
                val a = it.optJSONArray("a")?.takeIf { arr -> arr.length() == 6 }
                Motion(
                    it.getDouble("dx").toFloat(), it.getDouble("dy").toFloat(), it.getDouble("c").toFloat(),
                    a?.let { arr -> FloatArray(6) { i -> arr.getDouble(i).toFloat() } },
                    it.optDouble("in", 0.0).toFloat(),
                )
            }
            val counted = o.getJSONArray("counted").let { a -> (0 until a.length()).map { a.getString(it) } }
            val tr = o.optJSONArray("tr")
            val tracks = (0 until (tr?.length() ?: 0)).map { i ->
                val a = tr!!.getJSONArray(i)
                TrackView(
                    a.getLong(0),
                    Box(a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat(), a.getDouble(4).toFloat()),
                    if (a.isNull(5)) null else a.getString(5),
                    counted = a.getInt(6) == 1, visible = a.getInt(7) == 1, unreadable = a.getInt(8) == 1,
                )
            }
            return Step.Frame(o.getLong("t"), o.optInt("w"), o.optInt("h"), motion, detections, counted, tracks)
        }
    }
}
