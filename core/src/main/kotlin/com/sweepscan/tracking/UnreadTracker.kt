package com.sweepscan.tracking

import kotlin.math.hypot

/**
 * Follows barcodes the decoders *located* but never read ("ghosts"), separately from the real
 * tracks, so counting behaviour is unchanged. A ghost that leaves view after being seen in enough
 * frames is one label the operator probably missed: an undercount warning (S7).
 *
 * Only frames in which the label is completely inside the picture count towards "enough": a label
 * cut by the frame edge can't be read, so seeing it there says nothing about its readability.
 *
 * A label is reported once: detection of an unreadable label flickers, and every dropout would
 * otherwise add another warning for the same spot. Reported spots are remembered in scene
 * coordinates under the same rule as lost tracks (for a while, or as long as the view stays put).
 */
internal class UnreadTracker(
    private val lostTimeoutMs: Long,
    private val minFrames: Int,
    private val gate: Float,
    private val rememberMs: Long,
    private val rememberMaxMs: Long,
) {
    private class Ghost(var box: Box, var lastSeenMs: Long, var frames: Int) {
        var seenThisFrame = true
    }

    private class Reported(var sceneBox: Box, val atMs: Long, val viewX: Float, val viewY: Float)

    private val ghosts = mutableListOf<Ghost>()
    private val reported = mutableListOf<Reported>()

    val count: Int get() = ghosts.size

    fun boxes(): List<Pair<Box, Boolean>> = ghosts.map { it.box to it.seenThisFrame }

    /**
     * @param located located-only detections no real track used this frame
     * @param readable boxes of real tracks visible this frame (a ghost on one turned out readable)
     * @param viewX integrated scene offset (image position = scene position + offset)
     * @param holdPx view travel below which a reported spot is remembered beyond [rememberMs]
     * @param fullyVisible false for a box cut by the frame edge
     * @return ghosts that just expired after enough frames (labels likely never read)
     */
    fun update(
        located: List<Box>,
        readable: List<Box>,
        shiftX: Float,
        shiftY: Float,
        nowMs: Long,
        viewX: Float = 0f,
        viewY: Float = 0f,
        holdPx: Float = 0f,
        fullyVisible: (Box) -> Boolean = { true },
    ): Int {
        ghosts.forEach { it.box = it.box.translate(shiftX, shiftY); it.seenThisFrame = false }
        ghosts.removeAll { g -> readable.any { overlaps(g.box, it) } }

        val pairs = ArrayList<Triple<Ghost, Int, Float>>()
        for (g in ghosts) for ((i, b) in located.withIndex()) {
            gatedCost(g.box, b, gate)?.let { pairs += Triple(g, i, it) }
        }
        pairs.sortBy { it.third }
        val used = BooleanArray(located.size)
        for ((g, i, _) in pairs) {
            if (g.seenThisFrame || used[i]) continue
            used[i] = true
            g.box = located[i]
            g.lastSeenMs = nowMs
            if (fullyVisible(g.box)) g.frames++
            g.seenThisFrame = true
        }
        for ((i, b) in located.withIndex()) {
            if (!used[i] && readable.none { overlaps(b, it) }) ghosts += Ghost(b, nowMs, if (fullyVisible(b)) 1 else 0)
        }

        reported.removeAll { r ->
            val age = nowMs - r.atMs
            age > rememberMaxMs || (age > rememberMs && hypot(viewX - r.viewX, viewY - r.viewY) >= holdPx)
        }
        var expired = 0
        val iterator = ghosts.iterator()
        while (iterator.hasNext()) {
            val g = iterator.next()
            if (nowMs - g.lastSeenMs <= lostTimeoutMs) continue
            if (g.frames >= minFrames && report(g, nowMs, viewX, viewY)) expired++
            iterator.remove()
        }
        return expired
    }

    /** See [BarcodeTracker.applyZoom]; [offsetX]/[offsetY] = the current scene offset. */
    fun applyZoom(scale: Float, cx: Float, cy: Float, offsetX: Float, offsetY: Float) {
        for (g in ghosts) g.box = g.box.scaleAbout(scale, cx, cy)
        for (r in reported) {
            r.sceneBox = r.sceneBox.translate(offsetX, offsetY).scaleAbout(scale, cx, cy).translate(-offsetX, -offsetY)
        }
    }

    /** Ends tracking (pause/reset): live ghosts with enough frames count as unread. */
    fun flush(nowMs: Long = 0L, viewX: Float = 0f, viewY: Float = 0f): Int {
        val n = ghosts.count { it.frames >= minFrames && report(it, nowMs, viewX, viewY) }
        ghosts.clear()
        reported.clear()
        return n
    }

    /** False if this spot was already reported. */
    private fun report(g: Ghost, nowMs: Long, viewX: Float, viewY: Float): Boolean {
        val scene = g.box.translate(-viewX, -viewY)
        if (reported.any { overlaps(it.sceneBox, scene) }) return false
        reported += Reported(scene, nowMs, viewX, viewY)
        return true
    }

    private fun overlaps(a: Box, b: Box) = a.iou(b) > 0.2f || gatedCost(a, b, 0.5f) != null
}
