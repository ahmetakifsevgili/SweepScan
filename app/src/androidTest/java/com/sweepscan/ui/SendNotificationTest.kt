package com.sweepscan.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.sweepscan.SweepScanApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "typing into DİA" progress notification: posted as ongoing progress, on Android 16+ with the
 * progress-centric template and the Live Update request; whether the system promoted it (status-bar
 * chip) is logged, since the user can turn that off per app.
 */
@RunWith(AndroidJUnit4::class)
class SendNotificationTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @After
    fun cleanUp() = SendNotification.cancel(app)

    private fun posted(): Notification? {
        repeat(30) {
            nm.activeNotifications.firstOrNull { it.notification.channelId == "sending" }?.let { return it.notification }
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun progressIsPostedAndCancelled() {
        SendNotification.show(app, done = 3, total = 10, leftMs = 10_500, paused = false)
        val n = posted()
        assertNotNull("posted", n)
        n!!
        assertTrue("ongoing", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.CATEGORY_PROGRESS, n.category)
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals("3/10", n.shortCriticalText)
            assertTrue("asks to be a Live Update", n.extras.getBoolean("android.requestPromotedOngoing"))
            val promoted = n.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            Log.i(TAG, "canPostPromoted=${nm.canPostPromotedNotifications()} promoted=$promoted")
        } else {
            assertEquals(10, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertEquals(3, n.extras.getInt(Notification.EXTRA_PROGRESS))
        }
        SendNotification.cancel(app)
        Thread.sleep(300)
        assertNull("cancelled", nm.activeNotifications.firstOrNull { it.notification.channelId == "sending" })
    }

    private companion object {
        const val TAG = "SendNotificationTest"
    }
}
