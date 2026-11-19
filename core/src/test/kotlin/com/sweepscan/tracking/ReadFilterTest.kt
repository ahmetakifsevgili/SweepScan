package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadFilterTest {

    private val config = TrackerConfig()
    private val w = 1080
    private val h = 1920

    private fun det(value: String?, l: Float, t: Float, r: Float = l + 240f, b: Float = t + 60f) = Detection(value, Box(l, t, r, b))

    private fun run(vararg dets: Detection, cfg: TrackerConfig = config) = ReadFilter.apply(dets.toList(), w, h, cfg)

    @Test
    fun `valid 13 and 8 digit reads pass`() {
        val out = run(det("1639069419578", 100f, 100f), det("10148017", 100f, 400f))
        assertEquals(listOf(ReadVerdict.OK, ReadVerdict.OK), out.verdicts)
        assertEquals(2, out.detections.count { it.decoded })
    }

    @Test
    fun `a located-only box that fills the picture is dropped, a decoded or normal one is not`() {
        val whole = Box(27f, 22f, 1050f, 1800f)
        val out = run(Detection(null, whole), det(null, 100f, 100f), Detection("1639069419578", Box(60f, 300f, 1000f, 1700f)))
        assertEquals(listOf(ReadVerdict.OVERSIZE, ReadVerdict.LOCATED, ReadVerdict.OK), out.verdicts)
        assertEquals(2, out.detections.size)
    }

    @Test
    fun `wrong characters and lengths are downgraded to located, not dropped`() {
        val out = run(det("ABC1234567890", 100f, 100f), det("163906941957", 100f, 400f), det("16390694195781", 100f, 700f))
        assertEquals(listOf(ReadVerdict.BAD_CHARS, ReadVerdict.BAD_LENGTH, ReadVerdict.BAD_LENGTH), out.verdicts)
        assertEquals(3, out.detections.size)
        out.detections.forEach { assertNull(it.value) }
    }

    @Test
    fun `loose lengths accept 12 and 14 digits`() {
        val out = run(det("163906941957", 100f, 100f), det("16390694195781", 100f, 400f), cfg = TrackerConfig(lengths = BarcodeRules.LOOSE_LENGTHS))
        assertEquals(listOf(ReadVerdict.OK, ReadVerdict.OK), out.verdicts)
    }

    @Test
    fun `gs1 rule only applies when enabled`() {
        val bad = "1639069419571" // correct check digit is 8
        assertEquals(ReadVerdict.OK, run(det(bad, 100f, 100f)).verdicts.single())
        assertEquals(ReadVerdict.BAD_CHECK, run(det(bad, 100f, 100f), cfg = TrackerConfig(requireGs1For13 = true)).verdicts.single())
    }

    @Test
    fun `reads touching the frame edge are downgraded`() {
        val out = run(det("1639069419578", 0f, 100f), det("5075212470427", 100f, h - 61f, 340f, h - 1f))
        assertEquals(listOf(ReadVerdict.EDGE, ReadVerdict.EDGE), out.verdicts)
    }

    @Test
    fun `edge rule is off when the image size is unknown or the margin is negative`() {
        assertEquals(ReadVerdict.OK, ReadFilter.apply(listOf(det("1639069419578", 0f, 100f)), 0, 0, config).verdicts.single())
        assertEquals(ReadVerdict.OK, run(det("1639069419578", 0f, 100f), cfg = TrackerConfig(edgeMarginPx = -1f)).verdicts.single())
    }

    @Test
    fun `same value twice on one label is one detection`() {
        val out = run(det("1639069419578", 100f, 100f), det("1639069419578", 104f, 102f, 340f, 160f))
        assertEquals(1, out.detections.size)
        assertEquals(listOf(ReadVerdict.OK, ReadVerdict.DUPLICATE), out.verdicts)
    }

    @Test
    fun `a decoded read wins over an overlapping located box`() {
        val out = run(det(null, 95f, 95f, 345f, 165f), det("1639069419578", 100f, 100f))
        assertEquals(1, out.detections.size)
        assertEquals("1639069419578", out.detections.single().value)
        assertEquals(ReadVerdict.DUPLICATE, out.verdicts[0])
    }

    @Test
    fun `two different values on one label become one located box`() {
        val out = run(det("1639069419578", 100f, 100f), det("1639069419573", 102f, 101f))
        assertEquals(listOf(ReadVerdict.CONFLICT, ReadVerdict.CONFLICT), out.verdicts)
        assertNull(out.detections.single().value)
    }

    @Test
    fun `stacked twin labels do not overlap and both survive`() {
        val out = run(det("5075212470427", 100f, 600f), det("5075212470427", 100f, 820f))
        assertEquals(2, out.detections.count { it.decoded })
    }

    @Test
    fun `validation off passes everything through`() {
        val out = run(det("HELLO", 0f, 0f), cfg = TrackerConfig(validateReads = false))
        assertEquals("HELLO", out.detections.single().value)
    }
}
