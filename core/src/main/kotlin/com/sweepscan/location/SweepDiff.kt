package com.sweepscan.location

/**
 * What changed in a column between its previous sweep and the new one, per barcode (box counts).
 * [elsewhere] says where a newly seen barcode was recorded before (it may have been moved here).
 */
data class SweepDiff(
    val added: Map<String, Int>,
    val removed: Map<String, Int>,
    /** barcode → (boxes before, boxes now). */
    val changed: Map<String, Pair<Int, Int>>,
    val unchanged: Int,
    val firstSweep: Boolean,
    val elsewhere: Map<String, List<LocationCode>> = emptyMap(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    companion object {
        /** [before] / [after]: barcode → boxes; [before] null = the column was never swept. */
        fun of(before: Map<String, Int>?, after: Map<String, Int>): SweepDiff {
            val old = before.orEmpty()
            val added = after.filterKeys { it !in old }
            val removed = old.filterKeys { it !in after }
            val changed = after.filter { (b, n) -> b in old && old.getValue(b) != n }.mapValues { (b, n) -> old.getValue(b) to n }
            val unchanged = after.count { (b, n) -> old[b] == n }
            return SweepDiff(added, removed, changed, unchanged, before == null)
        }
    }
}
