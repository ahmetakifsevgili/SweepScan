package com.sweepscan.twin

import com.sweepscan.location.Pt

/** Ear clipping for the small, simple floor polygons of the plan (an L-shaped room is enough). */
object Triangulate {

    /** Triangles as index triples into [poly]; empty for fewer than three points. */
    fun polygon(poly: List<Pt>): List<Int> {
        val n = poly.size
        if (n < 3) return emptyList()
        val ccw = area(poly) > 0f
        val idx = (0 until n).toMutableList()
        val out = ArrayList<Int>((n - 2) * 3)
        var guard = 0
        while (idx.size > 3 && guard++ < n * n) {
            var clipped = false
            for (i in idx.indices) {
                val a = idx[(i + idx.size - 1) % idx.size]
                val b = idx[i]
                val c = idx[(i + 1) % idx.size]
                if (!convex(poly[a], poly[b], poly[c], ccw)) continue
                if (idx.any { it != a && it != b && it != c && inside(poly[it], poly[a], poly[b], poly[c]) }) continue
                out += a; out += b; out += c
                idx.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break // not simple: give up on the rest rather than loop forever
        }
        if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
        return out
    }

    /** Signed area (positive = counter-clockwise in a y-up frame). */
    fun area(poly: List<Pt>): Float {
        var s = 0f
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            s += p.x * q.y - q.x * p.y
        }
        return s / 2f
    }

    private fun cross(a: Pt, b: Pt, c: Pt) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun convex(a: Pt, b: Pt, c: Pt, ccw: Boolean) = if (ccw) cross(a, b, c) > 0f else cross(a, b, c) < 0f

    private fun inside(p: Pt, a: Pt, b: Pt, c: Pt): Boolean {
        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        val neg = d1 < 0f || d2 < 0f || d3 < 0f
        val pos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(neg && pos)
    }
}
