package com.sweepscan.location

/**
 * Map mode: the column label opens a column, the boxes counted after it belong to that column,
 * and the next label (or pause / finish) closes it. A closed column is saved as a snapshot that
 * replaces whatever was recorded there before.
 *
 * Rules (the "scan location, then items" pattern of warehouse apps):
 * - The label of the open column again: ignored.
 * - A label that is not in the layout: refused, the open column stays open.
 * - A box with no open column: refused ("scan the column label first").
 * - A column closed with no boxes is not saved (an accidental label read must not empty a column).
 */
class ColumnRecorder(private val clock: () -> Long = System::currentTimeMillis) {

    /** One barcode in a sweep: how many boxes, and where its first and last box were (1-based). */
    data class Line(val boxes: Int, val first: Int, val last: Int)

    /**
     * What the boxes of one sweep were, in the order they were counted. A row is swept from its
     * left end, so the order says where along the row a box is.
     */
    data class Snapshot(val code: LocationCode, val barcodes: List<String>, val timeMs: Long) {
        fun lines(): Map<String, Line> {
            val out = LinkedHashMap<String, Line>()
            barcodes.forEachIndexed { i, b ->
                val old = out[b]
                out[b] = if (old == null) Line(1, i + 1, i + 1) else old.copy(boxes = old.boxes + 1, last = i + 1)
            }
            return out
        }
    }

    sealed class Event {
        /** A new column is open; [closed] is the column just finished (null if none or empty). */
        data class Opened(val code: LocationCode, val closed: Snapshot?) : Event()
        object SameColumn : Event()
        data class UnknownLabel(val code: LocationCode) : Event()
        data class BoxAdded(val code: LocationCode, val boxes: Int) : Event()
        object NoColumn : Event()
    }

    var open: LocationCode? = null
        private set
    private val boxes = ArrayList<String>()
    private var openedMs = 0L

    val boxCount: Int get() = boxes.size

    /** Different labels read in the open row (a row is skimmed: one box per model is enough). */
    val labelCount: Int get() = boxes.toSet().size

    fun hasRead(barcode: String) = barcode in boxes

    fun onLabel(code: LocationCode, known: Boolean): Event {
        if (!known) return Event.UnknownLabel(code)
        if (code == open) return Event.SameColumn
        val closed = close()
        open = code
        openedMs = clock()
        return Event.Opened(code, closed)
    }

    fun onBox(barcode: String): Event {
        val code = open ?: return Event.NoColumn
        boxes += barcode
        return Event.BoxAdded(code, boxes.size)
    }

    /** Drops the boxes of the open column (sweep it again); the column stays open. */
    fun discard(): Int = boxes.size.also { boxes.clear() }

    /** Removes the last box (undo). */
    fun undo(): String? = boxes.removeLastOrNull()

    /** Closes the open column; returns its snapshot to save, or null when nothing was counted. */
    fun close(): Snapshot? {
        val code = open ?: return null
        val snap = if (boxes.isEmpty()) null else Snapshot(code, boxes.toList(), clock())
        open = null
        boxes.clear()
        return snap
    }
}
