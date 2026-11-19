package com.sweepscan.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.sweepscan.location.Face
import com.sweepscan.location.LocationCode
import com.sweepscan.location.Pt
import com.sweepscan.location.RackRow
import com.sweepscan.location.Rect
import com.sweepscan.location.SiteLayout

/**
 * The stock-room plan. A muted base (floor, walls, outlined racks split into columns) with few,
 * strong overlays: amber = the columns holding what was searched (amber is used for nothing
 * else), a white ring = the chosen one, a blue line with a dark casing = the walking route,
 * numbered stops = a pick list. In status mode the racks show how fresh each column's last sweep
 * is (filled = this week, hatched = older, plain = never swept).
 *
 * Pinch to zoom, drag to move, double tap to fit, tap a column to select it. [bottomInset] keeps
 * targets clear of the bottom sheet that covers the lower part of the view.
 */
class PlanView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Age { FRESH, OLD }

    var layout: SiteLayout? = null
        set(value) { field = value; fitPending = true; changed() }
    var highlighted: Set<LocationCode> = emptySet()
        set(value) { field = value; changed() }
    var selected: LocationCode? = null
        set(value) { field = value; changed() }
    var selectedRow: Pair<String, String>? = null
        set(value) { field = value; changed() }
    var route: List<Pt>? = null
        set(value) { field = value; changed() }
    /** Where along a row the searched model is (0 = left end, 1 = right end): a dot on the rack. */
    var markers: Map<LocationCode, Float> = emptyMap()
        set(value) { field = value; changed() }
    /** The shelf (1 = bottom) a found model was last read on, where known (shown by the 3D view). */
    var shelves: Map<LocationCode, Int> = emptyMap()
        set(value) { field = value; changed() }
    /** Pick list stops in walking order (numbered 1, 2, 3 …). */
    var stops: List<LocationCode> = emptyList()
        set(value) { field = value; changed() }
    var scanned: Map<LocationCode, Age> = emptyMap()
        set(value) { field = value; changed() }
    /** Show sweep freshness on the racks (no search active). */
    var statusMode = true
        set(value) { field = value; changed() }
    /** Only this depot is fitted to the view (null = all). */
    var depotFilter: String? = null
        set(value) { field = value; fitPending = true; changed() }
    /** Height (px) at the bottom covered by the bottom sheet's peek. */
    var bottomInset = 0f
        set(value) { field = value; fitPending = true; changed() }
    /** Tap on a rack: depot code, row name, column. */
    var onTap: ((String, String, Int) -> Unit)? = null

    private fun changed() {
        // TalkBack reads the map as one item: what it is and the place it marks.
        val marked = (listOfNotNull(selected) + highlighted).distinct().take(3)
        contentDescription = if (marked.isEmpty()) context.getString(com.sweepscan.R.string.plan_a11y)
        else context.getString(com.sweepscan.R.string.plan_a11y_marked, marked.joinToString(", ") { it.text })
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val view = Matrix()
    private val inverse = Matrix()
    private var fitPending = true
    private var minScale = 1f
    private var animator: ValueAnimator? = null

    // Palette: contrast measured against the floor (see reports/Konum uygulaması profesyonel tasarım.md).
    private val floorPaint = fill(FLOOR)
    private val wallPaint = stroke(WALL).apply { strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER }
    private val rackFill = fill(RACK)
    private val blockFill = fill(0xFF24292F.toInt())
    private val blockOutline = stroke(0xFF4A535D.toInt())
    private val rackOutline = stroke(RACK_OUTLINE)
    private val freshFill = fill(FRESH)
    private val hatch = stroke(OLD_HATCH)
    private val slotPaint = stroke(FLOOR)
    private val facePaint = stroke(FACE)
    private val targetFill = fill(TARGET)
    private val ringPaint = stroke(0xFFFFFFFF.toInt())
    private val rowLine = stroke(0xFFFFFFFF.toInt())
    private val casing = stroke(BACKGROUND).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val casingFill = fill(BACKGROUND)
    private val routePaint = stroke(ROUTE).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val routeEnd = fill(ROUTE)
    private val badge = fill(0xFFE3E8ED.toInt())
    private val badgeText = text(BACKGROUND, 11f, true)
    private val depotText = text(0xFFE3E8ED.toInt(), 13f, true)
    private val dimDepotText = text(0xFF8B95A1.toInt(), 13f, true)
    private val entrancePaint = fill(ENTRANCE)
    private val entranceText = text(ENTRANCE, 12f, true)
    private val stopFill = fill(TARGET)
    private val stopText = text(ON_TARGET, 12f, true)

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
    private fun stroke(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c }
    private fun text(c: Int, sp: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; textSize = sp * density; textAlign = Paint.Align.CENTER; isFakeBoldText = bold
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            animator?.cancel()
            val current = currentScale()
            val factor = d.scaleFactor.coerceIn(minScale * 0.8f / current, minScale * 10f / current)
            view.postScale(factor, factor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            animator?.cancel()
            view.postTranslate(-dx, -dy)
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { animateTo(fitMatrix()); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitPending = true
    }

    /** The part of the plan to show: one depot (with a margin) or everything. */
    private fun focusBounds(l: SiteLayout): Rect {
        val d = depotFilter?.let { l.depot(it) } ?: return l.bounds()
        val xs = d.rows.flatMap { listOf(it.rect.x, it.rect.x + it.rect.w) }
        val ys = d.rows.flatMap { listOf(it.rect.y, it.rect.y + it.rect.h) } + listOfNotNull(d.label?.y)
        val m = 24f
        return Rect(xs.min() - m, ys.min() - m, xs.max() - xs.min() + 2 * m, ys.max() - ys.min() + 2 * m)
    }

    private fun fitMatrix(): Matrix {
        val m = Matrix()
        val l = layout ?: return m
        val b = focusBounds(l)
        val all = l.bounds()
        val pad = 16f * density
        val top = 26f * density // room for the depot names above the walls
        val bottom = 30f * density // room for "Giriş" under the entrance
        val availH = (height - bottomInset - top - bottom).coerceAtLeast(height * 0.3f)
        val s = minOf((width - 2 * pad) / b.w, availH / b.h)
        minScale = minOf((width - 2 * pad) / all.w, availH / all.h)
        m.postTranslate(-b.x, -b.y)
        m.postScale(s, s)
        m.postTranslate((width - b.w * s) / 2, top + (availH - b.h * s) / 2)
        return m
    }

    fun fit() {
        if (width == 0 || height == 0 || layout == null) return
        view.set(fitMatrix())
        fitPending = false
        invalidate()
    }

    /** Animates back to the fitted view (after a depot change or a double tap). */
    fun refit() {
        if (width == 0 || layout == null) return
        animateTo(fitMatrix())
        fitPending = false
    }

    /** Brings [code]'s column into the visible part (above the sheet), panning only if needed. */
    fun focus(code: LocationCode) {
        val row = layout?.rowOf(code) ?: return
        if (width == 0) return
        if (fitPending) fit()
        val cell = row.cell(code.column)
        val target = Matrix(view)
        val p = floatArrayOf(cell.cx, cell.cy)
        target.mapPoints(p)
        val visibleBottom = height - bottomInset
        val margin = 48f * density
        if (p[0] < margin || p[0] > width - margin || p[1] < margin || p[1] > visibleBottom - margin) {
            target.postTranslate(width / 2f - p[0], visibleBottom / 2f - p[1])
            animateTo(target)
        }
    }

    private fun animateTo(target: Matrix) {
        animator?.cancel()
        val from = FloatArray(9).also { view.getValues(it) }
        val to = FloatArray(9).also { target.getValues(it) }
        val now = FloatArray(9)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                for (i in 0 until 9) now[i] = from[i] + (to[i] - from[i]) * t
                view.setValues(now)
                invalidate()
            }
            start()
        }
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        view.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val l = layout ?: return
        view.invert(inverse)
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        // A finger-sized margin (24 dp): racks are thin at the fitted scale. The nearest rack wins.
        val slop = 24f * density / currentScale()
        var best: Triple<String, RackRow, Float>? = null
        for (d in l.depots) for (r in d.rows) {
            if (!r.rect.contains(p[0], p[1], slop)) continue
            val dx = maxOf(r.rect.x - p[0], 0f, p[0] - r.rect.x - r.rect.w)
            val dy = maxOf(r.rect.y - p[1], 0f, p[1] - r.rect.y - r.rect.h)
            val dist = dx * dx + dy * dy
            if (best == null || dist < best.third) best = Triple(d.code, r, dist)
        }
        val (depot, r, _) = best ?: return
        val col = r.columnAt(p[0].coerceIn(r.rect.x, r.rect.x + r.rect.w), p[1].coerceIn(r.rect.y, r.rect.y + r.rect.h)) ?: return
        onTap?.invoke(depot, r.name, col)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        if (fitPending) fit()
        canvas.drawColor(BACKGROUND)
        val s = currentScale()
        val px = density / s // one dp in plan units
        canvas.save()
        canvas.concat(view)
        val path = Path()
        val rect = RectF()

        for (w in l.walls) {
            if (!w.closed) continue
            path.reset()
            w.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            path.close()
            canvas.drawPath(path, floorPaint)
        }
        wallPaint.strokeWidth = 3f * px
        for (w in l.walls) {
            if (w.closed) continue
            path.reset()
            w.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            canvas.drawPath(path, wallPaint)
        }

        rect.setEmpty()
        blockOutline.strokeWidth = 1f * px
        for (b in l.blocks) {
            val r = RectF(b.x, b.y, b.x + b.w, b.y + b.h)
            canvas.drawRect(r, blockFill)
            canvas.drawRect(r, blockOutline)
        }

        slotPaint.strokeWidth = 1.5f * px
        facePaint.strokeWidth = 2.5f * px
        rackOutline.strokeWidth = 1f * px
        hatch.strokeWidth = 1f * px
        ringPaint.strokeWidth = 2.5f * px
        rowLine.strokeWidth = 1.5f * px
        for (d in l.depots) for (r in d.rows) {
            for (c in 1..r.columns) {
                val cell = r.cell(c)
                rect.set(cell.x, cell.y, cell.x + cell.w, cell.y + cell.h)
                val lc = LocationCode(d.code, r.name.uppercase(), c)
                when {
                    lc in highlighted || lc == selected || lc in stops -> canvas.drawRect(rect, targetFill)
                    statusMode && scanned[lc] == Age.FRESH -> canvas.drawRect(rect, freshFill)
                    statusMode && scanned[lc] == Age.OLD -> {
                        canvas.drawRect(rect, rackFill)
                        drawHatch(canvas, rect, 5f * px)
                    }
                    else -> canvas.drawRect(rect, rackFill)
                }
                if (c < r.columns) {
                    if (r.face.vertical) canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, slotPaint)
                    else canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, slotPaint)
                }
            }
            val x0 = r.rect.x
            val y0 = r.rect.y
            val x1 = x0 + r.rect.w
            val y1 = y0 + r.rect.h
            rect.set(x0, y0, x1, y1)
            canvas.drawRect(rect, rackOutline)
            when (r.face) {
                Face.N -> canvas.drawLine(x0, y0, x1, y0, facePaint)
                Face.S -> canvas.drawLine(x0, y1, x1, y1, facePaint)
                Face.W -> canvas.drawLine(x0, y0, x0, y1, facePaint)
                Face.E -> canvas.drawLine(x1, y0, x1, y1, facePaint)
            }
            if (selectedRow == d.code to r.name) {
                rect.set(x0 - 3 * px, y0 - 3 * px, x1 + 3 * px, y1 + 3 * px)
                canvas.drawRect(rect, rowLine)
            }
        }
        // The chosen column: an outline as well as the colour (never colour alone).
        selected?.let { code ->
            val cell = l.rowOf(code)?.takeIf { code.column in 1..it.columns }?.cell(code.column) ?: return@let
            rect.set(cell.x - 2 * px, cell.y - 2 * px, cell.x + cell.w + 2 * px, cell.y + cell.h + 2 * px)
            canvas.drawRect(rect, ringPaint)
        }

        route?.let { pts ->
            if (pts.size >= 2) {
                path.reset()
                pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                casing.strokeWidth = 8f * px
                routePaint.strokeWidth = 5f * px
                canvas.drawPath(path, casing)
                canvas.drawPath(path, routePaint)
                val end = pts.last()
                canvas.drawCircle(end.x, end.y, 6f * px, casingFill)
                canvas.drawCircle(end.x, end.y, 4f * px, routeEnd)
            }
        }
        canvas.restore()

        drawLabels(canvas, l)
    }

    private fun drawHatch(canvas: Canvas, r: RectF, step: Float) {
        canvas.save()
        canvas.clipRect(r)
        var x = r.left - r.height()
        while (x < r.right) {
            canvas.drawLine(x, r.bottom, x + r.height(), r.top, hatch)
            x += step
        }
        canvas.restore()
    }

    /** Text and markers in screen space, so they keep their size when zooming. */
    private fun drawLabels(canvas: Canvas, l: SiteLayout) {
        val pt = FloatArray(2)
        val rect = RectF()
        val taken = l.depots.flatMap { d -> d.rows.map { r -> RectF(r.rect.x, r.rect.y, r.rect.x + r.rect.w, r.rect.y + r.rect.h).also { view.mapRect(it) } } }.toMutableList()
        for (d in l.depots) {
            for (r in d.rows) {
                badgeRect(r, taken, rect)
                taken += RectF(rect)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, badge)
                canvas.drawText(r.name, rect.centerX(), rect.centerY() + 4f * density, badgeText)
            }
            val at = d.label ?: Pt((d.rows.minOf { it.rect.x } + d.rows.maxOf { it.rect.x + it.rect.w }) / 2, d.rows.minOf { it.rect.y } - 8f)
            pt[0] = at.x; pt[1] = at.y; view.mapPoints(pt)
            val active = depotFilter == null || depotFilter == d.code
            canvas.drawText(d.name, pt[0], pt[1], if (active) depotText else dimDepotText)
        }
        l.nodes[l.entrance]?.let { e ->
            pt[0] = e.x; pt[1] = e.y; view.mapPoints(pt)
            val path = Path()
            val a = 7f * density
            path.moveTo(pt[0], pt[1] - a)
            path.lineTo(pt[0] - a, pt[1] + a * 0.6f)
            path.lineTo(pt[0] + a, pt[1] + a * 0.6f)
            path.close()
            canvas.drawPath(path, entrancePaint)
            canvas.drawText("Giriş", pt[0], pt[1] + a + 13f * density, entranceText)
        }
        for ((code, along) in markers) {
            val row = l.rowOf(code) ?: continue
            val thickness = if (row.face.vertical) row.rect.w else row.rect.h
            val p = row.pointAlong(along, -thickness / 2)
            pt[0] = p.x; pt[1] = p.y; view.mapPoints(pt)
            val big = code == selected
            val r = (if (big) 8f else 6f) * density
            canvas.drawCircle(pt[0], pt[1], r + 2.5f * density, casingFill)
            canvas.drawCircle(pt[0], pt[1], r, badge)
            canvas.drawCircle(pt[0], pt[1], r * 0.55f, stopFill)
        }
        stops.forEachIndexed { i, code ->
            val cell = l.rowOf(code)?.takeIf { code.column in 1..it.columns }?.cell(code.column) ?: return@forEachIndexed
            pt[0] = cell.cx; pt[1] = cell.cy; view.mapPoints(pt)
            val r = 10f * density
            canvas.drawCircle(pt[0], pt[1], r + 2f * density, casingFill)
            canvas.drawCircle(pt[0], pt[1], r, stopFill)
            canvas.drawText((i + 1).toString(), pt[0], pt[1] + 4.3f * density, stopText)
        }
    }

    /**
     * Where a row's number goes: in the corridor it faces, so it covers no columns; along the rack
     * or behind it when that spot is taken.
     */
    private fun badgeRect(r: RackRow, taken: List<RectF>, out: RectF) {
        val own = RectF(r.rect.x, r.rect.y, r.rect.x + r.rect.w, r.rect.y + r.rect.h).also { view.mapRect(it) }
        val half = 8f * density
        val gap = half + 3f * density
        fun at(side: Face, along: Float): RectF {
            val ax = own.left + own.width() * along
            val ay = own.top + own.height() * along
            val (cx, cy) = when (side) {
                Face.N -> ax to own.top - gap
                Face.S -> ax to own.bottom + gap
                Face.W -> own.left - gap to ay
                Face.E -> own.right + gap to ay
            }
            return RectF(cx - half, cy - half, cx + half, cy + half)
        }
        val back = when (r.face) { Face.N -> Face.S; Face.S -> Face.N; Face.E -> Face.W; Face.W -> Face.E }
        val candidates = listOf(0.5f, 0.3f, 0.7f, 0.15f, 0.85f).map { at(r.face, it) } + at(back, 0.5f)
        out.set(
            candidates.firstOrNull { c -> taken.none { RectF.intersects(it, c) } }
                ?: RectF(own.centerX() - half, own.centerY() - half, own.centerX() + half, own.centerY() + half),
        )
    }

    companion object {
        const val BACKGROUND = 0xFF0E1013.toInt()
        const val FLOOR = 0xFF1A1D21.toInt()
        const val WALL = 0xFF8B95A1.toInt()
        const val RACK = 0xFF2E353D.toInt()
        const val RACK_OUTLINE = 0xFF7A8591.toInt()
        const val FACE = 0xFFCFD8DC.toInt()
        const val FRESH = 0xFF2C5A4A.toInt()
        const val OLD_HATCH = 0xFF8B95A1.toInt()
        /** The searched columns. Text on it uses [ON_TARGET] (white on amber is unreadable). */
        const val TARGET = 0xFFFFB300.toInt()
        const val ON_TARGET = 0xFF241A00.toInt()
        /** Okabe-Ito sky blue: readable for colour-blind eyes, distinct from the amber. */
        const val ROUTE = 0xFF56B4E9.toInt()
        const val ENTRANCE = 0xFF3DDC97.toInt()
    }
}
