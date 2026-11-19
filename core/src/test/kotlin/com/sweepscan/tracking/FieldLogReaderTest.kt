package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldLogReaderTest {

    private fun frame(t: Long, y: Int, value: String?, counted: List<String> = emptyList(), typed: Boolean = true): String {
        val v = value?.let { "\"$it\"" } ?: "null"
        val type = if (typed) "\"type\":\"frame\"," else ""
        val c = counted.joinToString(",") { "\"$it\"" }
        return "{$type\"t\":$t,\"w\":1080,\"h\":1920,\"motion\":null,\"shift\":{\"dx\":0,\"dy\":0,\"src\":\"VELOCITY\"}," +
            "\"dets\":[{\"v\":$v,\"b\":[300,$y,540,${y + 60}],\"e\":\"mlkit\",\"f\":\"CODE_128\",\"r\":\"OK\"}],\"counted\":[$c]}"
    }

    @Test
    fun `parses schema 2 and skips non-frame lines`() {
        val lines = listOf(
            """{"type":"meta","schema":2,"tag":"C1","tracker":{"minHits":4,"lengths":[13],"absorbTwins":false}}""",
            frame(0, 300, "1639069419578"),
            """{"type":"hid","t":10,"ev":"sent","id":1}""",
            """{"type":"event","t":20,"ev":"pause"}""",
            """{"type":"event","t":30,"ev":"reset"}""",
            frame(50, 300, null),
        )
        val log = FieldLog.parse(lines)
        assertEquals(3, log.steps.size)
        assertTrue(log.steps[1] is FieldLog.Step.Reset)
        assertEquals("CODE_128", log.frames[0].detections.single().format)
        assertNull(log.frames[1].detections.single().value)
        val cfg = log.recordedConfig()
        assertEquals(4, cfg.minHits)
        assertEquals(setOf(13), cfg.lengths)
        assertEquals(false, cfg.absorbTwins)
    }

    @Test
    fun `reads the similarity motion and zxing line count when present`() {
        val withExtras = "{\"type\":\"frame\",\"t\":0,\"w\":1080,\"h\":1920," +
            "\"motion\":{\"dx\":-12.5,\"dy\":3,\"c\":0.9,\"a\":[1.01,-0.002,-14.1,0.002,1.01,1.8],\"in\":0.875}," +
            "\"shift\":{\"dx\":0,\"dy\":0,\"src\":\"FLOW\"}," +
            "\"dets\":[{\"v\":\"1639069419578\",\"b\":[300,300,540,360],\"e\":\"zxing\",\"f\":\"Code128\",\"lc\":7}],\"counted\":[]}"
        val log = FieldLog.parse(listOf(withExtras, frame(50, 300, "1639069419578")))

        val motion = log.frames[0].motion!!
        assertEquals(-12.5f, motion.dx, 0f)
        assertEquals(1.01f, motion.similarity!![0], 1e-6f)
        assertEquals(-14.1f, motion.similarity!![2], 1e-6f)
        assertEquals(0.875f, motion.inliers, 1e-6f)
        assertEquals(7, log.frames[0].detections.single().lines)
        // Older lines without the extras still parse.
        assertNull(log.frames[1].motion)
        assertEquals(0, log.frames[1].detections.single().lines)
    }

    @Test
    fun `reads the overlay tracks when present`() {
        val line = "{\"type\":\"frame\",\"t\":0,\"w\":1080,\"h\":1920,\"motion\":null," +
            "\"shift\":{\"dx\":0,\"dy\":0,\"src\":\"LABELS\"},\"dets\":[],\"counted\":[]," +
            "\"tr\":[[7,300,300,540,360,\"1639069419578\",1,1,0],[-1,10.5,900,200,960,null,0,0,1]]}"
        val log = FieldLog.parse(listOf(line, frame(50, 300, "1639069419578")))

        val (counted, ghost) = log.frames[0].liveTracks
        assertEquals(7L, counted.id)
        assertEquals("1639069419578", counted.value)
        assertTrue(counted.counted && counted.visible && !counted.unreadable)
        assertEquals(10.5f, ghost.box.left, 0f)
        assertNull(ghost.value)
        assertTrue(ghost.unreadable && !ghost.visible && !ghost.counted)
        assertTrue(log.frames[1].liveTracks.isEmpty())
    }

    @Test
    fun `reads v0_2 lines without a type field`() {
        val log = FieldLog.parse(listOf(frame(0, 300, "1639069419578", typed = false)))
        assertNull(log.meta)
        assertEquals(1, log.frames.size)
        assertEquals(TrackerConfig(), log.recordedConfig())
    }

    @Test
    fun `replay counts like the tracker and applies resets`() {
        val lines = (0 until 5).map { frame(it * 50L, 300, "1639069419578") } +
            """{"type":"event","t":300,"ev":"reset"}""" +
            (6 until 11).map { frame(it * 50L, 300, "1639069419578") }
        // The reset forgets the first track, so the same label is counted again after it.
        assertEquals(listOf("1639069419578", "1639069419578"), FieldLog.parse(lines).replay(TrackerConfig()))
    }
}
