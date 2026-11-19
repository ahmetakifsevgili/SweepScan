package com.sweepscan.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** What the camera actually did for one frame (from its capture result). */
data class FrameMeta(
    val timestampNs: Long,
    val exposureNs: Long?,
    val iso: Int?,
    val frameDurationNs: Long?,
    val focusDiopters: Float?,
    val zoom: Float?,
    val aeMode: Int?,
    val aeState: Int?,
    val aePriority: Int?,
    val afMode: Int?,
    val afState: Int?,
    val physicalCamera: String?,
    val flicker: Int?,
    val evCompensation: Int?,
    val fpsRange: IntArray?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("exp", exposureNs ?: JSONObject.NULL)
        put("iso", iso ?: JSONObject.NULL)
        put("dur", frameDurationNs ?: JSONObject.NULL)
        put("fd", focusDiopters?.toDouble() ?: JSONObject.NULL)
        put("zoom", zoom?.toDouble() ?: JSONObject.NULL)
        put("ae", aeMode ?: JSONObject.NULL)
        put("aeS", aeState ?: JSONObject.NULL)
        put("aePri", aePriority ?: JSONObject.NULL)
        put("af", afMode ?: JSONObject.NULL)
        put("afS", afState ?: JSONObject.NULL)
        put("phys", physicalCamera ?: JSONObject.NULL)
        put("flk", flicker ?: JSONObject.NULL)
        put("ev", evCompensation ?: JSONObject.NULL)
        put("fps", fpsRange?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
    }
}

/**
 * Session capture callback that keeps the last ~32 capture results by sensor timestamp, so the
 * analyser can log the real exposure, ISO, focus and zoom of each analysed frame.
 */
class CaptureMetaBuffer : CameraCaptureSession.CaptureCallback() {

    private val ring = TimestampRing<FrameMeta>(32)
    @Volatile var lookups = 0
        private set
    @Volatile var misses = 0
        private set

    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        ring.put(ts, extract(ts, result))
    }

    fun lookup(timestampNs: Long): FrameMeta? {
        lookups++
        return ring.lookup(timestampNs).also { if (it == null) misses++ }
    }

    private fun extract(ts: Long, r: CaptureResult): FrameMeta {
        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) r.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        val phys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        } else {
            null
        }
        val fps = r.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)?.let { intArrayOf(it.lower, it.upper) }
        return FrameMeta(
            timestampNs = ts,
            exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            iso = r.get(CaptureResult.SENSOR_SENSITIVITY),
            frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION),
            focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE),
            zoom = zoom,
            aeMode = r.get(CaptureResult.CONTROL_AE_MODE),
            aeState = r.get(CaptureResult.CONTROL_AE_STATE),
            aePriority = CameraKeys.resultAePriority?.let { r.get(it) },
            afMode = r.get(CaptureResult.CONTROL_AF_MODE),
            afState = r.get(CaptureResult.CONTROL_AF_STATE),
            physicalCamera = phys,
            flicker = r.get(CaptureResult.STATISTICS_SCENE_FLICKER),
            evCompensation = r.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION),
            fpsRange = fps,
        )
    }
}
