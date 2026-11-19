package com.sweepscan.tracking

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Diagnosis, not a guard: replays phone recordings and, whenever a new track appears with a value
 * the tracker already remembers (a lost track), prints how far the remembered box is predicted from
 * where the label really is, in the units the re-identification gate uses (barcode width / height).
 * A gate of 1.0 means |nx| and |ny| must both be <= 1.
 *
 *     gradlew.bat :app:testDebugUnitTest --tests "*ReidDiagnosisTest*" -i
 */
class ReidDiagnosisTest {

    @Test
    fun `how far off is the remembered position when a value comes back`() {
        val files = File("src/test/resources/field").listFiles { f -> f.name.startsWith("phone-") && f.extension == "jsonl" }
            .orEmpty().sortedBy { it.name }
        assumeTrue(files.isNotEmpty())
        for (file in files) {
            val log = FieldLog.read(file)
            val tracker = BarcodeTracker(TrackerConfig())
            val known = HashSet<Long>()
            var t0 = -1L
            println("\n${file.name}")
            for (step in log.steps) {
                when (step) {
                    is FieldLog.Step.Reset -> if (!step.afterAutoZoom) tracker.reset()
                    is FieldLog.Step.Zoom -> tracker.applyZoom(step.scale, step.cx, step.cy)
                    is FieldLog.Step.Frame -> {
                        if (t0 < 0) t0 = step.t
                        val before = tracker.lostPredictions()
                        val result = tracker.update(step.detections, step.t, step.motion, step.width, step.height)
                        for (v in result.tracks.filter { it.id >= 0 && it.id !in known && it.visible }) {
                            known += v.id
                            val value = v.value ?: continue
                            val candidates = before.filter { it.second == value && it.first != v.id }
                            if (candidates.isEmpty()) continue
                            val (sx, sy) = barcodeScale(v.box)
                            val best = candidates.map { (id, _, box) ->
                                val nx = (box.centerX - v.box.centerX) / sx
                                val ny = (box.centerY - v.box.centerY) / sy
                                Triple(id, nx, ny)
                            }.minByOrNull { abs(it.second) + abs(it.third) }!!
                            val revived = v.id in candidates.map { it.first }
                            println(
                                "  %6.1fs value %s new track %d: nearest remembered track %d off by nx=%+.2f ny=%+.2f (%s px wide, sy=%.0f px)%s".format(
                                    (step.t - t0) / 1000.0, value, v.id, best.first, best.second, best.third,
                                    v.box.width.toInt(), sy, if (revived) "  [revived]" else "",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
