package com.sweepscan.ui

import android.Manifest
import android.annotation.SuppressLint

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.camera.CameraCaps
import com.sweepscan.camera.CameraRig
import com.sweepscan.camera.CameraTuning
import com.sweepscan.camera.ExposureLoop
import com.sweepscan.camera.ExposureMode
import com.sweepscan.camera.ExposurePath
import com.sweepscan.camera.FocusMode
import com.sweepscan.camera.FocusPath
import com.sweepscan.camera.FrameMeta
import com.sweepscan.camera.TuningPlan
import com.sweepscan.hid.HidKeyboard
import com.sweepscan.location.ColumnRecorder
import com.sweepscan.location.LocationCode
import com.sweepscan.location.ModelText
import com.sweepscan.hid.PauseReason
import com.sweepscan.scan.FramePipeline
import com.sweepscan.scan.PipelineResult
import com.sweepscan.scan.RecordingMeta
import com.sweepscan.scan.ScanSettings
import com.sweepscan.scan.SettingsTour
import com.sweepscan.sensors.GyroMonitor
import com.sweepscan.sensors.GyroSummary
import com.sweepscan.tracking.CountedTrack
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val app get() = application as SweepScanApp
    private val settings get() = app.scanSettings

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var totalText: TextView
    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var infoText: TextView
    private lateinit var scanButton: MaterialButton
    private lateinit var zoomGroup: android.view.ViewGroup
    private lateinit var torchButton: MaterialButton
    private lateinit var autoZoomButton: MaterialButton
    private lateinit var countFlash: android.view.View
    private lateinit var statePill: TextView
    private lateinit var pausedOverlay: TextView
    private lateinit var recentStrip: android.widget.LinearLayout
    private lateinit var recentTitle: TextView
    private lateinit var modeChip: com.google.android.material.chip.Chip
    /** Map mode: the open rack column and the boxes counted in it. */
    private val recorder = ColumnRecorder()

    // The open row, copied for the analysis thread (shelf photos), and the labels photographed in it.
    @Volatile private var openRowCode: LocationCode? = null
    @Volatile private var photographedRow: LocationCode? = null
    private val photographed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** What this camera screen is for: chosen on the home screen, never switched here. */
    enum class Task { COUNT, MAP, FIND, LOOKUP }

    private var task = Task.COUNT
    private val mapMode get() = task == Task.MAP
    /** Lookup: the barcode was found and handed to the search screen. */
    private var lookupDone = false
    /** Find mode: barcodes to mark in the picture (from Find location → "Kamerada bul"); null = off. */
    private var findTargets: Set<String>? = null
    private var findLabel = ""
    /** Tracks already announced with a beep in find mode. */
    private val foundTracks = HashSet<Long>()
    private var lastWarnMs = 0L
    /** Smoothed barcode length on screen (px), 0 = none lately; drives "closer" / "farther". */
    private var sizeEma = 0f
    private var lastSizeMs = 0L
    private val autoZoom = com.sweepscan.scan.AutoZoom()
    private val zoomButtons = mapOf(R.id.zoom_1 to 1f, R.id.zoom_15 to 1.5f, R.id.zoom_2 to 2f, R.id.zoom_3 to 3f)

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var pipeline: FramePipeline
    private var rig: CameraRig? = null
    private var tuningPlan: TuningPlan? = null
    private lateinit var gyro: GyroMonitor
    private lateinit var suspectsText: TextView
    private lateinit var hintText: TextView

    // Written on the analysis thread, read on the main thread for the HUD and hints.
    @Volatile private var lastMeta: FrameMeta? = null
    @Volatile private var lastGyro: GyroSummary? = null
    @Volatile private var lastGyroBlurPx = 0f
    private var blurEma = 0f
    // A "sweep" is everything counted since scanning last started; it can be deleted as a whole.
    private var sweepStartId = 0L
    private var sweepStartUnread = 0
    private var sweepStartUncounted = 0
    private var pendingHint: Int? = null
    private var pendingSinceMs = 0L
    private val exposureLoop = ExposureLoop { iso ->
        val plan = tuningPlan ?: return@ExposureLoop
        rig?.applyTuning(plan, settings.zoom, iso)
    }

    private var tone: ToneGenerator? = null
    private val tour by lazy { SettingsTour(settings) }
    /** True when the tour started the recording itself (and so should stop it). */
    private var tourOwnsRecording = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshCounts()
            tourTick()
            ticker.postDelayed(this, 1000)
        }
    }

    private val sessionListener: () -> Unit = { refreshCounts() }
    private val keyboardListener = object : HidKeyboard.Listener {
        override fun onStatus(status: HidKeyboard.Status, hostName: String?) = refreshStatus()
        override fun onSent(entryId: Long) = refreshStatus()
        override fun onPauseChanged(reason: PauseReason?) = refreshStatus()
        override fun onUncertain(entryId: Long, text: String, typed: Int, enterSent: Boolean) =
            UncertainDialog.show(this@MainActivity, entryId, typed, enterSent)
        override fun onNotice(notice: HidKeyboard.Notice) = PcControls.showNotice(this@MainActivity, notice)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        task = intent.getStringExtra(EXTRA_TASK)?.let { t -> Task.entries.firstOrNull { it.name == t } } ?: Task.COUNT

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        // Scanning one box to look it up: an aiming frame, as in a phone's code scanner.
        overlay.viewfinder = task == Task.LOOKUP
        totalText = findViewById(R.id.total_text)
        statusText = findViewById(R.id.status_text)
        hudText = findViewById(R.id.hud_text)
        infoText = findViewById(R.id.tour_text)
        zoomGroup = findViewById(R.id.zoom_group)
        torchButton = findViewById(R.id.btn_torch)
        autoZoomButton = findViewById(R.id.zoom_auto)
        countFlash = findViewById(R.id.count_flash)
        statePill = findViewById(R.id.state_pill)
        pausedOverlay = findViewById(R.id.paused_overlay)
        recentStrip = findViewById(R.id.recent_strip)
        recentTitle = findViewById(R.id.recent_title)
        modeChip = findViewById(R.id.mode_chip)
        modeChip.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        modeChip.setChipIconResource(R.drawable.ic_back)
        modeChip.chipIconTint = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        statusText.visibility = if (task == Task.COUNT) android.view.View.VISIBLE else android.view.View.GONE
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        // Search lives on the home screen now; the camera screen does one job.
        findViewById<Button>(R.id.btn_locate).visibility = android.view.View.GONE
        pausedOverlay.setOnClickListener { toggleScanning() }
        suspectsText = findViewById(R.id.suspects_text)
        hintText = findViewById(R.id.hint_text)
        scanButton = findViewById(R.id.btn_scan)
        SystemBars.pad(findViewById(R.id.top_bar), top = true)
        SystemBars.pad(findViewById(R.id.bottom_bar), bottom = true)
        gyro = GyroMonitor(this)

        analysisExecutor = Executors.newSingleThreadExecutor()
        pipeline = FramePipeline(settings, app.logger, app.openCvReady) { result ->
            runOnUiThread { onPipelineResult(result) }
        }
        pipeline.onSuspects = { s -> runOnUiThread { if (!mapMode) app.session.addSuspects(s.unreadable, s.uncounted) } }
        // Map mode learns each barcode's model from the line printed above it ("0169 051 KAHVE 36").
        pipeline.modelWanted = { barcode -> mapMode && !app.locations.hasModel(barcode) }
        pipeline.onModelLines = { barcode, lines ->
            val text = ModelText.parse(lines)
            val agreed = text?.let { app.locations.votes.add(barcode, it) }
            if (agreed != null) {
                app.locations.learnModel(barcode, agreed, "etiket")
                app.logger.logEvent("model", mapOf("value" to barcode, "model" to agreed.model, "color" to agreed.color, "size" to agreed.size))
            }
        }
        pipeline.countedHook = { image, frame -> if (mapMode) keepShelfPhotos(image, frame) }
        showScanState() // needs the pipeline
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()

        scanButton.setOnClickListener { toggleScanning() }
        findViewById<Button>(R.id.btn_undo).apply {
            setOnClickListener { undo() }
            setOnLongClickListener { confirmDeleteSweep(); true }
        }
        markSweepStart()
        findViewById<Button>(R.id.btn_finish).setOnClickListener { finishColumn() }
        statusText.setOnClickListener { showPcDialog() }
        // Map mode: the big line on top names the open row; tap it to choose another.
        totalText.setOnClickListener { if (mapMode) pickRow() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { SettingsSheet(this, settings, sheetHost).show() }
        syncQuickControls()
        // The zoom capsule: one step lit at a time (plain checkable buttons, so every step keeps
        // its round shape; a toggle group squares the inner ones).
        for ((id, zoom) in zoomButtons) findViewById<MaterialButton>(id).setOnClickListener {
            checkZoom(id)
            // Programmatic checks (auto zoom, tour, sheet) arrive with the setting already changed.
            if (zoom == settings.zoom && !settings.autoZoom) return@setOnClickListener
            settings.zoom = zoom
            settings.autoZoom = false
            onZoomChanged()
            logSettings()
        }
        autoZoomButton.addOnCheckedChangeListener { _, checked ->
            if (checked == settings.autoZoom) return@addOnCheckedChangeListener
            settings.autoZoom = checked
            autoZoom.reset()
            logSettings()
        }
        torchButton.addOnCheckedChangeListener { _, checked ->
            if (checked == settings.torch) return@addOnCheckedChangeListener
            settings.torch = checked
            applyZoomAndTorch()
            logSettings()
        }
        applyViewSettings()

        permissionLauncher.launch(requiredPermissions())
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean(KEY_GUIDE_SHOWN, false)) showGuide()
        handleFind(intent)
        if (mapMode) {
            val row = intent.getStringExtra(EXTRA_ROW)?.let { LocationCode.parseText(it) }
            if (row != null && app.locations.layout.contains(row)) openRow(row) else pickRow()
        }
        refreshCounts()
    }

    /** Leaving the camera: an open row with boxes is saved first (asked), nothing is lost silently. */
    private fun leave() {
        val open = recorder.open
        if (mapMode && open != null && recorder.boxCount > 0) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.row_leave_title, app.locations.layout.describe(open)))
                .setMessage(getString(R.string.row_leave_body, recorder.labelCount))
                .setPositiveButton(R.string.row_leave_save) { _, _ ->
                    closeColumn()
                    finish()
                }
                .setNeutralButton(R.string.row_leave_drop) { _, _ ->
                    recorder.discard()
                    recorder.close()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFind(intent)
    }

    // ---- Find mode ---------------------------------------------------------------------------

    /** Opened from Find location with barcodes to look for: mark them in the picture, count nothing. */
    private fun handleFind(intent: Intent?) {
        val wanted = intent?.getStringArrayListExtra(EXTRA_FIND_BARCODES)?.toSet() ?: return
        intent.removeExtra(EXTRA_FIND_BARCODES)
        if (mapMode) closeColumn()
        task = Task.FIND
        findTargets = wanted
        findLabel = intent.getStringExtra(EXTRA_FIND_LABEL).orEmpty()
        foundTracks.clear()
        overlay.targets = wanted
        pipeline.requestReset(reportSuspects = false)
        if (!pipeline.scanning) toggleScanning()
        app.logger.logEvent("find", mapOf("label" to findLabel, "barcodes" to wanted.size))
        refreshCounts()
    }

    /** Beeps and vibrates the first time each wanted box shows up. */
    private fun announceFound(result: PipelineResult) {
        val wanted = findTargets ?: return
        var fresh = false
        for (t in result.frame.tracks) {
            if (t.visible && t.value != null && t.value in wanted && foundTracks.add(t.id)) fresh = true
        }
        if (fresh) {
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 220)
            vibrate()
            refreshCounts()
        }
    }

    /** Four lines on how to work; once on first start, then from the settings sheet. */
    private fun showGuide() {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean(KEY_GUIDE_SHOWN, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Either volume key pauses / resumes: no need to look at or touch the screen. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) toggleScanning()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        app.session.addListener(sessionListener)
        app.keyboard.addListener(keyboardListener)
        // Android drops the keyboard registration when the app leaves the foreground.
        if (bluetoothGranted()) app.keyboard.ensureRegistered()
        app.session.uncertainEntries().firstOrNull()?.let { UncertainDialog.show(this, it.id) }
        gyro.start()
        ticker.post(tick)
        refreshStatus()
    }

    override fun onStop() {
        app.logger.flush()
        app.session.removeListener(sessionListener)
        app.keyboard.removeListener(keyboardListener)
        gyro.stop()
        ticker.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        stopRecording()
        rig?.unbind()
        analysisExecutor.execute { pipeline.close() }
        analysisExecutor.shutdown()
        tone?.release()
        super.onDestroy()
    }

    // ---- Permissions -------------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (task == Task.COUNT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        return list.toTypedArray()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun onPermissionsResult() {
        if (granted(Manifest.permission.CAMERA)) startCamera()
        else Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        if (bluetoothGranted()) app.keyboard.start()
        refreshStatus()
    }

    // ---- Camera ------------------------------------------------------------------------------

    private fun startCamera() {
        val camera = rig ?: CameraRig(this, this, previewView).also { rig = it }
        pipeline.zoomOf = { image -> camera.captureMeta.lookup(image.imageInfo.timestamp)?.zoom }
        pipeline.gyroShiftOf = { image -> gyroShift(image, camera) }
        // Measures label brightness every frame; steers ISO on the manual short-exposure path.
        pipeline.frameHook = { image, detections ->
            val meta = camera.captureMeta.lookup(image.imageInfo.timestamp)
            lastMeta = meta
            exposureLoop.onFrame(image, detections, meta?.iso)
            measureGyroBlur(image.imageInfo.timestamp, maxOf(image.width, image.height), meta, camera)
        }
        // Per-frame capture metadata goes into the field log next to each frame.
        pipeline.frameExtras = { image ->
            val g = lastGyro
            JSONObject()
                .put("cap", lastMeta?.toJson() ?: JSONObject.NULL)
                .put("luma", Math.round(exposureLoop.lastLuma * 10) / 10.0)
                .put("isoCmd", exposureLoop.iso ?: JSONObject.NULL)
                .put("blurH", Math.round(lastGyroBlurPx * 10) / 10.0)
                .put(
                    "gyro",
                    g?.let {
                        JSONObject().put("n", it.samples).put("mean", it.meanDps.toDouble()).put("max", it.maxDps.toDouble())
                            .put("yaw", it.meanYawDps.toDouble())
                            .put("r", org.json.JSONArray(it.rotationDeg.map { d -> d.toDouble() }))
                    } ?: JSONObject.NULL,
                )
        }
        bindCamera()
    }

    /**
     * Expected motion blur across the bars, in pixels: sideways rotation rate × exposure time ×
     * focal length in pixels. Runs on the analysis thread.
     */
    private fun measureGyroBlur(frameTsNs: Long, bufferLongSide: Int, meta: FrameMeta?, camera: CameraRig) {
        val caps = camera.caps
        // Sensor and camera timestamps share a clock only when the camera says REALTIME.
        val end = if (caps?.timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) frameTsNs
        else SystemClock.elapsedRealtimeNanos()
        val summary = gyro.window.summary(end - GYRO_WINDOW_NS, end)
        lastGyro = summary
        val exposureS = (meta?.exposureNs ?: ASSUMED_EXPOSURE_NS) / 1e9f
        val fPx = caps?.focalPx(bufferLongSide, settings.zoom) ?: (0.667f * bufferLongSide * settings.zoom)
        lastGyroBlurPx = (summary?.meanYawDps ?: 0f) * (Math.PI / 180).toFloat() * exposureS * fPx
    }

    private var lastGyroFrameNs = 0L

    /**
     * Shift the camera's rotation since the previous frame should cause, in raw upright pixels
     * (rotation about the device x axis moves the scene vertically, about y sideways). Needs the
     * camera and sensor clocks to match (REALTIME). Analysis thread.
     */
    private fun gyroShift(image: androidx.camera.core.ImageProxy, camera: CameraRig): Pair<Float, Float>? {
        val ts = image.imageInfo.timestamp
        val previous = lastGyroFrameNs
        lastGyroFrameNs = ts
        val caps = camera.caps ?: return null
        if (previous == 0L || ts <= previous || caps.timestampSource != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) return null
        val rot = gyro.window.rotationBetween(previous, ts) ?: return null
        val zoom = camera.captureMeta.lookup(ts)?.zoom ?: settings.zoom
        val fPx = caps.focalPx(maxOf(image.width, image.height), zoom)
        return Pair(rot[1] * fPx, rot[0] * fPx)
    }

    /** Small, dense labels need pixels; the short side is selectable to trade FPS for detail. */
    private fun bindCamera() {
        rig?.bind(pipeline, analysisExecutor, settings.resolution, onBound = ::onCameraBound, onOpened = ::onCameraOpened)
    }

    private fun onCameraBound() {
        applyTuning()
        writeCapsOnce()
    }

    /** Also after returning from the review screen: CameraX has reset zoom and torch by then. */
    private fun onCameraOpened() {
        applyZoomAndTorch()
        app.logger.logEvent("camera_open", mapOf("zoom" to settings.zoom, "torch" to settings.torch))
    }

    private fun applyZoomAndTorch() {
        val camera = rig ?: return
        camera.setZoom(settings.zoom)
        camera.setTorch(settings.torch)
    }

    /** Resolves the exposure/focus modes against the camera's capabilities and applies them. */
    private fun applyTuning(announce: Boolean = false) {
        val camera = rig ?: return
        val plan = CameraTuning.resolve(camera.caps, settings.exposure, settings.focus, settings.shortExposureUs, settings.focusDistanceM)
        tuningPlan = plan
        exposureLoop.configure(plan, camera.caps)
        camera.applyTuning(plan, settings.zoom, exposureLoop.iso)
        if (announce && plan.exposure == ExposurePath.FALLBACK) {
            Toast.makeText(this, R.string.exposure_unsupported, Toast.LENGTH_LONG).show()
        }
        if (announce && plan.focus == FocusPath.CENTER_FALLBACK) {
            Toast.makeText(this, R.string.focus_fixed_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** The full capability dump is written once per app version (it doesn't change). */
    private fun writeCapsOnce() {
        val caps = rig?.caps ?: return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        if (prefs.getString("caps_written", null) == version) return
        val name = "caps-${Build.MODEL}-$version.json".replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (app.logger.writeFile(name, caps.fullDumpJson(this).toString(2))) {
            prefs.edit().putString("caps_written", version).apply()
        }
    }

    private fun showCameraInfo() {
        val caps = rig?.caps
        val text = if (caps == null) {
            getString(R.string.camera_info_none)
        } else {
            getString(
                R.string.camera_info_body,
                CameraCaps.levelName(caps.hardwareLevel),
                PcControls.onOff(this, caps.manualSensor),
                if (caps.aePriorityModes.isEmpty()) "-" else caps.aePriorityModes.joinToString(),
                caps.isoRange?.toString() ?: "?",
                caps.exposureRange?.let { "${it.lower / 1000} µs – ${it.upper / 1_000_000} ms" } ?: "?",
                CameraCaps.calibrationName(caps.afCalibration),
                caps.minFocusDiopters?.let { if (it > 0f) "%.2f m".format(1f / it) else "∞" } ?: "?",
                rig?.analysisSize?.toString() ?: "?",
                rig?.captureMeta?.let { "${it.misses}/${it.lookups}" } ?: "?",
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_info)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun onPipelineResult(result: PipelineResult) {
        overlay.setTracks(result.frame.tracks, result.imageWidth, result.imageHeight)
        if (findTargets != null) announceFound(result)
        if (mapMode && !tour.active && findTargets == null) onLabels(result)
        result.frame.newlyCounted.forEach(::onCounted)
        maybeAutoZoom(result)
        if (!tour.active && !mapMode && findTargets == null) result.frame.suspects.let { app.session.addSuspects(it.unreadable, it.uncounted) }
        val s = result.stats
        val motion = s.motion?.let { String.format(Locale.US, "%.2f", it.confidence) } ?: "-"
        val meta = lastMeta
        val g = lastGyro
        val blur = blurPx(s)
        val info = listOfNotNull(
            if (app.logger.isActive) getString(R.string.rec_indicator, pipeline.sampledFrames) else null,
            tour.current?.let { getString(R.string.tour_step, tour.index + 1, tour.size, it.zoom, it.exposureLabel, tour.remaining, tour.stepCounts) },
        ).joinToString("\n")
        infoText.text = info
        infoText.visibility = if (info.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        hudText.text = getString(
            R.string.hud,
            s.fps, s.msMlKit, s.msZxing, s.msFlow, s.msTracker,
            s.decoded, s.located, s.rescued,
            result.frame.motionSource.name, motion,
            result.imageWidth, result.imageHeight,
        ) + "\n" + getString(
            R.string.hud_camera,
            meta?.exposureNs?.let { String.format(Locale.US, "%.1fms", it / 1e6) } ?: "?",
            meta?.iso?.toString() ?: "?",
            meta?.focusDiopters?.let { if (it > 0f) String.format(Locale.US, "%.2fm", 1f / it) else "∞" } ?: "?",
            meta?.zoom ?: settings.zoom,
            g?.meanDps ?: 0f,
            blur,
        )
        trackSize(result)
        updateHint(g, blur)
    }

    private fun trackSize(result: PipelineResult) {
        val lengths = result.frame.tracks.filter { it.visible }.map { maxOf(it.box.width, it.box.height) }.sorted()
        val now = SystemClock.elapsedRealtime()
        if (lengths.isNotEmpty()) {
            val median = lengths[lengths.size / 2]
            sizeEma = if (sizeEma == 0f) median else sizeEma * 0.8f + median * 0.2f
            lastSizeMs = now
        } else if (now - lastSizeMs > SIZE_FORGET_MS) {
            sizeEma = 0f
        }
    }

    /** Expected blur across the bars: the larger of the gyro and optical-flow estimates. */
    private fun blurPx(s: com.sweepscan.scan.PipelineStats): Float {
        val exposureS = (lastMeta?.exposureNs ?: ASSUMED_EXPOSURE_NS) / 1e9f
        val flowBlur = (s.motion?.dx?.let { kotlin.math.abs(it) } ?: 0f) * s.fps * exposureS
        val raw = maxOf(lastGyroBlurPx, flowBlur)
        blurEma = if (blurEma == 0f) raw else blurEma * 0.7f + raw * 0.3f
        return blurEma
    }

    /** "Slow down" when turning too fast to track; "blurry" when sweeping across the bars. 300 ms hysteresis. */
    private fun updateHint(g: GyroSummary?, blur: Float) {
        val wanted = when {
            (g?.meanDps ?: 0f) > SLOW_DOWN_DPS -> R.string.hint_slow_down
            blur > BLUR_HINT_PX -> R.string.hint_sweep_vertical
            // Too small even at the longest zoom (or zoom by hand): move closer; too large: back off.
            sizeEma > 0f && sizeEma < CLOSER_BELOW_PX && (!settings.autoZoom || settings.zoom >= 3f) -> R.string.hint_closer
            sizeEma > FARTHER_ABOVE_PX && (!settings.autoZoom || settings.zoom <= 1f) -> R.string.hint_farther
            else -> null
        }
        val now = SystemClock.elapsedRealtime()
        if (wanted != pendingHint) {
            pendingHint = wanted
            pendingSinceMs = now
            return
        }
        if (now - pendingSinceMs < HINT_HYSTERESIS_MS) return
        if (wanted == null) {
            hintText.visibility = android.view.View.GONE
        } else {
            hintText.setText(wanted)
            hintText.visibility = android.view.View.VISIBLE
        }
    }

    private fun onCounted(counted: CountedTrack) {
        // Find mode only looks: nothing is counted, saved or typed.
        if (findTargets != null) return
        if (task == Task.LOOKUP) {
            if (lookupDone) return
            lookupDone = true
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 220)
            vibrate()
            app.logger.logEvent("lookup", mapOf("value" to counted.value))
            // Asked by the shop screen: hand the barcode back instead of searching here.
            if (intent.getBooleanExtra(EXTRA_RETURN, false)) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_BARCODE, counted.value))
                finish()
                return
            }
            startActivity(
                Intent(this, LocationActivity::class.java)
                    .putExtra(LocationActivity.EXTRA_QUERY, counted.value)
                    .putExtra(LocationActivity.EXTRA_SCANNED, true),
            )
            finish()
            return
        }
        if (tour.active) {
            // A settings test, not stock: logged for the report, never added to the count.
            tour.onCounted()
            app.logger.logEvent("count", mapOf("id" to -1, "track" to counted.trackId, "value" to counted.value, "tour" to tour.index))
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            return
        }
        if (mapMode) {
            mapCounted(counted)
            return
        }
        val entry = app.session.add(counted.value, counted.trackId)
        app.logger.logEvent("count", mapOf("id" to entry.id, "track" to counted.trackId, "value" to counted.value))
        if (app.liveSend) app.keyboard.enqueue(entry.id, entry.value)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        vibrate()
        flashCounted()
    }

    private fun flashCounted() {
        countFlash.animate().cancel()
        countFlash.alpha = 1f
        countFlash.animate().alpha(0f).setDuration(FLASH_MS).start()
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toggleScanning() {
        pipeline.scanning = !pipeline.scanning
        scanButton.setText(if (pipeline.scanning) R.string.pause else R.string.resume)
        scanButton.setIconResource(if (pipeline.scanning) R.drawable.ic_pause else R.drawable.ic_play)
        showScanState()
        app.logger.logEvent(if (pipeline.scanning) "resume" else "pause")
        autoZoom.reset()
        if (pipeline.scanning) {
            markSweepStart()
        } else if (mapMode) {
            overlay.clear()
            app.logger.flush()
            pipeline.requestReset(reportSuspects = false)
            refreshCounts()
        } else {
            overlay.clear()
            app.logger.flush()
            // After a pause the camera may point anywhere: start tracking from scratch.
            pipeline.requestReset()
            // One sweep is usually one column: its count is what gets compared with a count by eye.
            val boxes = app.session.since(sweepStartId).size
            if (boxes > 0) Toast.makeText(this, getString(R.string.sweep_summary, boxes), Toast.LENGTH_LONG).show()
        }
    }

    private fun markSweepStart() {
        sweepStartId = app.session.lastId()
        sweepStartUnread = app.session.unreadable()
        sweepStartUncounted = app.session.uncounted()
    }

    /** Long press on Undo: drop the whole current sweep so the column can be scanned again. */
    private fun confirmDeleteSweep() {
        if (mapMode) {
            val code = recorder.open ?: return
            val n = recorder.discard()
            pipeline.requestReset(reportSuspects = false)
            overlay.clear()
            Toast.makeText(this, getString(R.string.map_discarded, n, code.text), Toast.LENGTH_LONG).show()
            refreshCounts()
            return
        }
        val boxes = app.session.since(sweepStartId).size
        if (boxes == 0) {
            Toast.makeText(this, R.string.sweep_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sweep_delete_title)
            .setMessage(getString(R.string.sweep_delete_body, boxes))
            .setPositiveButton(R.string.sweep_delete_title) { _, _ -> deleteSweep() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSweep() {
        val sweep = app.session.since(sweepStartId)
        // Anything already typed into the PC (or being typed) stays: DİA has it.
        val removable = sweep.filter {
            !it.sent && !it.uncertain && (app.keyboard.cancel(it.id) || !app.keyboard.isQueued(it.id))
        }
        val removed = app.session.removeAll(removable.map { it.id }.toSet())
        val kept = sweep.size - removed
        app.session.setSuspects(sweepStartUnread, sweepStartUncounted)
        // Forget the tracks too, or labels still in view would stay "already counted".
        pipeline.requestReset(reportSuspects = false)
        overlay.clear()
        app.logger.logEvent("sweep_delete", mapOf("removed" to removed, "kept" to kept))
        val message = if (kept == 0) getString(R.string.sweep_deleted, removed) else getString(R.string.sweep_deleted_kept, removed, kept)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** "Finish": stop counting (stays paused on return, so nothing is counted by accident) and review. */
    private fun finishColumn() {
        if (pipeline.scanning) toggleScanning()
        if (mapMode) {
            if (recorder.open == null) pickRow() else closeColumn(finished = true)
            return
        }
        startActivity(Intent(this, SessionActivity::class.java))
    }

    private fun undo() {
        if (mapMode) {
            val value = recorder.undo() ?: return
            Toast.makeText(this, getString(R.string.undo_done, value), Toast.LENGTH_SHORT).show()
            refreshCounts()
            return
        }
        // The tracker keeps the box marked as counted, so a label still in view is not re-counted.
        val entry = app.session.undoLast() ?: return
        app.logger.logEvent("undo", mapOf("id" to entry.id, "value" to entry.value, "sent" to entry.sent))
        val message = when {
            entry.uncertain -> getString(R.string.undo_uncertain, entry.value)
            app.keyboard.cancel(entry.id) -> getString(R.string.undo_done, entry.value)
            // Still "queued" after a failed cancel = being typed right now.
            entry.sent || app.keyboard.isQueued(entry.id) -> getString(R.string.undo_already_sent, entry.value)
            else -> getString(R.string.undo_done, entry.value)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- Settings + field recording ----------------------------------------------------------

    private val sheetHost = object : SettingsSheet.Host {
        override val recording get() = app.logger.isActive
        override val tourActive get() = tour.active
        override val flowAvailable get() = app.openCvReady
        override fun onZoomChanged() = this@MainActivity.onZoomChanged()
        override fun onResolutionChanged() {
            bindCamera()
            // Old tracks are in the previous image size's coordinates.
            pipeline.requestReset()
        }
        override fun onTuningChanged(announce: Boolean) = applyTuning(announce)
        override fun onTorchChanged() {
            applyZoomAndTorch()
            syncQuickControls()
        }
        override fun onViewChanged() = applyViewSettings()
        override fun onSettingsChanged() = logSettings()
        override fun toggleRecording() = if (app.logger.isActive) stopRecording() else askRecordingTag()
        override fun toggleTour() = if (tour.active) finishTour(getString(R.string.tour_cancelled)) else startTour()
        override fun showCameraInfo() = this@MainActivity.showCameraInfo()
        override fun showGuide() = this@MainActivity.showGuide()
    }

    private fun onZoomChanged() {
        applyZoomAndTorch()
        applyTuning() // the centre metering region depends on zoom
        syncQuickControls()
    }

    /** Lights the zoom step [id] (none for null). */
    private fun checkZoom(id: Int?) {
        for (b in zoomButtons.keys) findViewById<MaterialButton>(b).isChecked = b == id
    }

    /** The on-screen zoom and torch buttons follow settings changed elsewhere (sheet, tour). */
    private fun syncQuickControls() {
        checkZoom(zoomButtons.entries.firstOrNull { it.value == settings.zoom }?.key)
        torchButton.isChecked = settings.torch
        autoZoomButton.isChecked = settings.autoZoom
    }

    private fun applyViewSettings() {
        hudText.visibility = if (settings.showHud) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun logSettings() = app.logger.logEvent("settings", mapOf("settings" to settings.toJson()))

    /**
     * One zoom step when the barcodes stay too small or too large ([com.sweepscan.scan.AutoZoom]).
     * Applied only while no real track is on screen; the pipeline then rescales what the tracker
     * remembers on the first frame at the new zoom (v0.3.9 restarted tracking instead, which forgot
     * labels counted moments before and could count them again).
     */
    private fun maybeAutoZoom(result: PipelineResult) {
        if (!settings.autoZoom || tour.active || !pipeline.scanning) return
        val tracks = result.frame.tracks
        val lengths = tracks.filter { it.visible }.map { maxOf(it.box.width, it.box.height) }
        val busy = tracks.any { it.id >= 0 }
        val from = settings.zoom
        val next = autoZoom.update(SystemClock.elapsedRealtime(), lengths, busy, from) ?: return
        settings.zoom = next
        onZoomChanged()
        app.logger.logEvent("auto_zoom", mapOf("from" to from, "to" to next, "medianPx" to autoZoom.median))
    }

    // ---- Settings tour -------------------------------------------------------------------------

    private fun startTour() {
        if (!app.logger.isActive) {
            startRecording("tur")
            if (!app.logger.isActive) return
            tourOwnsRecording = true
        }
        enterTourStep(tour.start())
        Toast.makeText(this, R.string.tour_started, Toast.LENGTH_LONG).show()
    }

    /** Counts down only while scanning, so pausing does not eat into a step. */
    private fun tourTick() {
        if (!tour.active || !pipeline.scanning) return
        val step = tour.tick() ?: return
        if (step === SettingsTour.FINISHED) finishTour(getString(R.string.tour_done)) else enterTourStep(step)
    }

    private fun enterTourStep(step: SettingsTour.Step) {
        applyZoomAndTorch()
        applyTuning()
        syncQuickControls()
        // Each step starts from a clean slate; its leftovers must not count or warn.
        pipeline.requestReset(reportSuspects = false)
        app.logger.logEvent(
            "tour_step",
            mapOf(
                "index" to tour.index, "zoom" to step.zoom, "exposure" to step.exposure.name,
                "shortExposureUs" to step.shortExposureUs, "exposurePath" to (tuningPlan?.exposure?.name ?: "?"),
            ),
        )
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    private fun finishTour(message: String) {
        if (tour.active) tour.stop()
        applyZoomAndTorch()
        applyTuning()
        syncQuickControls()
        pipeline.requestReset(reportSuspects = false)
        app.logger.logEvent("tour_end")
        if (tourOwnsRecording) stopRecording()
        tourOwnsRecording = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Recordings are named by column / setting so field files can be matched to hand counts. */
    private fun askRecordingTag() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val next = prefs.getInt("rec_counter", 0) + 1
        val input = EditText(this).apply {
            setText(getString(R.string.rec_tag_default, next))
            hint = getString(R.string.rec_tag_hint)
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rec_tag_title)
            .setView(input)
            .setPositiveButton(R.string.rec_start) { _, _ ->
                prefs.edit().putInt("rec_counter", next).apply()
                startRecording(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onOff(value: Boolean) = getString(if (value) R.string.on else R.string.off)

    private fun startRecording(tag: String) {
        val camera = JSONObject()
            .put("caps", rig?.caps?.summaryJson() ?: JSONObject.NULL)
            .put("analysis", rig?.analysisSize?.toString() ?: JSONObject.NULL)
            .put("exposurePath", tuningPlan?.exposure?.name ?: JSONObject.NULL)
            .put("focusPath", tuningPlan?.focus?.name ?: JSONObject.NULL)
        val meta = RecordingMeta.build(this, settings, pipeline.trackerConfig, app.keyboard.timing, app.liveSend, camera)
        if (!app.logger.start(tag, meta)) {
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.rec_started, app.logger.fileName), Toast.LENGTH_LONG).show()
    }

    private fun stopRecording() {
        if (app.logger.isActive) {
            val name = app.logger.fileName
            app.logger.stop()
            Toast.makeText(this, getString(R.string.rec_saved, name), Toast.LENGTH_LONG).show()
        }
    }

    // ---- Status lines ------------------------------------------------------------------------

    private fun refreshCounts() {
        openRowCode = recorder.open
        if (recorder.open == null) photographedRow = null
        showMode()
        if (task == Task.LOOKUP) {
            totalText.setText(R.string.lookup_title)
            suspectsText.visibility = android.view.View.VISIBLE
            suspectsText.setText(R.string.lookup_hint)
            recentTitle.visibility = android.view.View.GONE
            recentStrip.removeAllViews()
            return
        }
        if (findTargets != null) {
            totalText.text = getString(R.string.find_title, findLabel)
            suspectsText.visibility = android.view.View.VISIBLE
            suspectsText.text = if (foundTracks.isEmpty()) getString(R.string.find_hint) else getString(R.string.find_found, foundTracks.size)
            recentTitle.visibility = android.view.View.GONE
            recentStrip.removeAllViews()
            refreshStatus()
            return
        }
        if (mapMode) {
            val open = recorder.open
            val layout = app.locations.layout
            totalText.text = if (open == null) getString(R.string.map_scan_label) else getString(R.string.map_column, layout.short(open), recorder.labelCount)
            suspectsText.visibility = android.view.View.VISIBLE
            suspectsText.text = when {
                open == null -> getString(R.string.row_hint_none)
                !pipeline.scanning -> getString(R.string.row_hint_paused, layout.describe(open))
                else -> getString(R.string.row_hint_open, layout.describe(open))
            }
            recentTitle.visibility = android.view.View.GONE
            recentStrip.removeAllViews()
            refreshStatus()
            return
        }
        val session = app.session
        totalText.text = getString(R.string.counts, session.total(), session.variants())
        val unread = session.unreadable()
        val uncounted = session.uncounted()
        suspectsText.visibility = if (unread + uncounted > 0) android.view.View.VISIBLE else android.view.View.GONE
        suspectsText.text = getString(R.string.suspects, unread, uncounted)
        refreshStatus()
        refreshRecent()
    }

    /** "Scanning" / "Paused" in the top pill; while paused the camera view is dimmed and says so. */
    private fun showScanState() {
        val scanning = pipeline.scanning
        statePill.setText(if (scanning) R.string.state_scanning else R.string.state_paused)
        statePill.setTextColor(ContextCompat.getColor(this, if (scanning) R.color.ok else R.color.busy))
        pausedOverlay.visibility = if (scanning) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * The last three counted boxes, newest first: last five digits (as on the camera labels), how many
     * boxes have that barcode, and its colour: green = typed into the PC, amber = waiting in the queue,
     * grey = not sent yet.
     */
    private fun refreshRecent() {
        // Distinct barcodes, newest first: two boxes with the same barcode are one chip ("x2").
        val recent = app.session.recent(RECENT_COUNT * 10).distinctBy { it.value }.take(RECENT_COUNT)
        recentTitle.visibility = if (recent.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        recentStrip.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, e) in recent.withIndex()) {
            val n = app.session.countOf(e.value)
            val color = when {
                e.sent -> R.color.ok
                app.keyboard.isQueued(e.id) -> R.color.busy
                else -> R.color.text_dim
            }
            // A capsule per barcode: a dot in the status colour, the digits, "×n" when repeated.
            val dot = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@MainActivity, color))
                setSize((8 * density).toInt(), (8 * density).toInt())
            }
            val chip = TextView(this).apply {
                text = if (n > 1) "${BarcodeText.tail(e.value)}  ×$n" else BarcodeText.tail(e.value)
                textSize = if (i == 0) 20f else 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (i == 0) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt())
                setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
                compoundDrawablePadding = (8 * density).toInt()
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 999 * density
                    setColor(if (i == 0) 0x40FFFFFF else 0x26FFFFFF)
                }
                setPadding((14 * density).toInt(), (7 * density).toInt(), (14 * density).toInt(), (7 * density).toInt())
            }
            recentStrip.addView(
                chip,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (8 * density).toInt() },
            )
        }
    }

    /** Short PC status on the chip; a green icon when the keyboard is connected. */
    private fun refreshStatus() {
        val k = app.keyboard
        val queue = if (k.pending > 0) getString(R.string.pc_queue, k.pending) else ""
        statusText.text = PcControls.statusLine(this) + queue
        val connected = k.status == HidKeyboard.Status.CONNECTED && k.paused == null
        (statusText as? com.google.android.material.chip.Chip)?.chipIconTint =
            android.content.res.ColorStateList.valueOf(if (connected) 0xFF8BC34A.toInt() else 0xFFBDBDBD.toInt())
    }

    // ---- Map mode (location labels) -----------------------------------------------------------

    private fun showMode() {
        val (text, color) = when {
            findTargets != null -> R.string.task_find to 0xCC8A5A00.toInt()
            task == Task.LOOKUP -> R.string.task_lookup to 0x66000000
            mapMode -> R.string.task_map to 0xCC1565C0.toInt()
            else -> R.string.task_count to 0x66000000
        }
        modeChip.setText(text)
        modeChip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
    }

    /** The row after the last one swept (or the first): the usual next step of a walk. */
    private var lastRow: LocationCode? = null

    private val rowPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val code = result.data?.getStringExtra(RowListActivity.EXTRA_CHOSEN)?.let { LocationCode.parseText(it) }
        if (code != null) openRow(code) else if (recorder.open == null) refreshCounts()
    }

    /** The row list (freshness of every row, the suggested next one); the chosen row opens here. */
    private fun pickRow() {
        val next = lastRow?.let { RowListActivity.nextAfter(app.locations.layout, it) }
        rowPicker.launch(
            Intent(this, RowListActivity::class.java)
                .putExtra(RowListActivity.EXTRA_FOR_RESULT, true)
                .putExtra(RowListActivity.EXTRA_SUGGEST, next?.text),
        )
    }

    private fun openRow(code: LocationCode) {
        when (val e = recorder.onLabel(code, true)) {
            is ColumnRecorder.Event.Opened -> {
                e.closed?.let(::save)
                lastRow = code
                pipeline.requestReset(reportSuspects = false)
                overlay.clear()
                app.logger.logEvent("column", mapOf("code" to code.text, "picked" to true))
                Toast.makeText(this, getString(R.string.row_started, app.locations.layout.describe(code)), Toast.LENGTH_LONG).show()
                if (!pipeline.scanning) toggleScanning()
            }
            else -> Unit
        }
        refreshCounts()
    }

    /**
     * A label counts only near the middle of the picture (the neighbouring columns' labels are in
     * view too while sweeping): the operator points the phone at it on purpose.
     */
    private fun onLabels(result: PipelineResult) {
        if (!pipeline.scanning) return
        val centre = result.imageWidth / 2f
        for (label in result.labels) {
            if (kotlin.math.abs(label.box.centerX - centre) > LABEL_CENTRE_SHARE * result.imageWidth) continue
            val read = LocationCode.parse(label.value) ?: continue
            val layout = app.locations.layout
            val code = if (!layout.contains(read) && layout.rowOf(read)?.columns == 1) read.copy(column = 1) else read
            when (val e = recorder.onLabel(code, layout.contains(code))) {
                is ColumnRecorder.Event.Opened -> {
                    e.closed?.let(::save)
                    // Fresh tracking for the new column: boxes of the last one must not carry over.
                    pipeline.requestReset(reportSuspects = false)
                    overlay.clear()
                    app.logger.logEvent("column", mapOf("code" to code.text))
                    lastRow = code
                    tone?.startTone(ToneGenerator.TONE_PROP_ACK, 220)
                    vibrate()
                    Toast.makeText(this, app.locations.layout.describe(code), Toast.LENGTH_SHORT).show()
                    refreshCounts()
                }
                is ColumnRecorder.Event.UnknownLabel -> warn(getString(R.string.map_unknown_label, e.code.text))
                else -> Unit
            }
        }
    }

    /**
     * A row is skimmed, not counted: one box per model is enough. Every read is kept (its order
     * says where along the row it is), but only a label not read before in this row beeps.
     */
    private fun mapCounted(counted: CountedTrack) {
        val fresh = recorder.open != null && !recorder.hasRead(counted.value)
        when (val e = recorder.onBox(counted.value)) {
            is ColumnRecorder.Event.BoxAdded -> {
                app.logger.logEvent("count", mapOf("id" to -1, "track" to counted.trackId, "value" to counted.value, "column" to e.code.text, "new" to fresh))
                if (fresh) {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                    vibrate()
                    flashCounted()
                }
                refreshCounts()
            }
            else -> warn(getString(R.string.map_need_label))
        }
    }

    /**
     * Analysis thread: the first read of each label in the open row keeps a photo of the shelf
     * around it and the shelf it was on, from the phone's tilt and where the label was in the
     * picture (ShelfMemory, ShelfLevel).
     */
    private fun keepShelfPhotos(image: androidx.camera.core.ImageProxy, frame: com.sweepscan.tracking.FrameResult) {
        val code = openRowCode ?: return
        if (photographedRow != code) {
            photographed.clear()
            photographedRow = code
        }
        val fresh = frame.newlyCounted.filter { c -> frame.tracks.any { it.id == c.trackId } && photographed.add(c.value) }
        if (fresh.isEmpty()) return
        val rotation = image.imageInfo.rotationDegrees
        val (w, h) = com.sweepscan.scan.ImageGeometry.uprightSize(image.width, image.height, rotation)
        val longSide = maxOf(image.width, image.height)
        val fPx = rig?.caps?.focalPx(longSide, settings.zoom) ?: (0.667f * longSide * settings.zoom)
        val tilt = gyro.rotation?.let { com.sweepscan.location.ShelfLevel.cameraElevationDeg(it) }
        val levels = app.locations.layout.rowOf(code)?.levels ?: 1
        val calibration = app.shelfCalibration
        // Only the shelf around each label is cut out and turned upright (a fraction of the frame),
        // so this costs the analysis thread little.
        val full = runCatching { image.toBitmap() }.getOrNull() ?: return
        val turn = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        for (c in fresh) {
            val box = frame.tracks.first { it.id == c.trackId }.box
            val level = tilt?.let {
                com.sweepscan.location.ShelfLevel.level(com.sweepscan.location.ShelfLevel.pointElevationDeg(it, box.centerY, h, fPx), levels, calibration)
            }
            val region = com.sweepscan.location.ShelfMemory.region(box, w, h)
            val b = com.sweepscan.scan.ImageGeometry.boxToBuffer(region, rotation, image.width, image.height)
            val x = b.left.toInt().coerceIn(0, full.width - 1)
            val y = b.top.toInt().coerceIn(0, full.height - 1)
            val cut = runCatching {
                android.graphics.Bitmap.createBitmap(
                    full, x, y, b.width.toInt().coerceIn(1, full.width - x), b.height.toInt().coerceIn(1, full.height - y), turn, true,
                )
            }.getOrNull() ?: continue
            val inRegion = com.sweepscan.tracking.Box(box.left - region.left, box.top - region.top, box.right - region.left, box.bottom - region.top)
            app.shelf.save(c.value, code, level, cut, inRegion)
            app.logger.logEvent("shelf_photo", mapOf("value" to c.value, "column" to code.text, "level" to level, "tilt" to tilt))
        }
        full.recycle()
    }

    private fun warn(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWarnMs < WARN_EVERY_MS) return
        lastWarnMs = now
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Pause / finish / mode change: the open column is done. */
    /** Pause / finish / leaving: the open row is done. [finished] = the operator pressed Finish. */
    private fun closeColumn(finished: Boolean = false) {
        val open = recorder.open
        val snap = recorder.close()
        when {
            snap != null -> save(snap, finished)
            open != null -> Toast.makeText(this, getString(R.string.map_not_saved_empty, app.locations.layout.short(open)), Toast.LENGTH_LONG).show()
        }
        refreshCounts()
    }

    private fun save(snap: ColumnRecorder.Snapshot) = save(snap, false)

    private fun save(snap: ColumnRecorder.Snapshot, finished: Boolean) {
        app.logger.logEvent("column_saved", mapOf("code" to snap.code.text, "boxes" to snap.barcodes.size))
        app.locations.saveSnapshot(snap) { saved ->
            if (isFinishing || isDestroyed) return@saveSnapshot
            if (finished) {
                showFinished(saved)
                return@saveSnapshot
            }
            val text = getString(R.string.map_saved, app.locations.layout.short(saved.code), saved.labels) + " · " + DiffText.summary(this, saved.diff)
            com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), text, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAnchorView(findViewById(R.id.bottom_bar))
                .setAction(R.string.undo) {
                    app.locations.undoSweep(saved.sweepId) {
                        app.logger.logEvent("column_undone", mapOf("code" to saved.code.text))
                        Toast.makeText(this, getString(R.string.map_undone, app.locations.layout.short(saved.code)), Toast.LENGTH_LONG).show()
                    }
                }
                .show()
        }
    }

    /** After Finish: what was saved and changed, Undo, and one tap to the next row. */
    private fun showFinished(saved: com.sweepscan.location.LocationStore.Saved) {
        val layout = app.locations.layout
        val next = RowListActivity.nextAfter(layout, saved.code)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.row_saved_title, layout.describe(saved.code), saved.labels))
            .setMessage(
                DiffText.summary(this, saved.diff) +
                    if (saved.unknown > 0) "\n\n" + getString(R.string.row_unknown_models, saved.unknown) else "",
            )
            .setNeutralButton(R.string.undo) { _, _ ->
                app.locations.undoSweep(saved.sweepId) {
                    Toast.makeText(this, getString(R.string.map_undone, layout.short(saved.code)), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.row_done) { _, _ -> finish() }
        if (next != null) {
            dialog.setPositiveButton(getString(R.string.row_next, layout.short(next))) { _, _ -> openRow(next) }
        } else {
            dialog.setPositiveButton(R.string.row_pick_other) { _, _ -> pickRow() }
        }
        dialog.setCancelable(false).show()
    }

    // ---- PC (Bluetooth keyboard) -------------------------------------------------------------

    private fun showPcDialog() {
        if (!bluetoothGranted()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        PcControls.showMenu(this) { refreshStatus() }
    }

    companion object {
        /** Find mode: barcodes to mark (ArrayList<String>) and what they are ("0169 051 · KAHVE"). */
        const val EXTRA_FIND_BARCODES = "find_barcodes"
        const val EXTRA_FIND_LABEL = "find_label"
        /** Which [Task] (its name); counting when missing. */
        const val EXTRA_TASK = "task"
        /** With [Task.LOOKUP]: return the barcode read as [EXTRA_BARCODE] instead of opening the search. */
        const val EXTRA_RETURN = "return"
        const val EXTRA_BARCODE = "barcode"
        /** Map task: the row to open ("B-3"). */
        const val EXTRA_ROW = "row"

        fun intent(context: android.content.Context, task: Task): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, task.name)

        const val GYRO_WINDOW_NS = 100_000_000L
        const val KEY_GUIDE_SHOWN = "guide_shown"
        const val RECENT_COUNT = 3
        const val SIZE_FORGET_MS = 1000L
        const val CLOSER_BELOW_PX = 200f
        const val FARTHER_ABOVE_PX = 850f
        const val FLASH_MS = 350L
        /** Used for blur estimates when the capture result is missing (typical indoor auto exposure). */
        const val ASSUMED_EXPOSURE_NS = 16_000_000L
        /** Above this, frame-to-frame motion gets too large to track reliably (~45 px/frame at 30 fps). */
        const val SLOW_DOWN_DPS = 60f
        /** About one module at 2 px/module: reads start failing. */
        const val BLUR_HINT_PX = 3f
        const val HINT_HYSTERESIS_MS = 300L
        /** A location label must be within this share of the picture width from its centre. */
        const val LABEL_CENTRE_SHARE = 0.22f
        const val WARN_EVERY_MS = 2500L
    }
}
