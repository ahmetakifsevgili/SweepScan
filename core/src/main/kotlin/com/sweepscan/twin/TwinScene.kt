package com.sweepscan.twin

import com.sweepscan.location.Face
import com.sweepscan.location.LocationCode
import com.sweepscan.location.Pt
import com.sweepscan.location.RackRow
import com.sweepscan.location.SiteLayout
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the 3D view shows besides the building: the same state as the 2D plan. */
data class TwinState(
    val highlighted: Set<LocationCode> = emptySet(),
    val selected: LocationCode? = null,
    /** Where along a row the searched model is (0 = left end, 1 = right end, seen facing the row). */
    val markers: Map<LocationCode, Float> = emptyMap(),
    /** The shelf (1 = bottom) a found model was last read on, where known. */
    val shelves: Map<LocationCode, Int> = emptyMap(),
    /** Walking route in plan units. */
    val route: List<Pt>? = null,
    /** Pick list stops in walking order. */
    val stops: List<LocationCode> = emptyList(),
    /** Last sweep per column: true = this week, false = older. */
    val scanned: Map<LocationCode, Boolean> = emptyMap(),
    /** No search active: show how fresh each row's last sweep is. */
    val statusMode: Boolean = true,
    val depotFilter: String? = null,
)

/**
 * The stock rooms as boxes for the 3D view, built from the plan: floors, low cut-away walls, racks
 * with their shelves, shoe boxes on every shelf, the route on the floor, a glowing band and a pin
 * where the searched model is.
 *
 * Everything is an axis-aligned box turned around the vertical axis ([Instances]), so one cube
 * mesh drawn with instancing renders the whole site. The boxes on the shelves are illustrative:
 * the map knows the row and roughly where along it a model is, not each box, so the fill is
 * generated (the same every time) and only the band around a found model is meaningful.
 */
