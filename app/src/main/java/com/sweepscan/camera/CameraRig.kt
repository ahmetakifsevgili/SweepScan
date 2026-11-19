package com.sweepscan.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Binds exactly two use cases, Preview + ImageAnalysis (16:9, same field of view, so the overlay
 * maps with one scale + offset), on the back camera.
 *
 * Compared with LifecycleCameraController this gives a session capture callback (per-frame
 * exposure/ISO/focus/zoom, [captureMeta]), avoids a third video stream, and leaves gestures to us:
 * no pinch zoom (preset zoom only), tap-to-focus only when [tapToFocus] is on.
 */
class CameraRig(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    val captureMeta = CaptureMetaBuffer()

    var camera: Camera? = null
        private set
    var caps: CameraCaps? = null
        private set
    /** The analysis size CameraX actually chose (buffer orientation), once bound. */
    var analysisSize: Size? = null
        private set

    @Volatile var tapToFocus = true

    private var provider: ProcessCameraProvider? = null

    /**
     * (Re)binds; [onBound] runs on the main thread after every successful bind, [onOpened] every
     * time the camera opens. CameraX resets zoom to 1× and turns the torch off whenever the camera
     * closes (another screen, screen off), so those must be re-applied in [onOpened]; Camera2
     * interop options survive.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun bind(
        analyzer: ImageAnalysis.Analyzer,
        executor: Executor,
        resolutionShortSide: Int,
        onBound: () -> Unit,
        onOpened: () -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
            preview.surfaceProvider = previewView.surfaceProvider

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector(resolutionShortSide))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(captureMeta)
            val analysis = analysisBuilder.build()
            analysis.setAnalyzer(executor, analyzer)

            p.unbindAll()
            camera?.cameraInfo?.cameraState?.removeObservers(owner)
            val bound = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            camera = bound
            bound.cameraInfo.cameraState.observe(owner) { state ->
                if (state.type == CameraState.Type.OPEN) onOpened()
            }
            caps = CameraCaps.read(context, bound)
            analysisSize = analysis.resolutionInfo?.resolution
            installTapToFocus()
            onBound()
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        provider?.unbindAll()
        camera = null
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    /** Exposure/focus overrides; tap-to-focus only makes sense in the plain continuous mode. */
    fun applyTuning(plan: TuningPlan, zoom: Float, iso: Int?) {
        val bound = camera ?: return
        tapToFocus = plan.focus == FocusPath.CONTINUOUS
        CameraTuning.apply(bound, caps, plan, zoom, iso)
    }

    fun setTorch(on: Boolean) {
        if (camera?.cameraInfo?.hasFlashUnit() == true) camera?.cameraControl?.enableTorch(on)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTapToFocus() {
        previewView.setOnTouchListener { view, event ->
            if (event.action != MotionEvent.ACTION_UP || !tapToFocus) return@setOnTouchListener true
            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            camera?.cameraControl?.startFocusAndMetering(action)
            view.performClick()
            true
        }
    }

    private fun analysisSelector(shortSide: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(shortSide * 16 / 9, shortSide), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        )
        .build()
}
