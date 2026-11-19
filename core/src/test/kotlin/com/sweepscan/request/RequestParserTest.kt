package com.sweepscan.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestParserTest {

    @Test fun messageFromTheGroup() {
        // As written in "Ürün isteme telsiz" on 17 Nov.
        val w = RequestParser.parse("0209 13243 siyah açma 46").single()
        assertEquals("0209 13243", w.model)
        assertEquals("020913243", w.digits)
        assertEquals("46", w.size)
        assertEquals("SİYAH", w.color)
    }

    @Test fun shortCodesAndSeveralInOneMessage() {
        val list = RequestParser.parse("109 100 40 ve 178 137581 kahve 38")
        assertEquals(listOf("109100", "178137581"), list.map { it.digits })
        assertEquals(listOf("40", "38"), list.map { it.size })
        assertEquals("KAHVE", list[1].color)
    }

    @Test fun notACode() {
        assertTrue(RequestParser.parse("saat 13:01 de gel").isEmpty())
        assertTrue(RequestParser.parse("4731710551237").isEmpty())
        assertTrue(RequestParser.parse("tamam abi").isEmpty())
    }

    @Test fun spokenCodes() {
        assertEquals("020913243", RequestParser.parseSpoken("0209 13243 46")!!.digits)
        // The recogniser split the number differently: all digits as one code.
        assertEquals("20913243", RequestParser.parseSpoken("209 13 243")!!.digits)
        assertNull(RequestParser.parseSpoken("kırk altı"))
    }

    @Test fun voiceNotes() {
        assertTrue(RequestParser.isVoice("🎤 Sesli mesaj (0:04)"))
        assertTrue(RequestParser.isVoice("🎤 Voice message (0:08)"))
        assertFalse(RequestParser.isVoice("0209 13243 siyah açma 46"))
    }
}
