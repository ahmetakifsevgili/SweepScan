package com.sweepscan.hid

/** The transport under the typist: the Bluetooth HID link in the app, a fake in tests. */
interface HidLink {
    fun isConnected(): Boolean
    /** True only means the report was handed to the Bluetooth stack, not that the PC got it. */
    fun send(report: ByteArray): Boolean
}

/**
 * Types one barcode (digits + Enter) as key reports.
 *
 * Rules that protect the ERP from wrong input:
 * - every key is pressed, held, then released with an all-zero report, so a lost release can't
 *   leave a key repeating on the PC;
 * - nothing is ever erased: no Backspace (a retry that erases can delete the *next* field);
 * - if anything goes wrong after the first key reached the stack, the result is [Outcome.UNCERTAIN]
 *   and the caller must not retry on its own — a person checks the ERP;
 * - a barcode only counts as [Outcome.SENT] if the link stays up for a short grace period after Enter.
 */
class HidTypist(private val link: HidLink, private val sleep: (Long) -> Unit) {

    enum class Outcome { NOT_STARTED, SENT, UNCERTAIN }

    /** [typed] = digit key-downs handed to the stack; [enterSent] = the Enter key-down was. */
    data class Result(val outcome: Outcome, val typed: Int, val enterSent: Boolean, val reason: String?)

    fun type(text: String, timing: HidTiming): Result {
        val codes = text.mapNotNull { KeyboardReport.keyCode(it) } + KeyboardReport.ENTER
        if (!link.isConnected()) return Result(Outcome.NOT_STARTED, 0, false, "disconnected")
        val hold = timing.holdMs * timing.factor
        var typed = 0
        var enterSent = false

        for ((i, code) in codes.withIndex()) {
            val isEnter = i == codes.lastIndex
            if (i > 0 && !link.isConnected()) return uncertain(typed, enterSent, "disconnected")
            if (!link.send(KeyboardReport.keyDown(code))) {
                link.send(KeyboardReport.allReleased())
                return if (i == 0) Result(Outcome.NOT_STARTED, 0, false, "send_false")
                else uncertain(typed, enterSent, "send_false")
            }
            if (isEnter) enterSent = true else typed++
            sleep(hold)
            if (!link.send(KeyboardReport.allReleased())) return uncertain(typed, enterSent, "release_false")
            sleep(hold)
        }
        link.send(KeyboardReport.allReleased())

        var waited = 0L
        while (waited < timing.confirmGraceMs) {
            sleep(GRACE_STEP_MS)
            waited += GRACE_STEP_MS
            if (!link.isConnected()) return Result(Outcome.UNCERTAIN, typed, true, "disconnected_in_grace")
        }
        return Result(Outcome.SENT, typed, true, null)
    }

    /** Best-effort release of every key (after reconnects and as an idle keepalive). */
    fun releaseAll(): Boolean = link.isConnected() && link.send(KeyboardReport.allReleased())

    private fun uncertain(typed: Int, enterSent: Boolean, reason: String): Result {
        link.send(KeyboardReport.allReleased())
        return Result(Outcome.UNCERTAIN, typed, enterSent, reason)
    }

    private companion object {
        const val GRACE_STEP_MS = 50L
    }
}
