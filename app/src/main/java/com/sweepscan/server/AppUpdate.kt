package com.sweepscan.server

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.sweepscan.R
import java.io.File

/**
 * Updates without a store or the internet: the depot phone serves the app it runs
 * ([DepotServer], GET /api/app) and a shop-floor phone with an older version downloads it and
 * installs it over itself (data kept). Android asks the user once to allow SweepScan to install
 * apps, then shows its own "update?" confirmation.
 */
object AppUpdate {

    fun versionCode(c: Context): Long =
        runCatching { PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)) }.getOrDefault(0L)

    fun isNewer(mine: Long, depot: Long) = depot > mine

    fun file(c: Context) = File(c.cacheDir, "update/sweepscan.apk")

    /** The app this phone runs, as one file (null when installed as split APKs, which cannot be passed on). */
    fun ownApk(c: Context): File? {
        val info = c.applicationInfo
        if (!info.splitSourceDirs.isNullOrEmpty()) return null
        return File(info.sourceDir).takeIf { it.canRead() }
    }

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /** Settings → "Install unknown apps" → SweepScan. */
    fun allowIntent(c: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    /** Hands [apk] to the system installer; [Result] arrives in [Receiver]. */
    fun install(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(c.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable: the installer adds the status and its confirmation screen to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(c, id, Intent(c, Receiver::class.java).setPackage(c.packageName), flags)
            session.commit(done.intentSender)
        }
    }

    /** The installer's answer: show its confirmation screen, or say why it did not install. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // the new version takes over
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user said no
                else -> {
                    val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "($status)"
                    Toast.makeText(c, c.getString(R.string.update_failed, why), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
