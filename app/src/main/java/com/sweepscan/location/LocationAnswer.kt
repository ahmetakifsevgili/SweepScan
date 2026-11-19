package com.sweepscan.location

/**
 * The short answer to "where is this model?" for places outside the search screen (request
 * notifications): the nearest place holding it, where along that row, which model from the left,
 * the sizes seen, and how many places hold it in all.
 */
object LocationAnswer {

    data class Answer(
        val model: String?,
        val color: String?,
        val code: LocationCode,
        val along: Float?,
        val ordinal: Int?,
        val sizes: List<String>,
        val places: Int,
    )

    /** Runs on the database thread. */
    fun find(dao: LocationDao, layout: SiteLayout, digits: String): Answer? {
        val rows = dao.findByModel(digits).filter { layout.contains(it.code) }
        if (rows.isEmpty()) return null
        val codes = rows.map { it.code }.toSet()
        val nearest = codes.minBy { RouteFinder.route(layout, it)?.length ?: Float.MAX_VALUE }
        val items = rows.filter { it.code == nearest }
        val along = if (layout.wholeRow(nearest)) {
            RowPosition.fraction(items.minOf { it.firstOrder }, items.maxOf { maxOf(it.lastOrder, it.firstOrder) }, items.first().total ?: 0)
        } else {
            null
        }
        val contents = dao.column(nearest.depot, nearest.row, nearest.column)
        fun key(s: SightingWithModel) = s.model?.let { m -> listOfNotNull(m, s.color).joinToString(" · ") } ?: s.barcode
        val ordinals = RowPosition.modelOrdinals(contents.map { Triple(it.barcode, key(it), it.firstOrder) })
        val ordinal = items.mapNotNull { ordinals[it.barcode] }.minOrNull()
        val sizes = rows.mapNotNull { it.size }.distinct().sortedBy { it.toFloatOrNull() ?: 999f }
        return Answer(items.first().model, items.first().color, nearest, along, ordinal, sizes, codes.size)
    }
}
