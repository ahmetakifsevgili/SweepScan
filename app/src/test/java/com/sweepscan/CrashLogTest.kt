package com.sweepscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun reportNamesVersionDeviceAndCause() {
        val text = CrashLog.format(0L, "3.0.1", "samsung SM-A546E", 36, "main", IllegalStateException("shelf missing"))
        assertTrue(text.startsWith("SweepScan 3.0.1 · samsung SM-A546E · Android API 36"))
        assertTrue(text.contains("thread main"))
        assertTrue(text.contains("java.lang.IllegalStateException: shelf missing"))
        assertTrue(text.contains("at com.sweepscan.CrashLogTest"))
    }

    @Test
    fun keepsOnlyTheNewestFiveNewestFirst() {
        val dir = tmp.newFolder("crashes")
        val base = 1_763_560_000_000L
        for (i in 0 until 8) CrashLog.write(dir, base + i * 1500L, "crash $i")
        val kept = CrashLog.reports(dir)
        assertEquals(CrashLog.KEEP, kept.size)
        assertEquals(listOf("crash 7", "crash 6", "crash 5", "crash 4", "crash 3"), kept.map { it.readText() })
    }

    @Test
    fun twoCrashesInOneSecondBothKept() {
        val dir = tmp.newFolder("crashes")
        CrashLog.write(dir, 1_763_560_000_005L, "first")
        CrashLog.write(dir, 1_763_560_000_250L, "second")
        assertEquals(listOf("second", "first"), CrashLog.reports(dir).map { it.readText() })
    }
}
