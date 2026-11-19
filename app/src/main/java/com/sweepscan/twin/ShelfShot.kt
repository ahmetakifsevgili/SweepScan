package com.sweepscan.twin

import android.graphics.Bitmap
import com.sweepscan.location.LocationCode
import com.sweepscan.location.RouteFinder
import com.sweepscan.location.SiteLayout
import java.io.ByteArrayOutputStream

/**
 * What the 3D answer shows for one found place: the site with that place glowing (on its shelf
 * when known), the route from the entrance, a pin, and the latest shelf photo standing beside the
 * rack, turned to the camera; plus the camera's flight there. Built off the main thread.
 */
object ShelfShot {

    class Prepared(val glb: ByteArray, val flight: FlyPath)

    /**
     * [along]: where along the row (0 = left end seen facing it), [shelf]: 1 = bottom, [photo]:
     * the latest shelf photo (kept small: at most 640 px on its long side).
     */
    fun prepare(layout: SiteLayout, code: LocationCode, along: Float?, shelf: Int?, photo: Bitmap?): Prepared? {
        val row = layout.rowOf(code) ?: return null
        val route = (if (along != null) RouteFinder.route(layout, row.pointAlong(along)) else RouteFinder.route(layout, code))?.points
        val state = TwinState(
            highlighted = setOf(code),
            selected = code,
            markers = along?.let { mapOf(code to it) } ?: emptyMap(),
            shelves = shelf?.let { mapOf(code to it) } ?: emptyMap(),
            route = route,
            statusMode = false,
        )
        val scene = TwinScene.build(layout, state)
        val (front, face) = scene.focus[code] ?: return null
        val levelH = if (row.levels <= 1) TwinScene.SINGLE_LEVEL_H else TwinScene.LEVEL_H
        // Look at the boxes on the found shelf, or the middle of the rack.
        val y = if (shelf != null && shelf in 1..row.levels) (shelf - 1) * levelH + 0.03f + TwinScene.BOARD + 0.12f else front.y
        val focus = V3(front.x, y, front.z)
        val flight = FlyPath.toShelf(route, focus, face)
        var parts = TwinMesh.fromScene(scene)
        var jpeg: ByteArray? = null
        if (photo != null) {
            val small = scaled(photo, 640)
            jpeg = ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
            val out = FlyPath.outward(face)
            val right = V3(out.z, 0f, -out.x)
            val w = 0.95f
            val h = (w * small.height / small.width).coerceIn(0.35f, 1.2f)
            val center = V3(focus.x, 0f, focus.z) + right * 0.95f + out * 0.55f + V3(0f, (focus.y + 0.25f).coerceAtLeast(h / 2 + 0.25f), 0f)
            // Turned towards where the camera stops.
            val toEye = V3(flight.end.x - center.x, 0f, flight.end.z - center.z).normalized()
            parts = parts + TwinMesh.photoCard(center, toEye, w, h)
        }
        return Prepared(Glb.write(parts, jpeg), flight)
    }

    private fun scaled(b: Bitmap, max: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= max) return b
        val f = max.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }
}
