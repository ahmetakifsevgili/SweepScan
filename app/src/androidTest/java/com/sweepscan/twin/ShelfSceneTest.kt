package com.sweepscan.twin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.sweepscan.location.DefaultLayout
import com.sweepscan.location.LocationCode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The 3D flight drawn by Filament off screen (works with the phone locked): the start over the
 * entrance, the middle of the flight and the landing in front of the shelf are saved as
 * files/shot-3d-*.png; the landing must show the amber glow of the found boxes.
 */
@RunWith(AndroidJUnit4::class)
class ShelfSceneTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(): Bitmap {
        val b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(90, 80, 70))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 6) {
            p.color = listOf(Color.rgb(176, 138, 90), Color.rgb(230, 226, 218), Color.rgb(47, 74, 115))[i % 3]
            c.drawRect(20f + i * 102f, 60f, 110f + i * 102f, 340f, p)
        }
        p.color = Color.WHITE
        p.textSize = 40f
        c.drawText("0159 2000 38", 200f, 210f, p)
        return b
    }

    @Test
    fun flightRendersOffScreen() {
        assertTrue(ShelfScene.init())
        val layout = DefaultLayout.create()
        val code = LocationCode("B", "3", 1)
        val prepared = ShelfShot.prepare(layout, code, 0.5f, 2, photo())
        assertNotNull(prepared)
        val w = 720
        val h = 540
        val engine = Engine.create()
        val shot = ShelfScene(engine)
        val chain = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE)
        try {
            shot.load(prepared!!.glb, prepared.flight)
            shot.resize(w, h)
            val dir = context.filesDir
            val times = listOf("start" to 0f, "middle" to prepared.flight.seconds * 0.55f, "end" to prepared.flight.seconds + 0.3f)
            var end: Bitmap? = null
            for ((name, t) in times) {
                val bmp = OffscreenRender.frame(engine, shot, chain, w, h, t)
                File(dir, "shot-3d-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("$name frame is empty", spread(bmp) > 20)
                if (name == "end") end = bmp
            }
            // Landing: amber glow in the middle third of the picture.
            val e = end!!
            var amber = 0
            for (y in h / 3 until 2 * h / 3 step 2) for (x in w / 4 until 3 * w / 4 step 2) {
                val c = e.getPixel(x, y)
                if (Color.red(c) > 170 && Color.green(c) in 60..200 && Color.blue(c) < 90) amber++
            }
            assertTrue("no glow in front of the camera ($amber px)", amber > 150)
        } finally {
            engine.destroySwapChain(chain)
            shot.destroy()
            engine.destroy()
        }
    }

    /** How different the brightest and darkest parts are (0 = one flat colour). */
    private fun spread(b: Bitmap): Int {
        var lo = 255
        var hi = 0
        for (y in 0 until b.height step 8) for (x in 0 until b.width step 8) {
            val c = b.getPixel(x, y)
            val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            if (l < lo) lo = l
            if (l > hi) hi = l
        }
        return hi - lo
    }

}
