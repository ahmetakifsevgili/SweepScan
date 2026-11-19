package com.sweepscan.location

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 0.6.0 database already holds columns swept in the stock room: upgrading to the sweep history
 * (version 2) must keep every one of them, as the first sweep of its history.
 */
@RunWith(AndroidJUnit4::class)
class LocationDbMigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LocationDb::class.java)

    @Test
    fun version1DataSurvivesAsHistory() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO column_scan (depot, `row`, col, scannedAt, boxes) VALUES ('B', '3', 7, 1000, 3)")
            execSQL("INSERT INTO sighting (depot, `row`, col, barcode, boxes, firstOrder, seenAt) VALUES ('B', '3', 7, '4731710551237', 2, 1, 1000)")
            execSQL("INSERT INTO sighting (depot, `row`, col, barcode, boxes, firstOrder, seenAt) VALUES ('B', '3', 7, '4731710551244', 1, 3, 1000)")
            execSQL("INSERT INTO barcode_model (barcode, model, modelDigits, color, size, source, updatedAt) VALUES ('4731710551237', '0169 051', '0169051', 'KAHVE', '36', 'etiket', 1000)")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, LocationDb.MIGRATION_1_2, LocationDb.MIGRATION_2_3).close()

        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LocationDb::class.java, name)
            .addMigrations(LocationDb.MIGRATION_1_2, LocationDb.MIGRATION_2_3)
            .build()
        try {
            val dao = db.dao()
            val sweeps = dao.sweepsOf("B", "3", 7)
            assertEquals(1, sweeps.size)
            assertEquals(3, sweeps[0].boxes)
            assertEquals(listOf("4731710551237", "4731710551244"), dao.linesOf(sweeps[0].id).map { it.barcode })
            assertEquals(2, dao.column("B", "3", 7).size)
            assertNotNull(dao.model("4731710551237"))

            // A new sweep after the upgrade compares against the migrated one, and can be undone.
            val (id, diff) = dao.recordSweep(
                Sweep(depot = "B", row = "3", col = 7, finishedAt = 2000, boxes = 2),
                listOf(SweepLine(0, "4731710551237", 2, 1)),
            )
            assertEquals(setOf("4731710551244"), diff.removed.keys)
            assertTrue(diff.added.isEmpty())
            assertEquals(1, dao.column("B", "3", 7).size)
            dao.undoSweep(id)
            assertEquals(2, dao.column("B", "3", 7).size)
            assertEquals(1, dao.sweepsOf("B", "3", 7).size)
        } finally {
            db.close()
        }
    }
}
