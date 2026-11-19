package com.sweepscan.request

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.location.LocationAnswer
import com.sweepscan.location.LocationCode
import com.sweepscan.location.RowFlags
import com.sweepscan.location.RowPosition
import com.sweepscan.ui.LocationActivity
import com.sweepscan.ui.RequestsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Requests from the shop floor's WhatsApp group ("Ürün isteme telsiz"), as a small queue:
 *
 * - a written request is answered with a notification saying where the model is, with
 *   **Gönderdim** / **Yok** buttons; a voice request gets **Söyle** (say the code) and **Dinle**;
 * - Gönderdim / Yok mark the request and (if on) reply in the group through WhatsApp's own reply
 *   action ("✅ Gönderdim: 0209 13243 46"), so the floor knows without a call;
 * - "Yok" for a model the map put somewhere flags that row for a new sweep.
 *
 * Only that group's notifications are read; only time, sender's name, code and status are kept;
 * nothing leaves the phone except the replies the operator taps.
 */
object Requests {

    enum class Status { NEW, LOOKING, SENT, MISSING;

        /** Still on the waiting list. */
        val open: Boolean get() = this == NEW || this == LOOKING
    }

    data class Request(
        val id: Long,
        val time: Long,
        val sender: String,
        val text: String,
        val voice: Boolean,
        val digits: String?,
        val model: String?,
        val size: String?,
        val answer: String?,
        /** Where the map put it ("B-3"), for flagging the row on "Yok". */
        val code: String?,
        val status: Status = Status.NEW,
        val doneAt: Long = 0L,
        /** Where it came from: null = the WhatsApp group (or typed here), [SOURCE_SHOP] = a shop-floor phone. */
        val source: String? = null,
    )

    /** A request sent by a shop-floor phone over the shop's Wi-Fi (DepotServer). */
    const val SOURCE_SHOP = "shop"

    private const val PREFS = "requests"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REPLY = "reply"
    private const val KEY_GROUP = "group"
    private const val KEY_LIST = "list"
    private const val CHANNEL = "requests_v2"
    private const val OLD_CHANNEL = "requests"
    /** Two short pulses and a long one: not WhatsApp's buzz. */
    private val VIBRATION = longArrayOf(0, 120, 90, 120, 90, 420)
    const val DEFAULT_GROUP = "Ürün isteme"
    private const val KEEP = 60
    /** The same code asked again within this time, while the first is open, is one request. */
    private const val DUPLICATE_MS = 20L * 60 * 1000
    const val ACTION_STATUS = "com.sweepscan.REQUEST_STATUS"
    const val EXTRA_ID = "id"
    const val EXTRA_STATUS = "status"

    /**
     * The request group's reply action, from its latest notification (valid while that is shown),
     * with the chat it belongs to: an answer is only ever written into the request group.
     */
    private class Reply(val chatKey: String, val action: Notification.Action, val rehearsal: Boolean)

    @Volatile private var replyTo: Reply? = null
    /** WhatsApp's intent opening the group chat (from its latest notification). */
    @Volatile private var lastChat: PendingIntent? = null

    /** Changes whenever the queue does: open screens refresh at once. */
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Where the queue is kept: apart from the real one while presenting (see [Presentation]). */
    private fun listPrefs(c: Context) =
        if (Presentation.isOn(c)) c.getSharedPreferences(Presentation.REQUEST_PREFS, Context.MODE_PRIVATE) else prefs(c)

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
    fun replies(c: Context) = prefs(c).getBoolean(KEY_REPLY, true)
    fun setReplies(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_REPLY, on).apply()
    fun group(c: Context): String = prefs(c).getString(KEY_GROUP, null) ?: DEFAULT_GROUP
    fun setGroup(c: Context, name: String) = prefs(c).edit().putString(KEY_GROUP, name.trim()).apply()

    /** Notification access granted to the watcher (Android settings → Bildirim erişimi). */
    fun accessGranted(c: Context) = c.packageName in NotificationManagerCompat.getEnabledListenerPackages(c)

