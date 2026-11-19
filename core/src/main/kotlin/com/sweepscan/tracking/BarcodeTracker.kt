package com.sweepscan.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * One barcode seen in one camera frame.
 * [value] is null when the detector located a barcode but could not decode it.
 * [corners] are the four corner points (x0,y0,…,x3,y3) in upright image pixels, when known.
 */
data class Detection(
    val value: String?,
    val box: Box,
    val engine: String = "",
    val format: String = "",
    val corners: FloatArray? = null,
    /** zxing-cpp: how many scan lines agreed on the value (0 = not reported). */
    val lines: Int = 0,
) {
    val decoded: Boolean get() = value != null
}

/**
 * Scene motion between the previous frame and this one, from an image-based estimator.
 *
 * The tracker uses the translation ([dx], [dy]). [similarity] is the same motion as rotation +
 * uniform scale + translation in upright image pixels, row-major 2×3 `[a, b, tx, c, d, ty]`
 * (x' = a·x + b·y + tx, y' = c·x + d·y + ty), with [inliers] = share of flow vectors that fit it.
 * It is only logged for now, so recordings can later replay a rotation/scale-aware motion model.
 */
data class Motion(
    val dx: Float,
    val dy: Float,
    val confidence: Float,
    val similarity: FloatArray? = null,
    val inliers: Float = 0f,
)

