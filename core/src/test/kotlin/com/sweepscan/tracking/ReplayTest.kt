package com.sweepscan.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Replays field logs recorded on the phone (⚙ → field recording) through the tracker under
 * several configurations, so tracker changes can be judged on real warehouse data.
 *
 * Copy the `.jsonl` files from the phone's Download/SweepScan folder into
 * `core/src/test/resources/field/`. Optionally add `<name>.expected`: first line = true box count,
 * following lines `<barcode> <quantity>` for a per-barcode diff. Then run:
 *
 *     gradlew.bat :app:testDebugUnitTest --tests "*ReplayTest*" -i
 *
 * With `-Dreplay.strict=true`, schema-2 logs must replay to exactly the phone's live count.
 *
 * The error is counted per barcode (over + under) when the `.expected` file lists barcodes: a
 * double count and a miss cancel out in the total but not here. Recordings named `phone-*` come
 * from the real phone; on those the default configuration must do at least as well as the
 * phone did live.
 */
class ReplayTest {

    private val variants: List<Pair<String, (FieldLog) -> TrackerConfig>> = listOf(
        "asRecorded" to { log -> log.recordedConfig() },
        "default" to { _ -> TrackerConfig() },
        "v02" to { _ -> TrackerConfig(validateReads = false, lengths = BarcodeRules.LOOSE_LENGTHS, reidWindowMs = 5000, reidHoldTravel = 0f, priorFirst = false) },
        "noPrior" to { _ -> TrackerConfig(priorFirst = false) },
        // Before 0.3.11: lost tracks remembered for 5 s (longer only while the view stays put).
        "shortReid" to { _ -> TrackerConfig(reidWindowMs = 5000, reidHoldTravel = 0.5f, reidHoldMaxMs = 60_000) },
        "noHold" to { _ -> TrackerConfig(reidHoldTravel = 0f) },
        "noTwin" to { _ -> TrackerConfig(absorbTwins = false) },
        "noReid" to { _ -> TrackerConfig(reidentify = false) },
        "noTwinNoReid" to { _ -> TrackerConfig(absorbTwins = false, reidentify = false) },
        "loose" to { _ -> TrackerConfig(lengths = BarcodeRules.LOOSE_LENGTHS) },
        "noEdge" to { _ -> TrackerConfig(edgeMarginPx = -1f) },
        "gs1" to { _ -> TrackerConfig(requireGs1For13 = true) },
        // The three drift remedies of 0.3.12, one at a time off, and all off (= 0.3.11).
        "noFlowFirst" to { _ -> TrackerConfig(flowShiftFirst = false) },
        "gyro" to { _ -> TrackerConfig(gyroFallback = true) },
        "noNeighbour" to { _ -> TrackerConfig(neighbourReid = false) },
        "v0311" to { _ -> TrackerConfig(flowShiftFirst = false, gyroFallback = false, neighbourReid = false) },
    )

    @Test
    fun `replay field logs`() {
        val dir = File("src/test/resources/field")
        val logs = dir.listFiles { f -> f.extension == "jsonl" }.orEmpty().sortedBy { it.name }
        assumeTrue("no field logs in ${dir.absolutePath}", logs.isNotEmpty())
        val strict = System.getProperty("replay.strict") == "true"
        val totalError = LinkedHashMap<String, Int>()
        val regressions = mutableListOf<String>()

        for (file in logs) {
            val log = FieldLog.read(file)
            val live = log.frames.sumOf { it.liveCounted.size }
            val expected = readExpected(file)
            println("\n${file.name}: frames=${log.frames.size} resets=${log.steps.count { it is FieldLog.Step.Reset }} live=$live expected=${expected?.first ?: "?"}")
            val liveError = expected?.let { barcodeError(log.frames.flatMap { f -> f.liveCounted }, it) }
            for ((name, config) in variants) {
                val counted = log.replay(config(log), asRecorded = name == "asRecorded")
                val total = expected?.first?.let { counted.size - it }
                val error = expected?.let { barcodeError(counted, it) }
                println("  %-13s count=%4d variants=%3d total=%s barcode error=%s".format(
                    name, counted.size, counted.toSet().size, total?.let { "%+d".format(it) } ?: "?", error ?: "?"))
                if (error != null) totalError[name] = (totalError[name] ?: 0) + error
                if (name == "default" && file.name.startsWith("phone-") && error != null && liveError != null && error > liveError) {
                    regressions += "${file.name}: default config ($error) worse than the phone live ($liveError)"
                }
                if (expected != null && expected.second.isNotEmpty() && (name == "default" || file.name.startsWith("phone-"))) printDiff(counted, expected.second)
                if (strict && name == "asRecorded" && log.meta != null) assertEquals("${file.name}: replay != live", live, counted.size)
            }
        }
        if (totalError.isNotEmpty()) {
            println("\nSum of per-barcode count errors over files with .expected:")
            totalError.forEach { (name, err) -> println("  %-13s %d".format(name, err)) }
        }
        assertTrue(regressions.joinToString("\n"), regressions.isEmpty())
    }

    /** Over + under per barcode; falls back to |total difference| without a per-barcode list. */
    private fun barcodeError(counted: List<String>, expected: Pair<Int, Map<String, Int>>): Int {
        if (expected.second.isEmpty()) return abs(counted.size - expected.first)
        val got = counted.groupingBy { it }.eachCount()
        return (got.keys + expected.second.keys).sumOf { abs((got[it] ?: 0) - (expected.second[it] ?: 0)) }
    }

    private fun readExpected(log: File): Pair<Int, Map<String, Int>>? {
        val file = File(log.path.removeSuffix(".jsonl") + ".expected")
        if (!file.exists()) return null
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val total = lines.firstOrNull()?.toIntOrNull() ?: return null
        val perBarcode = lines.drop(1).mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size == 2) parts[1].toIntOrNull()?.let { parts[0] to it } else null
        }.toMap()
        return total to perBarcode
    }

    private fun printDiff(counted: List<String>, expected: Map<String, Int>) {
        val got = counted.groupingBy { it }.eachCount()
        (got.keys + expected.keys).sorted().forEach { value ->
            val g = got[value] ?: 0
            val e = expected[value] ?: 0
            if (g != e) println("    $value counted=$g expected=$e")
        }
    }
}
