package com.sweepscan.request

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Watches WhatsApp's notifications for the request group only (the name set in Ayarlar); every
 * other notification is ignored and never stored. Works while the chat is not open on the phone
 * (WhatsApp shows no notification then).
 */
class RequestWatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Requests.enabled(this)) return
        // Presenting: the real group stays out of the demo (and its names off the screen).
        if (Presentation.isOn(this)) return
        val incoming = WhatsAppParse.read(sbn.packageName, sbn.notification ?: return) ?: return
        try {
            Intake.whatsApp(this, incoming)
        } catch (e: Exception) {
            android.util.Log.w("RequestWatcher", "request not handled", e)
        }
    }

    /**
     * After a restart the reply action is gone from memory: find the group's notification again
     * among those still shown. Messages already handled are skipped (Intake remembers them).
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    /** The system can drop the listener silently (updates, memory): ask to be bound again. */
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(android.content.ComponentName(this, RequestWatcher::class.java))
    }

}
