package com.sweepscan.twin

import kotlin.math.sqrt
import kotlin.math.tan

/** A 3D vector (metres in the twin's world: x = east, y = up, z = south). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): V3 = length().let { if (it < 1e-6f) this else this * (1f / it) }

    companion object { val ZERO = V3(0f, 0f, 0f) }
}

/**
 * 4×4 matrices as column-major float arrays (the layout OpenGL expects), in plain Kotlin so the
 * camera and picking maths run in JVM unit tests.
 */
object Mat4 {

    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = s
        }
        return r
    }

    /** Perspective projection, [fovY] in radians. */
    fun perspective(fovY: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(fovY / 2f)
        val r = FloatArray(16)
        r[0] = f / aspect
        r[5] = f
        r[10] = (far + near) / (near - far)
        r[11] = -1f
        r[14] = 2f * far * near / (near - far)
        return r
    }

    fun lookAt(eye: V3, target: V3, up: V3 = V3(0f, 1f, 0f)): FloatArray {
        val f = (target - eye).normalized()
        val s = f.cross(up).normalized()
        val u = s.cross(f)
        val r = identity()
        r[0] = s.x; r[4] = s.y; r[8] = s.z
        r[1] = u.x; r[5] = u.y; r[9] = u.z
        r[2] = -f.x; r[6] = -f.y; r[10] = -f.z
        r[12] = -s.dot(eye); r[13] = -u.dot(eye); r[14] = f.dot(eye)
        return r
    }

    /** Moves the projected image by ([dx], [dy]) in normalised device units (2 = the full width/height). */
    fun shiftNdc(projection: FloatArray, dx: Float, dy: Float): FloatArray {
        val t = identity()
        t[12] = dx
        t[13] = dy
        return multiply(t, projection)
    }

    /**
     * Projects a world point to the screen: pixel x, pixel y (from the top), and whether it is in
     * front of the camera.
     */
    fun project(viewProj: FloatArray, p: V3, width: Int, height: Int): Projected {
        val x = viewProj[0] * p.x + viewProj[4] * p.y + viewProj[8] * p.z + viewProj[12]
        val y = viewProj[1] * p.x + viewProj[5] * p.y + viewProj[9] * p.z + viewProj[13]
        val w = viewProj[3] * p.x + viewProj[7] * p.y + viewProj[11] * p.z + viewProj[15]
        if (w <= 1e-4f) return Projected(0f, 0f, false)
        val nx = x / w
        val ny = y / w
        return Projected((nx + 1f) / 2f * width, (1f - ny) / 2f * height, true)
    }

    data class Projected(val x: Float, val y: Float, val visible: Boolean)
}
