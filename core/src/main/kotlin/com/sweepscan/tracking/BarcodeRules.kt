package com.sweepscan.tracking

/**
 * Which decoded strings are allowed to become a count.
 *
 * The shoe-box labels carry numeric Code 128 values of 13 digits (some 8), so anything else seen
 * by the camera (price tags, stray codes, truncated reads) is ignored. The strict set can be
 * loosened to 8–14 digits from the settings if real labels turn out to differ.
 */
object BarcodeRules {
    private val NUMERIC = Regex("^[0-9]+$")

    val STRICT_LENGTHS: Set<Int> = setOf(8, 13)
    val LOOSE_LENGTHS: Set<Int> = (8..14).toSet()

    @Volatile
    var allowedLengths: Set<Int> = STRICT_LENGTHS

    fun isAcceptable(value: String?, lengths: Set<Int> = allowedLengths): Boolean =
        value != null && value.length in lengths && NUMERIC.matches(value)

    /** "8 or 13 digits" / "8–14 digits", for messages. */
    fun describe(lengths: Set<Int> = allowedLengths): String {
        val sorted = lengths.sorted()
        val contiguous = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
        return when {
            sorted.isEmpty() -> "-"
            contiguous && sorted.size > 2 -> "${sorted.first()}–${sorted.last()}"
            else -> sorted.joinToString("/")
        }
    }

    /** GS1 mod-10 check digit, used only as an informational flag (the labels are not required to be EAN). */
    fun hasValidGs1CheckDigit(value: String): Boolean {
        if (value.length < 8 || !NUMERIC.matches(value)) return false
        val digits = value.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.withIndex().sumOf { (i, d) -> if (i % 2 == 0) d * 3 else d }
        return (10 - sum % 10) % 10 == digits.last()
    }
}
