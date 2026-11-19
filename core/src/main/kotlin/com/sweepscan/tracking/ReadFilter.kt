package com.sweepscan.tracking

import kotlin.math.max

/** Why a raw detection was accepted, downgraded or merged before tracking. */
enum class ReadVerdict { OK, LOCATED, BAD_CHARS, BAD_LENGTH, BAD_CHECK, EDGE, DUPLICATE, CONFLICT, OVERSIZE }

class FilteredReads(
    /** What the tracker sees. */
    val detections: List<Detection>,
    /** One verdict per *input* detection, index-aligned, for logging. */
    val verdicts: List<ReadVerdict>,
)

/**
 * Per-frame read validation (research report, S4).
 *
 * Decoders can return wrong values that still pass a check digit, and partial barcodes at the frame
 * edge are read as complete ones (googlesamples/mlkit #32). A rejected read is not thrown away: it
 * is downgraded to "located" (value = null), so it cannot vote or start a track but still keeps an
 * existing track alive. Overlapping detections in one frame are one physical label.
 *
 * A located-only "barcode" covering most of the picture is dropped altogether: pointed at a plain
 * wall, ML Kit reports the whole frame as a potential barcode, which would draw a screen-sized
 * "unread" marker.
 */
object ReadFilter {

    private val DIGITS = Regex("^[0-9]+$")

    fun apply(raw: List<Detection>, imageWidth: Int, imageHeight: Int, config: TrackerConfig): FilteredReads {
        if (!config.validateReads) return FilteredReads(raw, raw.map { if (it.decoded) ReadVerdict.OK else ReadVerdict.LOCATED })
        val lengths = config.lengths ?: BarcodeRules.allowedLengths
        val verdicts = MutableList(raw.size) { ReadVerdict.OK }
        val dets = raw.toMutableList()

        // 1. Validate each read on its own.
        for ((i, d) in raw.withIndex()) {
            val value = d.value
            val verdict = when {
                value == null -> ReadVerdict.LOCATED
                !DIGITS.matches(value) -> ReadVerdict.BAD_CHARS
                value.length !in lengths -> ReadVerdict.BAD_LENGTH
                config.requireGs1For13 && value.length == 13 && !BarcodeRules.hasValidGs1CheckDigit(value) -> ReadVerdict.BAD_CHECK
                touchesEdge(d.box, imageWidth, imageHeight, config.edgeMarginPx) -> ReadVerdict.EDGE
                else -> ReadVerdict.OK
            }
            verdicts[i] = verdict
            if (verdict != ReadVerdict.OK && value != null) dets[i] = d.copy(value = null)
        }

        // 2. Overlapping detections are one label.
        val removed = BooleanArray(dets.size)
        if (imageWidth > 0 && imageHeight > 0) {
            val limit = OVERSIZE_SHARE * imageWidth * imageHeight
            for ((i, d) in dets.withIndex()) {
                if (raw[i].value == null && area(d.box) > limit) {
                    removed[i] = true
                    verdicts[i] = ReadVerdict.OVERSIZE
                }
            }
        }
        val extra = ArrayList<Detection>()
        for (i in dets.indices) {
            if (removed[i]) continue
            for (j in i + 1 until dets.size) {
                if (removed[i]) break
                if (removed[j] || !overlaps(dets[i].box, dets[j].box, config.dedupeIou)) continue
                val a = dets[i]
                val b = dets[j]
                when {
                    a.value != null && b.value != null && a.value != b.value -> {
                        // Two different values on one label: trust neither, keep the location.
                        removed[i] = true; removed[j] = true
                        verdicts[i] = ReadVerdict.CONFLICT; verdicts[j] = ReadVerdict.CONFLICT
                        extra += Detection(null, union(a.box, b.box), a.engine)
                    }
                    a.value != null && b.value == null -> { removed[j] = true; verdicts[j] = ReadVerdict.DUPLICATE }
                    a.value == null && b.value != null -> { removed[i] = true; verdicts[i] = ReadVerdict.DUPLICATE }
                    area(a.box) >= area(b.box) -> { removed[j] = true; verdicts[j] = ReadVerdict.DUPLICATE }
                    else -> { removed[i] = true; verdicts[i] = ReadVerdict.DUPLICATE }
                }
            }
        }
        val kept = dets.filterIndexed { i, _ -> !removed[i] } + extra
        return FilteredReads(kept, verdicts)
    }

    fun touchesEdge(box: Box, width: Int, height: Int, marginPx: Float): Boolean {
        if (marginPx < 0f || width <= 0 || height <= 0) return false
        val m = max(marginPx, 0.004f * width)
        return box.left < m || box.top < m || box.right > width - m || box.bottom > height - m
    }

    private fun overlaps(a: Box, b: Box, iouThreshold: Float): Boolean =
        a.iou(b) > iouThreshold || contains(a, b.centerX, b.centerY) || contains(b, a.centerX, a.centerY)

    private fun contains(box: Box, x: Float, y: Float) = x in box.left..box.right && y in box.top..box.bottom

    private fun union(a: Box, b: Box) =
        Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

    private fun area(b: Box) = b.width * b.height

    /** A real label never fills this share of the picture, even with one box in view. */
    private const val OVERSIZE_SHARE = 0.45f
}
