package com.sweepscan.server

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sweepscan.SweepScanApp
import com.sweepscan.location.ColumnRecorder
import com.sweepscan.location.LocationCode
import com.sweepscan.location.ModelText
import com.sweepscan.request.Requests
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The depot server on the phone, called the way a shop-floor phone calls it ([ShopClient]): the PIN
 * is required, a scanned barcode becomes a request with its place, the status set on the depot
 * comes back, and the panel's page, numbers and 3D scene are served.
 */
@RunWith(AndroidJUnit4::class)
class DepotServerTest {

    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()
    private val port = 18737
    private lateinit var server: DepotServer
    private lateinit var client: ShopClient

    @Before
    fun setUp() {
        if (app.demoMode) {
            val off = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { app.setDemoMode(false) { off.countDown() } }
            off.await(10, TimeUnit.SECONDS)
        }
        val store = app.locations
        store.learnModel("4731710559999", ModelText("0777 12345", "SİYAH", "41"), "test")
        val saved = CountDownLatch(1)
        store.saveSnapshot(ColumnRecorder.Snapshot(LocationCode("B", "5", 1), listOf("4731710559999"), System.currentTimeMillis())) { saved.countDown() }
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        server = DepotServer(app, port).also { it.start() }
        client = ShopClient(app)
        client.address = "127.0.0.1:$port"
        client.pin = DepotServer.pin(app)
        client.name = "Test kasa"
    }

    @After
    fun tearDown() {
        server.stop()
        client.close()
    }

    private fun <T> await(call: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var out: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { call { out = it; latch.countDown() } }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        return out!!
    }

    @Test
    fun shopPhoneAsksAndSeesTheStatus() {
        // Without the PIN: refused.
        val goodPin = client.pin
        client.pin = "0000".takeIf { it != goodPin } ?: "9999"
        val refused = await<JSONObject> { client.requests(it) }
        assertEquals(401, (refused.exceptionOrNull() as ShopClient.Failure).code)
        client.pin = goodPin

        val ping = await<JSONObject> { client.ping(client.address!!, it) }.getOrThrow()
        assertEquals("depot", ping.getString("role"))

        // A scanned shoe: the depot resolves the barcode to its model and finds it in B-5.
        val made = await<JSONObject> { client.send(null, "4731710559999", null, it) }.getOrThrow()
        val r = made.getJSONArray("requests").getJSONObject(0)
        assertEquals("0777 12345", r.getString("model"))
        assertEquals("B-5", r.getString("code"))
        assertEquals("shop", r.getString("source"))
        val id = r.getLong("id")

        // Unknown barcode: a clear error, nothing queued.
        val unknown = await<JSONObject> { client.send(null, "1111111111116", null, it) }
        assertEquals("unknown_barcode", (unknown.exceptionOrNull() as ShopClient.Failure).error)

        // The depot answers; the shop phone sees it.
        assertTrue(Requests.setStatus(app, id, Requests.Status.SENT))
        val list = await<JSONObject> { client.requests(it) }.getOrThrow().getJSONArray("requests")
        val mine = (0 until list.length()).map { list.getJSONObject(it) }.first { it.getLong("id") == id }
        assertEquals("SENT", mine.getString("status"))

        val find = await<JSONObject> { client.find("0777 12345", it) }.getOrThrow()
        assertTrue(find.getBoolean("found"))
        assertEquals("B-5", find.getString("code"))
    }

    @Test
    fun shopPhoneGetsTheDepotsApp() {
        // The ping says which version the depot runs and that its APK can be passed on.
        val ping = await<JSONObject> { client.ping(client.address!!, it) }.getOrThrow()
        val mine = AppUpdate.versionCode(app)
        assertEquals(mine, ping.getLong("code"))
        assertTrue(ping.getBoolean("apk"))
        assertFalse(AppUpdate.isNewer(mine, ping.getLong("code")))
        assertTrue(AppUpdate.isNewer(mine, mine + 1))

        // The download is the depot's own APK, byte for byte, with progress up to 100.
        val to = java.io.File(app.cacheDir, "test-update/app.apk").apply { delete() }
        var last = -1
        val got = await<java.io.File> { client.download(to, { p -> last = p }, it) }.getOrThrow()
        val own = AppUpdate.ownApk(app)!!
        assertEquals(own.length(), got.length())
        assertTrue(own.readBytes().contentEquals(got.readBytes()))
        assertEquals(100, last)
        assertFalse(java.io.File(to.path + ".part").exists())

        // Without the PIN: no APK.
        val goodPin = client.pin
        client.pin = "0000".takeIf { it != goodPin } ?: "9999"
        val refused = await<java.io.File> { client.download(to, {}, it) }
        assertEquals(401, (refused.exceptionOrNull() as ShopClient.Failure).code)
        client.pin = goodPin
        to.delete()
    }

