package com.sweepscan.ui

import android.Manifest
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sweepscan.ui.theme.SectionLabel
import com.sweepscan.ui.theme.SettingRow
import com.sweepscan.ui.theme.Space
import com.sweepscan.ui.theme.SweepCard
import com.sweepscan.ui.theme.SweepScreen
import com.sweepscan.ui.theme.SweepTheme
import com.sweepscan.R
import com.sweepscan.SweepScanApp

/**
 * Every setting in one place: the PC connection, the stock-room layout and labels, data in and
 * out, sample data, help. The camera's test settings stay out of the way under "Gelişmiş".
 */
class SettingsActivity : AppCompatActivity() {

    private val app get() = application as SweepScanApp

    private val backupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val out = uri?.let { contentResolver.openOutputStream(it) } ?: return@registerForActivityResult
        app.locations.backup(out) { ok ->
            android.widget.Toast.makeText(this, if (ok) R.string.backup_done else R.string.backup_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val restoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val input = uri?.let { contentResolver.openInputStream(it) } ?: return@registerForActivityResult
        app.locations.restore(input) { ok ->
            if (ok) app.reopenLocations()
            android.widget.Toast.makeText(this, if (ok) R.string.restore_done else R.string.restore_failed, android.widget.Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val btPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothGranted()) PcControls.showMenu(this) { refresh() }
    }

    /** What the screen lists, rebuilt by [refresh]. */
    private sealed interface Entry {
        data class Section(val title: Int) : Entry
        data class Item(val title: String, val sub: String?, val onClick: () -> Unit) : Entry
    }

    private var building = mutableListOf<Entry>()
    private var entries by mutableStateOf<List<Entry>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SweepTheme { SettingsList() } }
    }

