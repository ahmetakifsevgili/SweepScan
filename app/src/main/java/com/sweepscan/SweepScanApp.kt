package com.sweepscan

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.sweepscan.hid.HidKeyboard
import com.sweepscan.hid.SendSpeed
import com.sweepscan.scan.FieldLogger
import com.sweepscan.scan.ScanSettings
import com.sweepscan.session.ScanSession
import org.opencv.android.OpenCVLoader
import java.io.File

/** Holds the objects that must outlive a single screen: the scan session and the Bluetooth keyboard. */
class SweepScanApp : Application() {

    lateinit var session: ScanSession
        private set
    lateinit var keyboard: HidKeyboard
        private set
    lateinit var scanSettings: ScanSettings
        private set
    lateinit var logger: FieldLogger
        private set
    /** False if the OpenCV native library failed to load; optical flow is then skipped. */
    var openCvReady = false
        private set
    private lateinit var prefs: SharedPreferences

    /** Where boxes are: layout, sightings, barcode → model (the location map). */
    private var locationStore: com.sweepscan.location.LocationStore? = null
    val locations: com.sweepscan.location.LocationStore
        get() = locationStore ?: com.sweepscan.location.LocationStore(this, demoMode).also { locationStore = it }

    /** Photos of the shelves taken while sweeping, and the shelf each label was on. */
    val shelf: com.sweepscan.location.ShelfMemory by lazy { com.sweepscan.location.ShelfMemory(this) }

    /** How the operator holds the phone and how tall the shelves are (Ayarlar → Kat ayarı). */
    var shelfCalibration: com.sweepscan.location.ShelfLevel.Calibration
        get() = com.sweepscan.location.ShelfLevel.Calibration(
            eye = prefs.getFloat(KEY_CAL_EYE, com.sweepscan.location.ShelfLevel.EYE_M),
            reach = prefs.getFloat(KEY_CAL_REACH, com.sweepscan.location.ShelfLevel.REACH_M),
            levelM = prefs.getFloat(KEY_CAL_LEVEL, com.sweepscan.location.ShelfLevel.LEVEL_M),
        )
        set(c) = prefs.edit().putFloat(KEY_CAL_EYE, c.eye).putFloat(KEY_CAL_REACH, c.reach).putFloat(KEY_CAL_LEVEL, c.levelM).apply()

    /** The location map shows sample data (its own database) so the app can be tried without the shop. */
    val demoMode: Boolean get() = prefs.getBoolean(KEY_DEMO, false)

    fun setDemoMode(on: Boolean, done: () -> Unit) {
        prefs.edit().putBoolean(KEY_DEMO, on).apply()
        locationStore?.close()
        locationStore = null
        pickList.clear()
        if (on) com.sweepscan.location.DemoData.seedIfEmpty(locations, done) else done()
    }

    /** After a restore: the old store's database is closed; the next use opens the restored one. */
    fun reopenLocations() {
        locationStore = null
        pickList.clear()
    }

    /** Stops gathered on the Find location screen ("toplama listesi"), kept while the app runs. */
    val pickList = mutableListOf<com.sweepscan.location.PickItem>()

    /** Type each counted barcode into the PC as soon as it is counted (default: count, review, then send). */
    var liveSend: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SEND, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_SEND, value).apply()

    /** Typing pace into the PC (see [SendSpeed]). NORMAL by default: the shop laptop dropped fast input. */
    var sendSpeed: SendSpeed
        get() = prefs.getString(KEY_SPEED, null)?.let { s -> SendSpeed.entries.firstOrNull { it.name == s } } ?: SendSpeed.NORMAL
        set(value) = prefs.edit().putString(KEY_SPEED, value.name).apply().also { applyTiming() }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        migrate()
        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) Log.w("SweepScan", "OpenCV failed to load; optical flow disabled")
        scanSettings = ScanSettings(prefs).also { it.applyLengths() }
        logger = FieldLogger(this)
        session = ScanSession(File(filesDir, "session.json")).also { it.load() }
        keyboard = HidKeyboard(this, prefs)
        applyTiming()
        keyboard.eventSink = logger::logHid
        keyboard.onJobStarted = { id -> session.markSending(id) }
        keyboard.onJobNotStarted = { id -> session.markSending(id, sending = false) }
        keyboard.addListener(object : HidKeyboard.Listener {
            override fun onSent(entryId: Long) = session.markSent(entryId)
            override fun onUncertain(entryId: Long, text: String, typed: Int, enterSent: Boolean) =
                session.markUncertain(entryId)
        })
        // Presentation mode survives a restart: its requests keep arriving.
        com.sweepscan.request.Presentation.start(this)
    }

    fun applyTiming() {
        if (!::keyboard.isInitialized) return
        keyboard.timing = sendSpeed.timing()
    }

    /** One-time switches of existing installs to new defaults (v0.3: count first, then send; v0.3.7: camera). */
    private fun migrate() {
        val schema = prefs.getInt(KEY_SCHEMA, 0)
        if (schema < 3) prefs.edit().putBoolean(KEY_LIVE_SEND, false).apply()
        if (schema < 4) {
            // v0.3.7: first phone recordings showed auto exposure (20 ms) and 1x zoom were the cause
            // of slow, flaky reads; move existing installs to 4 ms and 1.5x once.
            prefs.edit()
                .putFloat("zoom", 1.5f)
                .putString("exposure", "SHORT")
                .putInt("short_exposure_us", 4000)
                .apply()
        }
        if (schema < 4) prefs.edit().putInt(KEY_SCHEMA, 4).apply()
    }

    private companion object {
        private const val KEY_CAL_EYE = "shelf_eye"
        private const val KEY_CAL_REACH = "shelf_reach"
        private const val KEY_CAL_LEVEL = "shelf_level"
        const val KEY_SCHEMA = "schema"
        const val KEY_LIVE_SEND = "live_send"
        const val KEY_SPEED = "send_speed"
        const val KEY_DEMO = "demo_mode"
    }
}
