package com.sweepscan.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidTypistTest {

    private class FakeLink(var connected: Boolean = true, val failAt: Int = -1, val dropAt: Int = -1) : HidLink {
        val reports = ArrayList<ByteArray>()
        override fun isConnected(): Boolean {
            if (dropAt >= 0 && reports.size >= dropAt) connected = false
            return connected
        }
        override fun send(report: ByteArray): Boolean {
            if (reports.size == failAt) return false
            reports += report
            return true
        }
    }

    private val timing = HidTiming(holdMs = 1, confirmGraceMs = 100)

    @Test
    fun `each key is followed by an all-released report and nothing is ever erased`() {
        val link = FakeLink()
        val result = HidTypist(link) {}.type("12", timing)
        assertEquals(HidTypist.Outcome.SENT, result.outcome)
        // 1, release, 2, release, Enter, release, extra release
        assertEquals(7, link.reports.size)
        assertTrue(link.reports.none { it[2] == 0x2A.toByte() })
        assertEquals(KeyboardReport.ENTER, link.reports[4][2])
    }

    @Test
    fun `not connected means not started`() {
        val result = HidTypist(FakeLink(connected = false)) {}.type("12", timing)
        assertEquals(HidTypist.Outcome.NOT_STARTED, result.outcome)
    }

    @Test
    fun `failure in the middle is uncertain, never retried`() {
        val link = FakeLink(failAt = 2)
        val result = HidTypist(link) {}.type("123", timing)
        assertEquals(HidTypist.Outcome.UNCERTAIN, result.outcome)
        assertEquals(1, result.typed)
    }

    @Test
    fun `disconnect during the grace period after Enter is uncertain`() {
        val link = FakeLink(dropAt = 7)
        val result = HidTypist(link) {}.type("12", timing)
        assertEquals(HidTypist.Outcome.UNCERTAIN, result.outcome)
        assertTrue(result.enterSent)
    }
}
