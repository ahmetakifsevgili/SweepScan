package com.sweepscan.scan

import android.content.Context
import android.os.Build
import com.sweepscan.hid.HidTiming
import com.sweepscan.tracking.BarcodeRules
import com.sweepscan.tracking.TrackerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the `meta` line that opens a field recording, so a log fully describes its own setup. */
object RecordingMeta {

    fun build(
        context: Context,
        settings: ScanSettings,
        tracker: TrackerConfig,
        timing: HidTiming,
        liveSend: Boolean,
        camera: JSONObject? = null,
    ): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return JSONObject()
            .put("app", JSONObject().put("name", info.versionName).put("code", code))
            .put("started", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .put(
                "device",
                JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT).put("display", Build.DISPLAY),
            )
            .put("settings", settings.toJson().put("liveSend", liveSend))
            .put("tracker", trackerJson(tracker))
            .put(
                "hid",
                JSONObject().put("holdMs", timing.holdMs).put("gapMs", timing.gapMs)
                    .put("slowPc", timing.slowPc).put("newValueExtra", timing.newValueExtra.toDouble()),
            )
            .put("camera", camera ?: JSONObject.NULL)
    }

    fun trackerJson(c: TrackerConfig): JSONObject = JSONObject()
        .put("minHits", c.minHits)
        .put("minAgreement", c.minAgreement.toDouble())
        .put("lostTimeoutMs", c.lostTimeoutMs)
        .put("reidWindowMs", c.reidWindowMs)
        .put("matchGate", c.matchGate.toDouble())
        .put("reidGate", c.reidGate.toDouble())
        .put("velocitySmoothing", c.velocitySmoothing.toDouble())
        .put("minMotionConfidence", c.minMotionConfidence.toDouble())
        .put("validateReads", c.validateReads)
        .put("lengths", JSONArray((c.lengths ?: BarcodeRules.allowedLengths).sorted()))
        .put("edgeMarginPx", c.edgeMarginPx.toDouble())
        .put("dedupeIou", c.dedupeIou.toDouble())
        .put("requireGs1For13", c.requireGs1For13)
        .put("uncountedWarnMs", c.uncountedWarnMs)
        .put("flowShiftFirst", c.flowShiftFirst)
        .put("gyroFallback", c.gyroFallback)
        .put("neighbourReid", c.neighbourReid)
        .put("reidAlongNoConflict", c.reidAlongNoConflict.toDouble())
        .put("reidAlongWithNeighbour", c.reidAlongWithNeighbour.toDouble())
        .put("appearanceVeto", c.appearanceVeto?.toDouble() ?: org.json.JSONObject.NULL)
        .put("absorbTwins", c.absorbTwins)
        .put("reidentify", c.reidentify)
        .put("reidHoldTravel", c.reidHoldTravel.toDouble())
        .put("reidHoldMaxMs", c.reidHoldMaxMs)
        .put("priorFirst", c.priorFirst)
}