    /**
     * The downloaded APK goes to the system installer, which brings up its own "update this app?"
     * screen (the user's OK is always asked). Cancelled here: nothing is installed.
     */
    @Test
    fun updateBringsUpTheSystemConfirmation() {
        val auto = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(auto.executeShellCommand(cmd)).use { it.readBytes().toString(Charsets.UTF_8) }
        shell("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
        assertTrue(AppUpdate.canInstall(app))
        val apk = java.io.File(app.cacheDir, "test-update/self.apk")
        apk.parentFile!!.mkdirs()
        AppUpdate.ownApk(app)!!.copyTo(apk, overwrite = true)
        AppUpdate.install(app, apk)
        var top = ""
        for (i in 0 until 50) {
            top = shell("dumpsys activity activities").lines().firstOrNull { it.contains("topResumedActivity") }.orEmpty()
            if (top.contains("packageinstaller")) break
            Thread.sleep(200)
        }
        assertTrue("top: $top", top.contains("packageinstaller"))
        shell("input keyevent 4")
        apk.delete()
    }

    /**
     * Keeps the server up for a browser on the PC (instrument with -e serveSeconds 180): a few
     * requests in the queue, the PIN in the log ("DepotPanel"). Does nothing in a normal run.
     */
    @Test
    fun serveForPanel() {
        val seconds = InstrumentationRegistry.getArguments().getString("serveSeconds")?.toIntOrNull() ?: return
        val now = System.currentTimeMillis()
        val done = CountDownLatch(3)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Requests.handle(app, now - 9 * 60_000, "Kasa 1", "0777 12345 41", null, notify = false, source = Requests.SOURCE_SHOP) { done.countDown() }
            Requests.handle(app, now - 4 * 60_000, "Ayşe", "0169 051 kahve 37", null, notify = false) { done.countDown() }
            Requests.handle(app, now - 60_000, "Kasa 2", "0209 13243 46", null, notify = false, source = Requests.SOURCE_SHOP) { done.countDown() }
        }
        done.await(10, TimeUnit.SECONDS)
        Requests.recent(app).firstOrNull { it.digits == "077712345" }?.let { Requests.setStatus(app, it.id, Requests.Status.SENT) }
        android.util.Log.i("DepotPanel", "pin=${DepotServer.pin(app)} port=$port")
        Thread.sleep(seconds * 1000L)
    }

    @Test
    fun serviceStartsAndAnswers() {
        DepotService.start(app)
        try {
            var ping: String? = null
            for (i in 0 until 30) {
                ping = runCatching { java.net.URL("http://127.0.0.1:${DepotServer.PORT}/api/ping").readText() }.getOrNull()
                if (ping != null) break
                Thread.sleep(200)
            }
            assertTrue("ping: $ping", ping?.contains("\"role\":\"depot\"") == true)
            assertTrue(DepotService.running)
            assertTrue(DepotService.address(app)?.endsWith(":${DepotServer.PORT}") == true)
        } finally {
            DepotService.stop(app)
        }
    }

    @Test
    fun panelPageNumbersAndScene() {
        fun get(path: String): Pair<Int, String> {
            val conn = java.net.URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("X-Pin", DepotServer.pin(app))
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
            return code to body
        }
        val page = get("/")
        assertEquals(200, page.first)
        assertTrue(page.second.contains("SweepScan · Depo paneli"))
        val stats = JSONObject(get("/api/stats").second)
        assertTrue(stats.has("today") && stats.getJSONArray("byHour").length() == 24)
        val scene = JSONObject(get("/api/scene").second)
        assertTrue(scene.getJSONArray("opaque").length() > 13 * 5000)
        assertTrue(scene.getJSONObject("shaders").getString("BOX_FS").contains("uFog"))
    }
}
