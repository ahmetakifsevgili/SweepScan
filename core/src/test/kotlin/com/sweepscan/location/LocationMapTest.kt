package com.sweepscan.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCodeTest {

    @Test fun payloadIsLettersAndDigitsOnly() {
        val code = LocationCode("B", "3", 7)
        assertEquals("LOCB307", code.payload)
        assertEquals("B-3-07", code.text)
        assertTrue(code.payload.all { it.isLetterOrDigit() })
    }

    @Test fun readsEveryLabelGeneration() {
        assertEquals(LocationCode("B", "3", 7), LocationCode.parse("LOCB307"))
        assertEquals(LocationCode("K", "A", 12), LocationCode.parse("LOCKA12"))
        // Two-character rows (big depot row 10).
        assertEquals("LOCB1003", LocationCode("B", "10", 3).payload)
        assertEquals(LocationCode("B", "10", 3), LocationCode.parse("LOCB1003"))
        assertEquals(LocationCode("B", "10", 3), LocationCode.parse("LOC:B-10-03"))
        assertEquals(LocationCode("B", "10", 3), LocationCode.parseText("b 10 3"))
        assertEquals(LocationCode("B", "3", 7), LocationCode.parse("LOC:B-3-07"))
        // First printed test labels had no depot: the big depot.
        assertEquals(LocationCode("B", "2", 7), LocationCode.parse("LOC:2-07"))
    }

    @Test fun survivesTurkishKeyboardLayoutAndCase() {
        // A HID scanner on a Turkish layout: ':' -> 'Ş', '-' -> '*'.
        assertEquals(LocationCode("B", "3", 7), LocationCode.parse("LOCŞB*3*07"))
        assertEquals(LocationCode("K", "A", 3), LocationCode.parse("locka03"))
        assertEquals(LocationCode("O", "C", 2), LocationCode.parse("LOC0C02"))
        assertEquals(LocationCode("K", "I", 4), LocationCode.parse("LOCKı04"))
    }

    @Test fun rejectsOtherCodes() {
        assertNull(LocationCode.parse("4731710551237"))
        assertNull(LocationCode.parse("LOC"))
        assertNull(LocationCode.parse("LOCB300"))
        assertNull(LocationCode.parse(null))
        assertFalse(LocationCode.isLocation("8690000000000"))
        assertTrue(LocationCode.isLocation("loc:b-3-07"))
    }

    @Test fun parsesTypedSearch() {
        assertEquals(LocationCode("B", "3", 7), LocationCode.parseText("b 3 7"))
        assertEquals(LocationCode("O", "C", 2), LocationCode.parseText("O-C-02"))
        assertEquals(LocationCode("B", "3", 1), LocationCode.parseText("b-3"))
        assertNull(LocationCode.parseText("0159"))
    }
}

class ColumnRecorderTest {

    private var now = 1000L
    private val rec = ColumnRecorder { now }
    private val b307 = LocationCode("B", "3", 7)
    private val b308 = LocationCode("B", "3", 8)

    @Test fun boxesBeforeAnyLabelAreRefused() {
        assertEquals(ColumnRecorder.Event.NoColumn, rec.onBox("111"))
        assertNull(rec.close())
    }

    @Test fun nextLabelClosesTheColumnWithItsBoxesInOrder() {
        assertTrue(rec.onLabel(b307, known = true) is ColumnRecorder.Event.Opened)
        rec.onBox("A"); rec.onBox("B"); rec.onBox("A")
        assertEquals(ColumnRecorder.Event.SameColumn, rec.onLabel(b307, known = true))
        now = 5000L
        val e = rec.onLabel(b308, known = true) as ColumnRecorder.Event.Opened
        val snap = e.closed!!
        assertEquals(b307, snap.code)
        assertEquals(listOf("A", "B", "A"), snap.barcodes)
        assertEquals(mapOf("A" to ColumnRecorder.Line(2, 1, 3), "B" to ColumnRecorder.Line(1, 2, 2)), snap.lines())
        assertEquals(b308, rec.open)
        assertEquals(0, rec.boxCount)
    }

    @Test fun unknownLabelKeepsTheOpenColumn() {
        rec.onLabel(b307, known = true)
        rec.onBox("A")
        assertTrue(rec.onLabel(LocationCode("Z", "9", 1), known = false) is ColumnRecorder.Event.UnknownLabel)
        assertEquals(b307, rec.open)
        assertEquals(1, rec.boxCount)
    }

    @Test fun emptyColumnIsNotSaved() {
        rec.onLabel(b307, known = true)
        val e = rec.onLabel(b308, known = true) as ColumnRecorder.Event.Opened
        assertNull(e.closed)
    }

    @Test fun undoAndDiscard() {
        rec.onLabel(b307, known = true)
        rec.onBox("A"); rec.onBox("B")
        assertEquals("B", rec.undo())
        assertEquals(1, rec.discard())
        assertEquals(b307, rec.open)
        assertNull(rec.close())
    }
}

