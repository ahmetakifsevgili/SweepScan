package com.sweepscan.location

import kotlin.random.Random

/** One stop of a pick list: a column and what to take from it. */
data class PickItem(val code: LocationCode, val label: String, val barcodes: Set<String>)

/**
 * Sample data for the location map, so the app can be shown and tried without the shop: most
 * rows swept (some this week, some weeks ago, some twice, so the history and the differences
 * show), models side by side along each row, twelve shoe models in sizes 36–41. Barcodes use the GS1 in-store
 * prefix 200, so they can never be real product barcodes. Deterministic (fixed seed).
 */
object DemoData {

    val MODELS = listOf(
        "0159 2000" to "SİYAH", "0159 2010" to "BEYAZ", "0159 2041" to "SİYAH", "0169 051" to "KAHVE",
        "0171 3300" to "TABA", "0171 3310" to "LACİVERT", "0188 120" to "BEJ", "0190 4400" to "GRİ",
        "0203 015" to "BORDO", "0215 7720" to "VİZON", "0221 090" to "SİYAH", "0230 1180" to "KREM",
    )
    private val SIZES = listOf("36", "37", "38", "39", "40", "41")
    private const val DAY = 24L * 3600 * 1000

    fun barcode(model: Int, size: Int): String {
        val body = "200" + model.toString().padStart(3, '0') + size.toString().padStart(2, '0') + "0000"
        return body + checkDigit(body)
    }

    private fun checkDigit(body: String): Int {
        val sum = body.reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10
    }

    fun seedIfEmpty(store: LocationStore, done: () -> Unit) {
        val layout = store.layout
        store.query({ dao ->
            if (dao.scannedColumns() > 0) return@query Unit
            val now = System.currentTimeMillis()
            val random = Random(17)
            dao.putModels(
                MODELS.indices.flatMap { m ->
                    SIZES.indices.map { s ->
                        val (model, color) = MODELS[m]
                        BarcodeModel(barcode(m, s), model, model.filter { it.isDigit() }, color, SIZES[s], "örnek", now)
                    }
                },
            )
            for (depot in layout.depots) for (row in depot.rows) for (col in 1..row.columns) {
                if (random.nextFloat() > 0.8f) continue
                val code = LocationCode(depot.code, row.name.uppercase(), col)
                // Older sweep first for some rows: history and differences to look at.
                val sweeps = if (random.nextFloat() < 0.3f) listOf(now - (10 + random.nextInt(12)) * DAY, now - random.nextInt(4) * DAY)
                else listOf(now - (if (random.nextFloat() < 0.3f) 9 + random.nextInt(20) else random.nextInt(6)) * DAY - random.nextInt(8) * 3600_000L)
                // A row holds models side by side, left to right: 4–9 groups of 3–12 boxes.
                val groups = List(4 + random.nextInt(6)) { random.nextInt(MODELS.size) }
                for ((n, time) in sweeps.withIndex()) {
                    val boxes = ArrayList<String>()
                    // The newer sweep moved one group: something sold out, something new came.
                    val order = if (n == 0) groups else groups.dropLast(1) + random.nextInt(MODELS.size)
                    for (m in order) repeat(3 + random.nextInt(10)) { boxes += barcode(m, random.nextInt(SIZES.size)) }
                    val snap = ColumnRecorder.Snapshot(code, boxes, time)
                    val lines = snap.lines().map { (b, v) -> SweepLine(0, b, v.boxes, v.first, v.last) }
                    dao.recordSweep(Sweep(depot = code.depot, row = code.row, col = code.column, finishedAt = time, boxes = boxes.size), lines)
                }
            }
            Unit
        }) { done() }
    }
}
