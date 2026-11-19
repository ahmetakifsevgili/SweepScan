package com.sweepscan.scan

import androidx.camera.core.ImageProxy
import com.sweepscan.tracking.ReadVerdict
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Saves analysed frames (luma, buffer orientation) while recording, so decoders and settings can
 * be compared offline on exactly what the app saw (S8). Encoded video would be a pessimistic
 * stand-in: compression damages thin bars.
 *
 * Rate-limited: one frame every 2 s, plus "near-read" frames (a label located but rejected or not
 * read) at most once a second, at most [MAX_FRAMES] per recording. The copy happens on the
 * analysis thread (the frame is closed right after); PNG encoding runs on its own thread and
 * frames are dropped rather than queued when it falls behind.
 */
class FrameSampler(private val logger: FieldLogger) {

    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), ThreadPoolExecutor.DiscardPolicy())
    private val pool = ArrayBlockingQueue<ByteArray>(3)
    private var recording: String? = null
    private var saved = 0
    private var lastBaselineMs = 0L
    private var lastNearMs = 0L

    /**
     * Returns the saved file name (logged in the frame line) or null.
     * Call only while [FieldLogger.isActive].
     */
    fun maybeSample(image: ImageProxy, verdicts: List<ReadVerdict>, nowMs: Long, frameLine: () -> JSONObject): String? {
        val name = logger.fileName ?: return null
        if (name != recording) {
            recording = name
            saved = 0
            lastBaselineMs = 0L
            lastNearMs = 0L
        }
        if (saved >= MAX_FRAMES) return null
        val near = verdicts.any { it != ReadVerdict.OK && it != ReadVerdict.DUPLICATE }
        val take = when {
            nowMs - lastBaselineMs >= BASELINE_MS -> { lastBaselineMs = nowMs; true }
            near && nowMs - lastNearMs >= NEAR_MS -> { lastNearMs = nowMs; true }
            else -> false
        }
        if (!take || writer.queue.remainingCapacity() == 0) return null

        val w = image.width
        val h = image.height
        val buffer = pool.poll()?.takeIf { it.size == w * h } ?: ByteArray(w * h)
        val plane = image.planes[0]
        val src = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        for (y in 0 until h) {
            src.position(y * stride)
            src.get(buffer, y * w, w)
        }
        val index = ++saved
        val file = "f%05d.png".format(index)
        val folder = name.removeSuffix(".jsonl")
        val sidecar = frameLine().put("rot", image.imageInfo.rotationDegrees).put("img", file)
        writer.execute {
            try {
                logger.writeBinary(folder, file) { out -> GrayPng.write(out, buffer, w, h) }
                logger.writeText(folder, "f%05d.json".format(index), sidecar.toString())
            } finally {
                pool.offer(buffer)
            }
        }
        return file
    }

    val count: Int get() = saved

    private companion object {
        const val MAX_FRAMES = 600
        const val BASELINE_MS = 2000L
        const val NEAR_MS = 1000L
    }
}
