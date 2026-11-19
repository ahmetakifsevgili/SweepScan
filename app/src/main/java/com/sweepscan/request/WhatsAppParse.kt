package com.sweepscan.request

import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Reads one WhatsApp notification, without side effects (the watcher and the tests use the same
 * code): which chat it is, whether that chat is the request group, the messages in it and the
 * notification's reply action.
 *
 * Safety: only a **group conversation** whose name starts with the configured group name counts.
 * A one-to-one chat with someone whose name contains the group's name is not the group, and its
 * reply action is never kept (an answer could otherwise go to that person). The phone owner's
 * own messages (WhatsApp adds our "✅ Gönderdim" replies to the notification) are not requests.
 */
object WhatsAppParse {

    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    data class Message(val time: Long, val sender: String, val text: String)

    data class Incoming(
        /** The chat's name, normalised ([key]). */
        val chatKey: String,
        val group: Boolean,
        val messages: List<Message>,
        val reply: Notification.Action?,
        val open: PendingIntent?,
    )

    private val TR: Locale = Locale.forLanguageTag("tr")

    /** Lower case (Turkish rules), letters, digits and single spaces only: "ÜRÜN İSTEME 📦" → "ürün isteme". */
    fun key(name: String): String =
        name.lowercase(TR).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim().replace(Regex(" +"), " ")

    /** A chat named [chatKey] is the request group named [group] ("Ürün isteme" matches "Ürün isteme telsiz"). */
    fun isRequestGroup(chatKey: String, isGroup: Boolean, group: String): Boolean {
        val want = key(group)
        if (!isGroup || want.isEmpty()) return false
        return chatKey == want || chatKey.startsWith("$want ")
    }

    /** Our own replies, as WhatsApp shows them back in the chat. */
    private val OWN_REPLY = Regex("^\\s*(✅|❌|🔎)")

    /** The notification's content, or null when it is not a WhatsApp chat notification at all. */
    fun read(packageName: String, n: Notification): Incoming? {
        if (packageName !in PACKAGES) return null
        // The group summary ("3 yeni mesaj") repeats what the chat notification says.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return null
        val title = style.conversationTitle?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val me = style.user.name?.toString()
        val messages = style.messages.mapNotNull { m ->
            val text = m.text?.toString().orEmpty()
            val sender = m.person?.name?.toString()
            when {
                text.isBlank() -> null
                // No person = the phone's owner wrote it (our replies among them).
                sender == null || sender == me -> null
                OWN_REPLY.containsMatchIn(text) -> null
                else -> Message(m.timestamp, sender, text)
            }
        }
        val reply = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        return Incoming(key(title), style.isGroupConversation, messages, reply, n.contentIntent)
    }
}
