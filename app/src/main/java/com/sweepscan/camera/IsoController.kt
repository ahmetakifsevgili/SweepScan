package com.sweepscan.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Brightness loop for manual short exposure (AE off): with the exposure time fixed at ~1 ms the
 * only free knob is ISO. Steers mean label luma towards a target with a dead band and bounded
 * steps, and waits until the camera has actually applied the previous command.
 */
class IsoController(private val minIso: Int, maxIso: Int, start: Int = 1600) {

    private val maxIso = minOf(maxIso, ISO_CAP)

    var iso: Int = start.coerceIn(minIso, this.maxIso)
        private set

    private var staleRun = 0

    /**
     * @param luma mean luma (0–255) of the region that matters
     * @param appliedIso ISO reported by the capture result of the frame the luma came from
     * @return the new ISO to command, or null to keep the current one
     */
    fun update(luma: Float, target: Float, appliedIso: Int?): Int? {
        // Stale frame: the previous command hasn't reached the sensor yet. Some HALs round or
        // clamp ISO and never report the commanded value, so stop waiting after a few updates.
        if (appliedIso != null && abs(appliedIso - iso) > iso * 0.1f && staleRun < MAX_STALE) {
            staleRun++
            return null
        }
        staleRun = 0
        val next = if (luma >= SATURATED) {
            iso / 2
        } else {
            val ratio = target / max(luma, 1f)
            if (ratio in (1f - DEAD_BAND)..(1f + DEAD_BAND)) return null
            (iso * ratio.pow(GAIN).coerceIn(0.5f, 2f)).roundToInt()
        }.coerceIn(minIso, maxIso)
        if (next == iso) return null
        iso = next
        return next
    }

    companion object {
        const val ISO_CAP = 6400
        const val TARGET_LABELS = 110f
        const val TARGET_CENTER = 90f
        private const val DEAD_BAND = 0.15f
        private const val GAIN = 0.7f
        private const val SATURATED = 254f
        /** At one update per 400 ms: give the camera ~1.2 s to apply a command. */
        private const val MAX_STALE = 3
    }
}
