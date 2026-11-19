package com.sweepscan.twin

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The 3D scene as a few meshes for a real-time engine (Filament on the phone): every box of
 * [TwinScene] becomes five faces (the bottom is never seen), grouped by how they are lit:
 * plain surfaces, the glowing found boxes, the route on the floor, the pin above the target and
 * the floor. Colours are per vertex (linear), so one material per group draws everything.
 */
object TwinMesh {

    enum class Look { SOLID, FLOOR, GLOW, ROUTE, PIN, PHOTO }

    /** One mesh: positions and normals (xyz), linear colours (rgb), triangle indices; [origin] = its node's place. */
    class Part(
        val name: String,
        val look: Look,
        val positions: FloatArray,
        val normals: FloatArray,
        val colors: FloatArray,
        val indices: IntArray,
        val uvs: FloatArray? = null,
        val origin: V3 = V3.ZERO,
    ) {
        val vertexCount get() = positions.size / 3
    }

    private class Builder(val name: String, val look: Look, val origin: V3 = V3.ZERO) {
        val p = FloatList()
        val n = FloatList()
        val c = FloatList()
        val uv = FloatList()
        val idx = IntList()
        val vertices get() = p.size / 3

        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float) {
            p.add(x - origin.x); p.add(y - origin.y); p.add(z - origin.z)
            n.add(nx); n.add(ny); n.add(nz)
            c.add(r); c.add(g); c.add(b)
        }

        fun quad(base: Int) {
            idx.add(base); idx.add(base + 1); idx.add(base + 2)
            idx.add(base); idx.add(base + 2); idx.add(base + 3)
        }

