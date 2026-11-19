package com.sweepscan.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidSenderTest {

    private class Link(var connected: Boolean = true, var failNextKeyDownAt: Int = -1) : HidLink {
        val reports = ArrayList<ByteArray>()
        override fun isConnected() = connected
        override fun send(report: ByteArray): Boolean {
            if (reports.size == failNextKeyDownAt) {
                failNextKeyDownAt = -1
                return false
            }
            reports += report
            return true
        }
    }

    private class Recorder : HidSender.Listener {
        val sent = ArrayList<Long>()
        val uncertain = ArrayList<Long>()
        val pauses = ArrayList<PauseReason?>()
        val started = ArrayList<Long>()
        val notStarted = ArrayList<Long>()
        override fun onStarted(entryId: Long) { started += entryId }
        override fun onNotStarted(entryId: Long) { notStarted += entryId }
        override fun onSent(entryId: Long) { sent += entryId }
        override fun onUncertain(entryId: Long, text: String, result: HidTypist.Result) { uncertain += entryId }
        override fun onPauseChanged(reason: PauseReason?) { pauses += reason }
    }

    private val sleeps = ArrayList<Long>()
    private val timing = HidTiming(holdMs = 1, gapMs = 1000, confirmGraceMs = 0, newValueExtra = 0.5f)

    private fun sender(link: Link, rec: Recorder) = HidSender(
        HidTypist(link) {}, { timing }, rec, events = {}, sleep = { sleeps += it }, clock = { 0L },
    )

    @Test
    fun `sends in order after the reconnect release`() {
        val link = Link()
        val rec = Recorder()
        val s = sender(link, rec)
        s.enqueue(1, "11")
        s.enqueue(2, "22")
        s.step(true) // reconnect release
        s.step(true)
        s.step(true)
        assertEquals(listOf(1L, 2L), rec.sent)
        assertEquals(0, s.pending)
    }

    @Test
    fun `a new value waits longer than a repeated one`() {
        val link = Link()
        val s = sender(link, Recorder())
        s.enqueue(1, "11")
        s.enqueue(2, "11")
        s.step(true)
        sleeps.clear()
        s.step(true)
        s.step(true)
        assertEquals(listOf(1500L, 1000L), sleeps)
    }

    @Test
    fun `uncertain pauses the queue until resume`() {
        val link = Link()
        val rec = Recorder()
        val s = sender(link, rec)
        s.enqueue(1, "11")
        s.enqueue(2, "22")
        s.step(true)
        link.failNextKeyDownAt = link.reports.size + 2 // second digit's key-down fails
        s.step(true)
        assertEquals(listOf(1L), rec.uncertain)
        assertEquals(PauseReason.UNCERTAIN, s.paused)
        s.step(true)
        assertTrue(rec.sent.isEmpty())
        s.resume()
        assertNull(s.paused)
        s.step(true)
        assertEquals(listOf(2L), rec.sent)
    }

    @Test
    fun `not started goes back to the front without pausing`() {
        val link = Link()
        val rec = Recorder()
        val s = sender(link, rec)
        s.enqueue(1, "11")
        s.step(true)
        link.failNextKeyDownAt = link.reports.size // very first key-down fails
        s.step(true)
        assertNull(s.paused)
        assertEquals(1, s.pending)
        assertEquals(listOf(1L), rec.notStarted)
        s.step(true)
        assertEquals(listOf(1L), rec.sent)
        assertEquals(listOf(1L, 1L), rec.started)
    }

    @Test
    fun `a link drop with barcodes queued pauses until the user resumes`() {
        val link = Link()
        val rec = Recorder()
        val s = sender(link, rec)
        s.enqueue(1, "11")
        s.enqueue(2, "22")
        s.step(true) // reconnect release
        s.step(true) // types 1
        s.step(false) // link drops between barcodes
        assertEquals(PauseReason.LINK, s.paused)
        s.step(true) // back: release + settle
        s.step(true) // still paused
        assertEquals(listOf(1L), rec.sent)
        s.resume()
        s.step(true)
        assertEquals(listOf(1L, 2L), rec.sent)
    }

    @Test
    fun `a link drop with an empty queue does not pause`() {
        val s = sender(Link(), Recorder())
        s.step(true)
        s.step(false)
        assertNull(s.paused)
    }

    @Test
    fun `cancel removes a queued barcode`() {
        val s = sender(Link(), Recorder())
        s.enqueue(1, "11")
        assertTrue(s.cancel(1))
        assertFalse(s.isQueued(1))
        assertFalse(s.cancel(1))
    }
}
