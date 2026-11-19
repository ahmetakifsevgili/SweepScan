package com.sweepscan.location

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The last sweep of one column: when, and how many boxes. */
@Entity(tableName = "column_scan", primaryKeys = ["depot", "row", "col"])
data class ColumnScan(
    val depot: String,
    val row: String,
    val col: Int,
    val scannedAt: Long,
    val boxes: Int,
)

/** One barcode seen in one column during that column's last sweep. */
@Entity(
    tableName = "sighting",
    indices = [Index("barcode"), Index(value = ["depot", "row", "col"])],
)
data class Sighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val depot: String,
    val row: String,
    val col: Int,
    val barcode: String,
    val boxes: Int,
    /** Position of its first box in the sweep (1 = first counted: the row's left end). */
    val firstOrder: Int,
    val seenAt: Long,
    /** Position of its last box in the sweep (0 = recorded before 0.7.1: same as [firstOrder]). */
    @ColumnInfo(defaultValue = "0") val lastOrder: Int = 0,
) {
    val code: LocationCode get() = LocationCode(depot, row, col)
}

/** Which model, colour and size a barcode stands for (from the ERP list, the label text or by hand). */
@Entity(tableName = "barcode_model", indices = [Index("modelDigits")])
data class BarcodeModel(
    @PrimaryKey val barcode: String,
    val model: String,
    val modelDigits: String,
    val color: String?,
    val size: String?,
    /** "liste", "etiket" (text recognition) or "elle". */
    val source: String,
    val updatedAt: Long,
)

/**
 * One sweep of one column, kept forever (append-only): the history behind "what was here last
 * week", the difference screen and undo. [undone] = taken back by the operator.
 */
@Entity(tableName = "sweep", indices = [Index(value = ["depot", "row", "col"]), Index("finishedAt")])
data class Sweep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val depot: String,
    val row: String,
    val col: Int,
    val finishedAt: Long,
    val boxes: Int,
    val undone: Boolean = false,
) {
    val code: LocationCode get() = LocationCode(depot, row, col)
}

