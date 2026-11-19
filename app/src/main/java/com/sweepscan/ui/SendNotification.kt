package com.sweepscan.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sweepscan.R
import kotlin.math.ceil

/**
 * "Typing into DİA: 23 / 40" while the phone types a count into the PC. On Android 16+ it is a
 * progress-centric notification asking to be a Live Update (a chip in the status bar, Samsung's
 * Now Bar), so the progress shows over DİA's window on the phone's lock screen too; older phones
 * get an ordinary ongoing progress notification. Tap = back to the review screen.
 */
object SendNotification {

    private const val CHANNEL = "sending"
    private const val ID = 7301

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36.1; the key is read from Android 16 on). */
    private const val EXTRA_PROMOTE = "android.requestPromotedOngoing"

    fun show(c: Context, done: Int, total: Int, leftMs: Long, paused: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(c, nm)
        val title = c.getString(if (paused) R.string.notif_send_paused else R.string.notif_send_title)
        val minutes = ceil(leftMs / 60_000.0).toInt().coerceAtLeast(1)
        val text = c.getString(R.string.notif_send_text, done, total, minutes)
        val open = PendingIntent.getActivity(
            c, ID, Intent(c, SessionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(total.coerceAtLeast(1)).setColor(0xFFC6E81B.toInt())))
                .setProgress(done.coerceAtMost(total))
                .setStyledByProgress(true)
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_pc)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShortCriticalText("$done/$total")
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_PROMOTE, true) })
                .build()
        } else {
            NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_pc)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
        runCatching { nm.notify(ID, n) }
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun ensureChannel(c: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.notif_send_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }
}
