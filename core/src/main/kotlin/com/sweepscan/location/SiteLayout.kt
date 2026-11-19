package com.sweepscan.location

import org.json.JSONArray
import org.json.JSONObject

data class Pt(val x: Float, val y: Float)

/** Which side of a rack the boxes face (the corridor it is read from). Up = north on the plan. */
enum class Face { N, E, S, W;

    /** Columns run along the rack: top to bottom for a rack facing east/west, left to right otherwise. */
    val vertical: Boolean get() = this == E || this == W
}

/** A rect on the plan, in plan units. */
data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    fun contains(px: Float, py: Float, slop: Float = 0f) = px >= x - slop && px <= x + w + slop && py >= y - slop && py <= y + h + slop
}

/**
 * One rack row ("sıra"): a rectangle on the plan divided into [columns] columns, [levels] shelves
 * high. Column 1 is at the top (vertical racks) or left end, or the other end when [reverse].
 */
data class RackRow(
    val name: String,
    val rect: Rect,
    val columns: Int,
    val levels: Int,
    val face: Face,
    val reverse: Boolean = false,
) {
    /** Plan rectangle of column [column] (1-based). */
    fun cell(column: Int): Rect {
        val i = (if (reverse) columns - column else column - 1).coerceIn(0, columns - 1)
        return if (face.vertical) {
            val h = rect.h / columns
            Rect(rect.x, rect.y + i * h, rect.w, h)
        } else {
            val w = rect.w / columns
            Rect(rect.x + i * w, rect.y, w, rect.h)
        }
    }

    /** Column under a plan point, or null. */
    fun columnAt(px: Float, py: Float): Int? {
        if (!rect.contains(px, py, 2f)) return null
        val f = if (face.vertical) (py - rect.y) / rect.h else (px - rect.x) / rect.w
        val i = (f * columns).toInt().coerceIn(0, columns - 1)
        return if (reverse) columns - i else i + 1
    }

    /**
     * A point at [along] of the row's length (0 = left end, 1 = right end, as seen by someone
     * standing in front of the row, facing it), [offset] units out from the facing edge (negative:
     * onto the rack).
     */
    fun pointAlong(along: Float, offset: Float = 12f): Pt {
        val f = (if (reverse) 1f - along else along).coerceIn(0f, 1f)
        return when (face) {
            // Standing south of it, looking north: left is west.
            Face.S -> Pt(rect.x + f * rect.w, rect.y + rect.h + offset)
            // Standing north, looking south: left is east.
            Face.N -> Pt(rect.x + rect.w - f * rect.w, rect.y - offset)
            // Standing east, looking west: left is south.
            Face.E -> Pt(rect.x + rect.w + offset, rect.y + rect.h - f * rect.h)
            // Standing west, looking east: left is north.
            Face.W -> Pt(rect.x - offset, rect.y + f * rect.h)
        }
    }

    /** Where someone stands to read [column]: just outside its facing edge. */
    fun accessPoint(column: Int, offset: Float = 12f): Pt {
        val c = cell(column)
        return when (face) {
            Face.N -> Pt(c.cx, c.y - offset)
            Face.S -> Pt(c.cx, c.y + c.h + offset)
            Face.W -> Pt(c.x - offset, c.cy)
            Face.E -> Pt(c.x + c.w + offset, c.cy)
        }
    }
}

/** A depot: its racks, and where its name is written on the plan ([label], plan units). */
data class Depot(val code: String, val name: String, val rows: List<RackRow>, val label: Pt? = null) {
    fun row(name: String) = rows.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val columnCount: Int get() = rows.sumOf { it.columns }
    fun codes(): List<LocationCode> = rows.flatMap { r -> (1..r.columns).map { LocationCode(code, r.name.uppercase(), it) } }
}

/** Closed: a room's floor (filled). Open: a wall line (gaps are doors). */
data class Wall(val points: List<Pt>, val closed: Boolean)

/**
 * The whole stock-room site: depots with their racks, the walls, and the walkable corridors as a
 * small graph (nodes + edges) used for the route from the entrance.
 */