        fun build() = Part(name, look, p.toArray(), n.toArray(), c.toArray(), idx.toArray(), if (uv.size > 0) uv.toArray() else null, origin)
    }

    /** Local unit-cube faces (normal, then four corners counter-clockwise seen from outside); no bottom. */
    private val FACES = arrayOf(
        floatArrayOf(0f, 1f, 0f, -.5f, .5f, .5f, .5f, .5f, .5f, .5f, .5f, -.5f, -.5f, .5f, -.5f),
        floatArrayOf(0f, 0f, 1f, -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f, .5f, -.5f, .5f, .5f),
        floatArrayOf(0f, 0f, -1f, .5f, -.5f, -.5f, -.5f, -.5f, -.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f),
        floatArrayOf(1f, 0f, 0f, .5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, .5f, .5f, .5f),
        floatArrayOf(-1f, 0f, 0f, -.5f, -.5f, -.5f, -.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f, .5f, -.5f),
    )

    /** sRGB 0..1 → linear (glTF vertex colours are linear). */
    fun linear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** The scene's meshes; the transparent beam is left out (the glow and the pin show the place). */
    fun fromScene(scene: TwinScene): List<Part> {
        val solid = Builder("solid", Look.SOLID)
        val glow = Builder("glow", Look.GLOW)
        val route = Builder("route", Look.ROUTE)
        val pins = ArrayList<Builder>()
        val a = scene.opaque.array()
        for (i in 0 until scene.opaque.count) {
            val o = i * Instances.STRIDE
            val kind = a[o + 11]
            val target = when (kind) {
                TwinScene.GLOW -> glow
                TwinScene.ROUTE -> route
                TwinScene.PIN -> Builder("pin${pins.size}", Look.PIN, V3(a[o], a[o + 1], a[o + 2])).also { pins += it }
                else -> solid
            }
            box(target, a, o, if (target === glow) TwinScene.TARGET else null)
        }
        val floor = Builder("floor", Look.FLOOR)
        val f = scene.floor
        val fr = linear(((TwinScene.FLOOR shr 16) and 0xFF) / 255f)
        val fg = linear(((TwinScene.FLOOR shr 8) and 0xFF) / 255f)
        val fb = linear((TwinScene.FLOOR and 0xFF) / 255f)
        var k = 0
        while (k + 8 < f.size) {
            val base = floor.vertices
            // Triangles from the plan may wind either way: make them face up.
            val ax = f[k]; val az = f[k + 2]; val bx = f[k + 3]; val bz = f[k + 5]; val cx = f[k + 6]; val cz = f[k + 8]
            val up = (bx - ax) * (cz - az) - (bz - az) * (cx - ax) < 0f
            val order = if (up) intArrayOf(0, 1, 2) else intArrayOf(0, 2, 1)
            for (j in order) floor.vertex(f[k + j * 3], 0f, f[k + j * 3 + 2], 0f, 1f, 0f, fr, fg, fb)
            floor.idx.add(base); floor.idx.add(base + 1); floor.idx.add(base + 2)
            k += 9
        }
        return listOf(floor, solid, glow, route).filter { it.vertices > 0 }.map { it.build() } + pins.map { it.build() }
    }

    /** One box; [tint] replaces its colour (the found boxes are all amber, so the glow reads as one). */
    private fun box(b: Builder, a: FloatArray, o: Int, tint: Int? = null) {
        val cx = a[o]; val cy = a[o + 1]; val cz = a[o + 2]
        val sx = a[o + 3]; val sy = a[o + 4]; val sz = a[o + 5]
        val r = linear(if (tint != null) ((tint shr 16) and 0xFF) / 255f else a[o + 6])
        val g = linear(if (tint != null) ((tint shr 8) and 0xFF) / 255f else a[o + 7])
        val bl = linear(if (tint != null) (tint and 0xFF) / 255f else a[o + 8])
        val yaw = a[o + 10]
        val c = cos(yaw)
        val s = sin(yaw)
        for (face in FACES) {
            // Same turn as the old shader: x' = c·x + s·z, z' = −s·x + c·z.
            val nx = c * face[0] + s * face[2]
            val nz = -s * face[0] + c * face[2]
            val base = b.vertices
            for (v in 0 until 4) {
                val px = face[3 + v * 3] * sx
                val py = face[4 + v * 3] * sy
                val pz = face[5 + v * 3] * sz
                b.vertex(cx + c * px + s * pz, cy + py, cz - s * px + c * pz, nx, face[1], nz, r, g, bl)
            }
            b.quad(base)
        }
    }

    /**
     * A photo card standing in front of a rack: [center] (world), facing [out] (unit, horizontal),
     * [width] × [height] metres, with a thin white frame behind it. Returns the picture and the frame.
     */
    fun photoCard(center: V3, out: V3, width: Float, height: Float): List<Part> {
        val right = V3(out.z, 0f, -out.x) // the right hand of someone facing the card
        val pic = Builder("photo", Look.PHOTO, center)
        val hw = width / 2f
        val hh = height / 2f
        val base = pic.vertices
        val corners = listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh)
        val uvs = listOf(0f to 1f, 1f to 1f, 1f to 0f, 0f to 0f)
        for ((i, xy) in corners.withIndex()) {
            val p = center + right * xy.first + V3(0f, xy.second, 0f)
            pic.vertex(p.x, p.y, p.z, out.x, 0f, out.z, 1f, 1f, 1f)
            pic.uv.add(uvs[i].first); pic.uv.add(uvs[i].second)
        }
        pic.quad(base)
        val frame = Builder("photo_frame", Look.SOLID)
        val m = 0.035f
        val back = center - out * 0.012f
        // The frame as a thin box: yaw so its local x runs along [right].
        val yaw = kotlin.math.atan2(-right.z, right.x)
        val inst = Instances()
        inst.add(back.x, back.y, back.z, width + 2 * m, height + 2 * m, 0.02f, 0xFFF4F1EA.toInt(), yaw = yaw)
        box(frame, inst.array(), 0)
        return listOf(pic.build(), frame.build())
    }
}

internal class FloatList {
    private var a = FloatArray(1024)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): FloatArray = a.copyOf(size)
}

internal class IntList {
    private var a = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}
