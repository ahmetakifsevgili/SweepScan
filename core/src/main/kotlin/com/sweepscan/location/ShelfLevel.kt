package com.sweepscan.location

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.tan

/**
 * Which shelf (counted from the floor) a label was on, from how the phone was held when it was
 * read: the camera's tilt (from the rotation sensor) plus where the label was in the picture give
 * the angle up or down to the label; with the phone at about chest height and an arm's length from
 * the rack that is a height, and the height is a shelf.
 *
 * The defaults are typical, not measured: [Calibration] keeps what the operator set (Ayarlar →
 * Kat ayarı: read a label on the bottom shelf, then one on the top shelf).
 */
object ShelfLevel {

    /** Camera height and distance to the rack front (m). */
    const val EYE_M = 1.3f
    const val REACH_M = 0.85f

    /** A shelf's height (m) for racks with several shelves. */
    const val LEVEL_M = 0.48f

    /**
     * Elevation of the camera's optical axis above the horizon, in degrees, from a rotation
     * matrix (row-major 3×3 or 4×4 from SensorManager.getRotationMatrixFromVector: device → world,
     * world z up). The back camera looks along the device's −z.
     */
    fun cameraElevationDeg(r: FloatArray): Float {
        val zz = if (r.size == 16) r[10] else r[8]
        return Math.toDegrees(asin((-zz).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /**
     * Elevation of a point at upright picture row [y] (of [height] px), seen with a focal length of
     * [focalPx] (upright vertical), when the optical axis is at [cameraDeg].
     */
    fun pointElevationDeg(cameraDeg: Float, y: Float, height: Int, focalPx: Float): Float =
        cameraDeg + Math.toDegrees(atan(((height / 2f - y) / focalPx).toDouble())).toFloat()

    /** Height (m) of the point seen at [elevationDeg]. */
    fun heightM(elevationDeg: Float, c: Calibration = Calibration.DEFAULT): Float =
        c.eye + c.reach * tan(Math.toRadians(elevationDeg.coerceIn(-80f, 80f).toDouble())).toFloat()

    /** Shelf number from the floor (1 = bottom), clamped to the rack's [levels]. */
    fun levelAtHeight(heightM: Float, levels: Int, c: Calibration = Calibration.DEFAULT): Int {
        if (levels <= 1) return 1
        return (floor((heightM - c.floor) / c.levelM).toInt() + 1).coerceIn(1, levels)
    }

    /** Shelf number from the floor of a label seen at [elevationDeg]. */
    fun level(elevationDeg: Float, levels: Int, c: Calibration = Calibration.DEFAULT): Int = levelAtHeight(heightM(elevationDeg, c), levels, c)

    /**
     * How the operator holds the phone and how tall the shelves are. [fromTwoReads] fits it to a
     * label read on the bottom shelf and one on the top shelf of a rack with [levels] shelves.
     */
    data class Calibration(
        val eye: Float = EYE_M,
        val reach: Float = REACH_M,
        val levelM: Float = LEVEL_M,
        val floor: Float = 0.05f,
    ) {
        companion object {
            val DEFAULT = Calibration()

            /**
             * The bottom label is taken as the middle of shelf 1 and the top one as the middle of
             * shelf [levels]; the eye height stays, the reach and shelf height are solved.
             */
            fun fromTwoReads(bottomDeg: Float, topDeg: Float, levels: Int, eye: Float = EYE_M): Calibration? {
                if (levels < 2 || topDeg <= bottomDeg) return null
                val tb = tan(Math.toRadians(bottomDeg.toDouble())).toFloat()
                val tt = tan(Math.toRadians(topDeg.toDouble())).toFloat()
                // heights: hb = eye + reach·tb = floor + 0.5·L ; ht = eye + reach·tt = floor + (levels − 0.5)·L
                // → reach·(tt − tb) = (levels − 1)·L. One more condition: floor fixed at 0.05.
                val floor = 0.05f
                // eye + reach·tb = floor + 0.5·L and L = reach·(tt − tb)/(levels − 1)
                val k = (tt - tb) / (levels - 1)
                val denom = 0.5f * k - tb
                if (denom <= 0.01f) return null
                val reach = (eye - floor) / denom
                val levelM = reach * k
                if (reach !in 0.3f..3f || levelM !in 0.15f..1.2f) return null
                return Calibration(eye, reach, levelM, floor)
            }
        }
    }
}
