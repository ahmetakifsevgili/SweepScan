package com.sweepscan.location

/**
 * Where along a row a box is, from the order of the sweep. Rows are swept from their left end
 * (as seen standing in front of them), so a barcode whose boxes were counted around the middle of
 * the sweep stands around the middle of the row. A rough answer by design: no labels needed.
 */
object RowPosition {

    enum class Zone { LEFT, MIDDLE, RIGHT }

    /** 0 = left end, 1 = right end; 0.5 when the sweep's size is unknown. */
    fun fraction(first: Int, last: Int, total: Int): Float {
        if (total <= 0 || first <= 0) return 0.5f
        val end = if (last >= first) last else first
        return (((first + end) / 2f) - 0.5f).div(total).coerceIn(0f, 1f)
    }

    fun zone(fraction: Float): Zone = when {
        fraction < 1f / 3 -> Zone.LEFT
        fraction <= 2f / 3 -> Zone.MIDDLE
        else -> Zone.RIGHT
    }

    /**
     * The n-th different model from the left (1-based) for each barcode of one row's contents,
     * given as (barcode, model key or the barcode itself, first position).
     */
    fun modelOrdinals(items: List<Triple<String, String, Int>>): Map<String, Int> {
        val order = items.sortedBy { it.third }.map { it.second }.distinct()
        return items.associate { (barcode, key, _) -> barcode to order.indexOf(key) + 1 }
    }
}
