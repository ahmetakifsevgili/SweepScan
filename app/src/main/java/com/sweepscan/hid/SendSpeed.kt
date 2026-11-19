package com.sweepscan.hid

/**
 * Typing speed presets for the PC, instead of separate hold / gap / grace settings.
 *
 * Per barcode on the S23 Ultra (16 Nov, SAFE): 14 key presses ~0.8 s, then the gap after Enter
 * (during which the link must stay up for [graceMs]). [FAST] aims at about one barcode a second, so
 * barcodes counted while sweeping leave in order (FIFO) roughly as fast as they are counted. Whether
 * DİA keeps up at that pace has to be checked in DİA itself: the shop laptop dropped input that
 * came too fast.
 */
enum class SendSpeed(val holdMs: Long, val gapMs: Long, val newValueExtra: Float, val graceMs: Long) {
    SAFE(25, 2000, 0.5f, 700),
    NORMAL(20, 1000, 0.3f, 500),
    FAST(15, 500, 0f, 300),
    ;

    fun timing() = HidTiming(holdMs = holdMs, gapMs = gapMs, newValueExtra = newValueExtra, confirmGraceMs = graceMs)

    /** Rough time per 13-digit barcode, for labels: keys, a little Bluetooth overhead, then the gap. */
    val approxMsPerBarcode: Long
        get() = 14 * 2 * holdMs + 100 + maxOf(gapMs, graceMs)
}
