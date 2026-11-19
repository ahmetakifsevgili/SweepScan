package com.sweepscan.server

import android.content.Context
import com.sweepscan.SweepScanApp
import com.sweepscan.location.LocationAnswer
import com.sweepscan.location.LocationCode
import com.sweepscan.request.RequestParser
import com.sweepscan.request.Requests
import com.sweepscan.twin.TwinScene
import com.sweepscan.twin.TwinState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The depot phone as a small server on the shop's Wi-Fi: shop-floor phones send requests and
 * watch their status, the shop PC opens the panel (queue, today's numbers, the stock rooms in 3D).
 * No account, no internet: every call but /api/ping carries the depot's 4-digit PIN.
 *
 *  GET  /                         the panel (assets/panel/index.html)
 *  GET  /api/ping                 who is this (no PIN)
 *  GET  /api/requests             the queue, newest first
 *  POST /api/requests             {"from": "Ayşe", "text": "0209 13243 46"} or {"barcode": "…", "size": "…"}
 *  POST /api/requests/{id}/status {"status": "SENT" | "MISSING" | "LOOKING"}
 *  GET  /api/find?q=0209 13243    where a model is
 *  GET  /api/stats                today's numbers
 *  GET  /api/scene                the 3D scene (boxes, floor, labels) for the panel
 *  GET  /api/app                  this phone's APK, for shop phones with an older version
 */
class DepotServer(context: Context, private val port: Int = PORT) {

    private val c = context.applicationContext
    private val app get() = c as SweepScanApp
    private val http = MiniHttp(port, ::route)

    val running get() = http.running

    fun start() = http.start()
    fun stop() = http.stop()

    private fun authorised(r: MiniHttp.Request): Boolean {
        val pin = r.header("x-pin") ?: r.query["pin"]
        return pin == pin(c)
    }

    /**
     * Wrong PINs: after [MAX_TRIES] in a row an address waits [LOCK_MS], and after [MAX_TRIES_ALL]
     * wrong ones from anywhere within [LOCK_MS] every caller waits, so a 4-digit PIN cannot be found
     * by trying them all.
     */
    private val tries = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    private val recentFailures = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    private fun locked(remote: String, now: Long): Boolean {
        while (recentFailures.peekFirst()?.let { now - it > LOCK_MS } == true) recentFailures.pollFirst()
        if (recentFailures.size >= MAX_TRIES_ALL) return true
        val (n, last) = tries[remote] ?: return false
        return n >= MAX_TRIES && now - last < LOCK_MS
    }

    private fun failed(remote: String, now: Long) {
        recentFailures.addLast(now)
        tries.compute(remote) { _, old -> if (old == null || now - old.second > LOCK_MS) 1 to now else (old.first + 1) to now }
    }

    private fun route(r: MiniHttp.Request): MiniHttp.Response {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) return asset("panel/index.html", "text/html; charset=utf-8")
        if (r.path == "/api/ping") {
            return ok(
                JSONObject().put("app", "SweepScan").put("role", "depot").put("name", android.os.Build.MODEL)
                    .put("version", version()).put("code", AppUpdate.versionCode(c)).put("apk", AppUpdate.ownApk(c) != null),
            )
        }
        if (!r.path.startsWith("/api/")) return MiniHttp.Response.text(404, "not found")
        val now = System.currentTimeMillis()
        if (locked(r.remote, now)) return MiniHttp.Response.json(429, JSONObject().put("error", "wait").toString())
        if (!authorised(r)) {
            failed(r.remote, now)
            return MiniHttp.Response.json(401, JSONObject().put("error", "pin").toString())
        }
        tries.remove(r.remote)
        lastClient = r.remote to System.currentTimeMillis()
        val parts = r.path.removePrefix("/api/").split('/')
        return when {
            parts == listOf("requests") && r.method == "GET" -> ok(JSONObject().put("requests", requestsJson(Requests.recent(c))).put("now", System.currentTimeMillis()))
            parts == listOf("requests") && r.method == "POST" -> create(JSONObject(r.text.ifEmpty { "{}" }))
            parts.size == 3 && parts[0] == "requests" && parts[2] == "status" && r.method == "POST" -> status(parts[1], JSONObject(r.text.ifEmpty { "{}" }))
            parts == listOf("find") -> find(r.query["q"].orEmpty())
            parts == listOf("stats") -> ok(stats(Requests.recent(c), System.currentTimeMillis()))
            parts == listOf("scene") -> scene()
            parts == listOf("app") && r.method == "GET" -> apk()
            else -> MiniHttp.Response.text(404, "not found")
        }
    }

    private fun create(body: JSONObject): MiniHttp.Response {
        val from = body.optString("from").trim().take(30).ifEmpty { "Mağaza" }
        var text = body.optString("text").trim().take(200)
        val barcode = body.optString("barcode").filter { it.isDigit() }
        if (barcode.isNotEmpty()) {
            // A scanned shoe: its model and size from the depot's list.
            val model = await { dao -> dao.model(barcode) }
            if (model == null) return MiniHttp.Response.json(404, JSONObject().put("error", "unknown_barcode").toString())
            text = listOfNotNull(model.model, model.color, body.optString("size").ifEmpty { model.size }).joinToString(" ")
        }
        if (RequestParser.parse(text).isEmpty()) return MiniHttp.Response.json(400, JSONObject().put("error", "no_code").toString())
        val latch = CountDownLatch(1)
        var made: List<Requests.Request> = emptyList()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            com.sweepscan.request.Intake.message(c, com.sweepscan.request.Intake.Source.SHOP, from, text) {
                made = it
                latch.countDown()
            }
        }
        // Not answered in time: say so, rather than "asked" for a request that may not be queued.
        if (!latch.await(8, TimeUnit.SECONDS)) return MiniHttp.Response.json(503, JSONObject().put("error", "busy").toString())
        return MiniHttp.Response.json(201, JSONObject().put("requests", requestsJson(made)).toString())
    }

    private fun status(id: String, body: JSONObject): MiniHttp.Response {
        val status = runCatching { Requests.Status.valueOf(body.optString("status")) }.getOrNull()
            ?: return MiniHttp.Response.json(400, JSONObject().put("error", "status").toString())
        val ok = Requests.setStatus(c, id.toLongOrNull() ?: -1L, status)
        return if (ok) ok(JSONObject().put("ok", true)) else MiniHttp.Response.json(404, JSONObject().put("error", "id").toString())
    }

    private fun find(q: String): MiniHttp.Response {
        val wanted = RequestParser.parse(q).firstOrNull()
            ?: return MiniHttp.Response.json(400, JSONObject().put("error", "no_code").toString())
        val layout = app.locations.layout
        val a = await { dao -> LocationAnswer.find(dao, layout, wanted.digits) }
        val o = JSONObject().put("model", wanted.model).put("found", a != null)
        if (a != null) {
            o.put("where", Requests.describe(c, a)).put("code", layout.short(a.code)).put("places", a.places)
                .put("sizes", JSONArray(a.sizes))
        }
        return ok(o)
    }

    private fun scene(): MiniHttp.Response {
        val layout = app.locations.layout
        val scans = await { dao -> dao.scans() }.orEmpty()
        val now = System.currentTimeMillis()
        val scanned = scans.associate { LocationCode(it.depot, it.row, it.col) to (now - it.scannedAt < 7L * DAY) }
        val open = Requests.recent(c).filter { it.status.open }.mapNotNull { r -> r.code?.let { LocationCode.parseText(it) } }.toSet()
        val s = TwinScene.build(layout, TwinState(scanned = scanned, highlighted = open, statusMode = open.isEmpty()), "Giriş")
        val sb = StringBuilder("{\"opaque\":[")
        s.opaque.array().joinTo(sb, ",")
        sb.append("],\"transparent\":[")
        s.transparent.array().joinTo(sb, ",")
        sb.append("],\"floor\":[")
        s.floor.joinTo(sb, ",")
        sb.append("],\"bounds\":[${s.bounds.minX},${s.bounds.minZ},${s.bounds.maxX},${s.bounds.maxZ}],\"labels\":")
        sb.append(JSONArray(s.labels.map { l ->
            JSONObject().put("t", l.text).put("s", l.style.name).put("a", l.active).put("p", JSONArray(listOf(l.at.x.toDouble(), l.at.y.toDouble(), l.at.z.toDouble())))
        }).toString())
        sb.append(",\"shaders\":").append(JSONObject(PanelShaders.SHADERS).toString()).append('}')
        return MiniHttp.Response.json(200, sb.toString())
    }

    /** The app this phone runs, for shop phones with an older one ([AppUpdate]). */
    private fun apk(): MiniHttp.Response {
        val file = AppUpdate.ownApk(c) ?: return MiniHttp.Response.text(404, "not found")
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return MiniHttp.Response.text(404, "not found")
        return MiniHttp.Response(200, "application/vnd.android.package-archive", bytes, mapOf("X-Version-Code" to AppUpdate.versionCode(c).toString()))
    }

    private fun <T> await(work: (com.sweepscan.location.LocationDao) -> T): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        app.locations.query(work) { out = it; latch.countDown() }
        latch.await(8, TimeUnit.SECONDS)
        return out
    }

    private fun asset(name: String, type: String): MiniHttp.Response = runCatching {
        MiniHttp.Response(200, type, c.assets.open(name).use { it.readBytes() })
    }.getOrElse { MiniHttp.Response.text(404, "not found") }

    private fun ok(o: JSONObject) = MiniHttp.Response.json(200, o.toString())

    private fun version() = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    companion object {
        const val PORT = 8737
        private const val MAX_TRIES = 5
        private const val MAX_TRIES_ALL = 30
        private const val LOCK_MS = 60_000L
        const val SERVICE_TYPE = "_sweepscan._tcp."
        private const val PREFS = "depot_server"
        private const val KEY_PIN = "pin"
        private const val DAY = 24L * 60 * 60 * 1000

        /** The last shop phone or browser that called, and when (for the settings line). */
        @Volatile var lastClient: Pair<String, Long>? = null

        /** The depot's PIN: made once, shown in the settings and the server's notification. */
        fun pin(c: Context): String {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString(KEY_PIN, null)?.let { return it }
            val pin = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
            p.edit().putString(KEY_PIN, pin).apply()
            return pin
        }

        fun newPin(c: Context): String {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PIN).apply()
            return pin(c)
        }

        fun requestsJson(list: List<Requests.Request>) = JSONArray(list.map { r ->
            JSONObject().put("id", r.id).put("time", r.time).put("from", r.sender).put("text", r.text)
                .put("voice", r.voice).put("model", r.model ?: "").put("size", r.size ?: "").put("where", r.answer ?: "")
                .put("code", r.code ?: "").put("status", r.status.name).put("doneAt", r.doneAt).put("source", r.source ?: "whatsapp")
        })

        /** Today's numbers: requests, sent, not there, waiting, average minutes to answer. */
        fun stats(list: List<Requests.Request>, now: Long): JSONObject {
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = list.filter { it.time >= start }
            val answered = today.filter { !it.status.open && it.doneAt > it.time }
            val avg = if (answered.isEmpty()) null else answered.map { (it.doneAt - it.time) / 60000.0 }.average()
            val models = today.mapNotNull { it.model }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
            return JSONObject()
                .put("today", today.size)
                .put("sent", today.count { it.status == Requests.Status.SENT })
                .put("missing", today.count { it.status == Requests.Status.MISSING })
                .put("waiting", list.count { it.status.open })
                .put("avgMinutes", avg ?: JSONObject.NULL)
                .put("shop", today.count { it.source == Requests.SOURCE_SHOP })
                .put("top", JSONArray(models.map { JSONObject().put("model", it.key).put("count", it.value) }))
                .put("byHour", JSONArray((0 until 24).map { h -> today.count { Calendar.getInstance().apply { timeInMillis = it.time }.get(Calendar.HOUR_OF_DAY) == h } }))
        }
    }
}