data class TrackerConfig(
    /** Frames a track must be decoded in before it may be counted. */
    val minHits: Int = 3,
    /** Share of those decodes that must agree on the same value. */
    val minAgreement: Float = 0.6f,
    /** A track not seen for this long stops coasting and moves to the lost list. */
    val lostTimeoutMs: Long = 500,
    /**
     * How long a lost track can be re-identified (same value, same scene position). Since 0.3.11 the
     * whole sweep (until Pause): on the phone (16 Nov) sweeping 12 boxes down and back up counted
     * the 7 oldest again after ~5 s of memory had run out. Replays of every recording showed no
     * identical boxes absorbed by the longer memory, and the emulator's back-and-forth run went
     * from 65 to the true 40.
     */
    val reidWindowMs: Long = 600_000,
    /**
     * A lost track read too few times to count raises its "too few reads" warning after this long,
     * independent of how long it stays re-identifiable (otherwise, with whole-sweep memory, the
     * warning would only appear at Pause).
     */
    val uncountedWarnMs: Long = 5000,
    /** Frame-to-frame gate, in units of the barcode's size along each axis. */
    val matchGate: Float = 0.7f,
    /** Looser gate used when re-identifying lost tracks (motion estimate has drifted). */
    val reidGate: Float = 1.0f,
    /** EMA factor for the scene velocity estimate. */
    val velocitySmoothing: Float = 0.5f,
    /** Optical-flow estimates below this confidence are ignored. */
    val minMotionConfidence: Float = 0.5f,
    /** Run [ReadFilter] before tracking (S4). Off reproduces v0.2 behaviour in replay. */
    val validateReads: Boolean = true,
    /** Allowed value lengths; null = [BarcodeRules.allowedLengths]. */
    val lengths: Set<Int>? = null,
    /** Reads closer than this to the frame edge are downgraded; negative disables the rule. */
    val edgeMarginPx: Float = 4f,
    /** IoU above which two detections in one frame are the same label. */
    val dedupeIou: Float = 0.3f,
    /**
     * Downgrade 13-digit reads with a wrong GS1 check digit to "located". Off: the phone's misreads
     * (16 Nov: 5895214670360, 7802772809684) all fail the check, but some real labels may not carry
     * a valid one, and the majority vote already keeps single misreads out of the count. The `gs1`
     * replay variant shows the trade-off: on the 1x phone recording it loses a label whose third
     * read was a misread.
     */
    val requireGs1For13: Boolean = false,
    /** Merge a newly counted track into an earlier counted twin predicted at the same spot. */
    val absorbTwins: Boolean = true,
    /** Revive lost tracks by value + scene position. */
    val reidentify: Boolean = true,
    /** A located-only label must be seen this many frames before it counts as "seen but unread". */
    val unreadMinFrames: Int = 3,
    /**
     * [reidWindowMs] is a stand-in for drift, and drift comes from camera motion, not from time: a
     * lost track stays re-identifiable for as long as the view has moved less than this share of
     * the image's longer side since it was lost (up to [reidHoldMaxMs]). Without it, a label that
     * drops out for a few seconds while the phone is held still is counted again. 0 = time only.
     */
    val reidHoldTravel: Float = 100f,
    val reidHoldMaxMs: Long = 600_000,
    /**
     * Take the frame shift from optical flow when the label vote agrees with it; labels only confirm.
     * On the phone the label vote is noisy (ML Kit returns the scan line it read, which jumps along
     * the bars): 9-14 px per frame against flow, 1.8 px on the emulator, and the noise adds up to
     * the 1-2 barcode heights of drift that broke re-identification on the way back up a column.
     */
    val flowShiftFirst: Boolean = true,
    /**
     * When optical flow is unusable, predict the shift from the gyroscope (rotation, scaled by gains
     * learned from frames where flow was good) instead of extrapolating the last velocity. Off: on the
     * 16 Nov phone recordings the gyro guess missed flow by more than the velocity guess (median
     * 7-36 px against 5-24 px) - the logged rotation is a coarse 100 ms window and half the motion is
     * translation, which a gyroscope cannot see. Recordings since 0.3.12 log the exact rotation
     * between frames (`gs`) to measure it again.
     */
    val gyroFallback: Boolean = false,
    /**
     * Re-identify with neighbours: a lost box whose predicted spot is off along the sweep (vertical)
     * by up to [reidAlongNoConflict] gate units comes back unless its neighbours contradict, and up
     * to [reidAlongWithNeighbour] units when a neighbour above or below matches. Across the sweep the
     * gate stays [reidGate]: another column is several barcode widths away.
     */
    val neighbourReid: Boolean = true,
    val reidAlongNoConflict: Float = 3f,
    val reidAlongWithNeighbour: Float = 8f,
    /**
     * Appearance fingerprints (see [BarcodeTracker.appearance]): null = observe only (similarities are
     * logged, decisions unchanged). A value vetoes a re-identification whose similarity is below it.
     */
    val appearanceVeto: Float? = null,
    /**
     * When labels vote for the scene shift, a vote that agrees with optical flow (or, without
     * flow, with the current velocity) beats one that merely has more supporters. A stack of
     * identical boxes is periodic: "everything moved by exactly one box" gathers as many votes
     * as the real, small motion, and without this the tracks hop onto their neighbours and the
     * stack is undercounted. False = most votes win (behaviour up to v0.3.2).
     */
    val priorFirst: Boolean = true,
)

/** What the overlay draws. [unreadable] = a label located but never read (a "ghost"). */
data class TrackView(
    val id: Long,
    val box: Box,
    val value: String?,
    val counted: Boolean,
    val visible: Boolean,
    val unreadable: Boolean = false,
)

/** Undercount warnings: labels seen but never read, tracks read too few times to count. */
data class Suspects(val unreadable: Int, val uncounted: Int)

data class CountedTrack(val trackId: Long, val value: String)

/** Which source the frame's scene shift came from (for logging and the debug HUD). */
enum class MotionSource { LABELS, FLOW, VELOCITY, GYRO }

/**
 * One re-identification attempt: a new label whose value a remembered (lost) box has, how far off the
 * remembered spot was ([nx], [ny] in gate units), what the neighbours said, the appearance
 * similarity when available, and whether the lost box was revived. Logged for calibration.
 */
