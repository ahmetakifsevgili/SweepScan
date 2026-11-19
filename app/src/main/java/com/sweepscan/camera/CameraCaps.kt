package com.sweepscan.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the bound camera can do, read from Camera2 characteristics. No public source lists the
 * Galaxy S23 Ultra's capabilities for third-party apps, so the app reads them on the device:
 * this decides which exposure/focus modes are offered (S2/S3) and is written to every log.
 */
class CameraCaps private constructor(val cameraId: String, val chars: CameraCharacteristics) {

    private fun <T> get(key: CameraCharacteristics.Key<T>): T? = runCatching { chars.get(key) }.getOrNull()

    val hardwareLevel: Int? = get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    val capabilities: IntArray = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val manualSensor: Boolean = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities
    val aePriorityModes: IntArray = CameraKeys.availableAePriorityModes?.let { get(it) } ?: IntArray(0)
    val isoRange: Range<Int>? = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val exposureRange: Range<Long>? = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val afCalibration: Int? = get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
    val minFocusDiopters: Float? = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    val timestampSource: Int? = get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
    val fpsRanges: Array<Range<Int>> = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
    val aeModes: IntArray = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: IntArray(0)
    val afModes: IntArray = get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val antibandingModes: IntArray = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES) ?: IntArray(0)
    val maxRegionsAf: Int = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxRegionsAe: Int = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val evRange: Range<Int>? = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
    val evStep: Rational? = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
    val activeArray: Rect? = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val focalLengths: FloatArray = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
    val physicalSize: SizeF? = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

    val exposurePriorityAvailable: Boolean get() = CameraKeys.AE_PRIORITY_EXPOSURE_TIME in aePriorityModes
    val aeOffAvailable: Boolean get() = CameraMetadata.CONTROL_AE_MODE_OFF in aeModes
    val fixedFocusAvailable: Boolean
        get() = CameraMetadata.CONTROL_AF_MODE_OFF in afModes && (minFocusDiopters ?: 0f) > 0f &&
            afCalibration != null && afCalibration != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED

    /** Focal length in pixels of a buffer [bufferWidth] wide (sensor long side), at [zoom]. */
    fun focalPx(bufferWidth: Int, zoom: Float): Float {
        val f = focalLengths.firstOrNull()
        val w = physicalSize?.width
        val base = if (f != null && w != null && w > 0f) f / w * bufferWidth else 0.667f * bufferWidth
        return base * zoom
    }

    fun summaryJson(): JSONObject = JSONObject()
        .put("cameraId", cameraId)
        .put("level", levelName(hardwareLevel))
        .put("manualSensor", manualSensor)
        .put("aePriority", JSONArray(aePriorityModes.toList()))
        .put("iso", isoRange?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
        .put("expNs", exposureRange?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
        .put("afCalibration", calibrationName(afCalibration))
        .put("minFocusD", minFocusDiopters?.toDouble() ?: JSONObject.NULL)
        .put("tsSource", if (timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN")
        .put("fpsRanges", JSONArray(fpsRanges.map { JSONArray(listOf(it.lower, it.upper)) }))
        .put("aeModes", JSONArray(aeModes.toList()))
        .put("afModes", JSONArray(afModes.toList()))
        .put("antibanding", JSONArray(antibandingModes.toList()))
        .put("maxRegionsAf", maxRegionsAf)
        .put("maxRegionsAe", maxRegionsAe)
        .put("ev", evRange?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL)
        .put("evStep", evStep?.toString() ?: JSONObject.NULL)
        .put("focalMm", JSONArray(focalLengths.map { it.toDouble() }))
        .put("sensorMm", physicalSize?.toString() ?: JSONObject.NULL)

    /** Every characteristic the device reports, vendor keys included. */
    fun fullDumpJson(context: Context): JSONObject {
        val root = JSONObject().put("summary", summaryJson()).put("logical", dumpChars(chars))
        val physical = JSONObject()
        val manager = context.getSystemService(CameraManager::class.java)
        runCatching { chars.physicalCameraIds }.getOrDefault(emptySet()).forEach { id ->
            runCatching { manager.getCameraCharacteristics(id) }.getOrNull()?.let { physical.put(id, dumpChars(it)) }
        }
        return root.put("physical", physical)
    }

    companion object {
        @OptIn(ExperimentalCamera2Interop::class)
        fun read(context: Context, camera: Camera): CameraCaps? = runCatching {
            val id = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            CameraCaps(id, context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id))
        }.getOrNull()

        fun levelName(level: Int?) = when (level) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            null -> "?"
            else -> "L$level"
        }

        fun calibrationName(c: Int?) = when (c) {
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
            else -> "?"
        }

        private fun dumpChars(chars: CameraCharacteristics): JSONObject {
            val out = JSONObject()
            for (key in chars.keys) {
                val value = runCatching { chars.get(key) }.getOrNull() ?: continue
                out.put(key.name, valueJson(value))
            }
            return out
        }

        private fun valueJson(v: Any): Any = when (v) {
            is IntArray -> JSONArray(v.toList())
            is LongArray -> JSONArray(v.toList())
            is FloatArray -> JSONArray(v.map { it.toDouble() })
            is BooleanArray -> JSONArray(v.toList())
            is ByteArray -> JSONArray(v.map { it.toInt() })
            is Array<*> -> JSONArray(v.mapNotNull { it?.let(::valueJson) })
            // Before Number: Rational is one, and org.json would write it bare ("1/2"), which is not JSON.
            is Size, is SizeF, is Rect, is Rational -> v.toString()
            is Number, is Boolean, is String -> v
            is Range<*> -> JSONArray(listOf(v.lower.toString(), v.upper.toString()))
            is StreamConfigurationMap -> JSONObject().apply {
                val yuv = v.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
                put("yuv", JSONArray(yuv.map { "${it}@${v.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it)}" }))
                val hi = runCatching { v.getHighResolutionOutputSizes(ImageFormat.YUV_420_888) }.getOrNull() ?: emptyArray()
                put("yuvHighRes", JSONArray(hi.map { it.toString() }))
            }
            else -> v.toString()
        }
    }
}
