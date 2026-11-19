package com.sweepscan.ui

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * How a barcode is shown on every screen: the last five digits bold and bright, the rest dimmed.
 * People match labels by their last digits; the camera overlay shows the same five.
 */
object BarcodeText {
    const val TAIL = 5

    fun tail(value: String): String = value.takeLast(TAIL)

    fun emphasised(value: String, dim: Int, bright: Int): CharSequence {
        val cut = (value.length - TAIL).coerceAtLeast(0)
        return SpannableString(value).apply {
            setSpan(ForegroundColorSpan(dim), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(bright), cut, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), cut, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.25f), cut, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
