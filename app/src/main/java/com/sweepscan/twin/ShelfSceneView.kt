package com.sweepscan.twin

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.filament.Engine
import com.google.android.filament.SwapChain
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.sweepscan.R

/**
 * The 3D answer card: the camera flies from the entrance to the found shelf by itself (no
 * gestures needed), then sways slowly in front of it. Tap to play the flight again. Renders only
 * while it is on screen, and stops a little after landing to save the battery.
 */
class ShelfSceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val texture = TextureView(context)
    private val replay = TextView(context)
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var engine: Engine? = null
    private var shot: ShelfScene? = null
    private var swapChain: SwapChain? = null
    private var displayHelper: DisplayHelper? = null
    private var pending: ShelfShot.Prepared? = null
    /** The shot shown, loaded again if the card comes back to the window. */
    private var current: ShelfShot.Prepared? = null
    private var startNanos = 0L
    private var running = false
    private val density = resources.displayMetrics.density

    /** Called with true when the first frame is on screen (the card can stop showing a placeholder). */
    var onFirstFrame: (() -> Unit)? = null
    private var drawn = false

    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val s = shot ?: return
            val chain = swapChain ?: return
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (startNanos == 0L) startNanos = frameTimeNanos
            val t = (frameTimeNanos - startNanos) / 1e9f
            if (!ui.isReadyToRender) return
            if (s.frame(chain, t, frameTimeNanos) && !drawn) {
                drawn = true
                onFirstFrame?.invoke()
            }
            val p = s.path
            // A while after landing the picture holds still: stop drawing.
            if (p != null && t > p.seconds + IDLE_SECONDS) running = false
        }
    }

    init {
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        replay.apply {
            setText(R.string.shelf3d_replay)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play_small, 0, 0, 0)
            compoundDrawablePadding = (6 * density).toInt()
            setPadding((12 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.glass_capsule)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setOnClickListener { play() }
        }
        addView(replay, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            val m = (10 * density).toInt()
            setMargins(m, m, m, m)
        })
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 16f * density)
        }
        contentDescription = context.getString(R.string.shelf3d_description)
        setOnClickListener { play() }
    }

    /** The shot shown (tests draw it off screen for screenshots). */
    val shown: ShelfShot.Prepared? get() = current

    /** Shows [prepared] and starts its flight. */
    fun show(prepared: ShelfShot.Prepared) {
        pending = prepared
        current = prepared
        if (shot != null) loadPending()
    }

    private fun loadPending() {
        val p = pending ?: return
        val s = shot ?: return
        pending = null
        s.load(p.glb, p.flight)
        replay.visibility = View.VISIBLE
        play()
    }

    /** Plays the flight from the start. */
    fun play() {
        startNanos = 0L
        if (!running && shot != null) {
            running = true
            Choreographer.getInstance().postFrameCallback(frames)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ShelfScene.init()) return
        val e = Engine.create()
        engine = e
        shot = ShelfScene(e)
        displayHelper = DisplayHelper(context)
        ui.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { e.destroySwapChain(it) }
                swapChain = e.createSwapChain(surface)
                getDisplay()?.let { d -> displayHelper?.attach(shot!!.renderer, d) }
            }

            override fun onDetachedFromSurface() {
                displayHelper?.detach()
                swapChain?.let {
                    e.destroySwapChain(it)
                    e.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                shot?.resize(width, height)
            }
        }
        ui.attachTo(texture)
        if (pending == null) pending = current
        drawn = false
        loadPending()
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frames)
        ui.detach()
        shot?.destroy()
        shot = null
        engine?.let { e ->
            swapChain?.let { e.destroySwapChain(it) }
            swapChain = null
            e.destroy()
        }
        engine = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) running = false
    }

    companion object {
        const val IDLE_SECONDS = 25f

        /** Filament needs OpenGL ES 3.0 (every phone since 2014; not some emulators). */
        fun supported(context: Context): Boolean {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return false
            return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        }
    }
}
