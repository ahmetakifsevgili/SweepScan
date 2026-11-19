package com.sweepscan.scan

import com.sweepscan.tracking.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGeometryTest {

    private val w = 1920
    private val h = 1080

    @Test
    fun `points survive a round trip for every rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val (u, v) = ImageGeometry.toUpright(300f, 200f, rotation, w, h)
            val (x, y) = ImageGeometry.toBuffer(u, v, rotation, w, h)
            assertEquals("x @$rotation", 300f, x, 0.01f)
            assertEquals("y @$rotation", 200f, y, 0.01f)
        }
    }

    @Test
    fun `portrait back camera maps the buffer top-left to the upright top-right`() {
        // rotation 90: rotating the landscape buffer clockwise puts its top-left corner at the top-right.
        val (u, v) = ImageGeometry.toUpright(0f, 0f, 90, w, h)
        assertEquals(h.toFloat(), u, 0.01f)
        assertEquals(0f, v, 0.01f)
    }

    @Test
    fun `box to buffer is normalised`() {
        val box = Box(100f, 400f, 500f, 500f) // a wide barcode in the upright (portrait) image
        val buffer = ImageGeometry.boxToBuffer(box, 90, w, h)
        assertEquals(Box(400f, 580f, 500f, 980f), buffer)
    }

    @Test
    fun `motion vectors rotate with the image`() {
        // Content moving +x in the buffer moves +y in the upright image at 90 degrees.
        assertEquals(Pair(-0f, 10f), ImageGeometry.vectorToUpright(10f, 0f, 90))
        assertEquals(Pair(-10f, -0f), ImageGeometry.vectorToUpright(10f, 0f, 180))
    }
}
