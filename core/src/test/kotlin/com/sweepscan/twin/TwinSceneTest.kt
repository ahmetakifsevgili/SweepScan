package com.sweepscan.twin

import com.sweepscan.location.DefaultLayout
import com.sweepscan.location.Face
import com.sweepscan.location.LocationCode
import com.sweepscan.location.Pt
import com.sweepscan.location.RouteFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class TwinSceneTest {

    private val layout = DefaultLayout.create()
    private val b3 = LocationCode("B", "3", 1)

    private fun kinds(i: Instances, kind: Float) = (0 until i.count).filter { i.kind(it) == kind }

    @Test
    fun triangulatesRectangleAndLShape() {
        val rect = listOf(Pt(0f, 0f), Pt(4f, 0f), Pt(4f, 2f), Pt(0f, 2f))
        assertEquals(6, Triangulate.polygon(rect).size)
        // The big depot's floor: an L shape with six corners → four triangles, same area.
        val l = layout.walls.filter { it.closed }.maxBy { it.points.size }.points
        val tri = Triangulate.polygon(l)
        assertEquals((l.size - 2) * 3, tri.size)
        var area = 0f
        for (k in tri.indices step 3) area += abs(Triangulate.area(listOf(l[tri[k]], l[tri[k + 1]], l[tri[k + 2]])))
        assertEquals(abs(Triangulate.area(l)), area, 0.5f)
    }

    @Test
    fun wholeSiteIsAFewThousandBoxes() {
        val scene = TwinScene.build(layout, TwinState())
        assertTrue("boxes: ${scene.opaque.count}", scene.opaque.count in 5_000..40_000)
        assertTrue(scene.floor.isNotEmpty())
        assertEquals(0, scene.transparent.count)
        assertFalse("nothing moves without a search", scene.animated)
        for (d in layout.depots) assertTrue(scene.labels.any { it.text == d.name && it.style == TwinScene.Style.DEPOT })
        assertEquals(layout.depots.sumOf { it.rows.size }, scene.rows.size)
    }

    @Test
    fun sameSceneEveryTime() {
        val a = TwinScene.build(layout, TwinState()).opaque.array()
        val b = TwinScene.build(layout, TwinState()).opaque.array()
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun foundModelGlowsAroundItsPlaceUnderAPin() {
        val state = TwinState(highlighted = setOf(b3), selected = b3, markers = mapOf(b3 to 0.5f), statusMode = false)
        val scene = TwinScene.build(layout, state)
        val glow = kinds(scene.opaque, TwinScene.GLOW)
        assertTrue(glow.isNotEmpty())
        assertEquals(1, kinds(scene.opaque, TwinScene.PIN).size)
        assertEquals(1, scene.transparent.count) // the beam
        assertTrue(scene.animated)
        // Row 3 runs north–south at x ≈ 62.4–63.4 m; the band is in its middle (z ≈ 1 + 28/2 m).
        val row = layout.rowOf(b3)!!
        val mid = row.pointAlong(0.5f, 0f)
        val shelfGlow = glow.map { scene.opaque.center(it) }.filter { it.y > 0.05f }
        assertTrue(shelfGlow.all { abs(it.z - mid.y * TwinScene.S) < 0.8f && it.x > 61.5f })
        val focus = scene.focus[b3]
        assertNotNull(focus)
        assertEquals(Face.W, focus!!.second)
    }

    @Test
    fun knownShelfGlowsAlone() {
        val state = TwinState(highlighted = setOf(b3), selected = b3, markers = mapOf(b3 to 0.5f), shelves = mapOf(b3 to 2), statusMode = false)
        val scene = TwinScene.build(layout, state)
        val ys = kinds(scene.opaque, TwinScene.GLOW).map { scene.opaque.center(it).y }.filter { it > 0.05f }
        assertTrue(ys.isNotEmpty())
        // Shelf 2 of 4 (46 cm each): boxes between 0.46 and 0.92 m.
        assertTrue(ys.all { it in 0.46f..0.92f })
    }

    @Test
    fun foundRowWithoutPositionGlowsWhole() {
        val state = TwinState(highlighted = setOf(b3), statusMode = false)
        val scene = TwinScene.build(layout, state)
        val glow = kinds(scene.opaque, TwinScene.GLOW).map { scene.opaque.center(it) }.filter { it.y > 0.05f }
        val zs = glow.map { it.z }
        assertTrue(zs.max() - zs.min() > 20f) // spread along the whole 28 m row
    }

    @Test
    fun freshnessStripsOnlyWithoutSearch() {
        val fresh = TwinScene.build(layout, TwinState(scanned = mapOf(b3 to true)))
        val colors = flats(fresh)
        assertTrue(TwinScene.FRESH in colors)
        assertTrue(TwinScene.NEVER in colors)
        val searching = TwinScene.build(layout, TwinState(highlighted = setOf(b3), scanned = mapOf(b3 to true), statusMode = false))
        assertFalse(TwinScene.FRESH in flats(searching))
    }

    private fun flats(scene: TwinScene): Set<Int> {
        val a = scene.opaque.array()
        return (0 until scene.opaque.count).filter { scene.opaque.kind(it) == TwinScene.FLAT }.map { i ->
            val o = i * Instances.STRIDE + 6
            (0xFF shl 24) or ((a[o] * 255 + 0.5f).toInt() shl 16) or ((a[o + 1] * 255 + 0.5f).toInt() shl 8) or (a[o + 2] * 255 + 0.5f).toInt()
        }.toSet()
    }

    @Test
    fun routeFlowsInOrder() {
        val route = RouteFinder.route(layout, b3)!!.points
        val scene = TwinScene.build(layout, TwinState(route = route, statusMode = false))
        val a = scene.opaque.array()
        val params = kinds(scene.opaque, TwinScene.ROUTE).map { a[it * Instances.STRIDE + 12] }
        assertTrue(params.size >= 2)
        assertEquals(params.sorted(), params)
        assertTrue(scene.animated)
    }

    @Test
    fun tapStraightDownPicksTheRack() {
        val scene = TwinScene.build(layout, TwinState())
        val row = layout.rowOf(b3)!!
        val o = V3(row.rect.cx * TwinScene.S, 20f, row.rect.cy * TwinScene.S)
        val hit = scene.pick(o, V3(0f, -1f, 0f))
        assertEquals(Triple("B", "3", 1), hit)
        assertNull(scene.pick(V3(-50f, 20f, -50f), V3(0f, -1f, 0f)))
    }

    @Test
    fun cameraPutsTargetInTheMiddleOfTheVisiblePart() {
        val cam = OrbitCamera(V3(50f, 0f, 15f), yaw = 0.4f, pitch = 0.8f, distance = 20f)
        val p = Mat4.project(cam.viewProj(1000, 2000), cam.target, 1000, 2000)
        assertEquals(500f, p.x, 0.5f)
        assertEquals(1000f, p.y, 0.5f)
        // The bottom 600 px are covered: the target moves up to the middle of the rest.
        val q = Mat4.project(cam.viewProj(1000, 2000, 600f), cam.target, 1000, 2000)
        assertEquals(700f, q.y, 0.5f)
        // And a tap there points at it.
        val (o, d) = cam.ray(500f, 700f, 1000, 2000, 600f)
        val toTarget = (cam.target - o).normalized()
        assertEquals(1f, d.dot(toTarget), 1e-4f)
    }

    @Test
    fun dragKeepsTheFloorUnderTheFinger() {
        val cam = OrbitCamera(V3(40f, 0f, 15f), yaw = 0.7f, pitch = 0.9f, distance = 30f)
        val w = 1080
        val h = 2000
        val inset = 500f
        val grabbed = cam.groundPoint(300f, 600f, w, h, inset)!!
        val moved = cam.panned(300f, 600f, 700f, 900f, w, h, inset)
        val p = Mat4.project(moved.viewProj(w, h, inset), grabbed, w, h)
        assertEquals(700f, p.x, 1f)
        assertEquals(900f, p.y, 1f)
    }

    @Test
    fun pinchAndTwistKeepThePointBetweenTheFingers() {
        val cam = OrbitCamera(V3(40f, 0f, 15f), yaw = 0.2f, pitch = 1.0f, distance = 40f)
        val w = 1080
        val h = 2000
        val f = cam.groundPoint(400f, 700f, w, h)!!
        for (next in listOf(cam.zoomedAt(400f, 700f, 0.6f, w, h), cam.rotatedAt(400f, 700f, 0.5f, w, h))) {
            val p = Mat4.project(next.viewProj(w, h), f, w, h)
            assertEquals(400f, p.x, 1.5f)
            assertEquals(700f, p.y, 1.5f)
        }
        assertEquals(24f, cam.zoomedAt(400f, 700f, 0.6f, w, h).distance, 0.01f)
        // Above the horizon there is no floor: nothing to drag.
        val level = OrbitCamera(V3.ZERO, pitch = 0.12f, distance = 10f)
        assertNull(level.groundPoint(540f, 10f, w, h))
    }

    @Test
    fun cameraTurnsTheShortWay() {
        val a = OrbitCamera(yaw = (PI - 0.1).toFloat())
        val b = OrbitCamera(yaw = (-PI + 0.1).toFloat())
        val mid = a.lerp(b, 0.5f)
        assertEquals(PI.toFloat(), abs(mid.yaw), 0.01f)
    }

    @Test
    fun overviewSeesTheWholeSite() {
        val scene = TwinScene.build(layout, TwinState())
        val b = scene.bounds
        val cam = OrbitCamera.bestOverview(b.minX, b.minZ, b.maxX, b.maxZ, 1080, 2000, 500f)
        val vp = cam.viewProj(1080, 2000, 500f)
        for (c in listOf(V3(b.minX, 0f, b.minZ), V3(b.maxX, 0f, b.minZ), V3(b.minX, 0f, b.maxZ), V3(b.maxX, 0f, b.maxZ))) {
            val p = Mat4.project(vp, c, 1080, 2000)
            assertTrue("$c → $p", p.visible && p.x in 0f..1080f && p.y in 0f..1500f)
        }
        // The long, flat site is turned to fill the tall screen; it is closer than seen from the south.
        val plain = OrbitCamera.overview(b.minX, b.minZ, b.maxX, b.maxZ, 1080, 2000, 500f)
        assertTrue(cam.distance < plain.distance)
    }
}
