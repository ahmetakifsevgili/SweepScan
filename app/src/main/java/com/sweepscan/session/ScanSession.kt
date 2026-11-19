package com.sweepscan.session

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The list of counted boxes in the current receiving session.
 *
 * One [Entry] is one physical box. It is written to disk after every change, so a crash or a
 * closed app never loses a half-finished count.
 *
 * An entry is *uncertain* when the keyboard link dropped while it was being typed: it may or may
 * not be in DİA. Uncertain entries are never sent again automatically; the user resolves them.
 * An entry being typed is saved as uncertain (write-ahead), so if the app dies mid-barcode it
 * comes back as "check DİA" instead of being typed a second time.
 */
class ScanSession(private val file: File) {

    data class Entry(
        val id: Long,
        val value: String,
        val trackId: Long,
        val timeMs: Long,
        val manual: Boolean,
        var sent: Boolean = false,
        var uncertain: Boolean = false,
        /** Being typed right now; in memory only (saved as uncertain). */
        var sending: Boolean = false,
    )

    /** One row of the summary: a barcode and how many boxes carry it. */
    data class Line(val value: String, val count: Int, val sent: Int, val uncertain: Int)

    private val entries = mutableListOf<Entry>()
    private var nextId = 1L
    /** Labels seen but never read / tracks read too few times to count (undercount warnings). */
    private var unreadable = 0
    private var uncounted = 0
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main: Handler? = Looper.getMainLooper()?.let { Handler(it) }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    @Synchronized
    fun add(value: String, trackId: Long = -1, manual: Boolean = false): Entry {
        val entry = Entry(nextId++, value, trackId, System.currentTimeMillis(), manual)
        entries += entry
        changed()
        return entry
    }

    @Synchronized
    fun undoLast(): Entry? = entries.removeLastOrNull()?.also { changed() }

    /** Id of the newest entry ever added (0 for none); entries added later have larger ids. */
    @Synchronized
    fun lastId(): Long = nextId - 1

    /** The last [n] counted entries, newest first. */
    @Synchronized
    fun recent(n: Int): List<Entry> = entries.takeLast(n).reversed().map { it.copy() }

    /** Ids of the entries holding [value]. */
    @Synchronized
    fun idsOf(value: String): List<Long> = entries.filter { it.value == value }.map { it.id }

    /** How many boxes carry [value]. */
    @Synchronized
    fun countOf(value: String): Int = entries.count { it.value == value }

    /** Entries added after [id], oldest first. */
    @Synchronized
    fun since(id: Long): List<Entry> = entries.filter { it.id > id }.map { it.copy() }

    @Synchronized
    fun removeAll(ids: Set<Long>): Int {
        val before = entries.size
        entries.removeAll { it.id in ids }
        if (entries.size != before) changed()
        return before - entries.size
    }

    /** Removes one box of [value], preferring one not yet typed into the PC. */
    @Synchronized
    fun removeOne(value: String): Entry? {
        val target = entries.lastOrNull { it.value == value && !it.sent && !it.uncertain }
            ?: entries.lastOrNull { it.value == value }
        return target?.also { entries.remove(it); changed() }
    }

    /** Typing of this entry is about to start (or, with false, turned out not to have started). */
    @Synchronized
    fun markSending(id: Long, sending: Boolean = true) {
        entries.firstOrNull { it.id == id }?.let { it.sending = sending; changed() }
    }

    @Synchronized
    fun markSent(id: Long) {
        entries.firstOrNull { it.id == id }?.let { it.sent = true; it.uncertain = false; it.sending = false; changed() }
    }

    @Synchronized
    fun markUncertain(id: Long) {
        entries.firstOrNull { it.id == id }?.let { it.uncertain = true; it.sending = false; changed() }
    }

    /** The user checked DİA: [inDia] = the line is there (sent), otherwise it can be sent again. */
    @Synchronized
    fun resolve(id: Long, inDia: Boolean) {
        entries.firstOrNull { it.id == id }?.let {
            it.uncertain = false
            it.sending = false
            it.sent = inDia
            changed()
        }
    }

    @Synchronized
    fun find(id: Long): Entry? = entries.firstOrNull { it.id == id }?.copy()

    /** Entries that may be typed: not sent and not waiting for the user. */
    @Synchronized
    fun unsent(): List<Entry> = entries.filter { !it.sent && !it.uncertain }.map { it.copy() }

    @Synchronized
    fun uncertainEntries(): List<Entry> = entries.filter { it.uncertain }.map { it.copy() }

    @Synchronized
    fun total(): Int = entries.size

    @Synchronized
    fun variants(): Int = entries.map { it.value }.toSet().size

    @Synchronized
    fun sentCount(): Int = entries.count { it.sent }

    @Synchronized
    fun uncertainCount(): Int = entries.count { it.uncertain }

    /** Wall-clock time of the first counted box, or null for an empty session. */
    @Synchronized
    fun startedAt(): Long? = entries.firstOrNull()?.timeMs

    @Synchronized
    fun addSuspects(unreadable: Int, uncounted: Int) {
        if (unreadable == 0 && uncounted == 0) return
        this.unreadable += unreadable
        this.uncounted += uncounted
        changed()
    }

    /** Puts the warning counters back to an earlier snapshot (a deleted sweep takes its warnings along). */
    @Synchronized
    fun setSuspects(unreadable: Int, uncounted: Int) {
        if (this.unreadable == unreadable && this.uncounted == uncounted) return
        this.unreadable = unreadable
        this.uncounted = uncounted
        changed()
    }

    @Synchronized
    fun unreadable(): Int = unreadable

    @Synchronized
    fun uncounted(): Int = uncounted

    /** Summary in first-seen order. */
    @Synchronized
    fun lines(): List<Line> =
        entries.groupBy { it.value }.map { (value, list) ->
            Line(value, list.size, list.count { it.sent }, list.count { it.uncertain })
        }

    @Synchronized
    fun clear() {
        entries.clear()
        unreadable = 0
        uncounted = 0
        changed()
    }

    @Synchronized
    fun toCsv(): String = buildString {
        append("barcode,quantity,sent,uncertain\n")
        this@ScanSession.lines().forEach { append("${it.value},${it.count},${it.sent},${it.uncertain}\n") }
    }

    /** Loads the v2 object format, or the v0.2 bare array. */
    @Synchronized
    fun load() {
        if (!file.exists()) return
        runCatching {
            val text = file.readText().trim()
            val array: JSONArray
            if (text.startsWith("[")) {
                array = JSONArray(text)
            } else {
                val root = JSONObject(text)
                array = root.optJSONArray("entries") ?: JSONArray()
                unreadable = root.optInt("unreadable")
                uncounted = root.optInt("uncounted")
            }
            entries.clear()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                entries += Entry(
                    o.getLong("id"), o.getString("value"), o.optLong("trackId", -1),
                    o.getLong("time"), o.optBoolean("manual"), o.optBoolean("sent"), o.optBoolean("uncertain"),
                )
            }
            nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        }
    }

    private fun save() {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("value", it.value).put("trackId", it.trackId)
                    .put("time", it.timeMs).put("manual", it.manual).put("sent", it.sent)
                    .put("uncertain", it.uncertain || it.sending),
            )
        }
        val root = JSONObject().put("v", 2).put("entries", array).put("unreadable", unreadable).put("uncounted", uncounted)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun changed() {
        save()
        main?.post { listeners.forEach { it() } }
    }
}