data class ReidAttempt(
    val value: String,
    val lostId: Long,
    val nx: Float,
    val ny: Float,
    val neighbourMatch: Boolean,
    val neighbourConflict: Boolean,
    val similarity: Float?,
    val accepted: Boolean,
)

class FrameResult(
    val tracks: List<TrackView>,
    val newlyCounted: List<CountedTrack>,
    val shiftX: Float,
    val shiftY: Float,
    val motionSource: MotionSource,
    /** One [ReadVerdict] per input detection, index-aligned. */
    val verdicts: List<ReadVerdict> = emptyList(),
    /** Warnings that became final this frame. */
    val suspects: Suspects = Suspects(0, 0),
    /** Located-but-unread labels currently followed. */
    val ghostCount: Int = 0,
    /** Re-identification attempts this frame (for the log). */
    val reid: List<ReidAttempt> = emptyList(),
)

/**
 * Turns per-frame barcode detections into physical-box counts.
 *
 * The barcode value identifies a product variant (model + colour + size), not a box, so two boxes
 * of the same variant carry the same value. A box is therefore identified by *where* its label
 * is in the scene, followed over time:
 *
 * 1. Scene motion: while the phone sweeps, every label moves by roughly the same translation.
 *    It comes from value-equal (track, detection) pairs when there is enough evidence, otherwise
 *    from image optical flow ([Motion]), otherwise from the last velocity. Integrating it gives a
 *    scene coordinate system that stays valid even while no label is in view.
 * 2. Association, in two stages (as in ByteTrack): decoded detections first; then detections the
 *    decoder located but could not read keep the remaining tracks alive without voting.
 *    Gates are scaled by the barcode's own width/height, which keeps stacked twins apart.
 * 3. Voting: a track is counted once, after [TrackerConfig.minHits] decodes that mostly agree.
 * 4. Re-identification: a lost track is kept in scene coordinates for a while; if the same value
 *    reappears where that box should now be, it is the same box, not a new one.
 * 5. Two tracks that were ever visible in the same frame are always different boxes.
 */
class BarcodeTracker(private val config: TrackerConfig = TrackerConfig()) {

    private class Track(val id: Long, var box: Box, var lastSeenMs: Long) {
        val votes = HashMap<String, Int>()
        val coSeen = HashSet<Long>()
        var hits = 0
        var countedValue: String? = null
        var seenThisFrame = false
        /** Its "too few reads" warning has been raised already. */
        var warned = false
        /** Values seen directly above / below it in the same column (co-visible or just before). */
        val above = HashSet<String>()
        val below = HashSet<String>()
        /** Appearance fingerprint, taken when it was counted. */
        var embedding: FloatArray? = null

        fun addVote(value: String) {
            votes[value] = (votes[value] ?: 0) + 1
            hits++
        }

        fun leader(): Pair<String, Int>? = votes.maxByOrNull { it.value }?.toPair()

        fun value(): String? = countedValue ?: leader()?.first
    }

    /** [viewX]/[viewY] = integrated scene offset when the track was lost (how far the view has moved since). */
    private class Lost(val track: Track, val sceneBox: Box, val lostAtMs: Long, val viewX: Float, val viewY: Float)

    private val active = mutableListOf<Track>()
    private val lost = mutableListOf<Lost>()
    private var nextId = 1L
    private var lastFrameMs = -1L

    // Scene velocity on screen (px/ms) and the integrated scene offset since the session started.
    private var vx = 0f
    private var vy = 0f
    private var offsetX = 0f
    private var offsetY = 0f

    // Gyro -> pixel gains per axis, learned from frames with good optical flow (least squares, decaying).
    private var gyroSxyX = 0f
    private var gyroSxxX = 0f
    private var gyroSxyY = 0f
    private var gyroSxxY = 0f
    private var gyroSamples = 0
    private val attempts = ArrayList<ReidAttempt>()

