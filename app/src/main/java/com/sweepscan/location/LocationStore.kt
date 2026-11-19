package com.sweepscan.location

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Everything the location map keeps on the phone: the site layout (a JSON file the layout screen
 * edits) and the database of sightings and barcode → model links. Database work runs on one
 * background thread; results come back on the main thread.
 *
 * [demo] = the sample-data database (a separate file): the real map is never touched by it.
 */
class LocationStore(context: Context, val demo: Boolean = false) {

    private val app = context.applicationContext
    private val layoutFile = File(app.filesDir, "layout.json")
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dbName = if (demo) "locations-demo.db" else "locations.db"
    private val db by lazy { LocationDb.open(app, dbName) }

    @Volatile var layout: SiteLayout = loadLayout()
        private set

    /** Barcodes whose model is known: text recognition is not run for them. */
    private val mapped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Two identical reads are enough (a row is skimmed: a label is in view only briefly). */
    val votes = ModelVotes(minReads = 2, minShare = 0.66f)

    init {
        io.execute { runCatching { mapped += db.dao().mappedBarcodes() }.onFailure { Log.w(TAG, "load models", it) } }
    }

    /**
     * The whole map (database and layout) into one zip, for a new phone or a second phone. The
     * database is checkpointed first so the file holds everything.
     */
    fun backup(out: java.io.OutputStream, done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
                java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_DB))
                    app.getDatabasePath(dbName).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (layoutFile.exists()) {
                        zip.putNextEntry(java.util.zip.ZipEntry(BACKUP_LAYOUT))
                        layoutFile.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }.onFailure { Log.e(TAG, "backup failed", it) }.isSuccess
            main.post { done(ok) }
        }
    }

    /**
     * Replaces the map with a backup made by [backup]. The store is closed afterwards: the caller
     * drops it and opens a fresh one. False (nothing changed) if the file is not a SweepScan backup.
     */
    fun restore(input: java.io.InputStream, done: (Boolean) -> Unit) {
        io.execute {
            val ok = runCatching {
                val tmp = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name == BACKUP_DB || e.name == BACKUP_LAYOUT) File(tmp, e.name).outputStream().use { zip.copyTo(it) }
                    }
                }
                val newDb = File(tmp, BACKUP_DB)
                require(newDb.length() > 0) { "not a SweepScan backup" }
                db.close()
                val path = app.getDatabasePath(dbName)
                File(path.path + "-wal").delete()
                File(path.path + "-shm").delete()
                newDb.copyTo(path, overwrite = true)
                File(tmp, BACKUP_LAYOUT).takeIf { it.exists() }?.copyTo(layoutFile, overwrite = true)
                tmp.deleteRecursively()
            }.onFailure { Log.e(TAG, "restore failed", it) }.isSuccess
            main.post { done(ok) }
        }
    }

    /** Closes the database (switching between the real and the sample map). */
    fun close() {
        io.execute { runCatching { db.close() } }
        io.shutdown()
    }

    fun hasModel(barcode: String) = barcode in mapped || votes.isLocked(barcode)

    /** Runs [work] on the database thread, then [done] with its result on the main thread. */
    fun <T> query(work: (LocationDao) -> T, done: (T) -> Unit) {
        io.execute {
            val result = try {
                work(db.dao())
            } catch (e: Exception) {
                Log.e(TAG, "query failed", e)
                null
            }
            @Suppress("UNCHECKED_CAST")
            if (result != null) main.post { done(result as T) }
        }
    }

    /** A saved sweep: its id (for undo) and what changed in the column. */
    class Saved(val sweepId: Long, val code: LocationCode, val boxes: Int, val diff: SweepDiff, val labels: Int = 0, val unknown: Int = 0)

    /** Appends the sweep to the column's history and makes it the column's contents. */
    fun saveSnapshot(snap: ColumnRecorder.Snapshot, done: (Saved) -> Unit = {}) {
        val c = snap.code
        val lines = snap.lines().map { (barcode, v) -> SweepLine(0, barcode, v.boxes, v.first, v.last) }
        val sweep = Sweep(depot = c.depot, row = c.row, col = c.column, finishedAt = snap.timeMs, boxes = snap.barcodes.size)
        query({ dao ->
            val (id, diff) = dao.recordSweep(sweep, lines)
            Saved(id, c, snap.barcodes.size, diff, lines.size, lines.count { dao.model(it.barcode) == null })
        }) { done(it) }
    }

    /** Takes a sweep back; the column shows what it held before. */
    fun undoSweep(sweepId: Long, done: () -> Unit = {}) {
        query({ it.undoSweep(sweepId) ?: Unit }) { done() }
    }

    fun learnModel(barcode: String, text: ModelText, source: String) {
        mapped += barcode
        query({
            it.putModels(listOf(BarcodeModel(barcode, text.model, text.digits, text.color, text.size, source, System.currentTimeMillis())))
        }) {}
    }

    fun saveLayout(newLayout: SiteLayout) {
        layout = newLayout
        layoutFile.writeText(newLayout.toJson().toString(1))
    }

    fun resetLayout() {
        layoutFile.delete()
        layout = DefaultLayout.create()
    }

    private fun loadLayout(): SiteLayout = try {
        val saved = if (layoutFile.exists()) SiteLayout.fromJson(JSONObject(layoutFile.readText())) else null
        // A layout saved from an older default plan is replaced (the plan itself changed).
        if (saved != null && saved.version >= DefaultLayout.VERSION) saved else DefaultLayout.create()
    } catch (e: Exception) {
        Log.w(TAG, "layout.json unreadable, using the default", e)
        DefaultLayout.create()
    }

    // ---- CSV in and out ------------------------------------------------------------------------

    /**
     * Imports a barcode → model list exported from the ERP (saved as CSV from Excel). Any column
     * order works: per line the first 8- or 13-digit value is the barcode and the rest is read like
     * label text ("0159 2041", colour, size). Returns (imported, skipped lines).
     */
    fun importModels(lines: Sequence<String>, done: (Pair<Int, Int>) -> Unit) {
        query({ dao ->
            val now = System.currentTimeMillis()
            var skipped = 0
            val models = ArrayList<BarcodeModel>()
            for (line in lines) {
                val cells = line.split(';', ',', '\t').map { it.trim().trim('"') }
                val barcode = cells.firstOrNull { BARCODE.matches(it) }
                if (barcode == null) {
                    skipped++
                    continue
                }
                val text = ModelText.parse(listOf(cells.filter { it != barcode }.joinToString("  ")))
                if (text == null) {
                    skipped++
                    continue
                }
                models += BarcodeModel(barcode, text.model, text.digits, text.color, text.size, "liste", now)
            }
            models.chunked(500).forEach { dao.putModels(it) }
            mapped += models.map { it.barcode }
            models.size to skipped
        }, done)
    }

    /** The whole map as a CSV file (semicolon separated, opens in Turkish Excel). */
    fun exportCsv(dir: File, done: (File) -> Unit) {
        query({ dao ->
            val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val out = File(dir, "konum-haritasi-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv")
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(BOM + "Depo;Sıra;Sütun;Konum;Barkod;Model;Renk;Numara;Kutu;Sırası;Görüldü\n")
                for (s in dao.everything()) {
                    val depot = layout.depot(s.depot)?.name ?: s.depot
                    w.write(listOf(depot, s.row, s.col, s.code.text, s.barcode, s.model.orEmpty(), s.color.orEmpty(), s.size.orEmpty(), s.boxes, s.firstOrder, day.format(Date(s.seenAt))).joinToString(";"))
                    w.write("\n")
                }
            }
            out
        }, done)
    }

    companion object {
        private const val TAG = "LocationStore"
        private const val BACKUP_DB = "locations.db"
        private const val BACKUP_LAYOUT = "layout.json"
        // Lets Excel open the UTF-8 file with the Turkish letters intact.
        private const val BOM = "\uFEFF"
        private val BARCODE = Regex("^[0-9]{8}$|^[0-9]{13}$")
    }
}
