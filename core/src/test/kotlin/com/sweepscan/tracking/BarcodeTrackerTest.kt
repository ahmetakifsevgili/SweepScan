package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class BarcodeTrackerTest {

    /** A label fixed in the scene; [x], [y] is its centre in scene pixels. */
    private data class Label(val value: String, val x: Float, val y: Float, val w: Float = 240f, val h: Float = 60f)

    private val screenW = 1080f
    private val screenH = 1920f

    /**
     * Sweeps a virtual camera over [labels]. [cameraAt] gives the scene position of the
     * screen's top-left corner for frame i; [dropped] decides which decodes are missed.
     */
    private fun sweep(
        labels: List<Label>,
        frames: Int,
        cameraAt: (Int) -> Pair<Float, Float>,
        dropped: (frame: Int, labelIndex: Int) -> Boolean = { _, _ -> false },
        misread: (frame: Int, labelIndex: Int) -> String? = { _, _ -> null },
        /** Located but not decoded (ML Kit "potential barcode"). */
        undecoded: (frame: Int, labelIndex: Int) -> Boolean = { _, _ -> false },
        /** Feed the true camera motion as an optical-flow estimate. */
        withFlow: Boolean = false,
        config: TrackerConfig = TrackerConfig(),
        /** Pass the screen size to the tracker (enables the frame-edge rule). */
        knownSize: Boolean = false,
        /** Emit a second, slightly shifted detection of the same label (decoder double report). */
        duplicate: (frame: Int, labelIndex: Int) -> Boolean = { _, _ -> false },
        /** Value read from a label that is only partly in view (clipped at the screen edge); null = not read. */
        clippedRead: ((Label) -> String?)? = null,
    ): List<CountedTrack> {
        val tracker = BarcodeTracker(config)
        val counted = ArrayList<CountedTrack>()
        var previous = cameraAt(0)
        for (f in 0 until frames) {
            val (camX, camY) = cameraAt(f)
            val detections = labels.withIndex().flatMap { (i, l) ->
                val cx = l.x - camX
                val cy = l.y - camY
                val inView = cx - l.w / 2 >= 0 && cx + l.w / 2 <= screenW && cy - l.h / 2 >= 0 && cy + l.h / 2 <= screenH
                val box = Box(cx - l.w / 2, cy - l.h / 2, cx + l.w / 2, cy + l.h / 2)
                val clipped = Box(box.left.coerceAtLeast(0f), box.top.coerceAtLeast(0f), box.right.coerceAtMost(screenW), box.bottom.coerceAtMost(screenH))
                val partlyInView = !inView && clipped.width > l.w / 3 && clipped.height > 0
                when {
                    partlyInView && clippedRead != null -> listOfNotNull(clippedRead(l)?.let { Detection(it, clipped) })
                    !inView || dropped(f, i) -> emptyList()
                    undecoded(f, i) -> listOf(Detection(null, box))
                    duplicate(f, i) -> listOf(Detection(l.value, box), Detection(l.value, box.translate(3f, 2f)))
                    else -> listOf(Detection(misread(f, i) ?: l.value, box))
                }
            }
            // The scene moves opposite to the camera.
            val flow = if (withFlow) Motion(previous.first - camX, previous.second - camY, 0.9f) else null
            previous = camX to camY
            val size = if (knownSize) screenW.toInt() to screenH.toInt() else 0 to 0
            counted += tracker.update(detections, f * 50L, flow, size.first, size.second).newlyCounted
        }
        return counted
    }

    private fun counts(result: List<CountedTrack>) = result.groupingBy { it.value }.eachCount()

    @Test
    fun `static view counts each label once`() {
        val labels = listOf(Label("1639069419578", 400f, 300f), Label("5075212470427", 400f, 600f))
        val result = sweep(labels, frames = 40, cameraAt = { 0f to 0f })
        assertEquals(mapOf("1639069419578" to 1, "5075212470427" to 1), counts(result))
    }

    @Test
    fun `two boxes with the same barcode stacked vertically count as two`() {
        // Same variant (black 38) twice, like the photographed stack.
        val labels = listOf(
            Label("1639069419578", 500f, 300f),
            Label("5075212470427", 500f, 520f),
            Label("5075212470427", 500f, 740f),
        )
        val result = sweep(labels, frames = 60, cameraAt = { f -> 0f to -400f + f * 15f })
        assertEquals(mapOf("1639069419578" to 1, "5075212470427" to 2), counts(result))
    }

    @Test
    fun `twins still count as two when one is often missed`() {
        val labels = listOf(Label("5075212470427", 500f, 600f), Label("5075212470427", 500f, 820f))
        val result = sweep(
            labels, frames = 60,
            cameraAt = { f -> 0f to f * 4f },
            dropped = { f, i -> i == 0 && f % 3 != 0 },
        )
        assertEquals(mapOf("5075212470427" to 2), counts(result))
    }

    @Test
    fun `panning across a wall of labels counts every label exactly once`() {
        val labels = ArrayList<Label>()
        var n = 0
        for (col in 0 until 6) for (row in 0 until 8) {
            labels += Label("86900000%05d".format(n++), 300f + col * 450f, 200f + row * 230f)
        }
        val result = sweep(
            labels, frames = 160,
            cameraAt = { f -> f * 12f to 0f },
            dropped = { f, i -> (f + i) % 4 == 0 },
        )
        assertEquals(labels.size, result.size)
        assertEquals(labels.size, result.map { it.value }.toSet().size)
    }

    @Test
    fun `a label that leaves and comes back is not counted again`() {
        val labels = listOf(
            Label("1639069419578", 300f, 400f),
            Label("5075212470427", 300f, 1000f),
            Label("2013636076406", 300f, 1600f),
        )
        // Pan down until the first label leaves the screen, then back up.
        val result = sweep(labels, frames = 120, cameraAt = { f ->
            val y = if (f < 60) f * 12f else (120 - f) * 12f
            0f to y
        })
        assertEquals(mapOf("1639069419578" to 1, "5075212470427" to 1, "2013636076406" to 1), counts(result))
    }

    @Test
    fun `occasional misreads do not create extra counts or wrong values`() {
        val labels = listOf(Label("1639069419578", 400f, 300f))
        val result = sweep(labels, frames = 40, cameraAt = { 0f to 0f }, misread = { f, _ -> if (f % 5 == 2) "1639069419573" else null })
        assertEquals(mapOf("1639069419578" to 1), counts(result))
    }

    @Test
    fun `located but unreadable frames keep the track alive during a long dropout`() {
        // Decoded only at the start and the end; in between the label is merely located while the
        // camera pans for longer than the lost timeout. Without stage-2 association it would be
        // counted twice.
        val labels = listOf(Label("1639069419578", 500f, 300f))
        val result = sweep(
            labels, frames = 60,
            cameraAt = { f -> 0f to -f * 20f },
            undecoded = { f, _ -> f in 5..54 },
        )
        assertEquals(mapOf("1639069419578" to 1), counts(result))
    }

    @Test
    fun `optical flow keeps scene position while no label is in view`() {
        // Look at two labels, swing far away (nothing visible), then come back.
        val labels = listOf(Label("1639069419578", 400f, 400f), Label("5075212470427", 400f, 700f))
        val path = { f: Int ->
            when {
                f < 20 -> 0f
                f < 40 -> (f - 20) * 150f // swing 3000 px away, fast
                f < 60 -> 3000f
                f < 80 -> (80 - f) * 150f
                else -> 0f
            }
        }
        val withFlow = sweep(labels, frames = 100, cameraAt = { f -> path(f) to 0f }, withFlow = true)
        assertEquals(mapOf("1639069419578" to 1, "5075212470427" to 1), counts(withFlow))
    }

    @Test
    fun `identical twins stay two boxes when motion comes from optical flow`() {
        val labels = listOf(Label("5075212470427", 500f, 600f), Label("5075212470427", 500f, 820f))
        val result = sweep(
            labels, frames = 80,
            cameraAt = { f -> 0f to f * 9f },
            undecoded = { f, i -> (f + i) % 2 == 0 },
            withFlow = true,
        )
        assertEquals(mapOf("5075212470427" to 2), counts(result))
    }

    @Test
    fun `invalid-length reads never vote but keep the track alive`() {
        // Every other frame the decoder returns a truncated value; the label still counts once, correctly.
        val labels = listOf(Label("1639069419578", 500f, 300f))
        val result = sweep(labels, frames = 40, cameraAt = { 0f to 0f }, misread = { f, _ -> if (f % 2 == 0) "163906941957" else null })
        assertEquals(mapOf("1639069419578" to 1), counts(result))
    }

    @Test
    fun `a label only ever read with an invalid value is never counted`() {
        val labels = listOf(Label("1639069419578", 500f, 300f))
        val result = sweep(labels, frames = 30, cameraAt = { 0f to 0f }, misread = { _, _ -> "16390694195" })
        assertEquals(0, result.size)
    }

    @Test
    fun `truncated reads at the frame edge do not create extra counts`() {
        // While a label slides in from the bottom, the decoder reads a truncated value (mlkit #32).
        val labels = listOf(Label("1639069419578", 500f, 2100f), Label("5075212470427", 500f, 2400f))
        val truncated = { l: Label -> l.value.dropLast(3) + "000" } // still 13 digits: only the edge rule can catch it
        val result = sweep(
            labels, frames = 70, cameraAt = { f -> 0f to f * 15f },
            knownSize = true, clippedRead = truncated,
        )
        assertEquals(mapOf("1639069419578" to 1, "5075212470427" to 1), counts(result))
    }

    @Test
    fun `duplicate reports of one label count once and stacked twins still count two`() {
        val labels = listOf(Label("5075212470427", 500f, 600f), Label("5075212470427", 500f, 820f), Label("1639069419578", 500f, 1040f))
        val result = sweep(labels, frames = 40, cameraAt = { 0f to 0f }, duplicate = { f, _ -> f % 2 == 0 })
        assertEquals(mapOf("5075212470427" to 2, "1639069419578" to 1), counts(result))
    }

    @Test
    fun `re-identification flag decides whether a returning label is counted again`() {
        // Same scenario as `a label that leaves and comes back`: with re-identification off the
        // returning label is a new track, so the flag must change the outcome.
        val labels = listOf(
            Label("1639069419578", 300f, 400f),
            Label("5075212470427", 300f, 1000f),
            Label("2013636076406", 300f, 1600f),
        )
        val path = { f: Int -> 0f to (if (f < 60) f * 12f else (120 - f) * 12f) }
        val withReid = sweep(labels, frames = 120, cameraAt = path)
        val withoutReid = sweep(labels, frames = 120, cameraAt = path, config = TrackerConfig(reidentify = false, absorbTwins = false))
        assertEquals(3, withReid.size)
        assertEquals(true, withoutReid.size > withReid.size)
    }

    /**
     * Labels one box apart, spaced so that a new one comes fully into view at the bottom just as
     * the top one leaves: the moment at which "every label moved up by one box" has more votes
     * than the real, small motion.
     */
    private fun stack(values: (Int) -> String) = (0 until 8).map { Label(values(it), 540f, 400f + it * 465f) }
    private val downTheStack = { f: Int -> Pair(0f, -1500f + f * 17f) }

    @Test
    fun `a stack of eight identical boxes swept top to bottom counts as eight`() {
        val labels = stack { "1815908301666" }
        assertEquals(8, sweep(labels, 420, downTheStack, withFlow = true, knownSize = true).size)
        assertEquals(8, sweep(labels, 420, downTheStack, withFlow = false, knownSize = true).size)
        assertEquals(8, sweep(labels, 420, downTheStack, withFlow = true, knownSize = true, dropped = { f, _ -> f % 5 == 0 }).size)
    }

    @Test
    fun `two barcodes alternating down a stack are all counted`() {
        val labels = stack { if (it % 2 == 0) "1815908301666" else "3080750196039" }
        val counted = sweep(labels, 420, downTheStack, withFlow = true, knownSize = true)
        assertEquals(mapOf("1815908301666" to 4, "3080750196039" to 4), counts(counted))
    }

    @Test
    fun `most votes win when the prior is switched off, and the identical stack is undercounted`() {
        val old = TrackerConfig(priorFirst = false)
        val labels = stack { "1815908301666" }
        val results = listOf(
            sweep(labels, 420, downTheStack, withFlow = true, knownSize = true, config = old).size,
            sweep(labels, 420, downTheStack, withFlow = false, knownSize = true, config = old).size,
            sweep(labels, 420, downTheStack, withFlow = true, knownSize = true, dropped = { f, _ -> f % 5 == 0 }, config = old).size,
        )
        assertEquals("counts with the old rule: $results", true, results.any { it < 8 })
    }

    /** Frames at 20 fps on a 1080x1920 image; returns how many boxes were counted. */
    private fun run(tracker: BarcodeTracker, startMs: Long, frames: Int, shiftPerFrame: Float = 0f, dets: (Int) -> List<Detection>): Int {
        var counted = 0
        repeat(frames) { f ->
            val motion = Motion(shiftPerFrame, 0f, 1f)
            counted += tracker.update(dets(f), startMs + f * 50L, motion, 1080, 1920).newlyCounted.size
        }
        return counted
    }

    @Test
    fun `a counted label that drops out for a long time while the camera is still is not counted again`() {
        val tracker = BarcodeTracker()
        val read = listOf(Detection("1639069419578", box))
        var counted = run(tracker, 0, 6) { read }
        // 20 s with the label merely located now and then (e.g. cut by the frame edge), camera still.
        counted += run(tracker, 300, 400) { emptyList() }
        counted += run(tracker, 20_300, 6) { read }
        assertEquals(1, counted)
    }

    @Test
    fun `after the camera has moved away for a long time a label at the old spot is a new box`() {
        val tracker = BarcodeTracker()
        val read = listOf(Detection("1639069419578", box))
        var counted = run(tracker, 0, 6) { read }
        // Sweeps two image widths away over 10 s, nothing in view, then an identical box shows up.
        counted += run(tracker, 300, 200, shiftPerFrame = 12f) { emptyList() }
        counted += run(tracker, 10_300, 6) { read }
        assertEquals(2, counted)
    }

    @Test
    fun `holding lost tracks while still can be switched off`() {
        // Time-only memory of 5 s (the pre-0.3.11 window): 20 s later the label is new again.
        val tracker = BarcodeTracker(TrackerConfig(reidHoldTravel = 0f, reidWindowMs = 5000))
        val read = listOf(Detection("1639069419578", box))
        var counted = run(tracker, 0, 6) { read }
        counted += run(tracker, 300, 400) { emptyList() }
        counted += run(tracker, 20_300, 6) { read }
        assertEquals(2, counted)
    }

    /** Runs frames by hand and sums the undercount warnings. */
    private fun suspects(frames: List<List<Detection>>, resetAtEnd: Boolean): Pair<Suspects, Int> {
        val tracker = BarcodeTracker()
        var unread = 0
        var uncounted = 0
        var counted = 0
        frames.forEachIndexed { f, dets ->
            val r = tracker.update(dets, f * 50L)
            unread += r.suspects.unreadable
            uncounted += r.suspects.uncounted
            counted += r.newlyCounted.size
        }
        if (resetAtEnd) tracker.reset().let { unread += it.unreadable; uncounted += it.uncounted }
        return Suspects(unread, uncounted) to counted
    }

    private val box = Box(400f, 300f, 640f, 360f)

    @Test
    fun `a label only ever located counts as one unread and never as a box`() {
        val frames = List(10) { listOf(Detection(null, box)) } + List(20) { emptyList() }
        val (s, counted) = suspects(frames, resetAtEnd = false)
        assertEquals(Suspects(1, 0), s)
        assertEquals(0, counted)
    }

    @Test
    fun `a label cut by the frame edge is not reported as unread`() {
        val tracker = BarcodeTracker()
        val cut = Box(0f, 300f, 150f, 360f)
        var unread = 0
        repeat(10) { f -> unread += tracker.update(listOf(Detection(null, cut)), f * 50L, null, 1080, 1920).suspects.unreadable }
        repeat(20) { f -> unread += tracker.update(emptyList(), 500L + f * 50L, null, 1080, 1920).suspects.unreadable }
        assertEquals(0, unread)

        // The same kind of label fully inside the picture for long enough is a real warning.
        repeat(10) { f -> unread += tracker.update(listOf(Detection(null, box)), 2000L + f * 50L, null, 1080, 1920).suspects.unreadable }
        repeat(20) { f -> unread += tracker.update(emptyList(), 2500L + f * 50L, null, 1080, 1920).suspects.unreadable }
        assertEquals(1, unread)
    }

    @Test
    fun `an unreadable label whose detection flickers is reported once while the camera is still`() {
        val tracker = BarcodeTracker()
        var unread = 0
        var t = 0L
        repeat(6) {
            // Located for half a second, gone for a second, again and again; then 8 s of nothing.
            repeat(10) { unread += tracker.update(listOf(Detection(null, box)), t, null, 1080, 1920).suspects.unreadable; t += 50 }
            repeat(20) { unread += tracker.update(emptyList(), t, null, 1080, 1920).suspects.unreadable; t += 50 }
        }
        repeat(160) { unread += tracker.update(emptyList(), t, null, 1080, 1920).suspects.unreadable; t += 50 }
        repeat(10) { unread += tracker.update(listOf(Detection(null, box)), t, null, 1080, 1920).suspects.unreadable; t += 50 }
        repeat(20) { unread += tracker.update(emptyList(), t, null, 1080, 1920).suspects.unreadable; t += 50 }
        assertEquals(1, unread)
    }

    @Test
    fun `a ghost that is later read is counted, not reported as unread`() {
        val frames = List(5) { listOf(Detection(null, box)) } + List(5) { listOf(Detection("1639069419578", box)) } + List(20) { emptyList() }
        val (s, counted) = suspects(frames, resetAtEnd = true)
        assertEquals(0, s.unreadable)
        assertEquals(1, counted)
    }

    @Test
    fun `a track read twice then gone is reported as too few reads`() {
        val frames = List(2) { listOf(Detection("1639069419578", box)) } + List(130) { emptyList() }
        val (s, counted) = suspects(frames, resetAtEnd = false)
        assertEquals(Suspects(0, 1), s)
        assertEquals(0, counted)
    }

    @Test
    fun `reset reports open ghosts and uncounted tracks`() {
        val other = box.translate(0f, 300f)
        val frames = List(4) { listOf(Detection(null, box), Detection("5075212470427", other)) }.mapIndexed { i, d -> if (i < 2) d else listOf(d[0]) }
        val (s, _) = suspects(frames, resetAtEnd = true)
        assertEquals(Suspects(1, 1), s)
    }

    @Test
    fun `non numeric codes are never counted`() {
        val labels = listOf(Label("HELLO-WORLD", 400f, 300f))
        val result = sweep(labels, frames = 20, cameraAt = { 0f to 0f })
        assertEquals(0, result.size)
    }
}
