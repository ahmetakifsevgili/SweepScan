package com.sweepscan.scan

import android.content.SharedPreferences
import com.sweepscan.camera.ExposureMode
import com.sweepscan.camera.FocusMode
import com.sweepscan.tracking.BarcodeRules
import org.json.JSONObject

/** Camera and pipeline switches, editable on the phone so settings can be A/B-tested in the field. */
class ScanSettings(private val prefs: SharedPreferences) {

    /** Short side of the analysis stream: 1080, 1440 or 2160. */
    var resolution: Int
        get() = prefs.getInt("resolution", 1080)
        set(value) = prefs.edit().putInt("resolution", value).apply()

    /**
     * 1.5x by default: at 1x a readable label needs the phone ~15-20 cm away, where autofocus keeps
     * hunting (16 Nov phone recordings: lens moved in 25 % of frames, read rate 60 %).
     */
    var zoom: Float
        get() = prefs.getFloat("zoom", 1.5f)
        set(value) = prefs.edit().putFloat("zoom", value).apply()

    /** Zoom follows the barcode size ([AutoZoom]); picking a zoom by hand switches it off. */
    var autoZoom: Boolean
        get() = prefs.getBoolean("auto_zoom", true)
        set(value) = prefs.edit().putBoolean("auto_zoom", value).apply()

    var torch: Boolean
        get() = prefs.getBoolean("torch", false)
        set(value) = prefs.edit().putBoolean("torch", value).apply()

    /** Second decoder on crops ML Kit located but could not read. */
    var zxing: Boolean
        get() = prefs.getBoolean("zxing", true)
        set(value) = prefs.edit().putBoolean("zxing", value).apply()

    /** Third reader: the number printed with a barcode whose bars cannot be read ([OcrEngine]). */
    var ocr: Boolean
        get() = prefs.getBoolean("ocr", true)
        set(value) = prefs.edit().putBoolean("ocr", value).apply()

    /** Optical-flow camera-motion estimate. */
    var flow: Boolean
        get() = prefs.getBoolean("flow", true)
        set(value) = prefs.edit().putBoolean("flow", value).apply()

    /** Only 8- or 13-digit values count (otherwise 8–14). Applied to [BarcodeRules.allowedLengths]. */
    var strictLengths: Boolean
        get() = prefs.getBoolean("strict_lengths", true)
        set(value) {
            prefs.edit().putBoolean("strict_lengths", value).apply()
            applyLengths()
        }

    fun applyLengths() {
        BarcodeRules.allowedLengths = if (strictLengths) BarcodeRules.STRICT_LENGTHS else BarcodeRules.LOOSE_LENGTHS
    }

    /**
     * SHORT by default: auto exposure picks 20 ms indoors and every hand movement smears the bars.
     * On the S23 Ultra, 4 ms (MANUAL path, ISO ~1600) read 83 % of frames against 17-60 % on auto.
     */
    var exposure: ExposureMode
        get() = enumPref("exposure", ExposureMode.SHORT)
        set(value) = prefs.edit().putString("exposure", value.name).apply()

    /** Exposure time in the SHORT mode, µs. */
    var shortExposureUs: Int
        get() = prefs.getInt("short_exposure_us", 4000)
        set(value) = prefs.edit().putInt("short_exposure_us", value).apply()

    var focus: FocusMode
        get() = enumPref("focus", FocusMode.CONTINUOUS)
        set(value) = prefs.edit().putString("focus", value.name).apply()

    /** Lens distance in the FIXED focus mode, metres. */
    var focusDistanceM: Float
        get() = prefs.getFloat("focus_distance_m", 0.9f)
        set(value) = prefs.edit().putFloat("focus_distance_m", value).apply()

    /**
     * Appearance fingerprints (MediaPipe) when a label is counted or may be a remembered box; logged
     * only (`reid` lines), never changing the count. Off by default: on the phone's label crops the
     * generic MobileNet-V3 embedder gave the same similarity to the same barcode as to different ones
     * (median 0.59 vs 0.59, AppearanceBenchmarkTest) and took ~33 ms per crop. Useful only with a
     * model trained on these labels.
     */
    var appearance: Boolean
        get() = prefs.getBoolean("appearance", false)
        set(value) = prefs.edit().putBoolean("appearance", value).apply()

    /** Technical lines on the scan screen (fps, decoder times, exposure, ISO, focus). */
    var showHud: Boolean
        get() = prefs.getBoolean("show_hud", false)
        set(value) = prefs.edit().putBoolean("show_hud", value).apply()

    /** Save analysed frames (PNG + metadata) while recording. */
    var sampleFrames: Boolean
        get() = prefs.getBoolean("sample_frames", true)
        set(value) = prefs.edit().putBoolean("sample_frames", value).apply()

    fun toJson(): JSONObject = JSONObject()
        .put("sampleFrames", sampleFrames)
        .put("resolution", resolution)
        .put("zoom", zoom.toDouble())
        .put("autoZoom", autoZoom)
        .put("appearance", appearance)
        .put("torch", torch)
        .put("zxing", zxing)
        .put("ocr", ocr)
        .put("flow", flow)
        .put("strictLengths", strictLengths)
        .put("exposure", exposure.name)
        .put("shortExposureUs", shortExposureUs)
        .put("focus", focus.name)
        .put("focusDistanceM", focusDistanceM.toDouble())

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    companion object {
        val RESOLUTIONS = intArrayOf(1080, 1440, 2160)
        /** 3x switches the S23 Ultra's logical camera to its 3x telephoto (sharp from 40 cm). */
        val ZOOMS = floatArrayOf(1f, 1.5f, 2f, 3f)
        val SHORT_EXPOSURES_US = intArrayOf(500, 1000, 2000, 4000)
        /** 0.2-0.3 m for close work at 1x; 0.45-0.7 m with the 3x telephoto (nearest focus 0.4 m). */
        val FOCUS_DISTANCES_M = floatArrayOf(0.2f, 0.3f, 0.45f, 0.7f, 0.9f, 1.2f)
    }
}
