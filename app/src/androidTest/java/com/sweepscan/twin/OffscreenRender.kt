package com.sweepscan.twin

import android.graphics.Bitmap
import com.google.android.filament.Engine
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Draws a 3D shot off screen (no window needed, so it works with the phone locked). Used by the
 * 3D test and by the screenshot tests, where a window drawing cannot show the 3D card's surface.
 */
object OffscreenRender {

    /** The shot at [seconds] into its flight, [w] × [h] pixels. */
    fun render(prepared: ShelfShot.Prepared, w: Int, h: Int, seconds: Float): Bitmap {
        check(ShelfScene.init())
        val engine = Engine.create()
        val shot = ShelfScene(engine)
        val chain = engine.createSwapChain(w, h, SwapChainFlags.CONFIG_READABLE)
        try {
            shot.load(prepared.glb, prepared.flight)
            shot.resize(w, h)
            return frame(engine, shot, chain, w, h, seconds)
        } finally {
            engine.destroySwapChain(chain)
            shot.destroy()
            engine.destroy()
        }
    }

    /** Renders a few frames at [t] (shadows and anti-aliasing settle) and reads the last back. */
    fun frame(engine: Engine, shot: ShelfScene, chain: SwapChain, w: Int, h: Int, t: Float): Bitmap {
        val buffer = ByteBuffer.allocateDirect(w * h * 4)
        val done = CountDownLatch(1)
        val now = Executor { it.run() }
        repeat(3) { shot.frame(chain, t); engine.flushAndWait() }
        val rendered = shot.frame(chain, t) { r ->
            r.readPixels(0, 0, w, h, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, now) { done.countDown() })
        }
        check(rendered) { "frame not rendered" }
        engine.flushAndWait()
        check(done.await(10, TimeUnit.SECONDS)) { "pixels never came back" }
        buffer.rewind()
        // Filament hands the rows back top first.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
    }
}
