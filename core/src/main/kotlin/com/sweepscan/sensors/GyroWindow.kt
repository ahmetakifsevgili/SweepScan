package com.sweepscan.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Summary of gyroscope samples in a time window (degrees, degrees per second). */
data class GyroSummary(
    val samples: Int,
    val meanDps: Float,
    val maxDps: Float,
    /** Mean |rate| about the device Y axis: in portrait this moves the image sideways, across the bars. */
    val meanYawDps: Float,
    /** Integrated rotation about x, y, z over the window, degrees. */
    val rotationDeg: FloatArray,
)

/** Ring buffer of gyroscope samples (rad/s, timestamps in ns). Thread-safe. */
class GyroWindow(private val capacity: Int = 512) {
    private val ts = LongArray(capacity)
    private val wx = FloatArray(capacity)
    private val wy = FloatArray(capacity)
    private val wz = FloatArray(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(timestampNs: Long, x: Float, y: Float, z: Float) {
        ts[next] = timestampNs
        wx[next] = x; wy[next] = y; wz[next] = z
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    /**
     * Rotation (rad) about the device axes between two sensor times. Each sample's rate holds until
     * the next sample, so the start of the interval is not lost. Null when samples cover less than
     * half the interval.
     */
    fun rotationBetween(fromNs: Long, toNs: Long): FloatArray? {
        if (size == 0 || toNs <= fromNs) return null
        val rot = FloatArray(3)
        var covered = 0L
        for (k in 0 until size) {
            val i = (next - size + k + capacity) % capacity
            val end = if (k + 1 < size) ts[(i + 1) % capacity] else toNs
            val a = maxOf(ts[i], fromNs)
            val b = minOf(end, toNs)
            if (b <= a) continue
            val dt = (b - a) / 1e9f
            rot[0] += wx[i] * dt
            rot[1] += wy[i] * dt
            rot[2] += wz[i] * dt
            covered += b - a
        }
        return if (covered * 2 >= toNs - fromNs) rot else null
    }

    fun summary(fromNs: Long, toNs: Long): GyroSummary? {
        var n = 0
        var sum = 0f
        var maxRate = 0f
        var sumYaw = 0f
        val rot = FloatArray(3)
        var prevTs = -1L
        // Oldest to newest.
        for (k in 0 until size) {
            val i = (next - size + k + capacity) % capacity
            if (ts[i] < fromNs || ts[i] > toNs) continue
            val rate = sqrt(wx[i] * wx[i] + wy[i] * wy[i] + wz[i] * wz[i])
            sum += rate
            maxRate = max(maxRate, rate)
            sumYaw += abs(wy[i])
            if (prevTs >= 0) {
                val dt = (ts[i] - prevTs) / 1e9f
                rot[0] += wx[i] * dt; rot[1] += wy[i] * dt; rot[2] += wz[i] * dt
            }
            prevTs = ts[i]
            n++
        }
        if (n == 0) return null
        return GyroSummary(
            n, deg(sum / n), deg(maxRate), deg(sumYaw / n),
            floatArrayOf(deg(rot[0]), deg(rot[1]), deg(rot[2])),
        )
    }

    private fun deg(rad: Float) = (rad * 180.0 / Math.PI).toFloat()
}
