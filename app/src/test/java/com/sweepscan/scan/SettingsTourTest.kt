package com.sweepscan.scan

import android.content.SharedPreferences
import com.sweepscan.camera.ExposureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTourTest {

    private val settings = ScanSettings(MemoryPrefs()).apply {
        zoom = 1f
        exposure = ExposureMode.AUTO
        shortExposureUs = 1000
    }

    @Test
    fun walksEveryStepThenRestoresTheOperatorSettings() {
        val tour = SettingsTour(settings)
        val first = tour.start()
        assertEquals(first.zoom, settings.zoom)
        val seen = mutableListOf(first)
        var finished = false
        repeat(SettingsTour.STEPS.size * SettingsTour.STEP_SECONDS) {
            when (val step = tour.tick()) {
                null -> Unit
                SettingsTour.FINISHED -> finished = true
                else -> {
                    seen += step
                    assertEquals(step.zoom, settings.zoom)
                    assertEquals(step.exposure, settings.exposure)
                    assertEquals(step.shortExposureUs, settings.shortExposureUs)
                }
            }
        }
        assertTrue(finished)
        assertEquals(SettingsTour.STEPS, seen)
        assertFalse(tour.active)
        assertEquals(1f, settings.zoom)
        assertEquals(ExposureMode.AUTO, settings.exposure)
        assertEquals(1000, settings.shortExposureUs)
    }

    @Test
    fun eachStepLastsStepSeconds() {
        val tour = SettingsTour(settings)
        tour.start()
        repeat(SettingsTour.STEP_SECONDS - 1) { assertNull(tour.tick()) }
        val second = tour.tick()
        assertSame(SettingsTour.STEPS[1], second)
    }

    @Test
    fun stoppingEarlyRestoresSettings() {
        val tour = SettingsTour(settings)
        tour.start()
        repeat(SettingsTour.STEP_SECONDS * 4) { tour.tick() }
        tour.stop()
        assertFalse(tour.active)
        assertNull(tour.tick())
        assertEquals(1f, settings.zoom)
        assertEquals(ExposureMode.AUTO, settings.exposure)
    }

    @Test
    fun coversThreeZoomsWithAutoAndTwoShortExposures() {
        assertEquals(setOf(1.5f, 2f, 3f), SettingsTour.STEPS.map { it.zoom }.toSet())
        assertEquals(9, SettingsTour.STEPS.size)
        assertEquals(setOf("auto", "4 ms", "2 ms"), SettingsTour.STEPS.map { it.exposureLabel }.toSet())
    }

    /** Minimal in-memory SharedPreferences for JVM tests. */
    private class MemoryPrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = also { map[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = also { map[key!!] = values }
            override fun putInt(key: String?, value: Int) = also { map[key!!] = value }
            override fun putLong(key: String?, value: Long) = also { map[key!!] = value }
            override fun putFloat(key: String?, value: Float) = also { map[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = also { map[key!!] = value }
            override fun remove(key: String?) = also { map.remove(key) }
            override fun clear() = also { map.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }
}
