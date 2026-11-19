package com.sweepscan.location

/**
 * The shop's three stock rooms as a clean plan (1 unit ≈ 10 cm; sizes are not measured yet).
 *
 * A location is a whole row ("Büyük depo · 3. sıra"): rows are swept from their left end and the
 * order of the sweep says roughly where along the row a model is, so no labels are needed. A row
 * can still be split into sections in the layout screen.
 *
 * - Left room: the small depot (racks A along the left wall and B, facing each other across a
 *   narrow aisle, as in the photo of 17 Nov; 5 shelves) and the middle area depot (C along the
 *   top wall, D a small island; 1 shelf). Entrance at the bottom.
 * - A short passage on the right leads into the big depot (B), drawn after the numbered sketch of
 *   17 Nov (turned so its entrance faces the passage): rows 1–6 along the walls, a middle island
 *   read from both long sides (7, 8), a second island read from both sides (9, 10); 4 shelves.
 */
object DefaultLayout {

    /** Bumped when the default plan changes; an older saved layout is replaced by the new one. */
    const val VERSION = 4

    fun create(): SiteLayout {
        val big = Depot(
            "B", "Büyük depo",
            listOf(
                RackRow("1", Rect(459f, 10f, 10f, 150f), 1, 4, Face.E),
                RackRow("2", Rect(469f, 0f, 155f, 10f), 1, 4, Face.S),
                RackRow("3", Rect(624f, 10f, 10f, 280f), 1, 4, Face.W),
                RackRow("4", Rect(430f, 290f, 194f, 10f), 1, 4, Face.N),
                RackRow("5", Rect(420f, 225f, 10f, 65f), 1, 4, Face.E),
                RackRow("6", Rect(420f, 215f, 26f, 10f), 1, 4, Face.N),
                RackRow("7", Rect(512f, 60f, 12f, 140f), 1, 4, Face.W),
                RackRow("8", Rect(557f, 60f, 12f, 140f), 1, 4, Face.E),
                RackRow("9", Rect(473f, 246f, 118f, 12f), 1, 4, Face.S),
                RackRow("10", Rect(519f, 236f, 60f, 10f), 1, 4, Face.N),
            ),
            label = Pt(546f, -12f),
        )
        val small = Depot(
            "K", "Küçük depo",
            listOf(
                RackRow("A", Rect(0f, 40f, 16f, 240f), 1, 5, Face.E),
                RackRow("B", Rect(90f, 60f, 16f, 190f), 1, 5, Face.W),
            ),
            label = Pt(55f, 8f),
        )
        val middle = Depot(
            "O", "Orta alan deposu",
            listOf(
                RackRow("C", Rect(160f, 20f, 220f, 16f), 1, 1, Face.S),
                RackRow("D", Rect(230f, 125f, 40f, 30f), 1, 1, Face.S),
            ),
            label = Pt(270f, 8f),
        )
        val floors = listOf(
            Wall(listOf(Pt(0f, 20f), Pt(400f, 20f), Pt(400f, 300f), Pt(0f, 300f)), closed = true),
            Wall(listOf(Pt(400f, 170f), Pt(420f, 170f), Pt(420f, 200f), Pt(400f, 200f)), closed = true),
            Wall(listOf(Pt(459f, 0f), Pt(634f, 0f), Pt(634f, 300f), Pt(420f, 300f), Pt(420f, 170f), Pt(459f, 170f)), closed = true),
        )
        val walls = listOf(
            // Left room, open on the right (passage) and at the bottom (entrance).
            Wall(listOf(Pt(400f, 170f), Pt(400f, 20f), Pt(0f, 20f), Pt(0f, 300f), Pt(150f, 300f)), closed = false),
            Wall(listOf(Pt(200f, 300f), Pt(400f, 300f), Pt(400f, 200f), Pt(420f, 200f), Pt(420f, 300f), Pt(634f, 300f), Pt(634f, 0f), Pt(459f, 0f), Pt(459f, 170f), Pt(400f, 170f)), closed = false),
        )
        // The middle island's solid core, and the unlabelled block just inside the big depot's door.
        val blocks = listOf(Rect(524f, 60f, 33f, 140f), Rect(455f, 190f, 17f, 34f))
        val nodes = mapOf(
            "entrance" to Pt(175f, 300f),
            "e1" to Pt(175f, 270f),
            "k1" to Pt(53f, 270f),
            "k2" to Pt(53f, 50f),
            "m1" to Pt(135f, 270f),
            "m2" to Pt(135f, 60f),
            "m3" to Pt(380f, 60f),
            "m4" to Pt(135f, 185f),
            "m5" to Pt(380f, 185f),
            "door" to Pt(410f, 185f),
            "bE" to Pt(440f, 180f),
            "bA" to Pt(490f, 180f),
            "bB" to Pt(490f, 35f),
            "bC" to Pt(595f, 35f),
            "bD" to Pt(595f, 218f),
            "bF" to Pt(595f, 274f),
            "bG" to Pt(440f, 274f),
            "bJ" to Pt(440f, 240f),
            "bI" to Pt(490f, 218f),
        )
        val edges = listOf(
            "entrance" to "e1", "e1" to "k1", "e1" to "m1", "k1" to "k2", "m1" to "m4", "m4" to "m2",
            "m2" to "m3", "m3" to "m5", "m4" to "m5", "m5" to "door", "door" to "bE", "bE" to "bA",
            "bA" to "bB", "bB" to "bC", "bC" to "bD", "bD" to "bF", "bF" to "bG", "bG" to "bJ", "bJ" to "bI",
            "bI" to "bA", "bI" to "bD",
        )
        return SiteLayout(listOf(big, small, middle), floors + walls, nodes, edges, "entrance", VERSION, blocks)
    }
}