    /**
     * Appearance fingerprint of a box in the frame being processed; set by the pipeline for each
     * frame (null in replays or when switched off). Called only when a box is counted and when a
     * new label could be a remembered box, so it costs a few calls per label, not per frame.
     */
    @Volatile var appearance: ((Box) -> FloatArray?)? = null

    private val unread = UnreadTracker(
        config.lostTimeoutMs, config.unreadMinFrames, config.matchGate, config.reidWindowMs, config.reidHoldMaxMs,
    )

    /** Forgets everything; returns the warnings for tracks and ghosts still open. */
    fun reset(): Suspects {
        val uncounted = active.count { it.countedValue == null && it.hits > 0 } +
            lost.count { it.track.countedValue == null && it.track.hits > 0 && !it.track.warned }
        val suspects = Suspects(unread.flush(), uncounted)
        active.clear()
        lost.clear()
        lastFrameMs = -1L
        vx = 0f; vy = 0f; offsetX = 0f; offsetY = 0f
        return suspects
    }

    /**
     * The camera zoomed by [scale] about ([cx], [cy]) (upright image pixels) between the previous
     * frame and the next one. Everything remembered is rescaled instead of forgotten, so a label
     * counted before the zoom is still recognised after it. Lost tracks are kept in scene
     * coordinates (image position = scene position + offset): they are rescaled at their current
     * image position.
     */
    fun applyZoom(scale: Float, cx: Float, cy: Float) {
        if (scale <= 0f || scale == 1f) return
        for (t in active) t.box = t.box.scaleAbout(scale, cx, cy)
        val rescaled = lost.map { l ->
            val now = l.sceneBox.translate(offsetX, offsetY).scaleAbout(scale, cx, cy)
            Lost(l.track, now.translate(-offsetX, -offsetY), l.lostAtMs, l.viewX, l.viewY)
        }
        lost.clear()
        lost += rescaled
        vx *= scale
        vy *= scale
        unread.applyZoom(scale, cx, cy, offsetX, offsetY)
    }

    /**
     * @param imageWidth upright analysis image size; 0 = unknown (disables the frame-edge rule).
     */
    fun update(
        detections: List<Detection>,
        nowMs: Long,
        motion: Motion? = null,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        /** Shift predicted from the gyroscope's rotation (raw pixels, before the learned gains). */
        gyroShift: Pair<Float, Float>? = null,
    ): FrameResult {
        val dt = if (lastFrameMs < 0) 0f else (nowMs - lastFrameMs).coerceIn(0, 250).toFloat()
        lastFrameMs = nowMs
        active.forEach { it.seenThisFrame = false }

        val filtered = ReadFilter.apply(detections, imageWidth, imageHeight, config)
        val decoded = filtered.detections.filter { it.decoded }
        val located = filtered.detections.filter { !it.decoded }

        // 1. Scene motion for this frame.
        val flow = motion?.takeIf { it.confidence >= config.minMotionConfidence }?.let { Pair(it.dx, it.dy) }
        if (flow != null && gyroShift != null) learnGyro(flow, gyroShift)
        val gyroGuess = if (config.gyroFallback && gyroShift != null && gyroSamples >= GYRO_MIN_SAMPLES) {
            Pair(gyroGain(gyroSxyX, gyroSxxX) * gyroShift.first, gyroGain(gyroSxyY, gyroSxxY) * gyroShift.second)
        } else {
            null
        }
        attempts.clear()
        val (shift, source) = chooseShift(decoded, flow, gyroGuess, Pair(vx * dt, vy * dt))
        val (shiftX, shiftY) = shift

        // 2a. Associate decoded detections with predicted tracks.
        val decodedUsed = associate(decoded, shiftX, shiftY, nowMs, vote = true)
        // 2b. Located-but-unreadable barcodes keep the remaining tracks alive.
        val locatedUsed = associate(located, shiftX, shiftY, nowMs, vote = false)

        // 3. Update motion state.
        if (source != MotionSource.VELOCITY && dt > 0f) {
            val a = config.velocitySmoothing
            vx = vx * (1 - a) + (shiftX / dt) * a
            vy = vy * (1 - a) + (shiftY / dt) * a
        } else {
            vx *= 0.85f
            vy *= 0.85f
        }
        offsetX += shiftX
        offsetY += shiftY

        // 4. Coast unmatched tracks; retire the stale ones.
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (t.seenThisFrame) continue
            t.box = t.box.translate(shiftX, shiftY)
            if (nowMs - t.lastSeenMs > config.lostTimeoutMs) {
                iterator.remove()
                lost += Lost(t, t.box.translate(-offsetX, -offsetY), nowMs, offsetX, offsetY)
            }
        }
        // A track gone for a while without being counted was read, but too few times: undercount
        // warning (once; it stays re-identifiable for much longer).
        var uncounted = 0
        val holdPx = config.reidHoldTravel * max(imageWidth, imageHeight)
        lost.removeAll { l ->
            val age = nowMs - l.lostAtMs
            val still = age <= config.reidHoldMaxMs && hypot(offsetX - l.viewX, offsetY - l.viewY) < holdPx
            val expired = age > config.reidWindowMs && !still
            val t = l.track
            if (!t.warned && t.countedValue == null && t.hits > 0 && (expired || age > config.uncountedWarnMs)) {
                uncounted++
                t.warned = true
            }
            expired
        }

