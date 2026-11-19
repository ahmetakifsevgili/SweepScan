package com.sweepscan.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * A shop-floor phone's line to the depot phone ([DepotServer]): finds it on the Wi-Fi (mDNS) or
 * uses the address typed in, and calls it with the PIN. Every call runs off the main thread and
 * answers on it.
 */
class ShopClient(context: Context) {

    data class Depot(val address: String, val name: String)

    class Failure(val code: Int, val error: String?) : Exception(error ?: "HTTP $code")

    private val c = context.applicationContext
    private val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null

    /** "192.168.0.8:8737", remembered once found or typed. */
    var address: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(v) = prefs.edit().putString(KEY_ADDRESS, v).apply()

    var pin: String?
        get() = prefs.getString(KEY_PIN, null)
        set(v) = prefs.edit().putString(KEY_PIN, v).apply()

    /** Name shown on the depot's queue ("Ayşe", "Kasa 2"). */
    var name: String
        get() = prefs.getString(KEY_NAME, null) ?: Build.MODEL
        set(v) = prefs.edit().putString(KEY_NAME, v.trim().take(30)).apply()

    /** Ids of the requests this phone sent (to show "my requests"). */
    var mine: Set<Long>
        get() = prefs.getStringSet(KEY_MINE, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet(KEY_MINE, v.sortedDescending().take(40).map { it.toString() }.toSet()).apply()

    /** Looks for a depot on the Wi-Fi; [found] is called for each one (main thread). */
    fun discover(found: (Depot) -> Unit) {
        val nsd = c.getSystemService(NsdManager::class.java) ?: return
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (host.contains(':')) return // IPv6: the IPv4 one comes too
                            main.post { found(Depot("$host:${info.port}", info.serviceName)) }
                        }
                    })
                }
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(DepotServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { c.getSystemService(NsdManager::class.java)?.stopServiceDiscovery(l) }
    }

    fun ping(address: String, done: (Result<JSONObject>) -> Unit) = call("GET", "http://$address/api/ping", null, null, done)

    fun requests(done: (Result<JSONObject>) -> Unit) = api("GET", "/api/requests", null, done)

    fun send(text: String?, barcode: String?, size: String?, done: (Result<JSONObject>) -> Unit) {
        val body = JSONObject().put("from", name)
        text?.let { body.put("text", it) }
        barcode?.let { body.put("barcode", it) }
        size?.let { body.put("size", it) }
        api("POST", "/api/requests", body, done)
    }

    fun find(q: String, done: (Result<JSONObject>) -> Unit) =
        api("GET", "/api/find?q=" + java.net.URLEncoder.encode(q, "UTF-8"), null, done)

    /** The depot's APK into [to] ([progress] 0–100 as it comes, main thread). */
    fun download(to: java.io.File, progress: (Int) -> Unit, done: (Result<java.io.File>) -> Unit) {
        val a = address ?: return done(Result.failure(Failure(0, "no_depot")))
        val pin = pin
        io.execute {
            val result = runCatching {
                val conn = URL("http://$a/api/app").openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 15000
                pin?.let { conn.setRequestProperty("X-Pin", it) }
                try {
                    val code = conn.responseCode
                    if (code >= 400) throw Failure(code, null)
                    val total = conn.contentLengthLong
                    to.parentFile?.mkdirs()
                    val part = java.io.File(to.path + ".part")
                    var got = 0L
                    var shown = -1
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val p = if (total > 0) (got * 100 / total).toInt() else 0
                                if (p != shown) { shown = p; main.post { progress(p) } }
                            }
                        }
                    }
                    // A cut connection must not leave half an APK to install.
                    if (total > 0 && got != total) { part.delete(); throw Failure(0, "short") }
                    to.delete()
                    if (!part.renameTo(to)) throw Failure(0, "rename")
                    to
                } finally {
                    conn.disconnect()
                }
            }
            main.post { done(result) }
        }
    }

    private fun api(method: String, path: String, body: JSONObject?, done: (Result<JSONObject>) -> Unit) {
        val a = address ?: return done(Result.failure(Failure(0, "no_depot")))
        call(method, "http://$a$path", body, pin, done)
    }

    private fun call(method: String, url: String, body: JSONObject?, pin: String?, done: (Result<JSONObject>) -> Unit) {
        io.execute {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 3000
                conn.readTimeout = 10000
                pin?.let { conn.setRequestProperty("X-Pin", it) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                conn.disconnect()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (code >= 400) throw Failure(code, json.optString("error").ifEmpty { null })
                json
            }
            main.post { done(result) }
        }
    }

    fun close() {
        stopDiscovery()
        io.shutdown()
    }

    companion object {
        private const val PREFS = "shop_client"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PIN = "pin"
        private const val KEY_NAME = "name"
        private const val KEY_MINE = "mine"
        private const val KEY_ROLE = "role_shop"

        /** This phone is a shop-floor phone: the app opens on the shop screen. */
        fun isShopPhone(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROLE, false)

        fun setShopPhone(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROLE, on).apply()
    }
}
