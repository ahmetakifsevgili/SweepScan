package com.sweepscan.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.location.ColumnRecorder
import com.sweepscan.location.LabelSheet
import com.sweepscan.location.LocationCode
import com.sweepscan.location.ModelText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The location map on the device (debug build, its own database): two swept columns, a model
 * search that must find both, the label PDF, and the scan screen switched to map mode and back.
 */
@RunWith(AndroidJUnit4::class)
class LocationScreenTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()

    private fun seed() {
        val store = app.locations
        store.learnModel("4731710551237", ModelText("0169 051", "KAHVE", "36"), "test")
        store.learnModel("4731710551244", ModelText("0169 051", "KAHVE", "37"), "test")
        val done = CountDownLatch(2)
        val now = System.currentTimeMillis()
        store.saveSnapshot(ColumnRecorder.Snapshot(LocationCode("B", "3", 1), listOf("4731710551237", "4731710551237", "4731710551244"), now)) { done.countDown() }
        store.saveSnapshot(ColumnRecorder.Snapshot(LocationCode("K", "A", 1), listOf("4731710551244", "9999999999994"), now)) { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    /** The sheet can be closed (button or pushed down); the map then uses the whole screen and a button brings it back. */
    @Test
    fun sheetClosesAndComesBack() {
        seed()
        val intent = Intent(app, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, "0169 051")
        ActivityScenario.launch<LocationActivity>(intent).use { scenario ->
            Thread.sleep(1500)
            var state = 0
            scenario.onActivity { a -> state = BottomSheetBehavior.from(a.findViewById<android.view.View>(R.id.loc_sheet)).state }
            assertEquals(BottomSheetBehavior.STATE_HALF_EXPANDED, state)
            scenario.onActivity { a -> a.findViewById<android.view.View>(R.id.loc_close).performClick() }
            Thread.sleep(800)
            var reopen = 0
            var inset = -1f
            scenario.onActivity { a ->
                state = BottomSheetBehavior.from(a.findViewById<android.view.View>(R.id.loc_sheet)).state
                reopen = a.findViewById<android.view.View>(R.id.loc_reopen).visibility
                inset = a.findViewById<PlanView>(R.id.loc_plan).bottomInset
            }
            assertEquals(BottomSheetBehavior.STATE_HIDDEN, state)
            assertEquals(android.view.View.VISIBLE, reopen)
            assertEquals(0f, inset, 0.5f)
            scenario.onActivity { a -> a.findViewById<android.view.View>(R.id.loc_reopen).performClick() }
            Thread.sleep(800)
            scenario.onActivity { a -> state = BottomSheetBehavior.from(a.findViewById<android.view.View>(R.id.loc_sheet)).state }
            assertEquals(BottomSheetBehavior.STATE_HALF_EXPANDED, state)
        }
    }

    @Test
    fun searchFindsBothColumnsAndDrawsTheRoute() {
        seed()
        val intent = Intent(app, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, "0169 051")
        ActivityScenario.launch<LocationActivity>(intent).use { scenario ->
            var hits = emptySet<LocationCode>()
            for (attempt in 0 until 50) {
                Thread.sleep(200)
                scenario.onActivity { a -> hits = a.findViewById<PlanView>(R.id.loc_plan).highlighted }
                if (hits.size >= 2) break
            }
            assertEquals(setOf(LocationCode("B", "3", 1), LocationCode("K", "A", 1)), hits)
            scenario.onActivity { a ->
                // The answer card names the nearest place in words.
                val results = a.findViewById<LinearLayout>(R.id.loc_results)
                val answer = (0 until results.childCount).map { results.getChildAt(it) }.firstNotNullOfOrNull { it.findViewById<TextView>(R.id.answer_where) }
                assertEquals("Küçük depo · A sırası", answer?.text?.toString())
            }
            scenario.onActivity { a ->
                val plan = a.findViewById<PlanView>(R.id.loc_plan)
                // Nearest first: the small depot is next to the entrance.
                assertEquals(LocationCode("K", "A", 1), plan.selected)
                assertTrue((plan.route?.size ?: 0) >= 2)
                // TalkBack hears the marked places, not just "a picture".
                val said = plan.contentDescription?.toString().orEmpty()
                assertTrue(said, said.startsWith("Depo planı, işaretli yer: ") && "K-A-01" in said && "B-3-01" in said)
                // A location typed in the box selects that column.
                a.findViewById<TextView>(R.id.loc_query).text = "B-3"
            }
            Thread.sleep(800)
            scenario.onActivity { a -> assertEquals(LocationCode("B", "3", 1), a.findViewById<PlanView>(R.id.loc_plan).selected) }
        }
    }

    /** Draws only this app's window (never the phone's screen) into files/<name>.png. */
    /** Presentation mode's flag and request list, without its scripted requests. */
    private fun presenting(on: Boolean) {
        app.getSharedPreferences("presentation", Context.MODE_PRIVATE).edit().putBoolean("on", on).commit()
        app.getSharedPreferences(com.sweepscan.request.Presentation.REQUEST_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun capture(a: android.app.Activity, name: String) {
        val root = a.window.decorView
        // With the phone's screen off the window skips layout passes: lay it out by hand.
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(root.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(root.height, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, root.width, root.height)
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        root.draw(canvas)
        // A window drawing skips the 3D card's surface: draw its shot there, rendered off screen.
        for (v in sceneViews(root)) {
            val shot = v.shown ?: continue
            if (v.width <= 0 || v.height <= 0) continue
            val at = IntArray(2).also { v.getLocationInWindow(it) }
            val pic = runCatching { com.sweepscan.twin.OffscreenRender.render(shot, v.width, v.height, shot.flight.seconds + 0.3f) }.getOrNull() ?: continue
            val r = 16f * a.resources.displayMetrics.density
            val clip = android.graphics.Path().apply {
                addRoundRect(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat(), r, r, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(pic, at[0].toFloat(), at[1].toFloat(), null)
            canvas.restore()
        }
        File(a.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sceneViews(v: android.view.View): List<com.sweepscan.twin.ShelfSceneView> = when (v) {
        is com.sweepscan.twin.ShelfSceneView -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { sceneViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** The four sheet modes on the sample data, saved as files/shot-*.png for a visual check. */
    @Test
    fun captureSampleScreens() {
        val ready = CountDownLatch(1)
        presenting(true)
        instrumentation.runOnMainSync { app.setDemoMode(true) { ready.countDown() } }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        try {
            ActivityScenario.launch(LocationActivity::class.java).use { scenario ->
                Thread.sleep(2500)
                scenario.onActivity { a -> capture(a, "shot-overview") }
                scenario.onActivity { a -> a.findViewById<TextView>(R.id.loc_query).text = "0159 2000" }
                Thread.sleep(2000)
                scenario.onActivity { a -> capture(a, "shot-search") }
                scenario.onActivity { a ->
                    com.google.android.material.bottomsheet.BottomSheetBehavior.from(a.findViewById<android.view.View>(R.id.loc_sheet)).state =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED
                }
                Thread.sleep(1200)
                scenario.onActivity { a -> capture(a, "shot-search-half") }
                scenario.onActivity { a ->
                    app.pickList.clear()
                    a.findViewById<TextView>(R.id.loc_query).text = "B-3"
                }
                Thread.sleep(2000)
                scenario.onActivity { a -> capture(a, "shot-column") }
                scenario.onActivity { a ->
                    listOf(LocationCode("B", "9", 1), LocationCode("K", "A", 1), LocationCode("O", "C", 1), LocationCode("B", "1", 1))
                        .forEach { app.pickList += com.sweepscan.location.PickItem(it, "0159 2000 · SİYAH", setOf("x")) }
                    a.findViewById<android.view.View>(R.id.loc_pick_chip).visibility = android.view.View.VISIBLE
                    a.findViewById<android.view.View>(R.id.loc_pick_chip).performClick()
                }
                Thread.sleep(2000)
                scenario.onActivity { a -> capture(a, "shot-pick") }
            }
        } finally {
            app.pickList.clear()
            val off = CountDownLatch(1)
            instrumentation.runOnMainSync { app.setDemoMode(false) { off.countDown() } }
            presenting(false)
            off.await(10, TimeUnit.SECONDS)
        }
    }

    /** Saves the search screen as files/location-screen.png (only this app's window, never the phone's screen). */
    @Test
    fun captureSearchScreen() {
        seed()
        val intent = Intent(app, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, "0169 051")
        ActivityScenario.launch<LocationActivity>(intent).use { scenario ->
            Thread.sleep(2500)
            scenario.onActivity { a ->
                val root = a.window.decorView
                // With the phone's screen off the window skips layout passes: lay it out by hand.
                root.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(root.width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(root.height, android.view.View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.width, root.height)
                val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bitmap))
                File(a.filesDir, "location-screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val results = a.findViewById<LinearLayout>(R.id.loc_results)
                val scroll = a.findViewById<android.view.View>(R.id.loc_scroll)
                File(a.filesDir, "location-screen.txt").writeText(
                    "scroll ${scroll.top}..${scroll.bottom} pad ${scroll.paddingBottom} results h=${results.height} children=${results.childCount}\n" +
                        (0 until results.childCount).joinToString("\n") { i -> results.getChildAt(i).let { c -> "${c.javaClass.simpleName} ${c.top}..${c.bottom} vis=${c.visibility}" } },
                )
            }
        }
    }

    @Test
    fun labelPdfHasOnePagePerFourLabels() {
        val out = File(app.cacheDir, "labels-test.pdf")
        val codes = app.locations.layout.depot("O")!!.codes()
        val pages = LabelSheet.write(app.locations.layout, codes, out)
        assertEquals((codes.size + 3) / 4, pages)
        assertTrue(out.length() > 1000)
    }

    @Test
    fun mapTaskOpensTheRowGivenByTheRowList() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("guide_shown", true).commit()
        val intent = MainActivity.intent(app, MainActivity.Task.MAP).putExtra(MainActivity.EXTRA_ROW, "B-3")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            Thread.sleep(1500)
            scenario.onActivity { a ->
                assertEquals(a.getString(R.string.task_map), a.findViewById<TextView>(R.id.mode_chip).text.toString())
                assertEquals(a.getString(R.string.map_column, "B-3", 0), a.findViewById<TextView>(R.id.total_text).text.toString())
                // The PC chip belongs to counting a delivery only.
                assertEquals(android.view.View.GONE, a.findViewById<android.view.View>(R.id.status_text).visibility)
                a.findViewById<android.view.View>(R.id.btn_scan).performClick() // pause: the row stays open
            }
            Thread.sleep(300)
            scenario.onActivity { a ->
                assertEquals(a.getString(R.string.map_column, "B-3", 0), a.findViewById<TextView>(R.id.total_text).text.toString())
            }
        }
    }

    /** Home, row list, settings and the introduction on the sample data, saved as files/shot-*.png. */
    @Test
    fun captureAppScreens() {
        val ready = CountDownLatch(1)
        presenting(true)
        instrumentation.runOnMainSync { app.setDemoMode(true) { ready.countDown() } }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        HomeActivity.setOnboarded(app)
        HomeActivity.addRecent(app, "0159 2000")
        // A few requests for the queue and home screens: one waiting from the WhatsApp group, one
        // from a shop-floor phone, one sent.
        val now = System.currentTimeMillis()
        val made = CountDownLatch(3)
        instrumentation.runOnMainSync {
            com.sweepscan.request.Requests.handle(app, now - 3 * 60_000, "Reyon", "0159 2000 39", null, notify = false) { made.countDown() }
            com.sweepscan.request.Requests.handle(app, now - 11 * 60_000, "Kasa 1", "0159 2010 41", null, notify = false,
                source = com.sweepscan.request.Requests.SOURCE_SHOP) { made.countDown() }
            com.sweepscan.request.Requests.handle(app, now - 40 * 60_000, "Kasa 2", "0159 2020 38", null, notify = false,
                source = com.sweepscan.request.Requests.SOURCE_SHOP) { r -> r.firstOrNull()?.let { com.sweepscan.request.Requests.setStatus(app, it.id, com.sweepscan.request.Requests.Status.SENT) }; made.countDown() }
        }
        made.await(10, TimeUnit.SECONDS)
        try {
            for ((cls, name) in listOf(
                HomeActivity::class.java to "shot-home",
                RowListActivity::class.java to "shot-rows",
                SettingsActivity::class.java to "shot-settings",
                OnboardingActivity::class.java to "shot-intro",
                RequestsActivity::class.java to "shot-requests",
                ShopActivity::class.java to "shot-shop",
            )) {
                ActivityScenario.launch(cls).use { scenario ->
                    Thread.sleep(2000)
                    scenario.onActivity { a -> capture(a, name) }
                }
            }
            // The camera screen's controls (the picture itself is a surface a window drawing skips).
            ActivityScenario.launch<MainActivity>(MainActivity.intent(app, MainActivity.Task.MAP).putExtra(MainActivity.EXTRA_ROW, "B-3")).use { scenario ->
                Thread.sleep(2500)
                scenario.onActivity { a -> capture(a, "shot-camera-map") }
            }
            ActivityScenario.launch<MainActivity>(MainActivity.intent(app, MainActivity.Task.COUNT)).use { scenario ->
                Thread.sleep(2500)
                scenario.onActivity { a -> capture(a, "shot-camera-count") }
            }
            // The review screen after a delivery count: a few boxes, one barcode twice.
            instrumentation.runOnMainSync {
                app.session.clear()
                for ((m, z) in listOf(0 to 2, 0 to 2, 1 to 4, 3 to 1, 5 to 3)) app.session.add(com.sweepscan.location.DemoData.barcode(m, z), manual = true)
            }
            ActivityScenario.launch(SessionActivity::class.java).use { scenario ->
                Thread.sleep(2000)
                scenario.onActivity { a -> capture(a, "shot-review") }
            }
            instrumentation.runOnMainSync { app.session.clear() }
            // The home-screen widget, drawn from its RemoteViews.
            instrumentation.runOnMainSync {
                val density = app.resources.displayMetrics.density
                val w = (360 * density).toInt()
                val h = (110 * density).toInt()
                val parent = android.widget.FrameLayout(app)
                val views = android.widget.RemoteViews(app.packageName, R.layout.widget_home)
                views.setTextViewText(R.id.widget_count, "3")
                val v = views.apply(app, parent)
                v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
                v.layout(0, 0, w, h)
                val pad = (16 * density).toInt()
                val bitmap = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(0xFF3B4A5C.toInt())
                canvas.translate(pad.toFloat(), pad.toFloat())
                v.draw(canvas)
                File(app.filesDir, "shot-widget.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val search = Intent(app, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, "0159 2000")
            ActivityScenario.launch<LocationActivity>(search).use { scenario ->
                Thread.sleep(2500)
                scenario.onActivity { a -> capture(a, "shot-answer") }
            }
        } finally {
            val off = CountDownLatch(1)
            instrumentation.runOnMainSync { app.setDemoMode(false) { off.countDown() } }
            presenting(false)
            off.await(10, TimeUnit.SECONDS)
        }
    }
}
