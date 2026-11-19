package com.sweepscan.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.random.Random

/**
 * OpenCV's native code on the phone, through the same calls MotionEstimator makes (corners, optical
 * flow, a partial affine fit): run against the R8 build (-Pr8test) it shows nothing OpenCV needs
 * by name was removed.
 */
@RunWith(AndroidJUnit4::class)
class OpenCvTest {

    @Test
    fun opticalFlowFindsAKnownShift() {
        assertTrue(OpenCVLoader.initLocal())
        val w = 320
        val h = 240
        val rnd = Random(7)
        val a = Mat(h, w, CvType.CV_8UC1)
        val bytes = ByteArray(w * h)
        // Blocky texture: plenty of corners.
        for (y in 0 until h) for (x in 0 until w) bytes[y * w + x] = (((x / 8 + y / 8) % 2) * 160 + rnd.nextInt(40)).toByte()
        a.put(0, 0, bytes)
        val b = Mat()
        val shift = Mat(2, 3, CvType.CV_64F).apply { put(0, 0, 1.0, 0.0, 6.0, 0.0, 1.0, -4.0) }
        Imgproc.warpAffine(a, b, shift, Size(w.toDouble(), h.toDouble()))

        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(a, corners, 150, 0.01, 8.0)
        assertTrue(corners.rows() > 30)
        val prev = MatOfPoint2f(*corners.toArray().map { org.opencv.core.Point(it.x, it.y) }.toTypedArray())
        val next = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        Video.calcOpticalFlowPyrLK(a, b, prev, next, status, err, Size(21.0, 21.0), 3)
        val ok = status.toArray().indices.filter { status.toArray()[it].toInt() == 1 }
        val from = MatOfPoint2f(*ok.map { prev.toArray()[it] }.toTypedArray())
        val to = MatOfPoint2f(*ok.map { next.toArray()[it] }.toTypedArray())
        val fit = Calib3d.estimateAffinePartial2D(from, to)
        assertEquals(6.0, fit.get(0, 2)[0], 0.5)
        assertEquals(-4.0, fit.get(1, 2)[0], 0.5)
    }
}
