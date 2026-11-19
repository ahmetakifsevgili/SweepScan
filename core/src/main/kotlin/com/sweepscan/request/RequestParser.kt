package com.sweepscan.request

import com.sweepscan.location.ModelText

/**
 * Reads what the shop floor asks for in the WhatsApp group ("0209 13243 siyah açma 46"): the
 * model code (two groups of digits, 6–10 in all), the size after it and a colour. Voice messages
 * are recognised by WhatsApp's notification text ("🎤 Sesli mesaj (0:04)").
 */
object RequestParser {

    data class Wanted(val model: String, val digits: String, val size: String?, val color: String?)

    private val MODEL = Regex("(?<![0-9])([0-9]{3,4})[ .\\-]?([0-9]{3,6})(?![0-9])")
    private val SIZE = Regex("(?<![0-9])([1-5][0-9])(?:[.,]5|½)?(?![0-9])")
    private val WORD = Regex("[A-ZÇĞİÖŞÜ]{3,}")
    private val TR = java.util.Locale.forLanguageTag("tr")

    /** Every model code in a message, each with the size and colour written after it. */
    fun parse(text: String): List<Wanted> {
        val out = ArrayList<Wanted>()
        val matches = MODEL.findAll(text).filter { (it.groupValues[1] + it.groupValues[2]).length in 6..10 }.toList()
        for ((i, m) in matches.withIndex()) {
            // What follows the code, up to the next code: "siyah açma 46".
            val rest = text.substring(m.range.last + 1, matches.getOrNull(i + 1)?.range?.first ?: text.length)
            val size = SIZE.findAll(rest).map { it.value }.firstOrNull { it.take(2).toInt() in 17..50 }
            val colour = WORD.findAll(rest.uppercase(TR)).map { it.value }.firstOrNull { it in ModelText.COLOURS }
            val model = m.groupValues[1] + " " + m.groupValues[2]
            out += Wanted(model, model.filter { it.isDigit() }, size?.replace(',', '.'), colour)
        }
        return out.distinctBy { it.digits }
    }

    /**
     * What the speech recogniser heard ("0209 13243 46", "209 13 243"): the code if it reads like one,
     * otherwise every digit said, as one code to search for.
     */
    fun parseSpoken(text: String): Wanted? {
        parse(text).firstOrNull()?.let { return it }
        val digits = text.filter { it.isDigit() }
        if (digits.length < 5) return null
        return Wanted(digits, digits, null, null)
    }

    fun isVoice(text: String): Boolean {
        val t = text.lowercase(TR)
        return "🎤" in text || "sesli mesaj" in t || "ses kaydı" in t || "voice message" in t || "audio" in t
    }
}
