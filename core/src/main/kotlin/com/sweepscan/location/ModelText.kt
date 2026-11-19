package com.sweepscan.location

/**
 * The human-readable line of a box label: model code, colour and size, e.g.
 * "0169 051 / KAHVE / 36" or "0159 2041 / SİYAH / 38". Parsed from text recognition lines.
 */
data class ModelText(val model: String, val color: String?, val size: String?) {

    /** Model code without spaces, the search key ("0169051"). */
    val digits: String get() = model.filter { it.isDigit() }

    companion object {
        // Model code: two groups of digits ("0159 2041", "0169 051", other brands "178 137581"),
        // 7–10 digits in all; never part of a longer number (the 13 barcode digits under the bars).
        private val MODEL = Regex("(?<![0-9])([0-9]{3,4}) ?([0-9]{3,6})(?![0-9])")
        private val SIZE = Regex("(?<![0-9])([1-5][0-9])(?:[.,]5|½)?(?![0-9])")
        private val WORD = Regex("[A-ZÇĞİÖŞÜ]{3,}")

        val COLOURS = setOf(
            "SİYAH", "BEYAZ", "KAHVE", "KAHVERENGİ", "LACİVERT", "GRİ", "BEJ", "KIRMIZI", "MAVİ", "YEŞİL",
            "PEMBE", "TABA", "VİZON", "HAKİ", "BORDO", "TURUNCU", "SARI", "MOR", "KREM", "ANTRASİT", "TEN",
            "GÜMÜŞ", "ALTIN", "BAKIR", "PUDRA", "FÜME", "LİLA", "NUDE", "SAKS", "İNDİGO", "KÜLRENGİ",
            "ÇOKRENKLİ", "RENKLİ", "LEOPAR", "ZEBRA", "EKRU", "TAŞ", "KUM", "HARDAL", "PETROL", "MİNT",
        )
        private val NOT_COLOURS = setOf("ÜRETİM", "YERİ", "TÜRKİYE", "DERİNET", "MADE", "TURKEY", "NUMARA", "BEDEN", "YERLİ")

        /** Fixes letters read where digits belong (O→0, I/l→1, S→5, B→8) inside number-like tokens. */
        fun fixDigits(token: String): String {
            val digits = token.count { it.isDigit() }
            if (digits * 2 < token.length) return token
            return token.map {
                when (it) {
                    'O', 'o', 'D', 'Q' -> '0'
                    'I', 'l', 'i', '|', 'İ', 'ı' -> '1'
                    'S', 's' -> '5'
                    'B' -> '8'
                    'Z' -> '2'
                    else -> it
                }
            }.joinToString("")
        }

        fun parse(lines: List<String>): ModelText? {
            val cleaned = lines.map { line -> line.split(' ').joinToString(" ") { fixDigits(it) } }
            var model: String? = null
            for (line in cleaned) {
                val m = MODEL.findAll(line).firstOrNull { (it.groupValues[1] + it.groupValues[2]).length in 7..10 } ?: continue
                model = m.groupValues[1] + " " + m.groupValues[2]
                break
            }
            model ?: return null
            val upper = lines.map { it.uppercase(java.util.Locale.forLanguageTag("tr")) }
            val colour = upper.flatMap { l -> WORD.findAll(l).map { it.value }.toList() }
                .firstOrNull { it in COLOURS }
                ?: upper.flatMap { l -> WORD.findAll(l).map { it.value }.toList() }
                    .firstOrNull { it !in NOT_COLOURS && it.length in 3..12 }
            // Searched in every line (the size box can be read as its own line or joined to the
            // model line), with the model code itself taken out.
            val size = cleaned
                .flatMap { l -> SIZE.findAll(l.replace(MODEL, " ")).map { it.value }.toList() }
                .firstOrNull { s -> s.take(2).toInt() in 17..50 }
            return ModelText(model, colour, size?.replace(',', '.'))
        }
    }
}

/**
 * Text recognition misreads sometimes, so a barcode's model is only trusted after several reads
 * agree: at least [minReads] reads and [minShare] of them on the same model code.
 */
class ModelVotes(private val minReads: Int = 3, private val minShare: Float = 2f / 3f) {

    private val reads = HashMap<String, MutableList<ModelText>>()
    private val locked = HashMap<String, ModelText>()

    fun isLocked(barcode: String) = barcode in locked

    /** Adds one read; returns the agreed model the first time the barcode becomes certain. */
    fun add(barcode: String, text: ModelText): ModelText? {
        if (barcode in locked) return null
        val list = reads.getOrPut(barcode) { mutableListOf() }
        list += text
        if (list.size < minReads) return null
        val (digits, votes) = list.groupingBy { it.digits }.eachCount().maxBy { it.value }
        if (votes < minShare * list.size) {
            if (list.size > 3 * minReads) list.removeAt(0) // keep it from growing forever
            return null
        }
        val agreeing = list.filter { it.digits == digits }
        val best = ModelText(
            agreeing.first().model,
            agreeing.mapNotNull { it.color }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            agreeing.mapNotNull { it.size }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
        )
        locked[barcode] = best
        reads.remove(barcode)
        return best
    }

    fun forget(barcode: String) {
        locked.remove(barcode)
        reads.remove(barcode)
    }
}
