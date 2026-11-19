package com.sweepscan.scan

import androidx.camera.core.ImageProxy
import com.sweepscan.tracking.Motion
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs

/**
 * Camera-motion compensation (as in BoT-SORT): estimates how far the scene moved between two
 * frames from sparse Lucas–Kanade optical flow on a downscaled luma image.
 *
 * Works on the whole picture (box edges, stripes, shelves), so it keeps measuring motion while no
 * barcode is in view. Translation is the median of the flow vectors; confidence is the share of
 * vectors that agree with it. The same vectors also give a RANSAC similarity (rotation + scale +
 * translation, BoT-SORT's GMC), which is logged but not yet used by the tracker.
 */
class MotionEstimator(private val workWidth: Int = 480) {

    private var previous: Mat? = null
    private var points: MatOfPoint2f? = null

    fun reset() {
        previous?.release()
        points?.release()
        previous = null
        points = null
    }

    /** Returns scene motion in *upright full-resolution* pixels, or null on the first frame / failure. */
    fun estimate(image: ImageProxy): Motion? {
        val plane = image.planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val full = Mat(image.height, image.width, CvType.CV_8UC1, buffer, plane.rowStride.toLong())
        val scale = workWidth.toDouble() / image.width
        val small = Mat()
        Imgproc.resize(full, small, Size(), scale, scale, Imgproc.INTER_AREA)
        full.release()

        val prev = previous
        var prevPts = points
        previous = small
        if (prev == null) {
            points = detectFeatures(small)
            return null
        }
        if (prevPts == null || prevPts.rows() < MIN_POINTS) {
            prevPts?.release()
            prevPts = detectFeatures(prev)
        }

        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        val motion = try {
            if (prevPts.rows() == 0) {
                null
            } else {
                Video.calcOpticalFlowPyrLK(prev, small, prevPts, nextPts, status, err, Size(21.0, 21.0), 3)
                measure(
                    prevPts.toArray(), nextPts.toArray(), status.toArray(), scale,
                    image.imageInfo.rotationDegrees, image.width, image.height,
                )
            }
        } finally {
            prev.release()
            status.release()
            err.release()
        }

        // Track the surviving points into the next frame; re-detect when too few are left.
        val survivors = motion?.second ?: emptyArray()
        prevPts.release()
        nextPts.release()
        points = MatOfPoint2f(*survivors)
        return motion?.first
    }

    private fun measure(
        from: Array<Point>,
        to: Array<Point>,
        status: ByteArray,
        scale: Double,
        rotation: Int,
        bufferWidth: Int,
        bufferHeight: Int,
    ): Pair<Motion, Array<Point>>? {
        val dxs = ArrayList<Double>()
        val dys = ArrayList<Double>()
        val kept = ArrayList<Point>()
        val before = ArrayList<Point>()
        for (i in from.indices) {
            if (i >= to.size || status.getOrNull(i)?.toInt() != 1) continue
            dxs += to[i].x - from[i].x
            dys += to[i].y - from[i].y
            kept += to[i]
            before += from[i]
        }
        if (dxs.size < MIN_VECTORS) return null
        val mx = dxs.sorted()[dxs.size / 2]
        val my = dys.sorted()[dys.size / 2]
        val agree = dxs.indices.count { abs(dxs[it] - mx) < AGREE_PX && abs(dys[it] - my) < AGREE_PX }
        val confidence = agree.toFloat() / dxs.size
        val (ux, uy) = ImageGeometry.vectorToUpright((mx / scale).toFloat(), (my / scale).toFloat(), rotation)
        val upright = { p: Point ->
            val (x, y) = ImageGeometry.toUpright((p.x / scale).toFloat(), (p.y / scale).toFloat(), rotation, bufferWidth, bufferHeight)
            Point(x.toDouble(), y.toDouble())
        }
        val fit = similarity(before.map(upright), kept.map(upright), AGREE_PX / scale)
        return Pair(Motion(ux, uy, confidence, fit?.first, fit?.second ?: 0f), kept.toTypedArray())
    }

    /**
     * 4-DoF similarity between matched points (upright full-resolution pixels) by RANSAC, as
     * `[a, b, tx, c, d, ty]` plus the inlier share; null when OpenCV finds no model.
     */
    private fun similarity(from: List<Point>, to: List<Point>, thresholdPx: Double): Pair<FloatArray, Float>? {
        val src = MatOfPoint2f(*from.toTypedArray())
        val dst = MatOfPoint2f(*to.toTypedArray())
        val inliers = Mat()
        var model: Mat? = null
        return try {
            model = Calib3d.estimateAffinePartial2D(src, dst, inliers, Calib3d.RANSAC, thresholdPx)
            if (model.empty()) {
                null
            } else {
                val m = DoubleArray(6)
                model.get(0, 0, m)
                Pair(FloatArray(6) { m[it].toFloat() }, Core.countNonZero(inliers).toFloat() / from.size)
            }
        } catch (e: Exception) {
            null
        } finally {
            src.release()
            dst.release()
            inliers.release()
            model?.release()
        }
    }

    private fun detectFeatures(gray: Mat): MatOfPoint2f {
        val corners = MatOfPoint()
        val mask = Mat()
        Imgproc.goodFeaturesToTrack(gray, corners, MAX_POINTS, 0.01, 8.0, mask)
        val result = MatOfPoint2f(*corners.toArray().map { Point(it.x, it.y) }.toTypedArray())
        corners.release()
        mask.release()
        return result
    }

    private companion object {
        const val MAX_POINTS = 200
        const val MIN_POINTS = 80
        const val MIN_VECTORS = 12
        /** Agreement tolerance in downscaled pixels. */
        const val AGREE_PX = 1.5
    }
}
