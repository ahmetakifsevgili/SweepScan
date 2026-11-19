package com.sweepscan.twin

import com.sweepscan.location.Face
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A camera that circles a target point: [yaw] turns around it (0 = standing south, looking
 * north, like the 2D plan), [pitch] looks down from above (0 = level, π/2 = straight down),
 * [distance] in metres.
 */
data class OrbitCamera(
    val target: V3 = V3.ZERO,
    val yaw: Float = 0f,
    val pitch: Float = 0.9f,
    val distance: Float = 30f,
) {
    val eye: V3
        get() = target + V3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance

    fun view(): FloatArray = Mat4.lookAt(eye, target)

    /** Fog from just behind the point the camera circles to well past it: start, end (m from the eye). */
    val fog: FloatArray get() = floatArrayOf(distance + 6f, distance * 2.2f + 30f)

    /**
     * View-projection for a [width]×[height] view whose bottom [insetPx] is covered (by the
     * bottom sheet): the target is drawn in the middle of the part that stays visible.
     */
    fun viewProj(width: Int, height: Int, insetPx: Float = 0f): FloatArray {
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        // Near and far follow the distance: depth precision stays good enough that the route
        // ribbon does not flicker into the floor when seen from far away.
        val p = Mat4.perspective(FOV, aspect, (distance * 0.04f).coerceIn(0.1f, 4f), distance * 4f + 100f)
        return Mat4.multiply(Mat4.shiftNdc(p, 0f, shift(height, insetPx)), view())
    }

    /** The ray through screen pixel ([px], [py]): origin and normalised direction. */
    fun ray(px: Float, py: Float, width: Int, height: Int, insetPx: Float = 0f): Pair<V3, V3> {
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        val nx = 2f * px / width - 1f
        val ny = 1f - 2f * py / height - shift(height, insetPx)
        val f = (target - eye).normalized()
        val s = f.cross(V3(0f, 1f, 0f)).normalized()
        val u = s.cross(f)
        val t = tan(FOV / 2f)
        return eye to (f + s * (nx * t * aspect) + u * (ny * t)).normalized()
    }

    /** Where the ray through pixel ([px], [py]) meets the floor (y = 0), or null above the horizon. */
    fun groundPoint(px: Float, py: Float, width: Int, height: Int, insetPx: Float = 0f): V3? {
        val (o, d) = ray(px, py, width, height, insetPx)
        if (d.y > -1e-3f) return null
        val t = -o.y / d.y
        return if (t > 0f && t < 2000f) o + d * t else null
    }

    /**
     * Map-style drag: the floor point under the finger at ([fromX], [fromY]) moves with it to
     * ([toX], [toY]).
     */
    fun panned(fromX: Float, fromY: Float, toX: Float, toY: Float, width: Int, height: Int, insetPx: Float = 0f): OrbitCamera {
        val a = groundPoint(fromX, fromY, width, height, insetPx) ?: return this
        val b = groundPoint(toX, toY, width, height, insetPx) ?: return this
        return copy(target = target + (a - b))
    }

    /** Pinch: closer by [factor] (< 1 = closer), keeping the floor point under ([px], [py]) in place. */
    fun zoomedAt(px: Float, py: Float, factor: Float, width: Int, height: Int, insetPx: Float = 0f): OrbitCamera {
        val newDistance = (distance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val f = groundPoint(px, py, width, height, insetPx) ?: return copy(distance = newDistance)
        val k = newDistance / distance
        return copy(target = f + (target - f) * k, distance = newDistance)
    }

    /** Twist: turns the view by [angle] (radians) around the floor point under ([px], [py]). */
    fun rotatedAt(px: Float, py: Float, angle: Float, width: Int, height: Int, insetPx: Float = 0f): OrbitCamera {
        val f = groundPoint(px, py, width, height, insetPx) ?: return copy(yaw = yaw + angle)
        val c = cos(angle)
        val s = sin(angle)
        val dx = target.x - f.x
        val dz = target.z - f.z
        // The same rotation as the camera's yaw, so the point stays put on screen.
        val t = V3(f.x + c * dx + s * dz, target.y, f.z - s * dx + c * dz)
        return copy(target = t, yaw = yaw + angle)
    }

    fun clamped(): OrbitCamera = copy(
        pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH),
        distance = distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE),
    )

    /** One step of an animation from this camera to [to], [t] in 0..1; turns the short way round. */
    fun lerp(to: OrbitCamera, t: Float): OrbitCamera {
        var dy = (to.yaw - yaw) % TWO_PI
        if (dy > PI) dy -= TWO_PI
        if (dy < -PI) dy += TWO_PI
        return OrbitCamera(
            target + (to.target - target) * t,
            yaw + dy * t,
            pitch + (to.pitch - pitch) * t,
            distance + (to.distance - distance) * t,
        )
    }

    companion object {
        const val FOV = 0.78f // ≈ 45°
        const val MIN_PITCH = 0.12f
        const val MAX_PITCH = 1.45f
        const val MIN_DISTANCE = 2.5f
        const val MAX_DISTANCE = 220f
        private const val TWO_PI = (2 * PI).toFloat()

        private fun shift(height: Int, insetPx: Float) = if (height <= 0) 0f else insetPx / height

        /**
         * Looks down on the box [minX]..[maxX] × [minZ]..[maxZ] (and up to [top] m high) from the
         * direction [yaw], as close as possible with all of it inside the visible part of a
         * [width]×[height] view (above [insetPx]), with a [marginPx] border.
         */
        fun overview(
            minX: Float, minZ: Float, maxX: Float, maxZ: Float, width: Int, height: Int, insetPx: Float = 0f,
            yaw: Float = DEFAULT_YAW, pitch: Float = 0.95f, top: Float = 2.6f, marginPx: Float = width * 0.06f,
        ): OrbitCamera {
            val center = V3((minX + maxX) / 2f, 0f, (minZ + maxZ) / 2f)
            val corners = listOf(minX, maxX).flatMap { x -> listOf(minZ, maxZ).flatMap { z -> listOf(V3(x, 0f, z), V3(x, top, z)) } }
            fun fits(d: Float): Boolean {
                val vp = OrbitCamera(center, yaw, pitch, d).viewProj(width, height, insetPx)
                return corners.all {
                    val p = Mat4.project(vp, it, width, height)
                    p.visible && p.x in marginPx..(width - marginPx) && p.y in marginPx..(height - insetPx - marginPx)
                }
            }
            var lo = MIN_DISTANCE
            var hi = 400f
            if (!fits(hi)) return OrbitCamera(center, yaw, pitch, hi)
            repeat(24) { val mid = (lo + hi) / 2f; if (fits(mid)) hi = mid else lo = mid }
            return OrbitCamera(center, yaw, pitch, hi)
        }

        /**
         * The closer of two overviews: from the south (like the plan) or turned a quarter, so a
         * long, flat site fills a tall phone screen. The plan's way round wins unless turning
         * brings the site clearly closer.
         */
        fun bestOverview(minX: Float, minZ: Float, maxX: Float, maxZ: Float, width: Int, height: Int, insetPx: Float = 0f): OrbitCamera {
            val plain = overview(minX, minZ, maxX, maxZ, width, height, insetPx, DEFAULT_YAW)
            val turned = overview(minX, minZ, maxX, maxZ, width, height, insetPx, DEFAULT_YAW + (PI / 2).toFloat())
            return if (turned.distance < plain.distance * 0.85f) turned else plain
        }

        const val DEFAULT_YAW = -0.32f

        /** Stands in front of a rack facing [face], looking at [point] a little from above. */
        fun facing(point: V3, face: Face, distance: Float = 6.5f): OrbitCamera {
            // The camera stands on the corridor side, i.e. the side the rack faces.
            val yaw = when (face) {
                Face.S -> 0f
                Face.E -> (PI / 2).toFloat()
                Face.N -> PI.toFloat()
                Face.W -> (-PI / 2).toFloat()
            }
            // A little to the side, so the rack is seen in depth rather than flat on.
            return OrbitCamera(point, yaw + 0.35f, 0.5f, distance).clamped()
        }
    }
}
