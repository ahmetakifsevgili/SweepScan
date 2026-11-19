package com.sweepscan.location

/**
 * One rack column: depot letter, row name, column number. Printed as "B-3-07", stored in the QR as
 * "LOCB307" / "LOCB1003": letters and digits only (depot letter, row, column always 2 digits), because a Bluetooth
 * scanner typing into a Turkish keyboard layout turns '-' into '*' and ':' into 'Ş'. The older
 * "LOC:B-3-07" and "LOC:2-07" labels are still read.
 */
data class LocationCode(val depot: String, val row: String, val column: Int) : Comparable<LocationCode> {

    /** "B-3-07": what is printed big on the label and shown in the app. */
    val text: String get() = "$depot-$row-${column.toString().padStart(2, '0')}"

    /** QR content ("LOCB307"). */
    val payload: String get() = "LOC" + depot + row + column.toString().padStart(2, '0')

    override fun toString() = text

    override fun compareTo(other: LocationCode): Int =
        compareValuesBy(this, other, { it.depot }, { it.row.length }, { it.row }, { it.column })

    companion object {
        const val PREFIX = "LOC:"

        /** Labels printed before depots existed (`LOC:2-07`) belong to the big depot. */
        const val DEFAULT_DEPOT = "B"

        // A handheld scanner typing into a Turkish keyboard layout can turn ':' and '-' into other
        // characters, and lower-case letters into dotted/dotless i: accept any separator.
        private val PATTERN = Regex("^LOC[^A-Z0-9]?(?:([A-Z])[^A-Z0-9])?([A-Z0-9]{1,2})[^A-Z0-9]([0-9]{1,3})$")

        /** Upper case without the Turkish dotted/dotless i (the phone's locale is Turkish). */
        fun normalise(value: String): String =
            value.trim().replace('ı', 'i').replace('İ', 'I').uppercase(java.util.Locale.ROOT)

        fun isLocation(value: String?): Boolean = value != null && normalise(value).startsWith("LOC")

        /** Parses a scanned value; null if it is not a location label. */
        fun parse(value: String?): LocationCode? {
            if (value == null) return null
            val v = normalise(value)
            val m = PATTERN.matchEntire(v) ?: return parseCompact(v)
            val depot = m.groupValues[1].ifEmpty { DEFAULT_DEPOT }
            val column = m.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return LocationCode(depot, m.groupValues[2], column)
        }

        /** "LOCB307" / "LOCB1003": depot letter, the row (1–2 characters), the column as its last 2 digits. */
        private fun parseCompact(v: String): LocationCode? {
            if (!v.startsWith("LOC")) return null
            val body = v.removePrefix("LOC")
            if (body.length !in 4..5 || !body.all { it.isLetterOrDigit() }) return null
            // The depot slot is always a letter: a 0 there is a misread O.
            val depot = body[0].let { if (it == '0') 'O' else it }
            if (!depot.isLetter()) return null
            val column = body.takeLast(2).toIntOrNull()?.takeIf { it > 0 } ?: return null
            return LocationCode(depot.toString(), body.substring(1, body.length - 2), column)
        }

        /** "B-3", "B-3-07" or "b 3 7" typed in a search box. */
        fun parseText(text: String): LocationCode? {
            val parts = normalise(text).split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            // "B 3" / "B-3": a whole row (its only location is column 1).
            if (parts.size == 2) return LocationCode(parts[0], parts[1], 1)
            if (parts.size != 3) return null
            val column = parts[2].toIntOrNull() ?: return null
            return LocationCode(parts[0], parts[1], column)
        }
    }
}
