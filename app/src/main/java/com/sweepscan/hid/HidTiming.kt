package com.sweepscan.hid

/**
 * Keyboard pacing. A keyboard can't know whether the PC accepted a line, so these are fixed
 * delays tuned in the field (Notepad test, then DİA), with every job's timing logged.
 */
data class HidTiming(
    /** How long each key is held, and the pause after its release. */
    val holdMs: Long = 25,
    /** Pause after Enter before the next barcode (DİA looks the barcode up and adds a line). */
    val gapMs: Long = 2000,
    /** Doubles every delay. */
    val slowPc: Boolean = false,
    /** Extra share of [gapMs] when a value is typed for the first time in a send run (new DİA line). */
    val newValueExtra: Float = 0.5f,
    /** After Enter, the link must stay up this long before the barcode counts as sent. */
    val confirmGraceMs: Long = 700,
) {
    val factor: Long get() = if (slowPc) 2 else 1

    fun gapAfter(firstOfValue: Boolean): Long =
        (gapMs * factor * (1f + if (firstOfValue) newValueExtra else 0f)).toLong()

    /** Rough time to type one barcode of [digits] digits plus its gap, for progress estimates. */
    fun perBarcodeMs(digits: Int = 13): Long = (digits + 1) * 2 * holdMs * factor + gapMs * factor
}
