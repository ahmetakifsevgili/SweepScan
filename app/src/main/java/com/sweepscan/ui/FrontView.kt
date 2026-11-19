package com.sweepscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sweepscan.location.RackRow

/**
 * One rack row seen from the front: its columns side by side, [RackRow.levels] shelves high
 * (shelf 1 at the bottom), column numbers underneath. The searched columns are amber, the chosen
 * one also gets a white outline, and its column number is amber too, so the column can be found
 * without counting.
 */
class FrontView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var row: RackRow? = null
        set(value) { field = value; changed() }
    var highlighted: Set<Int> = emptySet()
        set(value) { field = value; changed() }
    var selected: Int? = null
        set(value) { field = value; changed() }
    var onTap: ((Int) -> Unit)? = null
    /** Whole-row mode: where along the row the target is (0 = left end, 1 = right end); null = none. */
    var marker: Float? = null
        set(value) { field = value; invalidate() }
    /** The shelf the target is on (1 = bottom), when known: only that shelf is marked. */
    var shelfLevel: Int? = null
        set(value) { field = value; invalidate() }
    /** Under the row: what its two ends are called. */
    var endLabels: Pair<String, String> = "" to ""

    /** TalkBack reads the row as one item: which row, and the marked column. */
    private fun changed() {
        val r = row
        val marked = (listOfNotNull(selected) + highlighted).distinct().sorted().take(3)
        contentDescription = when {
            r == null -> null
            marked.isEmpty() -> context.getString(com.sweepscan.R.string.front_a11y, r.name)
            else -> context.getString(com.sweepscan.R.string.front_a11y_marked, r.name, marked.joinToString(", "))
        }
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val shelf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PlanView.RACK }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = PlanView.RACK_OUTLINE; strokeWidth = density }
    private val hit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PlanView.TARGET }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFB300 }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2.5f * density }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B8C1.toInt(); textSize = 12f * density; textAlign = Paint.Align.CENTER }
    private val numOn = Paint(num).apply { color = PlanView.TARGET; isFakeBoldText = true }
    private val level = Paint(num).apply { textAlign = Paint.Align.RIGHT; textSize = 11f * density }
    private val rect = RectF()

    private val levelSpace get() = 22f * density

    private fun cellWidth(): Float {
        val r = row ?: return 0f
        val usable = width - paddingLeft - paddingRight - levelSpace
        // A whole row is one long strip; sections are at most 48 dp wide each.
        return if (r.columns == 1) usable else (usable / r.columns).coerceAtMost(48f * density)
    }

    private fun left(): Float {
        val r = row ?: return 0f
        val usable = width - paddingLeft - paddingRight - levelSpace
        return paddingLeft + levelSpace + (usable - cellWidth() * r.columns) / 2
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = row ?: return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = ((event.x - left()) / cellWidth()).toInt()
            if (i in 0 until r.columns) onTap?.invoke(i + 1)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val r = row ?: return
        val cw = cellWidth()
        val x0 = left()
        val numberSpace = 18f * density
        val top = paddingTop.toFloat()
        val levelH = ((height - paddingBottom - top - numberSpace) / r.levels).coerceAtMost(30f * density)
        val bottom = top + levelH * r.levels
        val gap = 1.5f * density
        // Shelf numbers on the left, 1 = bottom shelf.
        for (lv in 0 until r.levels) {
            val y = top + (r.levels - lv - 0.5f) * levelH + 4f * density
            canvas.drawText("${lv + 1}", x0 - 6f * density, y, level)
        }
        if (r.columns == 1) {
            drawWholeRow(canvas, r, x0, cw, top, levelH, bottom)
            return
        }
        for (c in 1..r.columns) {
            val x = x0 + (c - 1) * cw
            val on = c == selected || c in highlighted
            for (lv in 0 until r.levels) {
                rect.set(x + gap, top + lv * levelH + gap, x + cw - gap, top + (lv + 1) * levelH - gap)
                canvas.drawRoundRect(rect, 3f * density, 3f * density, if (on) hit else shelf)
                if (!on) canvas.drawRoundRect(rect, 3f * density, 3f * density, edge)
            }
            if (c == selected) {
                rect.set(x, top, x + cw, bottom)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, ring)
            }
            canvas.drawText(c.toString().padStart(2, '0'), x + cw / 2, bottom + 14f * density, if (on) numOn else num)
        }
    }

    /**
     * One long row: its shelves, an amber band around where the target is (about a seventh of the
     * row: the position comes from the sweep order, so it is an estimate) and a line at its centre.
     */
    private fun drawWholeRow(canvas: Canvas, r: RackRow, x0: Float, w: Float, top: Float, levelH: Float, bottom: Float) {
        val gap = 1.5f * density
        for (lv in 0 until r.levels) {
            rect.set(x0 + gap, top + lv * levelH + gap, x0 + w - gap, top + (lv + 1) * levelH - gap)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, shelf)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, edge)
        }
        marker?.let { f ->
            val half = w * 0.07f
            val cx = x0 + w * f.coerceIn(0f, 1f)
            val lv = shelfLevel?.takeIf { it in 1..r.levels }
            // Known shelf: that shelf glows, the rest of the band is a faint guide.
            val y0 = if (lv == null) top else top + (r.levels - lv) * levelH
            val y1 = if (lv == null) bottom else y0 + levelH
            if (lv != null) {
                rect.set((cx - half).coerceAtLeast(x0), top, (cx + half).coerceAtMost(x0 + w), bottom)
                canvas.drawRoundRect(rect, 4f * density, 4f * density, faint)
            }
            rect.set((cx - half).coerceAtLeast(x0), y0, (cx + half).coerceAtMost(x0 + w), y1)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, hit)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, ring)
        }
        level.textAlign = Paint.Align.LEFT
        canvas.drawText(endLabels.first, x0, bottom + 14f * density, level)
        level.textAlign = Paint.Align.RIGHT
        canvas.drawText(endLabels.second, x0 + w, bottom + 14f * density, level)
    }
}