    @Composable
    private fun SettingsList() {
        // Each section is one card, its settings rows inside.
        val groups = remember(entries) {
            val out = mutableListOf<Pair<Int, MutableList<Entry.Item>>>()
            for (e in entries) when (e) {
                is Entry.Section -> out += e.title to mutableListOf()
                is Entry.Item -> out.lastOrNull()?.second?.add(e)
            }
            out
        }
        SweepScreen(title = stringResource(R.string.settings), onBack = ::finish, backDescription = stringResource(R.string.back)) {
            for ((title, items) in groups) {
                item(key = "s$title") { SectionLabel(stringResource(title)) }
                item(key = "c$title") {
                    SweepCard {
                        Column(Modifier.padding(horizontal = Space.m, vertical = Space.xs)) {
                            items.forEachIndexed { i, it ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                SettingRow(it.title, it.sub, onClick = it.onClick)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        building = mutableListOf()
        build()
        entries = building
    }

    private fun build() {
        section(R.string.req_section)
        if (!com.sweepscan.request.ShareRequestActivity.watcherAvailable(this)) {
            item(getString(R.string.req_title), getString(R.string.share_how_short)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.req_title)
                    .setMessage(R.string.share_how)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            val replies = com.sweepscan.request.Requests.replies(this)
            item(getString(R.string.reply_setting), getString(if (replies) R.string.reply_setting_on else R.string.reply_setting_off)) {
                com.sweepscan.request.Requests.setReplies(this, !replies)
                refresh()
            }
            section(R.string.set_pc)
            item(getString(R.string.set_pc_title), PcControls.statusLine(this)) {
                if (bluetoothGranted()) PcControls.showMenu(this) { refresh() } else btPermissions.launch(btPermissionList())
            }
            restOfSettings()
            return
        }
        val granted = com.sweepscan.request.Requests.accessGranted(this)
        val on = com.sweepscan.request.Requests.enabled(this)
        val group = com.sweepscan.request.Requests.group(this)
        item(
            getString(R.string.req_title),
            when {
                !granted -> getString(R.string.req_status_access)
                on -> getString(R.string.req_status_on, group)
                else -> getString(R.string.req_status_off)
            },
        ) {
            if (!granted) {
                showAccessSteps()
            } else {
                com.sweepscan.request.Requests.setEnabled(this, !on)
                if (!on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                refresh()
            }
        }
        item(getString(R.string.req_group), group) { askGroup() }
        val replies = com.sweepscan.request.Requests.replies(this)
        item(getString(R.string.reply_setting), getString(if (replies) R.string.reply_setting_on else R.string.reply_setting_off)) {
            com.sweepscan.request.Requests.setReplies(this, !replies)
            refresh()
        }

        section(R.string.set_pc)
        item(getString(R.string.set_pc_title), PcControls.statusLine(this)) {
            if (bluetoothGranted()) PcControls.showMenu(this) { refresh() } else btPermissions.launch(btPermissionList())
        }

        restOfSettings()
    }

    private fun restOfSettings() {
        shopLink()
        section(R.string.set_layout)
        item(getString(R.string.loc_edit), getString(R.string.set_layout_edit_sub)) { tool(LocationActivity.ACTION_EDIT) }
        item(getString(R.string.loc_labels), getString(R.string.set_labels_sub)) { tool(LocationActivity.ACTION_LABELS) }
        val cal = app.shelfCalibration
        item(getString(R.string.shelf_cal_title), getString(R.string.shelf_cal_sub, cm(cal.eye), cm(cal.reach), cm(cal.levelM))) { shelfCalibration() }

        section(R.string.set_data)
        item(getString(R.string.loc_import), getString(R.string.set_import_sub)) { tool(LocationActivity.ACTION_IMPORT) }
        item(getString(R.string.loc_export), getString(R.string.set_export_sub)) { tool(LocationActivity.ACTION_EXPORT) }
        item(getString(R.string.backup_title), getString(R.string.backup_sub)) {
            val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            backupFile.launch("sweepscan-yedek-$day.zip")
        }
        item(getString(R.string.restore_title), getString(R.string.restore_sub)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_warning)
                .setPositiveButton(R.string.restore_title) { _, _ -> restoreFile.launch(arrayOf("application/zip", "application/octet-stream")) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val photos = app.shelf.count()
        item(getString(R.string.shelf_photos_title), getString(R.string.shelf_photos_sub, photos)) {
            if (photos == 0) return@item
            AlertDialog.Builder(this)
                .setTitle(R.string.shelf_photos_title)
                .setMessage(getString(R.string.shelf_photos_clear, photos))
                .setPositiveButton(R.string.shelf_photos_clear_yes) { _, _ -> app.shelf.clear(); window.decorView.postDelayed({ refresh() }, 400) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val presenting = com.sweepscan.request.Presentation.isOn(this)
        item(getString(R.string.set_present), getString(if (presenting) R.string.on else R.string.off)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_present)
                .setMessage(if (presenting) R.string.present_off_body else R.string.present_on_body)
                .setPositiveButton(if (presenting) R.string.present_stop else R.string.present_start) { _, _ ->
                    com.sweepscan.request.Presentation.set(this, !presenting) { refresh() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        val demo = app.locations.demo
        item(getString(R.string.set_demo), getString(if (demo) R.string.on else R.string.off)) {
            AlertDialog.Builder(this)
                .setTitle(if (demo) R.string.demo_off else R.string.demo_on)
                .setMessage(if (demo) R.string.demo_off_body else R.string.demo_on_body)
                .setPositiveButton(android.R.string.ok) { _, _ -> app.setDemoMode(!demo) { refresh() } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        section(R.string.set_advanced)
        val prefs = HomeActivity.prefs(this)
        val advanced = prefs.getBoolean(KEY_ADVANCED, false)
        item(getString(R.string.set_advanced_camera), getString(if (advanced) R.string.set_advanced_on else R.string.set_advanced_off)) {
            prefs.edit().putBoolean(KEY_ADVANCED, !advanced).apply()
            refresh()
        }

        section(R.string.set_about)
        item(getString(R.string.set_howto), getString(R.string.set_howto_sub)) { startActivity(Intent(this, OnboardingActivity::class.java)) }
        item(getString(R.string.set_version), "SweepScan " + packageManager.getPackageInfo(packageName, 0).versionName) {}
        crashReports()
    }

    /** Crash reports kept on the phone (see [com.sweepscan.CrashLog]): share them or clear them. */
    private fun crashReports() {
        val reports = com.sweepscan.CrashLog.reports(this)
        val sub = reports.firstOrNull()?.let {
            getString(R.string.crash_sub, reports.size,
                java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.forLanguageTag("tr")).format(java.util.Date(it.lastModified())))
        } ?: getString(R.string.crash_none)
        item(getString(R.string.crash_title), sub) {
            if (reports.isEmpty()) return@item
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_title)
                .setMessage(R.string.crash_body)
                .setPositiveButton(R.string.crash_share) { _, _ ->
                    val file = com.sweepscan.CrashLog.bundle(this) ?: return@setPositiveButton
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { startActivity(Intent.createChooser(send, getString(R.string.crash_share))) }
                }
                .setNeutralButton(R.string.crash_clear) { _, _ -> com.sweepscan.CrashLog.clear(this); refresh() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Notification access for an app installed from a file: Android first wants "restricted
     * settings" allowed for it (App info → ⋮), then the switch under Notification access.
     */
    private fun showAccessSteps() {
        AlertDialog.Builder(this)
            .setTitle(R.string.req_title)
            .setMessage(R.string.req_steps)
            .setPositiveButton(R.string.req_step_access) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNeutralButton(R.string.req_step_restricted) { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askGroup() {
        val input = android.widget.EditText(this).apply {
            setText(com.sweepscan.request.Requests.group(this@SettingsActivity))
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.req_group)
            .setMessage(R.string.req_group_hint)
            .setView(box)
            .setPositiveButton(R.string.loc_edit_save) { _, _ ->
                com.sweepscan.request.Requests.setGroup(this, input.text.toString())
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tool(action: String) {
        startActivity(Intent(this, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_ACTION, action))
    }

    private fun btPermissionList(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()

    private fun bluetoothGranted() = btPermissionList().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun cm(m: Float) = (m * 100).toInt()

    /** The depot server for shop-floor phones and the manager panel, and the shop-phone switch. */
    private fun shopLink() {
        section(R.string.set_shop_section)
        val on = com.sweepscan.server.DepotService.isOn(this)
        val address = com.sweepscan.server.DepotService.address(this) ?: "—"
        val pin = com.sweepscan.server.DepotServer.pin(this)
        val last = com.sweepscan.server.DepotServer.lastClient
        val sub = when {
            !on -> getString(R.string.set_server_off)
            last != null -> getString(R.string.set_server_last, address, pin,
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.forLanguageTag("tr")).format(java.util.Date(last.second)))
            else -> getString(R.string.set_server_on, address, pin)
        }
        item(getString(R.string.set_server), sub) {
            if (on) com.sweepscan.server.DepotService.stop(this) else com.sweepscan.server.DepotService.start(this)
            window.decorView.postDelayed({ refresh() }, 300)
        }
        val url = "http://$address/?pin=$pin"
        item(getString(R.string.set_panel), if (on) getString(R.string.set_panel_sub, "http://$address") else getString(R.string.set_panel_off)) {
            if (!on) return@item
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("SweepScan", url))
            Toast.makeText(this, R.string.set_panel_copied, Toast.LENGTH_SHORT).show()
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), getString(R.string.set_panel))) }
        }
        if (on) {
            item(getString(R.string.set_new_pin), getString(R.string.set_new_pin_sub)) {
                com.sweepscan.server.DepotServer.newPin(this)
                com.sweepscan.server.DepotService.start(this) // refreshes the notification
                refresh()
            }
        }
        item(getString(R.string.set_shop_phone), getString(R.string.set_shop_phone_sub)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_shop_phone)
                .setMessage(R.string.set_shop_phone_confirm)
                .setPositiveButton(R.string.set_shop_phone_yes) { _, _ ->
                    com.sweepscan.server.ShopClient.setShopPhone(this, true)
                    startActivity(Intent(this, ShopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Phone height, distance to the rack and shelf height, in cm: what turns the phone's tilt into a shelf. */
    private fun shelfCalibration() {
        val cal = app.shelfCalibration
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(label: Int, value: Float): android.widget.EditText {
            box.addView(android.widget.TextView(this).apply { setText(label); setPadding(0, pad / 2, 0, 0) })
            return android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(cm(value).toString())
                box.addView(this)
            }
        }
        val eye = field(R.string.shelf_cal_eye, cal.eye)
        val reach = field(R.string.shelf_cal_reach, cal.reach)
        val level = field(R.string.shelf_cal_level, cal.levelM)
        AlertDialog.Builder(this)
            .setTitle(R.string.shelf_cal_title)
            .setMessage(R.string.shelf_cal_body)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                fun m(e: android.widget.EditText, lo: Int, hi: Int, d: Float) = (e.text.toString().toIntOrNull()?.coerceIn(lo, hi)?.div(100f)) ?: d
                app.shelfCalibration = cal.copy(eye = m(eye, 50, 220, cal.eye), reach = m(reach, 20, 300, cal.reach), levelM = m(level, 15, 120, cal.levelM))
                refresh()
            }
            .setNeutralButton(R.string.shelf_cal_reset) { _, _ -> app.shelfCalibration = com.sweepscan.location.ShelfLevel.Calibration.DEFAULT; refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun section(text: Int) {
        building += Entry.Section(text)
    }

    private fun item(title: String, sub: String?, onClick: () -> Unit) {
        building += Entry.Item(title, sub, onClick)
    }

    companion object {
        /** Show the camera's test settings (reading, recording, HUD) in the camera's settings. */
        const val KEY_ADVANCED = "advanced"
    }
}
