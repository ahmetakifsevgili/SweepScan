package com.sweepscan.camera

import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.sweepscan.scan.LumaStats
import com.sweepscan.tracking.Detection

/**
 * Runs on the analysis thread. Measures label luma every frame (logged) and, on the MANUAL short
 * exposure path, steers ISO through [IsoController] at most every 400 ms.
 */
class ExposureLoop(private val apply: (iso: Int) -> Unit) {

    @Volatile private var controller: IsoController? = null
    private var lastUpdateMs = 0L

    @Volatile var lastLuma = 0f
        private set

    val iso: Int? get() = controller?.iso

    /** Called after every bind / tuning change. */
    fun configure(plan: TuningPlan, caps: CameraCaps?) {
        controller = if (plan.exposure == ExposurePath.MANUAL) {
            val range = caps?.isoRange
            IsoController(range?.lower ?: 100, range?.upper ?: 3200)
        } else {
            null
        }
    }

    fun onFrame(image: ImageProxy, detections: List<Detection>, appliedIso: Int?) {
        val boxes = detections.map { it.box }
        lastLuma = LumaStats.meanLuma(image, boxes)
        val iso = controller ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdateMs < UPDATE_MS) return
        lastUpdateMs = now
        val target = if (boxes.isEmpty()) IsoController.TARGET_CENTER else IsoController.TARGET_LABELS
        iso.update(lastLuma, target, appliedIso)?.let(apply)
    }

    private companion object {
        const val UPDATE_MS = 400L
    }
}
