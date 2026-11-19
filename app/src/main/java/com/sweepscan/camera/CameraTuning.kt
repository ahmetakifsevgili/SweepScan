package com.sweepscan.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlin.math.roundToInt

enum class ExposureMode { AUTO, FPS30_EV, SHORT }
enum class FocusMode { CONTINUOUS, CENTER, FIXED }

/** What was actually applied, after checking the device's capabilities (logged in every recording). */
enum class ExposurePath { AUTO, FPS_EV, PRIORITY, MANUAL, FALLBACK }
enum class FocusPath { CONTINUOUS, CENTER, FIXED, CENTER_FALLBACK }

class TuningPlan(
    val exposure: ExposurePath,
    val focus: FocusPath,
    val shortExposureNs: Long,
    val focusDiopters: Float,
)

/**
 * Exposure and focus modes (research report S2/S3). Motion blur across the bars is the main read
 * killer: auto exposure indoors picks 1/30–1/60 s, a hand sweep needs ~1 ms. Every mode degrades
 * to something the device supports; AUTO clears all overrides (= v0.2 behaviour).
 */
@OptIn(ExperimentalCamera2Interop::class)
object CameraTuning {

    fun resolve(caps: CameraCaps?, exposure: ExposureMode, focus: FocusMode, shortExposureUs: Int, focusDistanceM: Float): TuningPlan {
        val shortNs = shortExposureUs * 1000L
        val exposurePath = when (exposure) {
            ExposureMode.AUTO -> ExposurePath.AUTO
            ExposureMode.FPS30_EV -> ExposurePath.FPS_EV
            ExposureMode.SHORT -> when {
                caps == null -> ExposurePath.FALLBACK
                caps.exposurePriorityAvailable && CameraKeys.requestAePriority != null -> ExposurePath.PRIORITY
                caps.manualSensor && caps.aeOffAvailable -> ExposurePath.MANUAL
                else -> ExposurePath.FALLBACK
            }
        }
        val focusPath = when (focus) {
            FocusMode.CONTINUOUS -> FocusPath.CONTINUOUS
            FocusMode.CENTER -> if ((caps?.maxRegionsAf ?: 0) > 0) FocusPath.CENTER else FocusPath.CONTINUOUS
            FocusMode.FIXED -> if (caps?.fixedFocusAvailable == true) FocusPath.FIXED else FocusPath.CENTER_FALLBACK
        }
        return TuningPlan(exposurePath, focusPath, shortNs, 1f / focusDistanceM)
    }

    /** Applies [plan] to the bound camera; [iso] is used only on the MANUAL path. */
    fun apply(camera: Camera, caps: CameraCaps?, plan: TuningPlan, zoom: Float, iso: Int?) {
        val control = Camera2CameraControl.from(camera.cameraControl)
        val options = options(caps, plan, zoom, iso)
        if (options == null) control.clearCaptureRequestOptions() else control.setCaptureRequestOptions(options)
    }

    private fun options(caps: CameraCaps?, plan: TuningPlan, zoom: Float, iso: Int?): CaptureRequestOptions? {
        if (plan.exposure == ExposurePath.AUTO && plan.focus == FocusPath.CONTINUOUS) return null
        val b = CaptureRequestOptions.Builder()

        if (plan.exposure != ExposurePath.AUTO) {
            if (caps != null && CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ in caps.antibandingModes) {
                // Turkey: 50 Hz mains, LED drivers flicker at 100 Hz.
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ)
            }
            fps30(caps)?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        val shortNs = caps?.exposureRange?.let { plan.shortExposureNs.coerceIn(it.lower, it.upper) } ?: plan.shortExposureNs
        when (plan.exposure) {
            ExposurePath.AUTO -> Unit
            ExposurePath.FPS_EV, ExposurePath.FALLBACK -> evSteps(caps, -1.5f)?.let {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
            }
            ExposurePath.PRIORITY -> {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                CameraKeys.requestAePriority?.let { b.setCaptureRequestOption(it, CameraKeys.AE_PRIORITY_EXPOSURE_TIME) }
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shortNs)
            }
            ExposurePath.MANUAL -> {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shortNs)
                val range = caps?.isoRange
                val sensitivity = (iso ?: 1600).let { if (range != null) it.coerceIn(range.lower, range.upper) else it }
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_DURATION_30FPS_NS)
            }
        }

        when (plan.focus) {
            FocusPath.CONTINUOUS -> Unit
            FocusPath.CENTER, FocusPath.CENTER_FALLBACK -> {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                centerRegion(caps, zoom)?.let { region ->
                    if ((caps?.maxRegionsAf ?: 0) > 0) b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                    // Keeps glossy box faces and ceiling spots out of metering.
                    if ((caps?.maxRegionsAe ?: 0) > 0 && plan.exposure != ExposurePath.MANUAL) {
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                    }
                }
            }
            FocusPath.FIXED -> {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                val maxD = caps?.minFocusDiopters ?: plan.focusDiopters
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, plan.focusDiopters.coerceIn(0f, maxD))
            }
        }
        return b.build()
    }

    /** (30,30) if listed, else the range with upper 30 and the highest lower bound. */
    private fun fps30(caps: CameraCaps?): Range<Int>? {
        val ranges = caps?.fpsRanges ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
    }

    private fun evSteps(caps: CameraCaps?, ev: Float): Int? {
        val step = caps?.evStep ?: return null
        val range = caps.evRange ?: return null
        if (step.toFloat() <= 0f) return null
        return (ev / step.toFloat()).roundToInt().coerceIn(range.lower, range.upper)
    }

    /** Centred 30 % × 20 % of the visible area (the active array cropped by zoom). */
    private fun centerRegion(caps: CameraCaps?, zoom: Float): MeteringRectangle? {
        val array = caps?.activeArray ?: return null
        val z = zoom.coerceAtLeast(1f)
        val w = (array.width() * 0.3f / z).toInt()
        val h = (array.height() * 0.2f / z).toInt()
        val cx = array.centerX()
        val cy = array.centerY()
        return MeteringRectangle(cx - w / 2, cy - h / 2, w, h, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private const val FRAME_DURATION_30FPS_NS = 33_333_333L
}
