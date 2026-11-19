package com.sweepscan.location

import com.sweepscan.tracking.BarcodeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourAndDemoTest {

    private val layout = DefaultLayout.create()

    @Test fun tourVisitsEveryStopOnceStartingNearTheEntrance() {
        val stops = listOf(LocationCode("B", "3", 1), LocationCode("K", "A", 1), LocationCode("O", "C", 1), LocationCode("B", "9", 1))
        val tour = RouteFinder.tour(layout, stops)!!
        assertEquals(stops.toSet(), tour.order.toSet())
        assertEquals(stops.size, tour.order.size)
        // The small depot is next to the entrance, the big depot at the far end.
        assertEquals(LocationCode("K", "A", 1), tour.order.first())
        assertTrue(tour.order.last().depot == "B")
        assertEquals(layout.nodes.getValue(layout.entrance), tour.points.first())
    }

    @Test fun tourIsNoLongerThanVisitingInTheGivenOrder() {
        val stops = listOf(LocationCode("B", "3", 1), LocationCode("K", "A", 1), LocationCode("B", "1", 1), LocationCode("O", "D", 1), LocationCode("K", "B", 1))
        val tour = RouteFinder.tour(layout, stops)!!
        val start = layout.nodes.getValue(layout.entrance)
        val given = (listOf(start) + stops.map { RouteFinder.accessPoint(layout, it)!! })
            .zipWithNext().sumOf { (a, b) -> RouteFinder.shortest(layout, a, b)!!.length.toDouble() }
        assertTrue("tour ${tour.length} vs given $given", tour.length <= given + 1e-3)
    }

    @Test fun shortestWithinOneCorridorIsStraight() {
        val r = RouteFinder.shortest(layout, Pt(490f, 60f), Pt(490f, 120f))!!
        assertEquals(60f, r.length, 0.5f)
    }

    @Test fun positionAlongTheRowFromTheSweepOrder() {
        // 20 boxes; a model counted 9th to 12th stands around the middle.
        val f = RowPosition.fraction(9, 12, 20)
        assertEquals(0.5f, f, 0.01f)
        assertEquals(RowPosition.Zone.MIDDLE, RowPosition.zone(f))
        assertEquals(RowPosition.Zone.LEFT, RowPosition.zone(RowPosition.fraction(1, 3, 20)))
        assertEquals(RowPosition.Zone.RIGHT, RowPosition.zone(RowPosition.fraction(18, 20, 20)))
        // Unknown size of the sweep: the middle, never a crash.
        assertEquals(0.5f, RowPosition.fraction(3, 0, 0), 0f)
        // Records from before 0.7.1 have no last position: the first one counts.
        assertEquals(RowPosition.fraction(5, 5, 10), RowPosition.fraction(5, 0, 10), 0f)
    }

    @Test fun nthModelFromTheLeft() {
        val n = RowPosition.modelOrdinals(listOf(Triple("a38", "M1", 1), Triple("b40", "M2", 4), Triple("a39", "M1", 2), Triple("c41", "M3", 9)))
        assertEquals(mapOf("a38" to 1, "a39" to 1, "b40" to 2, "c41" to 3), n)
    }

    @Test fun leftEndIsLeftWhenFacingTheRack() {
        val r = Rect(0f, 0f, 100f, 10f)
        // Boxes face south: standing south looking north, left is west (small x).
        assertTrue(RackRow("x", r, 1, 4, Face.S).pointAlong(0f).x < RackRow("x", r, 1, 4, Face.S).pointAlong(1f).x)
        // Facing north: left is east.
        assertTrue(RackRow("x", r, 1, 4, Face.N).pointAlong(0f).x > RackRow("x", r, 1, 4, Face.N).pointAlong(1f).x)
        val v = Rect(0f, 0f, 10f, 100f)
        // Facing east: left is south (large y). Facing west: left is north.
        assertTrue(RackRow("x", v, 1, 4, Face.E).pointAlong(0f).y > RackRow("x", v, 1, 4, Face.E).pointAlong(1f).y)
        assertTrue(RackRow("x", v, 1, 4, Face.W).pointAlong(0f).y < RackRow("x", v, 1, 4, Face.W).pointAlong(1f).y)
        // Reversed numbering flips the ends.
        assertEquals(RackRow("x", r, 1, 4, Face.S).pointAlong(1f), RackRow("x", r, 1, 4, Face.S, reverse = true).pointAlong(0f))
    }

    @Test fun rowsAreWholeLocationsByDefault() {
        assertTrue(layout.depots.all { d -> d.rows.all { it.columns == 1 } })
        assertEquals(listOf(5, 5), layout.depot("K")!!.rows.map { it.levels })
        val code = LocationCode("B", "3", 1)
        assertEquals("B-3", layout.short(code))
        assertEquals("Büyük depo · 3. sıra", layout.describe(code))
        assertEquals("Küçük depo · A sırası", layout.describe(LocationCode("K", "A", 1)))
    }

    @Test fun sampleBarcodesAreValidAndInStoreRange() {
        val all = DemoData.MODELS.indices.flatMap { m -> (0 until 6).map { s -> DemoData.barcode(m, s) } }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it.length == 13 && it.startsWith("200") && BarcodeRules.hasValidGs1CheckDigit(it) })
    }
}
