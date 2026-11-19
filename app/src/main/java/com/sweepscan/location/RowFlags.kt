package com.sweepscan.location

import android.content.Context

/**
 * Rows whose map was found wrong ("not there"): a model the map put there could not be found.
 * They are suggested first in the row list until swept again.
 */
object RowFlags {

    private const val PREFS = "row_flags"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun flag(c: Context, code: LocationCode, model: String?) {
        prefs(c).edit().putString(code.text, "${System.currentTimeMillis()}|${model.orEmpty()}").apply()
    }

    /** Flagged rows and when; a row swept after its flag is no longer flagged. */
    fun flagged(c: Context, lastSwept: Map<LocationCode, Long>): Map<LocationCode, Long> =
        prefs(c).all.mapNotNull { (k, v) ->
            val code = LocationCode.parseText(k) ?: return@mapNotNull null
            val time = (v as? String)?.substringBefore('|')?.toLongOrNull() ?: return@mapNotNull null
            if ((lastSwept[code] ?: 0L) > time) null else code to time
        }.toMap()

    fun clear(c: Context, code: LocationCode) = prefs(c).edit().remove(code.text).apply()
}
