package com.sweepscan.twin

import com.sweepscan.location.Face
import com.sweepscan.location.Pt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The camera's flight to a shelf, as a film shot: it starts high above the entrance looking at
 * the target (where in the building), glides like a drone over the walking route, looking ahead,
 * then comes down and turns to face the rack, stopping in front of the found boxes. No gestures
 * are needed; [at] gives the eye and the point looked at for any moment.
 *
 * The path is a dense, smoothed polyline (corners of the route rounded), walked at a speed that
 * eases in and out, so the camera never jumps.
 */
class FlyPath(
    private val eyes: List<V3>,
    private val aheads: List<V3>,
    /** The point the shot ends on (the found boxes). */
    val focus: V3,
    /** Where the camera stands at the end. */
    val end: V3,
    /** Seconds the flight takes. */
    val seconds: Float,
) {
    private val lengths: FloatArray = FloatArray(eyes.size).also { l ->
        for (i in 1 until eyes.size) l[i] = l[i - 1] + (eyes[i] - eyes[i - 1]).length()
    }

    /** Eye and look-at point at [time] seconds (clamped to the end). */
    fun at(time: Float): Pair<V3, V3> {
        val t = (time / seconds).coerceIn(0f, 1f)
        val d = ease(t) * lengths.last()
        var lo = 0
        var hi = lengths.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (lengths[mid] < d) lo = mid else hi = mid
        }
        val seg = (lengths[hi] - lengths[lo]).coerceAtLeast(1e-6f)
        val f = ((d - lengths[lo]) / seg).coerceIn(0f, 1f)
        val eye = lerp(eyes[lo], eyes[hi], f)
        val ahead = lerp(aheads[lo], aheads[hi], f)
        // Look at the target at the start (where it is) and at the end (what it is); ahead in between.
        val w = max(smooth(1f - t / 0.2f), smooth((t - 0.5f) / 0.4f))
        return eye to lerp(ahead, focus, w)
    }

    /** A slow sway around the final view after landing ([time] seconds since the start). */
    fun idle(time: Float): Pair<V3, V3> {
        val s = time - seconds
        val off = end - focus
        val angle = 0.12f * sin(s * 0.45f)
        val c = cos(angle)
        val sn = sin(angle)
        val turned = V3(off.x * c + off.z * sn, off.y + 0.06f * sin(s * 0.7f), -off.x * sn + off.z * c)
        return (focus + turned) to focus
    }

    companion object {
        /** Drone height over the route, metres. */
        const val HIGH = 7.5f

        fun ease(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2

        fun smooth(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }

        fun lerp(a: V3, b: V3, t: Float) = a + (b - a) * t

        /** Unit vector from a rack's front out into its corridor. */
        fun outward(face: Face) = when (face) {
            Face.N -> V3(0f, 0f, -1f)
            Face.S -> V3(0f, 0f, 1f)
            Face.E -> V3(1f, 0f, 0f)
            Face.W -> V3(-1f, 0f, 0f)
        }

        /**
         * The flight from the entrance ([route] in plan units, entrance first; may be null) to
         * [focus] on a rack facing [face].
         */
        fun toShelf(route: List<Pt>?, focus: V3, face: Face): FlyPath {
            val out = outward(face)
            val end = focus + out * 2.3f + V3(0f, 0.55f, 0f)
            // Ground track: the route (or a straight line from the corridor), rounded and resampled.
            val ground: List<V3> = route?.takeIf { it.size >= 2 }?.map { V3(it.x * TwinScene.S, 0f, it.y * TwinScene.S) }
                ?: listOf(end + out * 9f, end + out * 2f).map { V3(it.x, 0f, it.z) }
            val track = smoothPolyline(resample(ground + V3(end.x, 0f, end.z), 0.25f), 24)
            val total = arc(track)
            val eyes = ArrayList<V3>(track.size + 40)
            val aheads = ArrayList<V3>(track.size + 40)
            var run = 0f
            for ((i, p) in track.withIndex()) {
                if (i > 0) run += (p - track[i - 1]).length()
                val s = if (total > 0f) run / total else 1f
                // High over the route, coming down over the last third.
                val h = HIGH + (end.y - HIGH) * smooth((s - 0.55f) / 0.45f)
                // The last steps slide over to the standing point in front of the rack.
                val toEnd = smooth((s - 0.7f) / 0.3f)
                eyes += lerp(V3(p.x, h, p.z), end, toEnd)
                val look = pointAt(track, total, (run + 4f).coerceAtMost(total))
                aheads += V3(look.x, 0.6f, look.z)
            }
            // Start: back and above the entrance, so the first frame shows the whole way.
            val first = track.first()
            val dir = (track[minOf(8, track.size - 1)] - first).normalized()
            val start = first - dir * 5f + V3(0f, HIGH + 3f, 0f)
            val lead = ArrayList<V3>()
            val leadLook = ArrayList<V3>()
            for (k in 0 until 20) {
                val f = k / 20f
                lead += lerp(start, eyes.first(), f)
                leadLook += aheads.first()
            }
            eyes.addAll(0, lead)
            aheads.addAll(0, leadLook)
            eyes += end
            aheads += focus
            val length = arc(eyes)
            return FlyPath(eyes, aheads, focus, end, (2.8f + length / 12f).coerceIn(3.5f, 8f))
        }

        private fun arc(p: List<V3>): Float {
            var l = 0f
            for (i in 1 until p.size) l += (p[i] - p[i - 1]).length()
            return l
        }

        private fun pointAt(p: List<V3>, total: Float, d: Float): V3 {
            var run = 0f
            for (i in 1 until p.size) {
                val seg = (p[i] - p[i - 1]).length()
                if (run + seg >= d) return lerp(p[i - 1], p[i], ((d - run) / seg.coerceAtLeast(1e-6f)).coerceIn(0f, 1f))
                run += seg
            }
            return p.last()
        }

        /** Points every [step] metres along the polyline (ends kept). */
        fun resample(points: List<V3>, step: Float): List<V3> {
            val out = ArrayList<V3>()
            out += points.first()
            var carry = 0f
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val len = (b - a).length()
                var d = step - carry
                while (d <= len) {
                    out += lerp(a, b, d / len)
                    d += step
                }
                carry = len - (d - step)
            }
            if ((out.last() - points.last()).length() > 1e-3f) out += points.last()
            return out
        }

        /** Rounds corners: a moving average over ±[radius] points, ends pinned. */
        fun smoothPolyline(p: List<V3>, radius: Int): List<V3> {
            if (p.size < 3) return p
            return p.indices.map { i ->
                val r = minOf(radius, i, p.size - 1 - i)
                var x = 0f
                var z = 0f
                for (k in i - r..i + r) { x += p[k].x; z += p[k].z }
                val n = (2 * r + 1).toFloat()
                V3(x / n, p[i].y, z / n)
            }
        }
    }
}
