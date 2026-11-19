package com.sweepscan.location

import kotlin.math.hypot

/**
 * Walking routes over the corridor graph of a [SiteLayout] (Dijkstra on a handful of nodes).
 * Any point — the entrance, a column's reading spot — is joined to the nearest point of the
 * nearest corridor, so racks need no nodes of their own.
 *
 * [tour] orders several stops (a pick list) for the shortest walk from the entrance: nearest
 * neighbour first, then 2-opt until no swap helps. Exact enough for a few dozen stops.
 */
object RouteFinder {

    class Route(val points: List<Pt>, val length: Float)

    /** Several stops in walking order, the whole route, and its length. */
    class Tour(val order: List<LocationCode>, val points: List<Pt>, val length: Float)

    fun route(layout: SiteLayout, code: LocationCode): Route? {
        val start = layout.nodes[layout.entrance] ?: return null
        val target = accessPoint(layout, code) ?: return null
        return shortest(layout, start, target)
    }

    fun route(layout: SiteLayout, target: Pt): Route? {
        val start = layout.nodes[layout.entrance] ?: return null
        return shortest(layout, start, target)
    }

    fun accessPoint(layout: SiteLayout, code: LocationCode): Pt? {
        val row = layout.rowOf(code) ?: return null
        if (code.column !in 1..row.columns) return null
        return row.accessPoint(code.column)
    }

    /** Shortest walk from [from] to [to], both joined to their nearest corridor. */
    fun shortest(layout: SiteLayout, from: Pt, to: Pt): Route? {
        val a = nearestOnEdges(layout, from) ?: return null
        val b = nearestOnEdges(layout, to) ?: return null
        val nodes = HashMap(layout.nodes)
        nodes[FROM] = a.point
        nodes[TO] = b.point
        val adj = HashMap<String, MutableList<Pair<String, Float>>>()
        fun link(u: String, v: String) {
            val pu = nodes[u] ?: return
            val pv = nodes[v] ?: return
            val d = dist(pu, pv)
            adj.getOrPut(u) { mutableListOf() } += v to d
            adj.getOrPut(v) { mutableListOf() } += u to d
        }
        layout.edges.forEach { (u, v) -> link(u, v) }
        link(FROM, a.edge.first)
        link(FROM, a.edge.second)
        link(TO, b.edge.first)
        link(TO, b.edge.second)
        // Both on the same corridor: straight along it.
        if (a.edge == b.edge || a.edge == b.edge.second to b.edge.first) link(FROM, TO)

        val best = HashMap<String, Float>().apply { put(FROM, 0f) }
        val prev = HashMap<String, String>()
        val done = HashSet<String>()
        while (true) {
            val u = best.filterKeys { it !in done }.minByOrNull { it.value }?.key ?: break
            if (u == TO) break
            done += u
            for ((v, d) in adj[u].orEmpty()) {
                val nd = best.getValue(u) + d
                if (nd < (best[v] ?: Float.MAX_VALUE)) {
                    best[v] = nd
                    prev[v] = u
                }
            }
        }
        val total = best[TO] ?: return null
        val path = ArrayList<Pt>()
        var k: String? = TO
        while (k != null) {
            path.add(0, nodes.getValue(k))
            k = prev[k]
        }
        if (path.first() != from) path.add(0, from)
        if (path.last() != to) path += to
        return Route(path, total + a.distance + b.distance)
    }

    fun tour(layout: SiteLayout, stops: Collection<LocationCode>): Tour? {
        val start = layout.nodes[layout.entrance] ?: return null
        val codes = stops.distinct().filter { accessPoint(layout, it) != null }
        if (codes.isEmpty()) return null
        val pts = listOf(start) + codes.map { accessPoint(layout, it)!! }
        val n = pts.size
        val d = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val len = shortest(layout, pts[i], pts[j])?.length ?: Float.MAX_VALUE / 4
            d[i][j] = len
            d[j][i] = len
        }
        // Nearest neighbour from the entrance (index 0) …
        val order = mutableListOf(0)
        val left = (1 until n).toMutableSet()
        while (left.isNotEmpty()) {
            val next = left.minBy { d[order.last()][it] }
            order += next
            left -= next
        }
        // … then 2-opt on the open path (the walk ends at the last stop).
        fun length(o: List<Int>) = o.zipWithNext().sumOf { (x, y) -> d[x][y].toDouble() }.toFloat()
        var improved = true
        while (improved) {
            improved = false
            for (i in 1 until order.size - 1) for (j in i + 1 until order.size) {
                val candidate = order.subList(0, i) + order.subList(i, j + 1).reversed() + order.subList(j + 1, order.size)
                if (length(candidate) + 1e-3f < length(order)) {
                    order.clear()
                    order += candidate
                    improved = true
                }
            }
        }
        val points = ArrayList<Pt>()
        for ((x, y) in order.zipWithNext()) {
            val leg = shortest(layout, pts[x], pts[y])?.points ?: listOf(pts[x], pts[y])
            if (points.isEmpty()) points += leg else points += leg.drop(1)
        }
        return Tour(order.drop(1).map { codes[it - 1] }, points, length(order))
    }

    class Snap(val point: Pt, val edge: Pair<String, String>, val distance: Float)

    fun nearestOnEdges(layout: SiteLayout, p: Pt): Snap? {
        var best: Snap? = null
        for (e in layout.edges) {
            val a = layout.nodes[e.first] ?: continue
            val b = layout.nodes[e.second] ?: continue
            val q = project(p, a, b)
            val d = dist(p, q)
            if (best == null || d < best.distance) best = Snap(q, e, d)
        }
        return best
    }

    private fun project(p: Pt, a: Pt, b: Pt): Pt {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return a
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return Pt(a.x + t * dx, a.y + t * dy)
    }

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    private const val FROM = "\u0000from"
    private const val TO = "\u0000to"
}