class TwinScene(
    val opaque: Instances,
    val transparent: Instances,
    /** Floor triangles: x, y, z per vertex. */
    val floor: FloatArray,
    val labels: List<Label>,
    val rows: List<RowHit>,
    /** Something moves (route, glow, pin): render continuously. */
    val animated: Boolean,
    val bounds: Bounds,
    val depotBounds: Map<String, Bounds>,
    /** Where the selected target is (for the camera), with the face it is read from. */
    val focus: Map<LocationCode, Pair<V3, Face>>,
) {
    data class Label(val at: V3, val text: String, val style: Style, val active: Boolean = false)
    enum class Style { DEPOT, ROW, STOP, ENTRANCE }

    /** A rack's solid box for tap picking. */
    data class RowHit(val depot: String, val row: RackRow, val min: V3, val max: V3)

    data class Bounds(val minX: Float, val minZ: Float, val maxX: Float, val maxZ: Float)

    /**
     * The rack row under a ray, with the column (plan units are recovered from the hit point), or
     * null. Nearest hit wins.
     */
    fun pick(origin: V3, dir: V3): Triple<String, String, Int>? {
        var best = Float.MAX_VALUE
        var hit: Triple<String, String, Int>? = null
        for (r in rows) {
            val t = rayBox(origin, dir, r.min, r.max) ?: continue
            if (t >= best) continue
            val p = origin + dir * t
            val col = r.row.columnAt(p.x / S, p.z / S) ?: 1
            best = t
            hit = Triple(r.depot, r.row.name, col)
        }
        return hit
    }

    companion object {
        /** Metres per plan unit (the plan is drawn at 1 unit ≈ 10 cm). */
        const val S = 0.1f
        const val LEVEL_H = 0.46f
        const val SINGLE_LEVEL_H = 0.9f
        const val BOX_W = 0.2f
        const val BOX_GAP = 0.014f
        const val BOX_H = 0.12f
        const val BOX_D = 0.32f
        const val BOARD = 0.03f
        const val WALL_H = 0.45f

        // Instance kinds (the shader switches on them).
        const val SOLID = 0f
        const val GLOW = 1f
        const val ROUTE = 2f
        const val PIN = 3f
        const val BEAM = 4f
        const val FLAT = 5f

        const val BACKGROUND = 0xFF0E1013.toInt()
        const val FLOOR = 0xFF1C2127.toInt()
        const val WALL = 0xFF5D6873.toInt()
        const val BLOCK = 0xFF262C33.toInt()
        const val STEEL = 0xFF3B4552.toInt()
        const val BOARD_COLOR = 0xFF4B5563.toInt()
        const val BACK_MASS = 0xFF23282E.toInt()
        const val TARGET = 0xFFFFB300.toInt()
        const val ROUTE_COLOR = 0xFF56B4E9.toInt()
        const val ENTRANCE = 0xFF3DDC97.toInt()
        const val FRESH = 0xFF2FA37E.toInt()
        const val OLD = 0xFF8C6D2C.toInt()
        const val NEVER = 0xFF39404A.toInt()

        /** Shoe box colours: cardboard, black, white, grey, navy, red, teal, orange. */
        val BOXES = intArrayOf(
            0xFFB08A5A.toInt(), 0xFF2A2C30.toInt(), 0xFFE6E2DA.toInt(), 0xFF8D939A.toInt(),
            0xFF2F4A73.toInt(), 0xFFA8443A.toInt(), 0xFF2F7F7A.toInt(), 0xFFD9822B.toInt(),
            0xFFC9B79C.toInt(), 0xFF4A4F57.toInt(),
        )

        fun build(layout: SiteLayout, state: TwinState, entranceLabel: String = "Giriş"): TwinScene {
            val opaque = Instances()
            val transparent = Instances()
            val labels = ArrayList<Label>()
            val hits = ArrayList<RowHit>()
            val focus = HashMap<LocationCode, Pair<V3, Face>>()
            val active = state.highlighted.isNotEmpty() || state.stops.isNotEmpty()
            var animated = false

            // Floors (closed walls) and the low cut-away walls (open ones).
            val floor = ArrayList<Float>()
            for (w in layout.walls) {
                if (w.closed) {
                    val tri = Triangulate.polygon(w.points)
                    for (i in tri) { floor += w.points[i].x * S; floor += 0f; floor += w.points[i].y * S }
                } else {
                    for (i in 0 until w.points.size - 1) segment(opaque, w.points[i], w.points[i + 1], 0.12f, WALL_H, WALL, SOLID)
                }
            }
            for (b in layout.blocks) {
                opaque.add((b.x + b.w / 2) * S, 0.55f, (b.y + b.h / 2) * S, b.w * S, 1.1f, b.h * S, BLOCK)
            }

            for (depot in layout.depots) {
                depot.label?.let { labels += Label(V3(it.x * S, 2.9f, it.y * S), depot.name, Style.DEPOT, depot.code == state.depotFilter) }
                for (row in depot.rows) {
                    val g = RowGeometry(row)
                    fun inRow(c: LocationCode) = c.depot == depot.code && c.row.equals(row.name, ignoreCase = true)
                    val stopIndex = state.stops.indexOfFirst { inRow(it) }
                    val targeted = state.highlighted.any { inRow(it) } || stopIndex >= 0
                    val bands = state.markers.filterKeys { inRow(it) }.map { (_, f) -> g.alongOf(row.pointAlong(f, 0f)) }
                    // Known shelf: only its boxes glow (0-based level), else the whole band.
                    val shelf = state.shelves.entries.firstOrNull { inRow(it.key) }?.value?.minus(1)?.takeIf { it in 0 until row.levels }
                    val dim = active && !targeted
                    val wholeGlow = targeted && bands.isEmpty()

                    rack(opaque, g, row.levels, if (targeted) TARGET else STEEL, dim)
                    boxes(opaque, g, row.levels, seed(depot.code, row.name), dim, bands, wholeGlow, shelf)
                    if (targeted) animated = true

                    // Floor strip in front of the row: how fresh its last sweep is.
                    if (state.statusMode && !active) {
                        val cols = state.scanned.filterKeys { inRow(it) }.values
                        val c = when {
                            cols.any { it } -> FRESH
                            cols.isNotEmpty() -> OLD
                            else -> NEVER
                        }
                        g.box(opaque, 0f, g.length, -0.34f, -0.08f, 0.004f, 0.012f, c, FLAT)
                    }

                    val top = g.height + 0.12f
                    val center = g.point(g.length / 2f, g.depth / 2f, top)
                    labels += Label(center + V3(0f, 0.25f, 0f), row.name, Style.ROW, targeted)
                    hits += RowHit(depot.code, row, g.min(), g.max())

                    // Pins: the chosen code (or every found code when there are few), and pick stops.
                    val codes = state.highlighted.filter { inRow(it) }
                    for (code in codes) {
                        val f = state.markers[code]
                        val a = if (f != null) g.alongOf(row.pointAlong(f, 0f)) else g.length / 2f
                        val chosen = code == state.selected || (state.selected == null && state.highlighted.size <= 3)
                        val at = g.point(a, g.depth / 2f, g.height)
                        focus[code] = g.point(a, 0f, g.height * 0.55f) to row.face
                        if (chosen) pin(opaque, transparent, g, a, big = true)
                    }
                    if (stopIndex >= 0) {
                        val a = g.length / 2f
                        pin(opaque, transparent, g, a, big = false)
                        state.stops.forEachIndexed { i, s ->
                            if (inRow(s)) {
                                labels += Label(g.point(a, g.depth / 2f, g.height + 1.0f), (i + 1).toString(), Style.STOP, true)
                                focus[s] = g.point(a, 0f, g.height * 0.55f) to row.face
                            }
                        }
                    }
                }
            }

            // Entrance: a green mark on the floor.
            layout.nodes[layout.entrance]?.let { e ->
                opaque.add(e.x * S, 0.008f, e.y * S - 0.3f, 1.6f, 0.016f, 0.6f, ENTRANCE, kind = FLAT)
                labels += Label(V3(e.x * S, 0.6f, e.y * S), entranceLabel, Style.ENTRANCE)
            }

            // Route: a ribbon on the floor, flowing towards the target (where the pin's frame is).
            state.route?.takeIf { it.size >= 2 }?.let { pts ->
                var dist = 0f
                for (i in 0 until pts.size - 1) {
                    val len = segment(opaque, pts[i], pts[i + 1], 0.32f, 0.03f, ROUTE_COLOR, ROUTE, dist, y = 0.035f)
                    dist += len
                }
                animated = true
            }

            val all = layout.bounds()
            val bounds = Bounds(all.x * S, all.y * S, (all.x + all.w) * S, (all.y + all.h) * S)
            val depotBounds = layout.depots.associate { d ->
                val xs = d.rows.flatMap { listOf(it.rect.x, it.rect.x + it.rect.w) }
                val zs = d.rows.flatMap { listOf(it.rect.y, it.rect.y + it.rect.h) }
                d.code to if (xs.isEmpty()) bounds else Bounds(xs.min() * S, zs.min() * S, xs.max() * S, zs.max() * S)
            }
            return TwinScene(opaque, transparent, floor.toFloatArray(), labels, hits, animated, bounds, depotBounds, focus)
        }

        private fun rack(out: Instances, g: RowGeometry, levels: Int, frame: Int, dim: Boolean) {
            val steel = if (dim) shade(STEEL, 0.55f) else frame.takeIf { it == TARGET }?.let { mix(STEEL, TARGET, 0.45f) } ?: STEEL
            val board = if (dim) shade(BOARD_COLOR, 0.55f) else BOARD_COLOR
            val bays = max(1, (g.length / 1.2f).roundToInt())
            for (k in 0..bays) {
                val a = (k * g.length / bays).coerceIn(0.025f, g.length - 0.025f)
                g.box(out, a - 0.025f, a + 0.025f, 0f, g.depth, 0f, g.height, steel)
            }
            // Shelf boards; no roof, so the top shelf's boxes show from above.
            for (k in 0 until levels) {
                val y = k * g.levelH + 0.03f
                g.box(out, 0f, g.length, 0f, g.depth, y, y + BOARD, board)
            }
        }

        private fun boxes(
            out: Instances, g: RowGeometry, levels: Int, seed: Int, dim: Boolean, bands: List<Float>, wholeGlow: Boolean,
            shelf: Int? = null,
        ) {
            val stackMax = max(1, floor((g.levelH - 0.08f) / BOX_H).toInt())
            val front = min(BOX_D, g.depth * 0.6f)
            for (level in 0 until levels) {
                val base = level * g.levelH + 0.03f + BOARD
                // The depth behind the front layer: one dark mass per shelf, so oblique views look full.
                if (g.depth - front > 0.12f) {
                    g.box(out, 0.06f, g.length - 0.06f, front + 0.03f, g.depth - 0.03f, base, base + (stackMax - 0.6f) * BOX_H,
                        if (dim) shade(BACK_MASS, 0.6f) else BACK_MASS)
                }
                var a = 0.06f
                var slot = 0
                while (a + BOX_W < g.length - 0.05f) {
                    val h = hash(seed, level, slot++)
                    val w = BOX_W + (h % 5) * 0.008f
                    if (a + w > g.length - 0.05f) break
                    if (h % 100 >= 9) { // ~9 % of the slots are empty
                        val stack = (stackMax - (h / 7) % 2).coerceAtLeast(1)
                        val color = BOXES[(h / 13) % BOXES.size]
                        val mid = a + w / 2f
                        val glow = (shelf == null || shelf == level) && (wholeGlow || bands.any { kotlin.math.abs(it - mid) < 0.55f })
                        val c = when {
                            glow -> color
                            dim -> mix(shade(color, 0.45f), BACKGROUND, 0.35f)
                            else -> color
                        }
                        for (s in 0 until stack) {
                            val y0 = base + s * BOX_H
                            g.box(out, a, a + w, 0.02f, front, y0 + 0.004f, y0 + BOX_H, c,
                                if (glow) GLOW else SOLID, if (glow) (if (wholeGlow) 0.45f else 1f) else 0f)
                        }
                    }
                    a += w + BOX_GAP
                }
            }
        }

        private fun pin(opaque: Instances, transparent: Instances, g: RowGeometry, along: Float, big: Boolean) {
            val p = g.point(along, g.depth / 2f, g.height)
            val s = if (big) 0.3f else 0.2f
            opaque.add(p.x, p.y + 0.75f, p.z, s, s, s, TARGET, kind = PIN, param = 1f)
            if (big) {
                transparent.add(p.x, p.y + 1.9f, p.z, 0.42f, 3.8f, 0.42f, TARGET, alpha = 0.5f, kind = BEAM)
                // Where to stand: a glowing frame on the floor in front of the rack.
                val half = 0.4f
                val bar = 0.06f
                for ((da, dt, sa, st) in listOf(
                    floatArrayOf(0f, -half, 2 * half, bar), floatArrayOf(0f, half, 2 * half, bar),
                    floatArrayOf(-half, 0f, bar, 2 * half), floatArrayOf(half, 0f, bar, 2 * half),
                )) {
                    g.box(opaque, along + da - sa / 2, along + da + sa / 2, -0.6f + dt - st / 2, -0.6f + dt + st / 2,
                        0.01f, 0.03f, TARGET, GLOW, 1f)
                }
            }
        }

        /** A wall or route piece from [a] to [b] (plan units); returns its length in metres. */
        private fun segment(
            out: Instances, a: Pt, b: Pt, thickness: Float, height: Float, color: Int, kind: Float,
            param: Float = 0f, y: Float = height / 2f,
        ): Float {
            val dx = (b.x - a.x) * S
            val dz = (b.y - a.y) * S
            val len = sqrt(dx * dx + dz * dz)
            if (len < 1e-4f) return 0f
            val yaw = atan2(-dz, dx)
            val extra = if (kind == ROUTE) thickness else thickness * 0.5f
            out.add((a.x + b.x) / 2f * S, y, (a.y + b.y) / 2f * S, len + extra, height, thickness, color, yaw = yaw, kind = kind, param = param - extra / 2f)
            return len
        }

        private fun seed(depot: String, row: String) = (depot + "/" + row).hashCode()

        /** A small deterministic integer hash (non-negative). */
        fun hash(seed: Int, a: Int, b: Int): Int {
            var h = seed * 31 + a * 7919 + b * 104729
            h = h xor (h ushr 16)
            h *= 0x45d9f3b
            h = h xor (h ushr 16)
            return h and 0x7fffffff
        }

        fun shade(c: Int, f: Float): Int {
            val r = ((c shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
            val g = ((c shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Slab test; distance along the ray to the box, or null. */
        fun rayBox(o: V3, d: V3, min: V3, max: V3): Float? {
            var t0 = 0f
            var t1 = Float.MAX_VALUE
            val os = floatArrayOf(o.x, o.y, o.z)
            val ds = floatArrayOf(d.x, d.y, d.z)
            val mins = floatArrayOf(min.x, min.y, min.z)
            val maxs = floatArrayOf(max.x, max.y, max.z)
            for (i in 0 until 3) {
                if (kotlin.math.abs(ds[i]) < 1e-8f) {
                    if (os[i] < mins[i] || os[i] > maxs[i]) return null
                } else {
                    var a = (mins[i] - os[i]) / ds[i]
                    var b = (maxs[i] - os[i]) / ds[i]
                    if (a > b) { val t = a; a = b; b = t }
                    t0 = max(t0, a)
                    t1 = min(t1, b)
                    if (t0 > t1) return null
                }
            }
            return t0
        }
    }
}

/**
 * A rack row in metres, measured along its length (0 = the end with the smaller plan coordinate)
 * and in depth from its front edge (the side it faces) inwards.
 */
internal class RowGeometry(val row: RackRow) {
    private val s = TwinScene.S
    private val x0 = row.rect.x * s
    private val z0 = row.rect.y * s
    private val w = row.rect.w * s
    private val d = row.rect.h * s
    private val alongX = !row.face.vertical
    val length = if (alongX) w else d
    val depth = if (alongX) d else w
    val levelH = if (row.levels <= 1) TwinScene.SINGLE_LEVEL_H else TwinScene.LEVEL_H
    val height = row.levels * levelH + 0.06f

    /** World point at [along] (m), [inward] (m from the front edge; negative = out in the corridor), [y]. */
    fun point(along: Float, inward: Float, y: Float): V3 = if (alongX) {
        V3(x0 + along, y, if (row.face == Face.S) z0 + d - inward else z0 + inward)
    } else {
        V3(if (row.face == Face.E) x0 + w - inward else x0 + inward, y, z0 + along)
    }

    /** The along coordinate of a plan point. */
    fun alongOf(p: Pt): Float = (if (alongX) p.x * s - x0 else p.y * s - z0).coerceIn(0f, length)

    fun box(out: Instances, a0: Float, a1: Float, t0: Float, t1: Float, y0: Float, y1: Float, color: Int, kind: Float = TwinScene.SOLID, param: Float = 0f) {
        val c = point((a0 + a1) / 2f, (t0 + t1) / 2f, (y0 + y1) / 2f)
        val sa = a1 - a0
        val st = kotlin.math.abs(t1 - t0)
        if (alongX) out.add(c.x, c.y, c.z, sa, y1 - y0, st, color, kind = kind, param = param)
        else out.add(c.x, c.y, c.z, st, y1 - y0, sa, color, kind = kind, param = param)
    }

    fun min() = V3(x0, 0f, z0)
    fun max() = V3(x0 + w, height, z0 + d)
}

/**
 * Per-instance data for the cube mesh, [STRIDE] floats each: centre xyz, size xyz, colour rgba,
 * yaw (radians), kind, param.
 */
class Instances {
    private var data = FloatArray(STRIDE * 1024)
    var count = 0
        private set

    fun add(
        cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, color: Int,
        alpha: Float = 1f, yaw: Float = 0f, kind: Float = TwinScene.SOLID, param: Float = 0f,
    ) {
        if ((count + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        var i = count * STRIDE
        data[i++] = cx; data[i++] = cy; data[i++] = cz
        data[i++] = sx; data[i++] = sy; data[i++] = sz
        data[i++] = (color shr 16 and 0xFF) / 255f
        data[i++] = (color shr 8 and 0xFF) / 255f
        data[i++] = (color and 0xFF) / 255f
        data[i++] = alpha
        data[i++] = yaw; data[i++] = kind; data[i] = param
        count++
    }

    fun array(): FloatArray = data.copyOf(count * STRIDE)

    fun kind(i: Int) = data[i * STRIDE + 11]
    fun center(i: Int) = V3(data[i * STRIDE], data[i * STRIDE + 1], data[i * STRIDE + 2])

    companion object { const val STRIDE = 13 }
}