class ModelTextTest {

    @Test fun readsTheShopLabel() {
        // As text recognition returns the label of the phone frame (16 Nov): model, colour, size.
        val t = ModelText.parse(listOf("0169 051", "KAHVE", "36", "4731710551237", "ÜRETİM YERİ TÜRKİYE"))!!
        assertEquals("0169 051", t.model)
        assertEquals("0169051", t.digits)
        assertEquals("KAHVE", t.color)
        assertEquals("36", t.size)
    }

    @Test fun eightDigitModelOnOneLine() {
        val t = ModelText.parse(listOf("0159 2041 / SİYAH / 38"))!!
        assertEquals("0159 2041", t.model)
        assertEquals("SİYAH", t.color)
        assertEquals("38", t.size)
    }

    @Test fun fixesLettersReadAsDigits() {
        val t = ModelText.parse(listOf("O159 2O41", "Beyaz", "4O"))!!
        assertEquals("0159 2041", t.model)
        assertEquals("BEYAZ", t.color)
        assertEquals("40", t.size)
    }

    @Test fun otherBrandsCodes() {
        assertEquals("178 137581", ModelText.parse(listOf("178 137581", "SİYAH", "40"))!!.model)
        assertEquals("1781 3799", ModelText.parse(listOf("1781 3799"))!!.model)
        // Too short to be a model code (a size and a price).
        assertNull(ModelText.parse(listOf("40  129 90")))
    }

    @Test fun barcodeDigitsAreNotAModel() {
        assertNull(ModelText.parse(listOf("4731710551237", "ÜRETİM YERİ TÜRKİYE")))
    }

    @Test fun votesNeedAgreement() {
        val v = ModelVotes()
        val good = ModelText("0169 051", "KAHVE", "36")
        val bad = ModelText("0169 061", "KAHVE", "36")
        assertNull(v.add("X", good))
        assertNull(v.add("X", bad))
        // Third read: 2 of 3 agree, enough to trust it.
        assertEquals(good, v.add("X", good))
        assertTrue(v.isLocked("X"))
        assertNull(v.add("X", good)) // locked: no second answer
    }
}

class SiteLayoutTest {

    private val layout = DefaultLayout.create()

    @Test fun threeDepotsAsDrawn() {
        assertEquals(listOf("B", "K", "O"), layout.depots.map { it.code })
        assertEquals((1..10).map { it.toString() }, layout.depot("B")!!.rows.map { it.name }.sortedBy { it.toInt() })
        assertEquals(listOf("A", "B"), layout.depot("K")!!.rows.map { it.name })
        assertEquals(listOf(1, 1), layout.depot("O")!!.rows.map { it.levels })
        assertTrue(layout.depot("B")!!.rows.all { it.levels == 4 })
    }

    @Test fun everyColumnHasARouteFromTheEntrance() {
        val entrance = layout.nodes.getValue(layout.entrance)
        for (d in layout.depots) for (r in d.rows) for (c in 1..r.columns) {
            val code = LocationCode(d.code, r.name, c)
            val route = RouteFinder.route(layout, code)
            assertNotNull("no route to $code", route)
            assertEquals(entrance, route!!.points.first())
            assertEquals(r.accessPoint(c), route.points.last())
            // The reading spot is joined to a corridor close by, not across the room.
            assertTrue("$code is ${RouteFinder.nearestOnEdges(layout, r.accessPoint(c))!!.distance} from a corridor",
                RouteFinder.nearestOnEdges(layout, r.accessPoint(c))!!.distance < 30f)
        }
    }

    @Test fun bigDepotIsFartherThanTheSmallOne() {
        val small = RouteFinder.route(layout, LocationCode("K", "A", 1))!!.length
        val big = RouteFinder.route(layout, LocationCode("B", "3", 1))!!.length
        assertTrue(big > small)
    }

    @Test fun columnsRoundTripThroughThePlan() {
        for (d in layout.depots) for (r in d.rows) {
            val rev = r.copy(reverse = true)
            for (c in 1..r.columns) {
                listOf(r, rev).forEach { row ->
                    val cell = row.cell(c)
                    assertEquals(c, row.columnAt(cell.cx, cell.cy))
                }
            }
            assertEquals(rev.cell(1), r.cell(r.columns))
        }
    }

    @Test fun jsonRoundTrip() {
        val back = SiteLayout.fromJson(org.json.JSONObject(layout.toJson().toString()))
        assertEquals(layout.depots, back.depots)
        assertEquals(layout.edges, back.edges)
        assertEquals(layout.nodes.keys, back.nodes.keys)
        assertEquals(layout.blocks, back.blocks)
        assertTrue(layout.contains(LocationCode("B", "3", 1)))
        assertFalse(layout.contains(LocationCode("B", "3", 2)))
        assertFalse(layout.contains(LocationCode("X", "1", 1)))
    }
}
