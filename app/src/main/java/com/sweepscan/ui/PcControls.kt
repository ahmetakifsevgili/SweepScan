package com.sweepscan.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.hid.HidKeyboard
import com.sweepscan.hid.PauseReason

/** The "PC" (Bluetooth keyboard) menu and status text, shared by the scan and review screens. */
object PcControls {

    fun statusLine(context: Context): String {
        val app = context.applicationContext as SweepScanApp
        val keyboard = app.keyboard
        return when (keyboard.status) {
            HidKeyboard.Status.CONNECTED -> when (keyboard.paused) {
                PauseReason.UNCERTAIN -> context.getString(R.string.pc_paused_uncertain, app.session.uncertainCount())
                PauseReason.USER -> context.getString(R.string.pc_paused)
                PauseReason.LINK -> context.getString(R.string.pc_paused_link)
                null -> context.getString(R.string.pc_connected, keyboard.hostName ?: "PC")
            }
            HidKeyboard.Status.READY -> context.getString(R.string.pc_ready)
            HidKeyboard.Status.REGISTERING -> context.getString(R.string.pc_registering)
            HidKeyboard.Status.UNSUPPORTED ->
                context.getString(R.string.pc_unsupported_reason, keyboard.unsupportedReason ?: "?")
            HidKeyboard.Status.OFF -> context.getString(
                if (keyboard.isBluetoothOn()) R.string.pc_off else R.string.pc_bt_off,
            )
        }
    }

    /** Caller must already hold the Bluetooth permissions. */
    fun showMenu(activity: Activity, onChanged: () -> Unit) {
        val app = activity.application as SweepScanApp
        val keyboard = app.keyboard
        if (!keyboard.isBluetoothOn()) {
            Toast.makeText(activity, R.string.bluetooth_off, Toast.LENGTH_LONG).show()
            return
        }
        keyboard.start()
        val items = arrayOf(
            activity.getString(R.string.pc_pair_new),
            activity.getString(R.string.pc_connect_paired),
            activity.getString(if (app.liveSend) R.string.pc_live_on else R.string.pc_live_off),
            activity.getString(R.string.pc_speed, speedLabel(activity, app.sendSpeed)),
            activity.getString(if (keyboard.paused == null) R.string.pc_pause else R.string.pc_resume),
            activity.getString(R.string.pc_test),
            activity.getString(R.string.pc_test_100),
        )
        AlertDialog.Builder(activity)
            .setTitle(statusLine(activity))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> makeDiscoverable(activity)
                    1 -> pickPairedPc(activity)
                    2 -> {
                        app.liveSend = !app.liveSend
                        if (app.liveSend) {
                            AlertDialog.Builder(activity)
                                .setTitle(R.string.pc_live_on)
                                .setMessage(activity.getString(R.string.pc_live_hint, speedLabel(activity, app.sendSpeed)))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    3 -> pickSpeed(activity)
                    4 -> if (keyboard.paused == null) keyboard.pause() else resumeOrResolve(activity)
                    // Queued without a PC, a test would be typed into whatever is focused on connect.
                    5, 6 -> if (keyboard.status != HidKeyboard.Status.CONNECTED) {
                        Toast.makeText(activity, R.string.send_needs_pc, Toast.LENGTH_LONG).show()
                    } else if (which == 5) {
                        keyboard.enqueue(-SystemClock.elapsedRealtime(), "1234567890")
                    } else {
                        typeHundred(app)
                    }
                }
                onChanged()
            }
            .show()
    }

    /** Resuming with unresolved uncertain lines would append to half-typed digits: resolve first. */
    fun resumeOrResolve(activity: Activity) {
        val app = activity.application as SweepScanApp
        val pending = app.session.uncertainEntries().firstOrNull()
        if (pending != null) UncertainDialog.show(activity, pending.id) else app.keyboard.resume()
    }

    fun showNotice(activity: Activity, notice: HidKeyboard.Notice) {
        val text = when (notice) {
            HidKeyboard.Notice.BOOT_PROTOCOL -> R.string.pc_boot_protocol
            HidKeyboard.Notice.UNPLUGGED -> R.string.pc_unplugged
        }
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    /** HID stage-1 field test: 100 numbered 13-digit barcodes into Notepad, compared afterwards. */
    private fun typeHundred(app: SweepScanApp) {
        val base = SystemClock.elapsedRealtime() * 1000
        for (i in 1..100) app.keyboard.enqueue(-(base + i), "99000000%05d".format(i))
    }

    private fun makeDiscoverable(activity: Activity) {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        activity.startActivity(intent)
        AlertDialog.Builder(activity)
            .setTitle(R.string.pc_pair_new)
            .setMessage(R.string.pc_pair_instructions)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun pickPairedPc(activity: Activity) {
        val app = activity.application as SweepScanApp
        val devices = app.keyboard.bondedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(activity, R.string.pc_none_paired, Toast.LENGTH_LONG).show()
            return
        }
        val names = devices.map { "${it.name ?: "?"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.pc_connect_paired)
            .setItems(names) { _, which -> app.keyboard.connect(devices[which]) }
            .show()
    }

    private fun pickSpeed(activity: Activity) {
        val app = activity.application as SweepScanApp
        val options = com.sweepscan.hid.SendSpeed.entries
        val labels = options.map { speedLabel(activity, it) }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.pc_speed_title)
            .setSingleChoiceItems(labels, options.indexOf(app.sendSpeed)) { d, which ->
                app.sendSpeed = options[which]
                d.dismiss()
            }
            .show()
    }

    fun speedLabel(context: Context, speed: com.sweepscan.hid.SendSpeed): String {
        val name = context.getString(
            when (speed) {
                com.sweepscan.hid.SendSpeed.SAFE -> R.string.speed_safe
                com.sweepscan.hid.SendSpeed.NORMAL -> R.string.speed_normal
                com.sweepscan.hid.SendSpeed.FAST -> R.string.speed_fast
            },
        )
        return context.getString(R.string.speed_label, name, speed.approxMsPerBarcode / 1000.0)
    }

    fun onOff(context: Context, value: Boolean) = context.getString(if (value) R.string.on else R.string.off)
}
