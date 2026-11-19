package com.sweepscan.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `loads the v0_2 bare array format`() {
        val file = tmp.newFile("session.json")
        file.writeText("""[{"id":1,"value":"1639069419578","trackId":3,"time":1,"manual":false,"sent":true}]""")
        val s = ScanSession(file).also { it.load() }
        assertEquals(1, s.total())
        assertEquals(1, s.sentCount())
        assertEquals(2L, s.add("5075212470427").id)
    }

    @Test
    fun `uncertain entries are not sent again until resolved`() {
        val s = ScanSession(tmp.newFile("s.json"))
        val a = s.add("1639069419578")
        val b = s.add("5075212470427")
        s.markUncertain(a.id)
        assertEquals(listOf(b.id), s.unsent().map { it.id })
        assertEquals(1, s.uncertainCount())

        s.resolve(a.id, inDia = false)
        assertEquals(setOf(a.id, b.id), s.unsent().map { it.id }.toSet())

        s.markUncertain(b.id)
        s.resolve(b.id, inDia = true)
        assertTrue(s.find(b.id)!!.sent)
        assertFalse(s.find(b.id)!!.uncertain)
    }

    @Test
    fun `an entry being typed when the app dies comes back as uncertain`() {
        val file = tmp.newFile("s.json")
        val s = ScanSession(file)
        val a = s.add("1639069419578")
        val b = s.add("5075212470427")
        s.markSending(a.id)
        val crashed = ScanSession(file).also { it.load() }
        assertEquals(listOf(a.id), crashed.uncertainEntries().map { it.id })
        assertEquals(listOf(b.id), crashed.unsent().map { it.id })

        s.markSent(a.id)
        s.markSending(b.id)
        s.markSending(b.id, sending = false)
        val clean = ScanSession(file).also { it.load() }
        assertEquals(0, clean.uncertainCount())
        assertEquals(1, clean.sentCount())
    }

    @Test
    fun `a sweep can be removed as a whole with its warnings`() {
        val s = ScanSession(tmp.newFile("s.json"))
        s.add("1639069419578")
        s.addSuspects(unreadable = 1, uncounted = 0)
        val start = s.lastId()
        val a = s.add("5075212470427")
        val b = s.add("5075212470427")
        s.addSuspects(unreadable = 2, uncounted = 3)
        assertEquals(listOf(a.id, b.id), s.since(start).map { it.id })

        assertEquals(2, s.removeAll(setOf(a.id, b.id)))
        s.setSuspects(1, 0)
        assertEquals(1, s.total())
        assertEquals(1, s.unreadable())
        assertEquals(0, s.uncounted())
        assertTrue(s.add("5075212470427").id > b.id)
    }

    @Test
    fun `uncertain flags and suspect counters survive save and load`() {
        val file = tmp.newFile("s.json")
        val s = ScanSession(file)
        val a = s.add("1639069419578")
        s.markUncertain(a.id)
        s.addSuspects(unreadable = 3, uncounted = 2)
        val loaded = ScanSession(file).also { it.load() }
        assertEquals(1, loaded.uncertainCount())
        assertEquals(3, loaded.unreadable())
        assertEquals(2, loaded.uncounted())
    }
}
