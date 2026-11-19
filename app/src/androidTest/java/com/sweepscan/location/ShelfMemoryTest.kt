package com.sweepscan.location

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import com.sweepscan.tracking.Box
import com.sweepscan.ui.LocationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shelf photos on the device: kept, cropped and framed, then shown with the shelf on the answer card. */
@RunWith(AndroidJUnit4::class)
class ShelfMemoryTest {

    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()

    /** A fake 1080×1920 shelf picture with a white label at [box]. */
    private fun frame(box: Box): Bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        c.drawColor(Color.rgb(120, 90, 60))
        c.drawRect(box.left, box.top, box.right, box.bottom, Paint().apply { color = Color.WHITE })
    }

    /** As the camera does: cut the shelf around the label out of the frame, then keep it. */
    private fun save(barcode: String, code: LocationCode, box: Box, time: Long) {
        val f = frame(box)
        val region = ShelfMemory.region(box, f.width, f.height)
        app.shelf.save(barcode, code, 2, ShelfMemory.cut(f, region),
            Box(box.left - region.left, box.top - region.top, box.right - region.left, box.bottom - region.top), time)
    }

    private fun waitFor(barcode: String, time: Long): ShelfMemory.Shot {
        repeat(50) {
            app.shelf.get(barcode)?.takeIf { it.time == time }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("no shot for $barcode")
    }

    @Test
    fun keepsAFramedCropAndShowsItOnTheAnswer() {
        // The real map, not the sample data another test may have left on.
        if (app.demoMode) {
            val off = CountDownLatch(1)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { app.setDemoMode(false) { off.countDown() } }
            assertTrue(off.await(10, TimeUnit.SECONDS))
        }
        val box = Box(480f, 900f, 640f, 960f)
        val code = LocationCode("B", "3", 1)
        val time = System.currentTimeMillis()
        save("4731710551237", code, box, time)
        val shot = waitFor("4731710551237", time)
        assertEquals(code, shot.code)
        assertEquals(2, shot.level)
        val img = BitmapFactory.decodeFile(shot.file.path)
        assertNotNull(img)
        assertTrue("size ${img.width}×${img.height}", img.width in 200..560 && img.height in 100..560)
        assertTrue(shot.file.length() < 120_000)
        // The amber frame is drawn around the label.
        var amber = 0
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val p = img.getPixel(x, y)
            if (Color.red(p) > 220 && Color.green(p) in 150..200 && Color.blue(p) < 60) amber++
        }
        assertTrue("amber $amber", amber > 50)

        // The answer card for the model shows the photo and the shelf.
        val store = app.locations
        store.learnModel("4731710551237", ModelText("0169 051", "KAHVE", "36"), "test")
        val saved = CountDownLatch(1)
        store.saveSnapshot(ColumnRecorder.Snapshot(code, listOf("4731710551237"), System.currentTimeMillis())) { saved.countDown() }
        assertTrue(saved.await(10, TimeUnit.SECONDS))
        // The nearest place may be the small depot (other tests leave the model there too): a photo there as well.
        val kTime = System.currentTimeMillis()
        save("4731710551244", LocationCode("K", "A", 1), box, kTime)
        waitFor("4731710551244", kTime)
        val intent = Intent(app, LocationActivity::class.java).putExtra(LocationActivity.EXTRA_QUERY, "0169 051")
        ActivityScenario.launch<LocationActivity>(intent).use { scenario ->
            var card: View? = null
            for (attempt in 0 until 40) {
                Thread.sleep(200)
                scenario.onActivity { a ->
                    val results = a.findViewById<LinearLayout>(R.id.loc_results)
                    card = (0 until results.childCount).map { results.getChildAt(it) }.firstOrNull {
                        it.findViewById<TextView>(R.id.answer_where) != null && it.findViewById<View>(R.id.answer_photo_box) != null
                    }
                }
                if (card != null) break
            }
            assertNotNull("answer card", card)
            Thread.sleep(800) // the photo is decoded in the background
            scenario.onActivity { a ->
                val where = card!!.findViewById<TextView>(R.id.answer_where).text
                assertEquals("photo on '$where' (${app.shelf.get("4731710551244")}, ${app.shelf.get("4731710551237")})", View.VISIBLE, card!!.findViewById<View>(R.id.answer_photo_box).visibility)
                assertTrue(card!!.findViewById<TextView>(R.id.answer_pos).text.contains("2. raf"))
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(a.findViewById<View>(R.id.loc_sheet)).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            Thread.sleep(1200)
            scenario.onActivity { a ->
                val root = a.window.decorView
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.width, root.height)
                val b = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(b))
                File(a.filesDir, "shot-answer-photo.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