        // 5. Unmatched decoded detections: revive a lost track or start a new one.
        for ((di, d) in decoded.withIndex()) {
            if (decodedUsed[di]) continue
            val value = d.value ?: continue
            val revived = if (config.reidentify) findLost(value, d.box, nowMs) else null
            val t = if (revived != null) {
                lost.remove(revived)
                revived.track.also { it.box = d.box; it.lastSeenMs = nowMs }
            } else {
                Track(nextId++, d.box, nowMs)
            }
            t.seenThisFrame = true
            t.addVote(value)
            active += t
        }

        // 6. Remember which tracks were visible together: those are provably different boxes.
        val visible = active.filter { it.seenThisFrame }
        for (a in visible) for (b in visible) if (a !== b) a.coSeen += b.id
        rememberNeighbours(visible, nowMs)

        // 7. Count tracks whose value is now stable.
        val newly = ArrayList<CountedTrack>()
        // Iterate a copy: absorbTwin() may remove the twin from [active].
        for (t in active.toList()) {
            if (t.countedValue != null) continue
            val (value, votes) = t.leader() ?: continue
            if (t.hits < config.minHits || votes < config.minAgreement * t.hits) continue
            if (!BarcodeRules.isAcceptable(value, config.lengths ?: BarcodeRules.allowedLengths)) continue
            t.countedValue = value
            if (t.embedding == null) t.embedding = appearance?.invoke(t.box)
            if (config.absorbTwins && absorbTwin(t, value)) continue
            newly += CountedTrack(t.id, value)
        }

        // 8. Labels located but not read by any track: followed separately, never counted.
        val readable = active.filter { it.seenThisFrame }.map { it.box }
        val unmatchedLocated = located.filterIndexed { i, _ -> !locatedUsed[i] }.map { it.box }
        // Wider margin than for reads: the box of a barcode cut by the border jitters a few pixels inward.
        val ghostMargin = max(config.edgeMarginPx, GHOST_EDGE_MARGIN * max(imageWidth, imageHeight))
        val unreadable = unread.update(unmatchedLocated, readable, shiftX, shiftY, nowMs, offsetX, offsetY, holdPx) { box ->
            !ReadFilter.touchesEdge(box, imageWidth, imageHeight, ghostMargin)
        }