    /** Keeps the request group's reply action (only [Intake.whatsApp] calls this, for the group). */
    fun rememberReply(chatKey: String, action: Notification.Action?, chat: PendingIntent?, rehearsal: Boolean = false) {
        if (action != null) replyTo = Reply(chatKey, action, rehearsal)
        if (chat != null && !rehearsal) lastChat = chat
    }

    /** Drops the kept reply action (presentation mode switched, tests). */
    fun forgetReply() {
        replyTo = null
        lastChat = null
    }

    /** Notification id of a request (ids are longs; a notification takes an int). */
    fun notificationId(id: Long): Int = (id xor (id ushr 32)).toInt()

    // ---- The queue ---------------------------------------------------------------------------

    fun recent(c: Context): List<Request> {
        val arr = runCatching { JSONArray(listPrefs(c).getString(KEY_LIST, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun str(k: String) = o.optString(k).ifEmpty { null }
            Request(
                o.optLong("id", o.getLong("t")), o.getLong("t"), o.optString("s"), o.optString("x"), o.optBoolean("v"),
                str("d"), str("m"), str("z"), str("a"), str("c"),
                runCatching { Status.valueOf(o.optString("st", "NEW")) }.getOrDefault(Status.NEW), o.optLong("dt"),
                str("src"),
            )
        }
    }

    @Synchronized
    private fun save(c: Context, list: List<Request>) {
        val arr = JSONArray(list.take(KEEP).map {
            JSONObject().put("id", it.id).put("t", it.time).put("s", it.sender).put("x", it.text).put("v", it.voice)
                .put("d", it.digits ?: "").put("m", it.model ?: "").put("z", it.size ?: "").put("a", it.answer ?: "")
                .put("c", it.code ?: "").put("st", it.status.name).put("dt", it.doneAt).put("src", it.source ?: "")
        })
        listPrefs(c).edit().putString(KEY_LIST, arr.toString()).apply()
        changes.value = changes.value + 1
        runCatching { com.sweepscan.ui.HomeWidget.update(c) }
    }

    @Synchronized
    private fun record(c: Context, r: Request) = save(c, listOf(r) + recent(c).filter { it.id != r.id })

    /** A request id from its time ([time]·10 + [index]), moved on until no kept request has it. */
    @Synchronized
    private fun newId(c: Context, time: Long, index: Int): Long {
        val taken = recent(c).map { it.id }.toHashSet()
        var id = time * 10 + index
        while (id in taken) id++
        return id
    }

    /** Sent / not there: marks the request, replies in the group, flags the row on "not there". */
    @Synchronized
    fun setStatus(c: Context, id: Long, status: Status): Boolean {
        val list = recent(c)
        val r = list.firstOrNull { it.id == id } ?: return false
        val updated = r.copy(status = status, doneAt = if (status.open) 0L else System.currentTimeMillis())
        save(c, list.map { if (it.id == id) updated else it })
        if (!status.open) NotificationManagerCompat.from(c).cancel(notificationId(id))
        val source = Intake.Source.of(r.source)
        val presenting = Presentation.isOn(c)
        // A real "not there" flags the row for a new sweep (not the presentation's sample ones).
        if (status == Status.MISSING && !presenting) r.code?.let { LocationCode.parseText(it) }?.let { RowFlags.flag(c, it, r.model) }
        // Who hears about it: a shop-floor phone reads the status itself; typed requests have no chat;
        // WhatsApp ones are answered in the group; while presenting, only the rehearsal group is.
        if (!replies(c) || status == Status.NEW || !source.repliesInGroup) return true
        if (presenting != (source == Intake.Source.REHEARSAL)) return true
        return reply(c, replyText(c, r, status), rehearsal = source == Intake.Source.REHEARSAL)
    }

    /** "✅ Gönderdim: 0209 13243 46" and the like. */
    fun replyText(c: Context, r: Request, status: Status): String {
        val what = listOfNotNull(r.model ?: r.digits, r.size).joinToString(" ").ifEmpty { c.getString(R.string.req_voice_short) }
        return c.getString(
            when (status) {
                Status.SENT -> R.string.reply_sent
                Status.MISSING -> R.string.reply_missing
                else -> R.string.reply_looking
            },
            what,
        )
    }

    /**
     * When WhatsApp's notification is gone the reply cannot be sent from here: the text goes to
     * the clipboard, to paste into the chat.
     */
    fun copyReply(c: Context, text: String) {
        val cm = c.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("SweepScan", text))
    }

    /** Opens WhatsApp (the chat if its notification is still there). */
    fun openWhatsApp(c: Context) {
        val chat = lastChat
        if (chat != null) {
            try {
                chat.send()
                return
            } catch (_: PendingIntent.CanceledException) {
                lastChat = null
            }
        }
        val launch = c.packageManager.getLaunchIntentForPackage("com.whatsapp") ?: c.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        launch?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Writes [text] in the group through the reply action of WhatsApp's notification (the same
     * as typing it into the notification's reply field). False if that notification is gone.
     */
    fun reply(c: Context, text: String, rehearsal: Boolean = false): Boolean {
        val to = replyTo ?: return false
        // Only into the request group (and the rehearsal group only for rehearsal requests).
        if (to.rehearsal != rehearsal || !WhatsAppParse.isRequestGroup(to.chatKey, true, group(c))) return false
        val action = to.action
        val inputs = action.remoteInputs ?: return false
        val bundle = Bundle()
        inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, bundle)
        return try {
            action.actionIntent.send(c, 0, fill)
            true
        } catch (e: PendingIntent.CanceledException) {
            replyTo = null
            false
        }
    }

    /** A new message in the group: answer it. [open] = WhatsApp's own intent for the chat. */
    fun handle(
        c: Context, time: Long, sender: String, text: String, open: PendingIntent?, notify: Boolean = true,
        source: String? = null, done: ((List<Request>) -> Unit)? = null,
    ) {
        val app = c.applicationContext as SweepScanApp
        if (RequestParser.isVoice(text)) {
            val r = Request(newId(c, time, 0), time, sender, text, true, null, null, null, null, null, source = source)
            record(c, r)
            if (notify) notifyVoice(c, r, open)
            done?.invoke(listOf(r))
            return
        }
        val wanted = RequestParser.parse(text)
        if (wanted.isEmpty()) { done?.invoke(emptyList()); return }
        val openNow = recent(c).filter { it.status.open && time - it.time < DUPLICATE_MS }
        val waiting = openNow.mapNotNull { it.digits }.toSet()
        val fresh = wanted.filter { it.digits !in waiting }
        // Asked again while still waiting: the waiting one is the answer.
        val again = openNow.filter { r -> wanted.any { it.digits == r.digits } }
        if (fresh.isEmpty()) { done?.invoke(again); return }
        val store = app.locations
        val layout = store.layout
        store.query({ dao -> fresh.map { it to LocationAnswer.find(dao, layout, it.digits) } }) { answers ->
            for ((i, pair) in answers.withIndex()) {
                val (w, a) = pair
                val r = Request(
                    newId(c, time, i), time, sender, text, false, w.digits, a?.model ?: w.model, w.size,
                    a?.let { describe(c, it) }, a?.let { layout.short(it.code) }, source = source,
                )
                record(c, r)
                if (notify) notifyAnswer(c, r, a)
            }
            done?.invoke(again + recent(c).filter { r -> r.time == time && r.digits in fresh.map { it.digits } })
        }
    }

    /** A request told in person or by phone, typed into the queue ("0209 13243 46"). */
    fun addManual(c: Context, text: String, done: () -> Unit) {
        Intake.message(c, Intake.Source.MANUAL, c.getString(R.string.req_manual), text, notify = false)
        // The answer is looked up on the database thread; the queue refreshes after it.
        (c.applicationContext as SweepScanApp).locations.query({ Unit }) { done() }
    }

    /** "Büyük depo · 3. sıra · ortalarda · soldan 5. model". */
    fun describe(c: Context, a: LocationAnswer.Answer): String {
        val app = c.applicationContext as SweepScanApp
        val parts = mutableListOf(app.locations.layout.describe(a.code))
        a.along?.let { f ->
            parts += c.getString(
                when (RowPosition.zone(f)) {
                    RowPosition.Zone.LEFT -> R.string.pos_left
                    RowPosition.Zone.MIDDLE -> R.string.pos_middle
                    RowPosition.Zone.RIGHT -> R.string.pos_right
                },
            )
        }
        a.ordinal?.let { parts += c.getString(R.string.pos_nth_model, it) }
        return parts.joinToString(" · ")
    }

    fun clock(time: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(time))

    // ---- Notifications -----------------------------------------------------------------------

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.req_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = c.getString(R.string.req_channel_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION
                enableLights(true)
                lightColor = 0xFFFFB300.toInt()
            },
        )
    }

    private fun canNotify(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun statusAction(c: Context, r: Request, status: Status, label: Int): NotificationCompat.Action {
        val intent = Intent(c, RequestActionReceiver::class.java).setAction(ACTION_STATUS)
            .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_STATUS, status.name)
        val pi = PendingIntent.getBroadcast(c, notificationId(r.id) * 3 + status.ordinal, intent, flags())
        return NotificationCompat.Action(0, c.getString(label), pi)
    }

    private fun queue(c: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        c, id, Intent(c, RequestsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(),
    )

    // canNotify() checks POST_NOTIFICATIONS first; lint cannot see through the helper.
    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyAnswer(c: Context, r: Request, a: LocationAnswer.Answer?) {
        if (!canNotify(c)) return
        channel(c)
        val id = notificationId(r.id)
        val what = r.model ?: r.digits.orEmpty()
        val who = c.getString(R.string.req_who, r.sender, clock(r.time)) + (r.size?.let { " · " + c.getString(R.string.req_size, it) } ?: "")
        val title = if (a == null) c.getString(R.string.req_not_found, what) else c.getString(R.string.req_found, what, r.answer)
        val body = listOfNotNull(if (a != null && a.places > 1) c.getString(R.string.req_more_places, a.places - 1) else null, who).joinToString(" · ")
        val open = PendingIntent.getActivity(
            c, id,
            Intent(c, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, r.digits).putExtra(LocationActivity.EXTRA_REQUEST, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title + "\n" + body))
            .setWhen(r.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(statusAction(c, r, Status.SENT, R.string.req_sent))
            .addAction(statusAction(c, r, Status.MISSING, R.string.req_missing))
            .build()
        NotificationManagerCompat.from(c).notify(id, n)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyVoice(c: Context, r: Request, open: PendingIntent?) {
        if (!canNotify(c)) return
        channel(c)
        val id = notificationId(r.id)
        val say = PendingIntent.getActivity(
            c, id, Intent(c, VoiceRequestActivity::class.java)
                .putExtra(VoiceRequestActivity.EXTRA_FROM, c.getString(R.string.req_who, r.sender, clock(r.time)))
                .putExtra(VoiceRequestActivity.EXTRA_REQUEST, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags(),
        )
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_pin)
            .setContentTitle(c.getString(R.string.req_voice_title, r.sender))
            .setContentText(c.getString(R.string.req_voice_body, clock(r.time)))
            .setWhen(r.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(queue(c, id))
            .addAction(0, c.getString(R.string.req_say), say)
        if (open != null) b.addAction(0, c.getString(R.string.req_listen), open)
        NotificationManagerCompat.from(c).notify(id, b.build())
    }

    /** A voice request whose code was said: it becomes a normal request with its answer. */
    fun resolveVoice(c: Context, id: Long, w: RequestParser.Wanted) {
        val app = c.applicationContext as SweepScanApp
        val store = app.locations
        val layout = store.layout
        store.query({ dao -> LocationAnswer.find(dao, layout, w.digits) }) { a ->
            val r = recent(c).firstOrNull { it.id == id } ?: return@query
            record(c, r.copy(digits = w.digits, model = a?.model ?: w.model, size = w.size ?: r.size, answer = a?.let { describe(c, it) }, code = a?.let { layout.short(it.code) }))
            NotificationManagerCompat.from(c).cancel(notificationId(id))
        }
    }
}
