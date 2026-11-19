package com.sweepscan.twin

import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * The flight to a shelf, drawn by Filament: the .glb from [ShelfShot] (racks, boxes, the glowing
 * found place, the route, the pin, the shelf photo), a low sun with soft shadows, sky / floor
 * ambient light, ambient occlusion, bloom on the glow and a little fog in the distance.
 *
 * The same class draws into a window ([ShelfSceneView]) or off screen (device tests on a locked
 * phone): the caller owns the [SwapChain] and calls [frame].
 */
class ShelfScene(val engine: Engine) {

    val renderer: Renderer = engine.createRenderer()
    val view: View = engine.createView()
    private val scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    val camera: Camera = engine.createCamera(cameraEntity)
    private val sun = EntityManager.get().create()
    private val ambient: IndirectLight
    private val background: Skybox = Skybox.Builder().color(BG[0], BG[1], BG[2], 1f).build(engine)
    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine)
    private var asset: FilamentAsset? = null
    private var glow: MaterialInstance? = null
    private var pin = 0
    private var pinOrigin = floatArrayOf(0f, 0f, 0f)

    var path: FlyPath? = null
        private set
    private var width = 1
    private var height = 1

    init {
        view.scene = scene
        view.camera = camera
        // The background is a plain-colour skybox (goes through the same tone mapping as the scene).
        scene.skybox = background
        renderer.clearOptions = renderer.clearOptions.apply { clear = true }
        view.setShadowingEnabled(true)
        view.setShadowType(View.ShadowType.PCF)
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = true
            radius = 0.45f
            intensity = 1.4f
            quality = View.QualityLevel.MEDIUM
        }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = true
            strength = 0.16f
            threshold = true
        }
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
            enabled = true
            sampleCount = 4
        }
        view.fogOptions = view.fogOptions.apply {
            enabled = true
            distance = 9f
            density = 0.025f
            maximumOpacity = 0.85f
            color = floatArrayOf(BG[0], BG[1], BG[2])
        }
        camera.setExposure(1f)

        LightManager.Builder(LightManager.Type.SUN)
            .color(1f, 0.95f, 0.88f)
            .intensity(4.6f)
            .direction(-0.45f, -1f, -0.3f)
            .sunAngularRadius(2f)
            .castShadows(true)
            .build(engine, sun)
        scene.addEntity(sun)
        // Sky above, dark floor below: second-order spherical harmonics (constant + up/down).
        ambient = IndirectLight.Builder()
            .irradiance(2, floatArrayOf(
                0.55f, 0.58f, 0.64f, // L00
                0.20f, 0.21f, 0.25f, // L1-1 (y)
                0f, 0f, 0f, // L10 (z)
                0f, 0f, 0f, // L11 (x)
            ))
            .intensity(1f)
            .build(engine)
        scene.indirectLight = ambient
    }

    /** Shows a new shot: the scene in [glb] and the camera's [flight]. */
    fun load(glb: ByteArray, flight: FlyPath) {
        clear()
        val buffer = ByteBuffer.allocateDirect(glb.size).put(glb).also { it.flip() }
        val a = loader.createAsset(buffer) ?: return
        resources.loadResources(a)
        a.releaseSourceData()
        scene.addEntities(a.entities)
        asset = a
        path = flight
        val rcm = engine.renderableManager
        val g = a.getFirstEntityByName("glow")
        if (g != 0 && rcm.hasComponent(g)) glow = rcm.getMaterialInstanceAt(rcm.getInstance(g), 0)
        pin = a.getFirstEntityByName("pin0")
        if (pin != 0) {
            val tm = engine.transformManager
            val m = FloatArray(16)
            tm.getTransform(tm.getInstance(pin), m)
            pinOrigin = floatArrayOf(m[12], m[13], m[14])
        }
    }

    fun resize(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        view.viewport = Viewport(0, 0, width, height)
        camera.setProjection(FOV, width.toDouble() / height, 0.1, 150.0, Camera.Fov.VERTICAL)
    }

    /** Places the camera and animated parts for [seconds] since the shot began. */
    fun update(seconds: Float) {
        val p = path ?: return
        val (eye, look) = if (seconds <= p.seconds) p.at(seconds) else p.idle(seconds)
        camera.lookAt(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(), look.x.toDouble(), look.y.toDouble(), look.z.toDouble(), 0.0, 1.0, 0.0)
        // The found boxes breathe; the pin turns and bobs.
        val pulse = 0.75f + 0.35f * sin(seconds * 3.2f)
        glow?.setParameter("emissiveFactor", Glb.GLOW_RGB[0] * pulse, Glb.GLOW_RGB[1] * pulse, Glb.GLOW_RGB[2] * pulse)
        if (pin != 0) {
            val tm = engine.transformManager
            val yaw = seconds * 1.6f
            val c = cos(yaw)
            val s = sin(yaw)
            val y = pinOrigin[1] + 0.08f * sin(seconds * 2.6f)
            tm.setTransform(tm.getInstance(pin), floatArrayOf(
                c, 0f, -s, 0f,
                0f, 1f, 0f, 0f,
                s, 0f, c, 0f,
                pinOrigin[0], y, pinOrigin[2], 1f,
            ))
        }
    }

    /** Draws one frame into [swapChain]; false if the GPU was not ready for it. */
    fun frame(swapChain: SwapChain, seconds: Float, frameTimeNanos: Long = 0L, readBack: ((Renderer) -> Unit)? = null): Boolean {
        update(seconds)
        if (!renderer.beginFrame(swapChain, frameTimeNanos)) return false
        renderer.render(view)
        readBack?.invoke(renderer)
        renderer.endFrame()
        return true
    }

    private fun clear() {
        asset?.let {
            scene.removeEntities(it.entities)
            loader.destroyAsset(it)
        }
        asset = null
        glow = null
        pin = 0
        path = null
    }

    fun destroy() {
        clear()
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyIndirectLight(ambient)
        engine.destroySkybox(background)
        engine.destroyEntity(sun)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        EntityManager.get().destroy(sun)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
    }

    companion object {
        const val FOV = 48.0

        /** Background (linear), the app's dark surface. */
        val BG = floatArrayOf(0.0060f, 0.0075f, 0.0100f)

        @Volatile private var ready = false

        /** Loads Filament's native code once; false where it cannot run (no OpenGL ES 3). */
        fun init(): Boolean {
            if (ready) return true
            return runCatching { Gltfio.init(); ready = true }.isSuccess
        }
    }
}
