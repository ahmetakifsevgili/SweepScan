package com.sweepscan.twin

import com.sweepscan.location.DefaultLayout
import com.sweepscan.location.Face
import com.sweepscan.location.LocationCode
import com.sweepscan.location.RouteFinder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TwinMeshTest {

    private val layout = DefaultLayout.create()
    private val b3 = LocationCode("B", "3", 1)

    private fun foundScene(): TwinScene {
        val row = layout.rowOf(b3)!!
        val route = RouteFinder.route(layout, row.pointAlong(0.5f))!!.points
        return TwinScene.build(layout, TwinState(highlighted = setOf(b3), selected = b3, markers = mapOf(b3 to 0.5f), shelves = mapOf(b3 to 2), route = route, statusMode = false))
    }

    @Test
    fun everyBoxBecomesFiveFaces() {
        val scene = foundScene()
        val parts = TwinMesh.fromScene(scene)
        val boxes = parts.filter { it.look != TwinMesh.Look.FLOOR }.sumOf { it.vertexCount } / 20
        assertEquals(scene.opaque.count, boxes)
        for (p in parts) {
            assertEquals(p.vertexCount * 3, p.normals.size)
            assertEquals(p.vertexCount * 3, p.colors.size)
            assertTrue(p.indices.all { it in 0 until p.vertexCount })
        }
        // The found place glows, the route and one pin exist.
        assertTrue(parts.any { it.look == TwinMesh.Look.GLOW && it.vertexCount > 0 })
        assertTrue(parts.any { it.look == TwinMesh.Look.ROUTE })
        assertEquals(1, parts.count { it.look == TwinMesh.Look.PIN })
    }

    @Test
    fun facesPointOutwardAndFloorPointsUp() {
        val parts = TwinMesh.fromScene(foundScene())
        for (p in parts) {
            for (t in p.indices.indices step 3) {
                val a = v(p, p.indices[t]); val b = v(p, p.indices[t + 1]); val c = v(p, p.indices[t + 2])
                val n = (b - a).cross(c - a).normalized()
                val stored = V3(p.normals[p.indices[t] * 3], p.normals[p.indices[t] * 3 + 1], p.normals[p.indices[t] * 3 + 2])
                if ((b - a).cross(c - a).length() < 1e-7f) continue
                assertTrue("${p.name} triangle $t winds against its normal", n.dot(stored) > 0.99f)
            }
        }
        val floor = parts.first { it.look == TwinMesh.Look.FLOOR }
        assertTrue((0 until floor.vertexCount).all { floor.normals[it * 3 + 1] == 1f })
    }

    private fun v(p: TwinMesh.Part, i: Int) = V3(p.positions[i * 3], p.positions[i * 3 + 1], p.positions[i * 3 + 2])

    @Test
    fun glbIsAValidContainer() {
        val parts = TwinMesh.fromScene(foundScene()) + TwinMesh.photoCard(V3(60f, 1f, 15f), V3(-1f, 0f, 0f), 1f, 0.6f)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val glb = Glb.write(parts, jpeg)
        val b = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.int)
        assertEquals(2, b.int)
        assertEquals(glb.size, b.int)
        val jsonLength = b.int
        assertEquals(0x4E4F534A, b.int)
        val json = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
        b.position(20 + jsonLength)
        val binLength = b.int
        assertEquals(0x004E4942, b.int)
        assertEquals(glb.size, 28 + jsonLength + binLength)
        assertEquals(binLength, json.getJSONArray("buffers").getJSONObject(0).getInt("byteLength"))
        assertEquals(parts.size, json.getJSONArray("nodes").length())
        // Every view inside the buffer and 4-byte aligned; positions carry min/max.
        val views = json.getJSONArray("bufferViews")
        for (i in 0 until views.length()) {
            val v = views.getJSONObject(i)
            assertEquals(0, v.getInt("byteOffset") % 4)
            assertTrue(v.getInt("byteOffset") + v.getInt("byteLength") <= binLength)
        }
        val mesh0 = json.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        val pos = json.getJSONArray("accessors").getJSONObject(mesh0.getJSONObject("attributes").getInt("POSITION"))
        assertNotNull(pos.getJSONArray("min"))
        assertEquals("image/jpeg", json.getJSONArray("images").getJSONObject(0).getString("mimeType"))
        val photo = json.getJSONArray("materials").getJSONObject(TwinMesh.Look.PHOTO.ordinal)
        assertTrue(photo.getJSONObject("extensions").has("KHR_materials_unlit"))
    }

    @Test
    fun photoCardFacesTheCorridor() {
        val (pic, frame) = TwinMesh.photoCard(V3(10f, 1f, 5f), V3(0f, 0f, 1f), 1.2f, 0.8f)
        // Seen from the corridor (+z), u grows to the right (+x) and the picture is in front of its frame.
        val left = (0 until 4).minBy { pic.positions[it * 3] + pic.origin.x }
        assertEquals(0f, pic.uvs!![left * 2], 1e-6f)
        val frameZ = (0 until frame.vertexCount).maxOf { frame.positions[it * 3 + 2] }
        val picZ = pic.positions[2] + pic.origin.z
        assertTrue(picZ > frameZ)
    }

    @Test
    fun flightStartsHighAndEndsInFrontOfTheShelf() {
        val scene = foundScene()
        val (focus, face) = scene.focus[b3]!!
        assertEquals(Face.W, face)
        val row = layout.rowOf(b3)!!
        val route = RouteFinder.route(layout, row.pointAlong(0.5f))!!.points
        val fly = FlyPath.toShelf(route, focus, face)
        val (startEye, startLook) = fly.at(0f)
        assertTrue("starts above the building", startEye.y > 6f)
        assertTrue((startLook - focus).length() < 0.01f)
        val (endEye, endLook) = fly.at(fly.seconds + 1f)
        assertTrue((endEye - fly.end).length() < 0.01f)
        assertTrue((endLook - focus).length() < 0.01f)
        // In front of a west-facing rack: west of it, about two metres out.
        assertTrue(endEye.x < focus.x - 1.5f)
        // Smooth: no jump between frames at 60 fps.
        var last = fly.at(0f).first
        var t = 0f
        while (t < fly.seconds) {
            t += 1f / 60f
            val e = fly.at(t).first
            assertTrue("jump at $t", (e - last).length() < 0.6f)
            last = e
        }
        assertTrue(fly.seconds in 3.5f..8f)
    }

    @Test
    fun flightWithoutRouteStillLands() {
        val fly = FlyPath.toShelf(null, V3(5f, 1f, 5f), Face.N)
        val (eye, look) = fly.at(fly.seconds)
        assertTrue(eye.z < 5f - 1.5f)
        assertTrue((look - V3(5f, 1f, 5f)).length() < 0.01f)
    }
}
