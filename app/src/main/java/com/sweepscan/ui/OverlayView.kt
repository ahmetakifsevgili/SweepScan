package com.sweepscan.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sweepscan.tracking.TrackView
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the tracked labels over the camera preview, in the style of current scanner apps:
 * rounded frames, a filled check badge on counted boxes, quiet outlines for the rest.
 * Green = counted box, amber = still collecting reads, red with "!" = located but not read,
 * dashed = not seen this frame. In single-scan mode ([viewfinder]) a rounded aiming frame
 * with corner marks shows where to hold the label.
 *
 * Tracks are in upright analysis-image pixels. The preview uses FILL_CENTER and both streams are
 * 16:9 from the same sensor, so image → view is one uniform scale plus a centring offset.
 */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var tracks: List<TrackView> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private val corner = 8f * density

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = UNREAD
    }
    private val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (UNREAD and 0x00FFFFFF) or 0x40000000
    }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val textBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val dashed = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    private val rect = RectF()
    private val path = Path()

    /** Find mode: these barcodes are drawn loud (amber, "BURADA"), everything else faint. */
    var targets: Set<String>? = null
        set(value) { field = value; invalidate() }
    private val findStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * density; color = FOUND }
    private val findFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (FOUND and 0x00FFFFFF) or 0x4D000000 }
    private val otherStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x80FFFFFF.toInt() }
    private val findText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF241A00.toInt(); textSize = 15f * density; isFakeBoldText = true }
    private val findBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FOUND }

    /** Single-scan mode: a rounded aiming frame in the middle; it turns green while a label is read. */
    var viewfinder = false
        set(value) { field = value; invalidate() }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val frame = RectF()

    fun setTracks(newTracks: List<TrackView>, width: Int, height: Int) {
        tracks = newTracks
        imageWidth = width.coerceAtLeast(1)
        imageHeight = height.coerceAtLeast(1)
        invalidate()
    }

    fun clear() = setTracks(emptyList(), imageWidth, imageHeight)

    override fun onDraw(canvas: Canvas) {
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f
        fun place(t: TrackView) = rect.set(
            t.box.left * scale + offsetX, t.box.top * scale + offsetY,
            t.box.right * scale + offsetX, t.box.bottom * scale + offsetY,
        )

        if (viewfinder) drawViewfinder(canvas, tracks.any { it.value != null && !it.unreadable })

        targets?.let { wanted ->
            for (t in tracks) {
                if (t.unreadable) continue
                place(t)
                if (t.value == null || t.value !in wanted) {
                    canvas.drawRoundRect(rect, corner, corner, otherStroke)
                    continue
                }
                val pad = 10f * density
                rect.inset(-pad, -pad)
                canvas.drawRoundRect(rect, corner * 1.5f, corner * 1.5f, findFill)
                canvas.drawRoundRect(rect, corner * 1.5f, corner * 1.5f, findStroke)
                val label = context.getString(com.sweepscan.R.string.find_here)
                val w = findText.measureText(label) + 16f * density
                val h = findText.textSize + 12f * density
                val top = rect.top - h - 4f * density
                canvas.drawRoundRect(rect.left, top, rect.left + w, top + h, h / 2, h / 2, findBadge)
                canvas.drawText(label, rect.left + 8f * density, top + h - 7f * density, findText)
            }
            return
        }
        for (t in tracks) {
            place(t)
            if (t.unreadable) {
                // Located but never read: as loud as a counted box, so it isn't overlooked in a sweep.
                ghost.pathEffect = if (t.visible) null else dashed
                canvas.drawRoundRect(rect, corner, corner, ghostFill)
                canvas.drawRoundRect(rect, corner, corner, ghost)
                drawBadge(canvas, rect.left, rect.top, UNREAD) {
                    canvas.drawText("!", rect.left, rect.top - (badgeText.descent() + badgeText.ascent()) / 2f, badgeText)
                }
                continue
            }
            val color = if (t.counted) COUNTED else CANDIDATE
            stroke.color = color
            stroke.pathEffect = if (t.visible) null else dashed
            if (t.counted) {
                fill.color = (color and 0x00FFFFFF) or 0x40000000
                canvas.drawRoundRect(rect, corner, corner, fill)
            }
            canvas.drawRoundRect(rect, corner, corner, stroke)

            // Only counted boxes get a badge and a label (last five digits, as in the strip and the
            // list): fewer labels on screen, and what has been counted stands out when sweeping back.
            if (!t.counted) continue
            drawBadge(canvas, rect.left, rect.top, COUNTED) { drawCheck(canvas, rect.left, rect.top) }
            val label = t.value?.let { BarcodeText.tail(it) } ?: continue
            val w = text.measureText(label) + 14f * density
            val h = text.textSize + 10f * density
            val x = rect.left + 14f * density
            val y = rect.top - h / 2
            canvas.drawRoundRect(x, y, x + w, y + h, h / 2, h / 2, textBg)
            canvas.drawText(label, x + 7f * density, y + h - 6.5f * density, text)
        }
    }

    /** A round badge with a white ring, centred on a frame's corner. */
    private inline fun drawBadge(canvas: Canvas, cx: Float, cy: Float, color: Int, content: () -> Unit) {
        val r = 11f * density
        badge.color = color
        canvas.drawCircle(cx, cy, r, badge)
        canvas.drawCircle(cx, cy, r, badgeRing)
        content()
    }

    private fun drawCheck(canvas: Canvas, cx: Float, cy: Float) {
        val u = density
        path.reset()
        path.moveTo(cx - 4.5f * u, cy + 0.5f * u)
        path.lineTo(cx - 1.2f * u, cy + 3.8f * u)
        path.lineTo(cx + 5f * u, cy - 3.2f * u)
        canvas.drawPath(path, check)
    }

    /** Dims around a rounded frame (wide, for box labels) and draws its four corners. */
    private fun drawViewfinder(canvas: Canvas, reading: Boolean) {
        val w = width * 0.82f
        val h = min(w * 0.55f, height * 0.3f)
        val cx = width / 2f
        val cy = height * 0.45f
        frame.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val r = 20f * density
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(frame, r, r, Path.Direction.CW)
        canvas.drawPath(path, shade)
        path.fillType = Path.FillType.WINDING
        bracket.color = if (reading) COUNTED else Color.WHITE
        val len = 28f * density
        val (l, t, rr, b) = listOf(frame.left, frame.top, frame.right, frame.bottom)
        path.reset()
        // Each corner: a short line, the rounded corner, a short line.
        path.moveTo(l, t + len); path.lineTo(l, t + r); path.quadTo(l, t, l + r, t); path.lineTo(l + len, t)
        path.moveTo(rr - len, t); path.lineTo(rr - r, t); path.quadTo(rr, t, rr, t + r); path.lineTo(rr, t + len)
        path.moveTo(rr, b - len); path.lineTo(rr, b - r); path.quadTo(rr, b, rr - r, b); path.lineTo(rr - len, b)
        path.moveTo(l + len, b); path.lineTo(l + r, b); path.quadTo(l, b, l, b - r); path.lineTo(l, b - len)
        canvas.drawPath(path, bracket)
    }

    private companion object {
        const val COUNTED = 0xFF2ECC71.toInt() // @color/ok
        const val CANDIDATE = 0xFFFFB300.toInt()
        const val UNREAD = 0xFFE53935.toInt()
        const val FOUND = 0xFFFFB300.toInt()
    }
}
