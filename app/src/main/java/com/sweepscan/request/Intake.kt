package com.sweepscan.request

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one way a request gets into the queue, whatever it came from: the WhatsApp group, a message
 * shared from WhatsApp, a shop-floor phone, typed in, or the rehearsal group of presentation mode.
 * Every source goes through the same parsing, duplicate check and answer ([Requests.handle]); what
 * happens when a request is sent or missing depends on its [Source] ([Requests.setStatus]).
 */
object Intake {

    enum class Source(
        /** Stored on the request (null = the WhatsApp group, as before 2.4). */
        val key: String?,
        /** "Gönderdim" / "Yok" is answered in the group it came from. */
        val repliesInGroup: Boolean,
    ) {
        WHATSAPP(null, true),
        SHARE("share", true),
        MANUAL("manual", false),
        SHOP(Requests.SOURCE_SHOP, false),
        REHEARSAL("rehearsal", true);

        companion object {
            fun of(key: String?): Source = entries.firstOrNull { it.key == key } ?: WHATSAPP
        }
    }

    private const val PREFS = "intake"
    private const val KEY_SEEN = "seen"
    /** Messages older than this when first seen are history, not new requests. */
    const val MAX_AGE_MS = 30L * 60 * 1000
    /** How long a message is remembered as handled. */
    private const val SEEN_MS = 2L * 3600 * 1000
    private const val SEEN_MAX = 200

    /** A message from any source. [done] gets the requests it made (or the waiting ones it repeats). */
    fun message(
        c: Context, source: Source, sender: String, text: String, time: Long = System.currentTimeMillis(),
        notify: Boolean = true, done: ((List<Requests.Request>) -> Unit)? = null,
    ) = Requests.handle(c, time, sender, text, null, notify = notify, source = source.key, done = done)

    /**
     * A WhatsApp notification already read by [WhatsAppParse]. Only the request group counts; its
     * reply action is kept for the answers. [rehearsal]: the presentation's simulated group.
     */
    fun whatsApp(
        c: Context, incoming: WhatsAppParse.Incoming, rehearsal: Boolean = false, notify: Boolean = true,
        done: ((List<Requests.Request>) -> Unit)? = null,
    ): Int {
        if (!WhatsAppParse.isRequestGroup(incoming.chatKey, incoming.group, Requests.group(c))) return 0
        Requests.rememberReply(incoming.chatKey, incoming.reply, incoming.open, rehearsal)
        var taken = 0
        for (m in incoming.messages.sortedBy { it.time }) {
            if (!fresh(c, m)) continue
            taken++
            message(c, if (rehearsal) Source.REHEARSAL else Source.WHATSAPP, m.sender, m.text, m.time, notify, done)
        }
        return taken
    }

    /**
     * True once per message, and only for recent ones. Remembers the messages themselves (time,
     * sender, text), so a late one or two in the same millisecond are both taken, and the same
     * notification shown again (with its history) adds nothing.
     */
    @Synchronized
    fun fresh(c: Context, m: WhatsAppParse.Message): Boolean {
        val now = System.currentTimeMillis()
        if (now - m.time > MAX_AGE_MS) return false
        // Before 2.4 only the newest handled time was kept: everything up to it was handled then
        // (so an update does not take the messages still shown again).
        if (m.time <= c.getSharedPreferences("requests", Context.MODE_PRIVATE).getLong("last_time", 0L)) return false
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = runCatching { JSONArray(p.getString(KEY_SEEN, "[]")) }.getOrElse { JSONArray() }
        val key = "${m.time}|${m.sender}|${m.text.trim()}".hashCode().toString()
        val kept = JSONArray()
        var found = false
        for (i in 0 until seen.length()) {
            val o = seen.optJSONObject(i) ?: continue
            if (now - o.optLong("t") > SEEN_MS) continue
            if (o.optString("k") == key) found = true
            kept.put(o)
        }
        if (found) return false
        kept.put(JSONObject().put("k", key).put("t", now))
        while (kept.length() > SEEN_MAX) kept.remove(0)
        p.edit().putString(KEY_SEEN, kept.toString()).apply()
        return true
    }

    /** Forgets handled messages (tests, presentation mode switching). */
    fun forget(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
}