data class SiteLayout(
    val depots: List<Depot>,
    val walls: List<Wall>,
    val nodes: Map<String, Pt>,
    val edges: List<Pair<String, String>>,
    val entrance: String,
    val version: Int = 1,
    /** Solid things that are not racks (an island's core, a table): drawn, never walked through. */
    val blocks: List<Rect> = emptyList(),
) {
    fun depot(code: String) = depots.firstOrNull { it.code.equals(code, ignoreCase = true) }

    fun rowOf(code: LocationCode): RackRow? = depot(code.depot)?.row(code.row)

    /** True if the label points at a column that exists in this layout. */
    fun contains(code: LocationCode): Boolean {
        val row = rowOf(code) ?: return false
        return code.column in 1..row.columns
    }

    /** True when the row is one location (not split into sections). */
    fun wholeRow(code: LocationCode): Boolean = rowOf(code)?.columns == 1

    /** "3. sıra" / "A sırası". */
    fun rowName(row: String): String = if (row.all { it.isDigit() }) "$row. sıra" else "$row sırası"

    /** "B-3" for a whole row, "B-3-07" for a section. */
    fun short(code: LocationCode): String = if (wholeRow(code)) "${code.depot}-${code.row}" else code.text

    /** "Büyük depo · 3. sıra" (or "… · 3. sıra · bölüm 2" when the row is split). */
    fun describe(code: LocationCode): String {
        val depot = depot(code.depot)?.name ?: code.depot
        val row = rowName(code.row)
        return if (wholeRow(code)) "$depot · $row" else "$depot · $row · bölüm ${code.column}"
    }

    fun bounds(): Rect {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        walls.forEach { w -> w.points.forEach { xs += it.x; ys += it.y } }
        depots.forEach { d -> d.label?.let { xs += it.x; ys += it.y - 6f } }
        depots.forEach { d -> d.rows.forEach { r -> xs += r.rect.x; xs += r.rect.x + r.rect.w; ys += r.rect.y; ys += r.rect.y + r.rect.h } }
        if (xs.isEmpty()) return Rect(0f, 0f, 100f, 100f)
        return Rect(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    fun withRow(depotCode: String, oldName: String, row: RackRow): SiteLayout = copy(
        depots = depots.map { d ->
            if (d.code != depotCode) d else d.copy(rows = d.rows.map { if (it.name == oldName) row else it })
        },
    )

    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("entrance", entrance)
        .put("depots", JSONArray(depots.map { d ->
            JSONObject().put("code", d.code).put("name", d.name)
                .also { o -> d.label?.let { o.put("label", JSONArray(listOf(it.x.toDouble(), it.y.toDouble()))) } }
                .put("rows", JSONArray(d.rows.map { r ->
                JSONObject().put("name", r.name)
                    .put("x", r.rect.x.toDouble()).put("y", r.rect.y.toDouble())
                    .put("w", r.rect.w.toDouble()).put("h", r.rect.h.toDouble())
                    .put("columns", r.columns).put("levels", r.levels)
                    .put("face", r.face.name).put("reverse", r.reverse)
            }))
        }))
        .put("walls", JSONArray(walls.map { w ->
            JSONObject().put("closed", w.closed).put("points", JSONArray(w.points.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
        }))
        .put("nodes", JSONObject().also { o -> nodes.forEach { (k, p) -> o.put(k, JSONArray(listOf(p.x.toDouble(), p.y.toDouble()))) } })
        .put("edges", JSONArray(edges.map { JSONArray(listOf(it.first, it.second)) }))
        .put("blocks", JSONArray(blocks.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.w.toDouble(), it.h.toDouble())) }))

    companion object {
        fun fromJson(o: JSONObject): SiteLayout {
            fun pt(a: JSONArray) = Pt(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            val depots = o.getJSONArray("depots").let { arr ->
                (0 until arr.length()).map { i ->
                    val d = arr.getJSONObject(i)
                    val rows = d.getJSONArray("rows").let { ra ->
                        (0 until ra.length()).map { j ->
                            val r = ra.getJSONObject(j)
                            RackRow(
                                r.getString("name"),
                                Rect(r.getDouble("x").toFloat(), r.getDouble("y").toFloat(), r.getDouble("w").toFloat(), r.getDouble("h").toFloat()),
                                r.getInt("columns"), r.getInt("levels"), Face.valueOf(r.getString("face")), r.optBoolean("reverse"),
                            )
                        }
                    }
                    Depot(d.getString("code"), d.getString("name"), rows, d.optJSONArray("label")?.let { pt(it) })
                }
            }
            val walls = o.getJSONArray("walls").let { arr ->
                (0 until arr.length()).map { i ->
                    val w = arr.getJSONObject(i)
                    val pts = w.getJSONArray("points")
                    Wall((0 until pts.length()).map { pt(pts.getJSONArray(it)) }, w.optBoolean("closed"))
                }
            }
            val nodesJson = o.getJSONObject("nodes")
            val nodes = nodesJson.keys().asSequence().associateWith { pt(nodesJson.getJSONArray(it)) }
            val edges = o.getJSONArray("edges").let { arr -> (0 until arr.length()).map { arr.getJSONArray(it).let { e -> e.getString(0) to e.getString(1) } } }
            val blocks = o.optJSONArray("blocks")?.let { arr ->
                (0 until arr.length()).map { i ->
                    arr.getJSONArray(i).let { b -> Rect(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()) }
                }
            }.orEmpty()
            return SiteLayout(depots, walls, nodes, edges, o.getString("entrance"), o.optInt("version", 1), blocks)
        }
    }
}