/** One barcode in one sweep. */
@Entity(
    tableName = "sweep_line",
    primaryKeys = ["sweepId", "barcode"],
    foreignKeys = [ForeignKey(entity = Sweep::class, parentColumns = ["id"], childColumns = ["sweepId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("barcode")],
)
data class SweepLine(
    val sweepId: Long,
    val barcode: String,
    val boxes: Int,
    val firstOrder: Int,
    @ColumnInfo(defaultValue = "0") val lastOrder: Int = 0,
)

/** A sighting joined with its model (if known), for search results. */
data class SightingWithModel(
    val depot: String,
    val row: String,
    val col: Int,
    val barcode: String,
    val boxes: Int,
    val firstOrder: Int,
    val seenAt: Long,
    val model: String?,
    val color: String?,
    val size: String?,
    val lastOrder: Int = 0,
    /** Boxes in the whole sweep of that row (null if unknown). */
    val total: Int? = null,
) {
    val code: LocationCode get() = LocationCode(depot, row, col)

    /** Where along the row (0 = left end, 1 = right end), from the order of the sweep. */
    val along: Float get() = RowPosition.fraction(firstOrder, lastOrder, total ?: 0)
}

@Dao
abstract class LocationDao {

    @Query("DELETE FROM sighting WHERE depot = :depot AND `row` = :row AND col = :col")
    abstract fun clearColumn(depot: String, row: String, col: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putScan(scan: ColumnScan)

    @Insert
    abstract fun putSightings(sightings: List<Sighting>)

    /** A new sweep of a column replaces everything recorded there before. */
    @Transaction
    open fun replaceColumn(scan: ColumnScan, sightings: List<Sighting>) {
        clearColumn(scan.depot, scan.row, scan.col)
        putScan(scan)
        putSightings(sightings)
    }

    @Insert
    abstract fun putSweep(sweep: Sweep): Long

    @Insert
    abstract fun putSweepLines(lines: List<SweepLine>)

    @Query("SELECT * FROM sweep WHERE depot = :depot AND `row` = :row AND col = :col AND undone = 0 ORDER BY finishedAt DESC, id DESC")
    abstract fun sweepsOf(depot: String, row: String, col: Int): List<Sweep>

    @Query("SELECT * FROM sweep WHERE id = :id")
    abstract fun sweep(id: Long): Sweep?

    @Query("SELECT * FROM sweep_line WHERE sweepId = :id ORDER BY firstOrder")
    abstract fun linesOf(id: Long): List<SweepLine>

    @Query("UPDATE sweep SET undone = 1 WHERE id = :id")
    abstract fun markUndone(id: Long)

    @Query("SELECT * FROM sweep WHERE undone = 0 ORDER BY finishedAt DESC, id DESC LIMIT :limit")
    abstract fun recentSweeps(limit: Int): List<Sweep>

    /** Where else a barcode is recorded right now (for "moved here from …"). */
    @Query("SELECT * FROM sighting WHERE barcode = :barcode AND NOT (depot = :depot AND `row` = :row AND col = :col)")
    abstract fun elsewhere(barcode: String, depot: String, row: String, col: Int): List<Sighting>

    /**
     * Records a sweep: appends it to the history and makes it the column's current contents.
     * Returns the new sweep's id and what changed against the column's previous contents.
     */
    @Transaction
    open fun recordSweep(sweep: Sweep, lines: List<SweepLine>): Pair<Long, SweepDiff> {
        val before = if (sweepsOf(sweep.depot, sweep.row, sweep.col).isEmpty()) {
            null
        } else {
            column(sweep.depot, sweep.row, sweep.col).associate { it.barcode to it.boxes }
        }
        val after = lines.associate { it.barcode to it.boxes }
        val diff = SweepDiff.of(before, after)
        val elsewhere = diff.added.keys.associateWith { b ->
            elsewhere(b, sweep.depot, sweep.row, sweep.col).map { it.code }.distinct()
        }.filterValues { it.isNotEmpty() }
        val id = putSweep(sweep)
        putSweepLines(lines.map { it.copy(sweepId = id) })
        replaceColumn(
            ColumnScan(sweep.depot, sweep.row, sweep.col, sweep.finishedAt, sweep.boxes),
            lines.map { Sighting(depot = sweep.depot, row = sweep.row, col = sweep.col, barcode = it.barcode, boxes = it.boxes, firstOrder = it.firstOrder, seenAt = sweep.finishedAt, lastOrder = it.lastOrder) },
        )
        return id to diff.copy(elsewhere = elsewhere)
    }

    /**
     * Takes a sweep back: the column shows its previous sweep again (or nothing, if it was the
     * first). The sweep stays in the history, marked as undone.
     */
    @Transaction
    open fun undoSweep(id: Long): Sweep? {
        val s = sweep(id) ?: return null
        if (s.undone) return s
        markUndone(id)
        restoreColumn(s.depot, s.row, s.col)
        return s
    }

    /** Rebuilds a column's current contents from its newest sweep that was not undone. */
    @Transaction
    open fun restoreColumn(depot: String, row: String, col: Int) {
        val previous = sweepsOf(depot, row, col).firstOrNull()
        if (previous == null) {
            emptyColumn(depot, row, col)
            return
        }
        replaceColumn(
            ColumnScan(depot, row, col, previous.finishedAt, previous.boxes),
            linesOf(previous.id).map { Sighting(depot = depot, row = row, col = col, barcode = it.barcode, boxes = it.boxes, firstOrder = it.firstOrder, seenAt = previous.finishedAt, lastOrder = it.lastOrder) },
        )
    }

    @Query("DELETE FROM sighting WHERE depot = :depot AND `row` = :row AND col = :col")
    abstract fun deleteSightings(depot: String, row: String, col: Int)

    @Query("DELETE FROM column_scan WHERE depot = :depot AND `row` = :row AND col = :col")
    abstract fun deleteScan(depot: String, row: String, col: Int)

    @Transaction
    open fun emptyColumn(depot: String, row: String, col: Int) {
        deleteSightings(depot, row, col)
        deleteScan(depot, row, col)
    }

    @Query(
        """SELECT s.depot, s.`row`, s.col, s.barcode, s.boxes, s.firstOrder, s.seenAt, m.model, m.color, m.size, s.lastOrder, c.boxes AS total
           FROM sighting s JOIN barcode_model m ON m.barcode = s.barcode
           LEFT JOIN column_scan c ON c.depot = s.depot AND c.`row` = s.`row` AND c.col = s.col
           WHERE m.modelDigits LIKE '%' || :digits || '%'
           ORDER BY m.modelDigits, m.size, s.depot, s.`row`, s.col LIMIT 400""",
    )
    abstract fun findByModel(digits: String): List<SightingWithModel>

    @Query(
        """SELECT s.depot, s.`row`, s.col, s.barcode, s.boxes, s.firstOrder, s.seenAt, m.model, m.color, m.size, s.lastOrder, c.boxes AS total
           FROM sighting s LEFT JOIN barcode_model m ON m.barcode = s.barcode
           LEFT JOIN column_scan c ON c.depot = s.depot AND c.`row` = s.`row` AND c.col = s.col
           WHERE s.barcode = :barcode ORDER BY s.depot, s.`row`, s.col""",
    )
    abstract fun findByBarcode(barcode: String): List<SightingWithModel>

    @Query(
        """SELECT s.depot, s.`row`, s.col, s.barcode, s.boxes, s.firstOrder, s.seenAt, m.model, m.color, m.size, s.lastOrder, c.boxes AS total
           FROM sighting s LEFT JOIN barcode_model m ON m.barcode = s.barcode
           LEFT JOIN column_scan c ON c.depot = s.depot AND c.`row` = s.`row` AND c.col = s.col
           WHERE s.depot = :depot AND s.`row` = :row AND s.col = :col ORDER BY s.firstOrder""",
    )
    abstract fun column(depot: String, row: String, col: Int): List<SightingWithModel>

    @Query(
        """SELECT s.depot, s.`row`, s.col, s.barcode, s.boxes, s.firstOrder, s.seenAt, m.model, m.color, m.size, s.lastOrder, c.boxes AS total
           FROM sighting s LEFT JOIN barcode_model m ON m.barcode = s.barcode
           LEFT JOIN column_scan c ON c.depot = s.depot AND c.`row` = s.`row` AND c.col = s.col
           ORDER BY s.depot, s.`row`, s.col, s.firstOrder""",
    )
    abstract fun everything(): List<SightingWithModel>

    @Query("SELECT * FROM column_scan")
    abstract fun scans(): List<ColumnScan>

    @Query("SELECT * FROM barcode_model WHERE barcode = :barcode")
    abstract fun model(barcode: String): BarcodeModel?

    /** Models whose code starts like [prefix] ("0209 13…"): suggestions when a code is not found. */
    @Query("SELECT DISTINCT model FROM barcode_model WHERE modelDigits LIKE :prefix || '%' ORDER BY model LIMIT 6")
    abstract fun similarModels(prefix: String): List<String>

    @Query("SELECT barcode FROM barcode_model")
    abstract fun mappedBarcodes(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun putModels(models: List<BarcodeModel>)

    @Query("SELECT COUNT(*) FROM barcode_model")
    abstract fun modelCount(): Int

    @Query("SELECT COUNT(DISTINCT barcode) FROM sighting WHERE barcode NOT IN (SELECT barcode FROM barcode_model)")
    abstract fun unmappedCount(): Int

    @Query("SELECT COUNT(*) FROM column_scan")
    abstract fun scannedColumns(): Int
}

@Database(
    entities = [ColumnScan::class, Sighting::class, BarcodeModel::class, Sweep::class, SweepLine::class],
    version = 3,
    exportSchema = true,
)
abstract class LocationDb : RoomDatabase() {
    abstract fun dao(): LocationDao

    companion object {
        fun open(context: Context, name: String = "locations.db"): LocationDb =
            Room.databaseBuilder(context.applicationContext, LocationDb::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 0.7.0 → 0.7.1: where along the row a barcode's last box was (rows are swept left to right). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sweep_line ADD COLUMN lastOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sighting ADD COLUMN lastOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 0.6.0 → 0.7.0: sweep history. Every column already swept becomes the first sweep of its
         * history, so nothing recorded in the stock room is lost.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sweep` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `depot` TEXT NOT NULL, " +
                        "`row` TEXT NOT NULL, `col` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, `boxes` INTEGER NOT NULL, `undone` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sweep_depot_row_col` ON `sweep` (`depot`, `row`, `col`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sweep_finishedAt` ON `sweep` (`finishedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sweep_line` (`sweepId` INTEGER NOT NULL, `barcode` TEXT NOT NULL, `boxes` INTEGER NOT NULL, " +
                        "`firstOrder` INTEGER NOT NULL, PRIMARY KEY(`sweepId`, `barcode`), " +
                        "FOREIGN KEY(`sweepId`) REFERENCES `sweep`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sweep_line_barcode` ON `sweep_line` (`barcode`)")
                db.execSQL(
                    "INSERT INTO sweep (depot, `row`, col, finishedAt, boxes, undone) " +
                        "SELECT depot, `row`, col, scannedAt, boxes, 0 FROM column_scan",
                )
                db.execSQL(
                    "INSERT INTO sweep_line (sweepId, barcode, boxes, firstOrder) " +
                        "SELECT w.id, s.barcode, s.boxes, s.firstOrder FROM sighting s JOIN sweep w " +
                        "ON w.depot = s.depot AND w.`row` = s.`row` AND w.col = s.col",
                )
            }
        }

        fun inMemory(context: Context): LocationDb =
            Room.inMemoryDatabaseBuilder(context.applicationContext, LocationDb::class.java).build()
    }
}
