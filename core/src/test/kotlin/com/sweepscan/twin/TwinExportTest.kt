package com.sweepscan.twin

import com.sweepscan.location.DefaultLayout
import com.sweepscan.location.LocationCode
import com.sweepscan.location.RouteFinder
import org.junit.Test
import java.io.File

/**
 * Writes the 3D scenes (instances, floor, labels, cameras) as a script for
 * `tools/twin_preview.html`, which draws them with the app's own shaders in WebGL2. Lets the look
 * be checked on a PC without a phone. Runs only when TWIN_EXPORT names the output file.
 */
class TwinExportTest {

    @Test
    fun export() {
        val out = System.getenv("TWIN_EXPORT") ?: return
        val layout = DefaultLayout.create()
        val code = LocationCode("B", "3", 1)
        val w = 1080
        val h = 2000
        val inset = 528f
        val scenes = StringBuilder("window.SCENES = [\n")

        fun add(name: String, scene: TwinScene, cam: OrbitCamera) {
            val vp = cam.viewProj(w, h, inset)
            val labels = scene.labels.joinToString(",") { l ->
                val p = Mat4.project(vp, l.at, w, h)
                """{"t":"${l.text}","s":"${l.style}","a":${l.active},"x":${p.x},"y":${p.y},"v":${p.visible}}"""
            }
            scenes.append("{\"name\":\"$name · ${"%.0f".format(cam.distance)} m\",\"w\":$w,\"h\":$h,\"inset\":$inset,")
            scenes.append("\"opaque\":[${scene.opaque.array().joinToString(",")}],")
            scenes.append("\"transparent\":[${scene.transparent.array().joinToString(",")}],")
            scenes.append("\"floor\":[${scene.floor.joinToString(",")}],")
            scenes.append("\"vp\":[${vp.joinToString(",")}],")
            val e = cam.eye
            scenes.append("\"eye\":[${e.x},${e.y},${e.z}],\"fog\":[${cam.fog.joinToString(",")}],\"labels\":[$labels]},\n")
        }

        val status = TwinScene.build(layout, TwinState(scanned = mapOf(code to true, LocationCode("K", "A", 1) to false)))
        val b = status.bounds
        add("overview", status, OrbitCamera.bestOverview(b.minX, b.minZ, b.maxX, b.maxZ, w, h, inset))

        val route = RouteFinder.route(layout, layout.rowOf(code)!!.pointAlong(0.5f))!!.points
        val found = TwinScene.build(layout, TwinState(highlighted = setOf(code), selected = code, markers = mapOf(code to 0.5f), route = route, statusMode = false))
        add("found-overview", found, OrbitCamera.bestOverview(b.minX, b.minZ, b.maxX, b.maxZ, w, h, inset))
        val (p, face) = found.focus[code]!!
        add("found-close", found, OrbitCamera.facing(p, face))

        val big = status.depotBounds["B"]!!
        add("big-depot", status, OrbitCamera.bestOverview(big.minX, big.minZ, big.maxX, big.maxZ, w, h, inset))
        scenes.append("];\n")
        File(out).writeText(scenes.toString())
    }
}
