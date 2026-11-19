package com.sweepscan.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.sweepscan.R
import com.sweepscan.camera.ExposureMode
import com.sweepscan.camera.FocusMode
import com.sweepscan.scan.ScanSettings

/**
 * Settings as a bottom sheet that stays open: every option is visible at once and a change applies
 * immediately (the old list dialog closed after each tap and stepped one value at a time).
 */
class SettingsSheet(private val context: Context, private val settings: ScanSettings, private val host: Host) {

    interface Host {
        val recording: Boolean
        val tourActive: Boolean
        val flowAvailable: Boolean
        fun onZoomChanged()
        fun onResolutionChanged()
        fun onTuningChanged(announce: Boolean)
        fun onTorchChanged()
        fun onViewChanged()
        fun onSettingsChanged()
        fun toggleRecording()
        fun toggleTour()
        fun showCameraInfo()
        fun showGuide()
    }

    private lateinit var dialog: BottomSheetDialog

    fun show() {
        dialog = BottomSheetDialog(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        build(column)
        dialog.setContentView(NestedScrollView(context).apply { addView(column) })
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun build(c: LinearLayout) {
        section(c, R.string.sheet_camera)
        label(c, R.string.sheet_zoom)
        lateinit var autoSwitch: MaterialSwitch
        toggles(c, ScanSettings.ZOOMS.map { zoomLabel(it) to it }, settings.zoom) {
            if (it == settings.zoom) return@toggles
            settings.zoom = it
            settings.autoZoom = false
            autoSwitch.isChecked = false
            host.onZoomChanged()
            changed()
        }
        autoSwitch = switch(c, R.string.sw_auto_zoom, settings.autoZoom) {
            settings.autoZoom = it
            host.onZoomChanged()
            changed()
        }
        label(c, R.string.sheet_exposure)
        val exposure = when (settings.exposure) {
            ExposureMode.SHORT -> settings.shortExposureUs
            ExposureMode.AUTO -> 0
            ExposureMode.FPS30_EV -> -1
        }
        toggles(
            c,
            listOf(
                context.getString(R.string.opt_auto) to 0,
                "4 ms" to 4000,
                "2 ms" to 2000,
                "1 ms" to 1000,
            ),
            exposure,
        ) {
            if (it == 0) {
                settings.exposure = ExposureMode.AUTO
            } else {
                settings.exposure = ExposureMode.SHORT
                settings.shortExposureUs = it
            }
            host.onTuningChanged(announce = true)
            changed()
        }
        caption(c, R.string.sheet_exposure_note)

        label(c, R.string.sheet_focus)
        lateinit var distanceRow: View
        lateinit var distanceLabel: View
        toggles(
            c,
            listOf(
                context.getString(R.string.opt_focus_continuous) to FocusMode.CONTINUOUS,
                context.getString(R.string.opt_focus_center) to FocusMode.CENTER,
                context.getString(R.string.opt_focus_fixed) to FocusMode.FIXED,
            ),
            settings.focus,
        ) {
            settings.focus = it
            distanceLabel.visibility = if (it == FocusMode.FIXED) View.VISIBLE else View.GONE
            distanceRow.visibility = distanceLabel.visibility
            host.onTuningChanged(announce = true)
            changed()
        }
        distanceLabel = label(c, R.string.sheet_focus_distance)
        distanceRow = toggles(c, ScanSettings.FOCUS_DISTANCES_M.map { "${(it * 100).toInt()} cm" to it }, settings.focusDistanceM) {
            settings.focusDistanceM = it
            host.onTuningChanged(announce = false)
            changed()
        }
        distanceLabel.visibility = if (settings.focus == FocusMode.FIXED) View.VISIBLE else View.GONE
        distanceRow.visibility = distanceLabel.visibility

        label(c, R.string.sheet_resolution)
        toggles(c, ScanSettings.RESOLUTIONS.map { "${it}p" to it }, settings.resolution) {
            settings.resolution = it
            host.onResolutionChanged()
            changed()
        }
        switch(c, R.string.torch, settings.torch) {
            settings.torch = it
            host.onTorchChanged()
            changed()
        }

        // Reading, recording and the debug view are for testing the app: shown only when switched on
        // in Ayarlar → Gelişmiş.
        val advanced = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getBoolean(SettingsActivity.KEY_ADVANCED, false)
        if (!advanced) {
            action(c, R.string.guide_title) { host.showGuide() }
            return
        }

        section(c, R.string.sheet_reading)
        switch(c, R.string.sw_lengths, settings.strictLengths) { settings.strictLengths = it; changed() }
        switch(c, R.string.sw_zxing, settings.zxing) { settings.zxing = it; changed() }
        switch(c, R.string.sw_ocr, settings.ocr) { settings.ocr = it; changed() }
        switch(c, R.string.sw_flow, settings.flow && host.flowAvailable, enabled = host.flowAvailable) { settings.flow = it; changed() }

        section(c, R.string.sheet_recording)
        action(c, if (host.recording) R.string.rec_stop else R.string.rec_start) { host.toggleRecording() }
        action(c, if (host.tourActive) R.string.tour_stop else R.string.tour_start_short) { host.toggleTour() }
        switch(c, R.string.sw_samples, settings.sampleFrames) { settings.sampleFrames = it; changed() }

        section(c, R.string.sheet_view)
        switch(c, R.string.sw_hud, settings.showHud) {
            settings.showHud = it
            host.onViewChanged()
        }
        action(c, R.string.guide_title) { host.showGuide() }
        action(c, R.string.camera_info) { host.showCameraInfo() }
    }

    private fun changed() = host.onSettingsChanged()

    // ---- building blocks ---------------------------------------------------------------------

    private fun section(c: LinearLayout, text: Int) {
        c.addView(TextView(context).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun label(c: LinearLayout, text: Int): View = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        alpha = 0.8f
        setPadding(0, dp(10), 0, dp(4))
        c.addView(this)
    }

    private fun caption(c: LinearLayout, text: Int) {
        c.addView(TextView(context).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.6f
        })
    }

    private fun <T> toggles(c: LinearLayout, options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit): View {
        val group = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for ((text, value) in options) {
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                this.text = text
                isAllCaps = false
                tag = value
                minWidth = 0
                setPadding(dp(4), 0, dp(4), 0)
            }
            group.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value == selected) group.check(button.id)
        }
        group.addOnButtonCheckedListener { g, id, checked ->
            @Suppress("UNCHECKED_CAST")
            if (checked) onPick(g.findViewById<View>(id).tag as T)
        }
        c.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return group
    }

    private fun switch(c: LinearLayout, text: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): MaterialSwitch {
        val view = MaterialSwitch(context).apply {
            setText(text)
            isChecked = checked
            isEnabled = enabled
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        c.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return view
    }

    /** Buttons that open another dialog close the sheet first. */
    private fun action(c: LinearLayout, text: Int, onClick: () -> Unit) {
        c.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(text)
            isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                onClick()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun zoomLabel(z: Float): String = if (z % 1f == 0f) "${z.toInt()}×" else "${z.toString().replace('.', ',')}×"
    }
}
