package com.sweepscan.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sweepscan.R
import com.sweepscan.ui.SettingsActivity
import java.net.Inet4Address

/**
 * Keeps [DepotServer] running while the depot phone is in a pocket, and announces it on the Wi-Fi
 * (mDNS, "_sweepscan._tcp") so shop-floor phones find it without typing an address. A quiet
 * notification shows the address and the PIN; tapping it opens the settings.
 */
class DepotService : Service() {

    private var server: DepotServer? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // The manifest declares foregroundServiceType="specialUse"; lint does not see it through
    // ServiceCompat (checked on the phone by DepotServerTest.serviceStartsAndAnswers).
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setOn(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        channel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            // ServiceCompat drops the type below Android 14, where it does not exist.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (server?.running != true) {
            server = DepotServer(this).also { s ->
                runCatching { s.start() }.onFailure { Log.e(TAG, "server", it) }
            }
            announce()
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        registration?.let { r -> runCatching { nsd?.unregisterService(r) } }
        registration = null
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun announce() {
        val manager = getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "SweepScan depo"
            serviceType = DepotServer.SERVICE_TYPE
            port = DepotServer.PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS failed $code") }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DepotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(getString(R.string.server_on_title))
            .setContentText(getString(R.string.server_on_text, address(this) ?: "—", DepotServer.pin(this)))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.server_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "DepotService"
        private const val CHANNEL = "depot_server"
        private const val NOTIFICATION_ID = 7373
        private const val ACTION_STOP = "stop"
        private const val PREFS = "depot_server"
        private const val KEY_ON = "on"

        @Volatile var running = false
            private set

        fun isOn(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        private fun setOn(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()

        fun start(c: Context) {
            setOn(c, true)
            c.startForegroundService(Intent(c, DepotService::class.java))
        }

        fun stop(c: Context) {
            setOn(c, false)
            c.stopService(Intent(c, DepotService::class.java))
        }

        /** This phone's address on the Wi-Fi ("192.168.0.8:8737"), or null without a network. */
        fun address(c: Context): String? {
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return null
            val props: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
            val ip = props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
            return "${ip.hostAddress}:${DepotServer.PORT}"
        }
    }
}
