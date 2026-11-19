package com.sweepscan.scan

import com.sweepscan.camera.ExposureMode

/**
 * Automatic A/B test of camera settings: one recording, the app switches zoom and exposure every
 * [STEP_SECONDS] while the operator keeps sweeping the same labels. `tools/latency_report.py`
 * splits the recording at the logged `tour_step` events and compares the steps.
 *
 * Counts made during a tour are logged but never added to the stock count.
 */
class SettingsTour(private val settings: ScanSettings) {

    data class Step(val zoom: Float, val exposure: ExposureMode, val shortExposureUs: Int) {
        val exposureLabel: String
            get() = if (exposure == ExposureMode.SHORT) "${shortExposureUs / 1000} ms" else "auto"
    }

    private class Saved(val zoom: Float, val exposure: ExposureMode, val shortExposureUs: Int)

    private var saved: Saved? = null
    var index = -1
        private set
    /** Seconds of scanning left in the current step. */
    var remaining = 0
        private set
    /** Labels counted in the current step (shown on screen: tour counts never reach the stock count). */
    var stepCounts = 0
        private set

    fun onCounted() {
        if (active) stepCounts++
    }

    val active: Boolean get() = index >= 0
    val current: Step? get() = STEPS.getOrNull(index)
    val size: Int get() = STEPS.size

    /** Remembers the operator's settings and applies the first step. */
    fun start(): Step {
        saved = Saved(settings.zoom, settings.exposure, settings.shortExposureUs)
        index = 0
        return apply()
    }

    /**
     * Call once per second while scanning. Returns the step just entered, [FINISHED] when the
     * tour is over (settings already restored), or null when nothing changed.
     */
    fun tick(): Step? {
        if (!active) return null
        if (--remaining > 0) return null
        index++
        if (index >= STEPS.size) {
            stop()
            return FINISHED
        }
        return apply()
    }

    /** Restores the operator's settings. */
    fun stop() {
        saved?.let {
            settings.zoom = it.zoom
            settings.exposure = it.exposure
            settings.shortExposureUs = it.shortExposureUs
        }
        saved = null
        index = -1
        remaining = 0
    }

    private fun apply(): Step {
        val step = STEPS[index]
        settings.zoom = step.zoom
        settings.exposure = step.exposure
        settings.shortExposureUs = step.shortExposureUs
        remaining = STEP_SECONDS
        stepCounts = 0
        return step
    }

    companion object {
        const val STEP_SECONDS = 20

        /** Marker returned by [tick] when the last step has ended. */
        val FINISHED = Step(0f, ExposureMode.AUTO, 0)

        val STEPS: List<Step> = listOf(1.5f, 2f, 3f).flatMap { zoom ->
            listOf(
                Step(zoom, ExposureMode.AUTO, 1000),
                Step(zoom, ExposureMode.SHORT, 4000),
                Step(zoom, ExposureMode.SHORT, 2000),
            )
        }
    }
}