        val views = active.map { TrackView(it.id, it.box, it.value(), it.countedValue != null, it.seenThisFrame) } +
            unread.boxes().map { (box, visible) -> TrackView(-1, box, null, counted = false, visible = visible, unreadable = true) }
        return FrameResult(
            views, newly, shiftX, shiftY, source, filtered.verdicts,
            Suspects(unreadable, uncounted), unread.count, attempts.toList(),
        )
    }

    /**
     * Greedy gated matching of [detections] to tracks not yet seen this frame.
     * Returns which detections were used.
     */
    private fun associate(detections: List<Detection>, shiftX: Float, shiftY: Float, nowMs: Long, vote: Boolean): BooleanArray {
        val used = BooleanArray(detections.size)
        if (detections.isEmpty()) return used
        val candidates = active.filter { !it.seenThisFrame }
        val pairs = ArrayList<Triple<Track, Int, Float>>()
        for (t in candidates) {
            val predicted = t.box.translate(shiftX, shiftY)
            for ((di, d) in detections.withIndex()) {
                val cost = gatedCost(predicted, d.box, config.matchGate) ?: continue
                val penalty = if (vote && t.countedValue != null && t.countedValue != d.value) 1f else 0f
                pairs += Triple(t, di, cost + penalty)
            }
        }
        pairs.sortBy { it.third }
        for ((t, di, _) in pairs) {
            if (t.seenThisFrame || used[di]) continue
            used[di] = true
            val d = detections[di]
            t.box = d.box
            t.lastSeenMs = nowMs
            t.seenThisFrame = true
            if (vote) d.value?.let(t::addVote)
        }
        return used
    }

    /**
     * Picks this frame's scene translation.
     * Labels win when two or more agree, or when a single one agrees with optical flow; otherwise
     * optical flow; otherwise the velocity guess.
     */
    private fun chooseShift(
        decoded: List<Detection>,
        flow: Pair<Float, Float>?,
        gyroGuess: Pair<Float, Float>?,
        velocityGuess: Pair<Float, Float>,
    ): Pair<Pair<Float, Float>, MotionSource> {
        val prior = flow ?: gyroGuess ?: velocityGuess
        val priorGate = if (!config.priorFirst) 0f else if (flow != null) FLOW_PRIOR_GATE else VELOCITY_PRIOR_GATE
        val voted = estimateShift(decoded, prior, priorGate)
        if (voted != null) {
            val (shift, support, distToPrior) = voted
            if (support >= 2 || flow == null || distToPrior <= config.matchGate) {
                // Labels confirm the flow: keep the flow's (smoother) number.
                if (config.flowShiftFirst && flow != null && distToPrior <= FLOW_PRIOR_GATE) return Pair(flow, MotionSource.FLOW)
                return Pair(shift, MotionSource.LABELS)
            }
        }
        if (flow != null) return Pair(flow, MotionSource.FLOW)
        if (gyroGuess != null) return Pair(gyroGuess, MotionSource.GYRO)
        return Pair(velocityGuess, MotionSource.VELOCITY)
    }

    private fun learnGyro(flow: Pair<Float, Float>, gyro: Pair<Float, Float>) {
        if (abs(gyro.first) + abs(gyro.second) < GYRO_MIN_PX) return
        gyroSxyX = gyroSxyX * GYRO_DECAY + flow.first * gyro.first
        gyroSxxX = gyroSxxX * GYRO_DECAY + gyro.first * gyro.first
        gyroSxyY = gyroSxyY * GYRO_DECAY + flow.second * gyro.second
        gyroSxxY = gyroSxxY * GYRO_DECAY + gyro.second * gyro.second
        gyroSamples++
    }

    private fun gyroGain(sxy: Float, sxx: Float) = if (sxx > 1e-3f) (sxy / sxx).coerceIn(-3f, 3f) else 0f

    /**
     * Votes for the frame-to-frame translation using value-equal (track, detection) pairs.
     * Returns the shift, its support and its normalised distance to [guess], or null without evidence.
     * Candidates within [priorGate] (barcode sizes) of [guess] are preferred over all others; 0 = no preference.
     */
    private fun estimateShift(
        detections: List<Detection>,
        guess: Pair<Float, Float>,
        priorGate: Float,
    ): Triple<Pair<Float, Float>, Int, Float>? {
        val candidates = ArrayList<Triple<Float, Float, Box>>()
        for (t in active) {
            val value = t.value() ?: continue
            for (d in detections) {
                if (d.value == value) {
                    candidates += Triple(d.box.centerX - t.box.centerX, d.box.centerY - t.box.centerY, d.box)
                }
            }
        }
        if (candidates.isEmpty()) return null

        fun distToGuess(c: Triple<Float, Float, Box>): Float {
            val (sx, sy) = barcodeScale(c.third)
            return abs(c.first - guess.first) / sx + abs(c.second - guess.second) / sy
        }
        // Votes that fit the prior are the only ones considered, if there are any.
        val plausible = candidates.filter { distToGuess(it) <= priorGate }
        var best: Triple<Float, Float, Box>? = null
        var bestSupport = 0
        var bestDistToGuess = Float.MAX_VALUE
        for (c in plausible.ifEmpty { candidates }) {
            val (sx, sy) = barcodeScale(c.third)
            val support = candidates.count { abs(it.first - c.first) < 0.5f * sx && abs(it.second - c.second) < 0.5f * sy }
            val distToGuess = distToGuess(c)
            if (support > bestSupport || (support == bestSupport && distToGuess < bestDistToGuess)) {
                best = c
                bestSupport = support
                bestDistToGuess = distToGuess
            }
        }
        val winner = best ?: return null
        // A lone, implausibly large jump is more likely a different box than real motion.
        if (bestSupport == 1 && bestDistToGuess > 3f) return null
        val (sx, sy) = barcodeScale(winner.third)
        val inliers = candidates.filter { abs(it.first - winner.first) < 0.5f * sx && abs(it.second - winner.second) < 0.5f * sy }
        val shift = Pair(median(inliers.map { it.first }), median(inliers.map { it.second }))
        return Triple(shift, bestSupport, bestDistToGuess)
    }

    /** For tests: each remembered (lost) track with its value and where it is predicted on screen now. */
    internal fun lostPredictions(): List<Triple<Long, String?, Box>> =
        lost.map { Triple(it.track.id, it.track.countedValue ?: it.track.value(), it.sceneBox.translate(offsetX, offsetY)) }

    /**
     * The remembered box this new label most likely is, or null. Strict position match first; with
     * [TrackerConfig.neighbourReid], a looser match along the sweep that the neighbours support
     * (drift accumulates along the sweep, hardly across it). Every attempt is recorded.
     */
    private fun findLost(value: String, box: Box, nowMs: Long): Lost? {
        val candidates = lost.filter { it.track.value() == value }
        if (candidates.isEmpty()) return null
        val (newAbove, newBelow) = neighboursAt(box, nowMs, exclude = null)
        val embedding by lazy { appearance?.invoke(box) }
        var best: Lost? = null
        var bestCost = Float.MAX_VALUE
        for (l in candidates) {
            val predicted = l.sceneBox.translate(offsetX, offsetY)
            val (sx, sy) = barcodeScale(box)
            val nx = (predicted.centerX - box.centerX) / sx
            val ny = (predicted.centerY - box.centerY) / sy
            val match = newAbove.any { it in l.track.above } || newBelow.any { it in l.track.below }
            val conflict = (newAbove.isNotEmpty() && l.track.above.isNotEmpty() && newAbove.none { it in l.track.above }) ||
                (newBelow.isNotEmpty() && l.track.below.isNotEmpty() && newBelow.none { it in l.track.below })
            val similarity = l.track.embedding?.let { e -> embedding?.let { cosine(e, it) } }
            var cost = gatedCost(predicted, box, config.reidGate)
            if (cost == null && config.neighbourReid && abs(nx) <= config.reidGate && !conflict) {
                cost = when {
                    abs(ny) <= config.reidAlongNoConflict -> 10f + abs(ny)
                    abs(ny) <= config.reidAlongWithNeighbour && match -> 20f + abs(ny)
                    else -> null
                }
            }
            val veto = config.appearanceVeto
            if (cost != null && veto != null && similarity != null && similarity < veto) cost = null
            attempts += ReidAttempt(value, l.track.id, nx, ny, match, conflict, similarity, false)
            if (cost != null && cost < bestCost) {
                best = l
                bestCost = cost
            }
        }
        if (best != null) {
            val i = attempts.indexOfLast { it.lostId == best.track.id && it.value == value }
            if (i >= 0) attempts[i] = attempts[i].copy(accepted = true)
        }
        return best
    }

    /**
     * Values directly above and below [box] in the same column: tracks visible this frame, and tracks
     * lost in the last [NEIGHBOUR_RECENT_MS] (at high zoom the neighbour is often just out of view).
     */
    private fun neighboursAt(box: Box, nowMs: Long, exclude: Track?): Pair<Set<String>, Set<String>> {
        val above = HashSet<String>()
        val below = HashSet<String>()
        fun consider(other: Box, value: String?) {
            if (value == null) return
            val column = NEIGHBOUR_COLUMN * max(box.width, other.width)
            if (abs(other.centerX - box.centerX) > column) return
            val (_, sy) = barcodeScale(box)
            val dy = other.centerY - box.centerY
            if (abs(dy) < 0.5f * sy) return
            if (dy < 0) above += value else below += value
        }
        for (t in active) if (t.seenThisFrame && t !== exclude) consider(t.box, t.value())
        for (l in lost) if (nowMs - l.lostAtMs <= NEIGHBOUR_RECENT_MS && l.track !== exclude) {
            consider(l.sceneBox.translate(offsetX, offsetY), l.track.value())
        }
        return above to below
    }

    private fun rememberNeighbours(visible: List<Track>, nowMs: Long) {
        if (!config.neighbourReid) return
        for (t in visible) {
            val (above, below) = neighboursAt(t.box, nowMs, exclude = t)
            t.above += above
            t.below += below
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na > 0f && nb > 0f) dot / kotlin.math.sqrt(na * nb) else 0f
    }

    /**
     * A freshly counted track might be a box we already counted whose track broke (fast motion,
     * long dropout). If an earlier counted track with the same value, never visible together with
     * this one, is predicted at this spot, merge into it instead of counting again.
     */
    private fun absorbTwin(t: Track, value: String): Boolean {
        val coasting = active.firstOrNull { other ->
            other !== t && other.countedValue == value && !other.seenThisFrame &&
                other.id !in t.coSeen && gatedCost(other.box, t.box, config.reidGate) != null
        }
        if (coasting != null) {
            active.remove(coasting)
            return true
        }
        val gone = lost.firstOrNull { l ->
            l.track.countedValue == value && l.track.id !in t.coSeen &&
                gatedCost(l.sceneBox.translate(offsetX, offsetY), t.box, config.reidGate) != null
        }
        if (gone != null) {
            lost.remove(gone)
            return true
        }
        return false
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private companion object {
        /** Share of the image's longer side within which a located-only label counts as cut off. */
        const val GHOST_EDGE_MARGIN = 0.02f
        /** How far (in barcode sizes) a label vote may be from the prior and still count as agreeing with it. */
        const val FLOW_PRIOR_GATE = 1.5f
        const val VELOCITY_PRIOR_GATE = 3f
        /** A label within this many barcode widths sideways is in the same column. */
        const val NEIGHBOUR_COLUMN = 0.6f
        /** A just-lost label still counts as a neighbour for this long. */
        const val NEIGHBOUR_RECENT_MS = 1500L
        const val GYRO_MIN_SAMPLES = 20
        const val GYRO_MIN_PX = 1f
        const val GYRO_DECAY = 0.98f
    }
}
