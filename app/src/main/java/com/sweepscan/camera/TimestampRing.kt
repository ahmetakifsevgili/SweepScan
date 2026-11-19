package com.sweepscan.camera

/**
 * Small ring of values keyed by sensor timestamp (ns). Capture results arrive on a camera thread
 * slightly before or after the matching frame is analysed; the analyser looks them up here.
 */
class TimestampRing<T>(private val capacity: Int = 32) {
    private val keys = LongArray(capacity)
    private val values = arrayOfNulls<Any?>(capacity)
    private var next = 0
    private var size = 0

    @Synchronized
    fun put(timestampNs: Long, value: T) {
        keys[next] = timestampNs
        values[next] = value
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Exact match first, else the nearest entry within [toleranceNs], else null. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun lookup(timestampNs: Long, toleranceNs: Long = 2_000_000L): T? {
        var best = -1
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until size) {
            val diff = kotlin.math.abs(keys[i] - timestampNs)
            if (diff < bestDiff) {
                bestDiff = diff
                best = i
            }
        }
        return if (best >= 0 && bestDiff <= toleranceNs) values[best] as T else null
    }
}
