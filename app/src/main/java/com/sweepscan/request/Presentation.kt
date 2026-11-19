package com.sweepscan.request

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.sweepscan.SweepScanApp
import com.sweepscan.location.DemoData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Presentation mode, for filming a demo and rehearsing the WhatsApp automation without WhatsApp:
 * the sample map, its own request list, no "sample data" banner, and a **rehearsal group** whose
 * messages arrive by themselves.
 *
 * Each scripted message is built as a WhatsApp group notification and goes through exactly the
 * real path ([WhatsAppParse] → [Intake] → answer, notification, "Gönderdim" → reply action), only
 * the reply action leads to [RehearsalReceiver], which writes the answer into the rehearsal chat
 * shown on the Requests screen. The real queue and the real WhatsApp group are never read, shown
 * or written to while presenting; senders are shop-floor roles, not people.
 */
object Presentation {

    private const val PREFS = "presentation"
    private const val KEY_ON = "on"
    private const val KEY_CHAT = "chat"

    /** Requests kept apart from the real queue while presenting. */
    const val REQUEST_PREFS = "requests_presentation"

    /** Time between two scripted requests. */
    const val EVERY_MS = 40_000L

    const val ACTION_REPLY = "com.sweepscan.REHEARSAL_REPLY"
    const val KEY_REPLY = "reply"
    /** Our own name in the rehearsal chat. */
    const val ME = "SweepScan"

    private const val MAX = 14
    private val SENDERS = listOf("Kasa 1", "Kasa 2", "Reyon", "Vitrin")
    private val handler = Handler(Looper.getMainLooper())
    private var next = 0
    private var running = false

    data class Line(val time: Long, val sender: String, val text: String)

    fun isOn(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** Turns presentation mode on or off (sample map with it); [done] runs when the map is ready. */
    fun set(c: Context, on: Boolean, done: () -> Unit) {
        val app = c.applicationContext as SweepScanApp
        stop()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).remove(KEY_CHAT).apply()
        c.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        // The kept reply action belongs to the other mode now.
        Requests.forgetReply()
        next = 0
        app.setDemoMode(on) {
            if (on) start(app)
            done()
        }
    }

    /** Starts the script if presentation mode is on (app start, or just turned on). */
    fun start(c: Context) {
        if (!isOn(c) || running) return
        running = true
        val app = c.applicationContext
        // One request already answered, to show the history; the first live one soon after.
        if (Requests.recent(app).isEmpty()) {
            send(app, notify = false) { made -> made.firstOrNull()?.let { Requests.setStatus(app, it.id, Requests.Status.SENT) } }
        }
        handler.postDelayed(object : Runnable {
            override fun run() {
                // A dozen requests is a demo; more would only fill the notification shade.
                if (!running || !isOn(app) || next > MAX) { running = false; return }
                send(app, notify = true)
                handler.postDelayed(this, EVERY_MS)
            }
        }, 6_000L)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    /** The rehearsal group's name: the configured group's, so the real matching rule is exercised. */
    fun groupName(c: Context) = Requests.group(c) + " · prova"

    /**
     * A message from [sender] in the rehearsal group, as WhatsApp would show it: a group
     * conversation notification with a reply action (leading to [RehearsalReceiver]).
     */
    fun notification(c: Context, sender: String, text: String, time: Long): Notification {
        val reply = PendingIntent.getBroadcast(
            c, 0, Intent(c, RehearsalReceiver::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, "Yanıtla", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Mesaj").build())
            .build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(ME).build())
            .setConversationTitle(groupName(c))
            .setGroupConversation(true)
            .addMessage(text, time, Person.Builder().setName(sender).build())
        return NotificationCompat.Builder(c, "rehearsal")
            .setSmallIcon(com.sweepscan.R.drawable.ic_pin)
            .setStyle(style)
            .addAction(action)
            .build()
    }

    private fun send(c: Context, notify: Boolean, done: ((List<Requests.Request>) -> Unit)? = null) {
        val m = (next * 5 + 3) % DemoData.MODELS.size
        val size = 36 + (next * 3) % 6
        val sender = SENDERS[next % SENDERS.size]
        next++
        val text = "${DemoData.MODELS[m].first} $size"
        val time = System.currentTimeMillis()
        log(c, Line(time, sender, text))
        val incoming = WhatsAppParse.read(WhatsAppParse.PACKAGES.first(), notification(c, sender, text, time)) ?: return
        Intake.whatsApp(c, incoming, rehearsal = true, notify = notify, done = done)
    }

    // ---- The rehearsal chat ------------------------------------------------------------------

    fun chat(c: Context): List<Line> {
        val arr = runCatching { JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHAT, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Line(it.optLong("t"), it.optString("s"), it.optString("x")) } }
    }

    @Synchronized
    fun log(c: Context, line: Line) {
        val list = (chat(c) + line).takeLast(30)
        val arr = JSONArray(list.map { JSONObject().put("t", it.time).put("s", it.sender).put("x", it.text) })
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CHAT, arr.toString()).apply()
        Requests.changes.value = Requests.changes.value + 1
    }
}

/** The rehearsal group's "WhatsApp": receives the answers the app writes, into the rehearsal chat. */
class RehearsalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Presentation.ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Presentation.KEY_REPLY)?.toString() ?: return
        Presentation.log(context, Presentation.Line(System.currentTimeMillis(), Presentation.ME, text))
    }
}
