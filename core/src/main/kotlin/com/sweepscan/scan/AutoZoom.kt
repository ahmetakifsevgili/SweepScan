package com.sweepscan.scan

import kotlin.math.abs

/**
 * Picks the zoom step from how large the barcodes look, so the operator does not have to.
 *
 * The decode benchmark on real labels (16 Nov) puts the useful range at roughly 190-700 pixels of
 * barcode length: below ~190 px ML Kit stops reading, and larger only costs field of view. Pixels
 * per bar do not depend on the analysis resolution, so the thresholds are absolute.
 *
 * Rules:
 * - the median barcode length over the last [windowMs] must stay below [minLengthPx] (or above
 *   [maxLengthPx]) for [holdMs] before the zoom moves one step;
 * - a change waits while [update]'s `busy` is true (a real track is on screen): zooming rescales
 *   every box, and a counted label still in view would otherwise be counted again;
 * - after a change the samples are dropped and nothing moves for [cooldownMs] (the lens may switch
 *   and refocus).
 */
class AutoZoom(
    private val steps: List<Float> = listOf(1f, 1.5f, 2f, 3f),
    private val minLengthPx: Float = 260f,
    private val maxLengthPx: Float = 700f,
    private val windowMs: Long = 1200,
    private val holdMs: Long = 800,
    private val cooldownMs: Long = 1500,
    private val minSamples: Int = 5,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lowSince = NONE
    private var highSince = NONE
    private var lastChangeMs = NONE

    /** Median barcode length of the current window, for the log. */
    var median: Float? = null
        private set

    fun reset() {
        samples.clear()
        lowSince = NONE
        highSince = NONE
        median = null
    }

    /**
     * @param lengths barcode lengths (long side of the box, px) seen this frame
     * @param busy a real track is on screen: a change is held back until it is not
     * @param current the zoom now in use
     * @return the zoom to switch to, or null
     */
    fun update(nowMs: Long, lengths: List<Float>, busy: Boolean, current: Float): Float? {
        for (l in lengths) samples.addLast(nowMs to l)
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMs) samples.removeFirst()
        if (lastChangeMs != NONE && nowMs - lastChangeMs < cooldownMs) return null
        if (samples.size < minSamples) {
            median = null
            lowSince = NONE
            highSince = NONE
            return null
        }
        val m = samples.map { it.second }.sorted().let { it[it.size / 2] }
        median = m
        lowSince = if (m < minLengthPx) (if (lowSince == NONE) nowMs else lowSince) else NONE
        highSince = if (m > maxLengthPx) (if (highSince == NONE) nowMs else highSince) else NONE

        val index = steps.indices.minByOrNull { abs(steps[it] - current) } ?: return null
        val target = when {
            lowSince != NONE && nowMs - lowSince >= holdMs && index < steps.lastIndex -> steps[index + 1]
            highSince != NONE && nowMs - highSince >= holdMs && index > 0 -> steps[index - 1]
            else -> return null
        }
        if (busy) return null
        lastChangeMs = nowMs
        reset()
        median = m // kept for the log line of this change
        return target
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
