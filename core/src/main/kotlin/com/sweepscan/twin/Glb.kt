package com.sweepscan.twin

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes [TwinMesh] parts as one binary glTF 2.0 file (.glb), the format Filament's gltfio loads
 * directly. Each part is a node with one mesh; materials follow the part's look:
 *
 * - SOLID / FLOOR: vertex colours, rough plastic / matte floor;
 * - GLOW / PIN: vertex colours plus a strong amber emission (blooms; the app pulses it);
 * - ROUTE: blue emission;
 * - PHOTO: the shelf photo as an unlit texture (KHR_materials_unlit), embedded as JPEG.
 *
 * Plain JVM code so its output can be checked in unit tests.
 */
object Glb {

    private const val ARRAY_BUFFER = 34962
    private const val ELEMENT_ARRAY_BUFFER = 34963
    private const val FLOAT = 5126
    private const val UNSIGNED_INT = 5125

    /** Linear RGB of the found place's glow (amber) and of the route (blue). */
    val GLOW_RGB = floatArrayOf(1.0f, 0.42f, 0.0f)
    val ROUTE_RGB = floatArrayOf(0.09f, 0.45f, 0.82f)

    fun write(parts: List<TwinMesh.Part>, photoJpeg: ByteArray? = null): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = StringBuilder()
        val accessors = StringBuilder()
        var viewCount = 0
        var accessorCount = 0

        fun align() { while (bin.size() % 4 != 0) bin.write(0) }

        fun view(bytes: ByteArray, target: Int?): Int {
            align()
            val offset = bin.size()
            bin.write(bytes)
            if (viewCount > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":").append(offset).append(",\"byteLength\":").append(bytes.size)
            if (target != null) views.append(",\"target\":").append(target)
            views.append('}')
            return viewCount++
        }

        fun floats(a: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in a) b.putFloat(v)
            return b.array()
        }

        fun accessor(view: Int, componentType: Int, count: Int, type: String, minMax: String? = null): Int {
            if (accessorCount > 0) accessors.append(',')
            accessors.append("{\"bufferView\":").append(view).append(",\"componentType\":").append(componentType)
                .append(",\"count\":").append(count).append(",\"type\":\"").append(type).append('"')
            if (minMax != null) accessors.append(',').append(minMax)
            accessors.append('}')
            return accessorCount++
        }

        val meshes = StringBuilder()
        val nodes = StringBuilder()
        val looks = TwinMesh.Look.entries
        for ((i, p) in parts.withIndex()) {
            val n = p.vertexCount
            val min = FloatArray(3) { Float.MAX_VALUE }
            val max = FloatArray(3) { -Float.MAX_VALUE }
            for (v in 0 until n) for (k in 0 until 3) {
                val x = p.positions[v * 3 + k]
                if (x < min[k]) min[k] = x
                if (x > max[k]) max[k] = x
            }
            val pos = accessor(view(floats(p.positions), ARRAY_BUFFER), FLOAT, n, "VEC3",
                "\"min\":[${min.joinToString(",") { num(it) }}],\"max\":[${max.joinToString(",") { num(it) }}]")
            val nor = accessor(view(floats(p.normals), ARRAY_BUFFER), FLOAT, n, "VEC3")
            val col = accessor(view(floats(p.colors), ARRAY_BUFFER), FLOAT, n, "VEC3")
            val uv = p.uvs?.let { accessor(view(floats(it), ARRAY_BUFFER), FLOAT, n, "VEC2") }
            val ib = ByteBuffer.allocate(p.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in p.indices) ib.putInt(x)
            val idx = accessor(view(ib.array(), ELEMENT_ARRAY_BUFFER), UNSIGNED_INT, p.indices.size, "SCALAR")
            if (i > 0) { meshes.append(','); nodes.append(',') }
            meshes.append("{\"name\":\"").append(p.name).append("\",\"primitives\":[{\"attributes\":{\"POSITION\":").append(pos)
                .append(",\"NORMAL\":").append(nor).append(",\"COLOR_0\":").append(col)
            if (uv != null) meshes.append(",\"TEXCOORD_0\":").append(uv)
            meshes.append("},\"indices\":").append(idx).append(",\"material\":").append(looks.indexOf(p.look)).append("}]}")
            nodes.append("{\"name\":\"").append(p.name).append("\",\"mesh\":").append(i)
            if (p.origin != V3.ZERO) nodes.append(",\"translation\":[").append(num(p.origin.x)).append(',').append(num(p.origin.y)).append(',').append(num(p.origin.z)).append(']')
            nodes.append('}')
        }

        var image = ""
        if (photoJpeg != null) {
            val v = view(photoJpeg, null)
            image = ",\"images\":[{\"bufferView\":$v,\"mimeType\":\"image/jpeg\"}]" +
                ",\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":33071,\"wrapT\":33071}]" +
                ",\"textures\":[{\"source\":0,\"sampler\":0}]"
        }
        align()

        val materials = looks.joinToString(",", "[", "]") { look -> material(look, photoJpeg != null) }
        val json = buildString {
            append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"SweepScan\"}")
            append(",\"extensionsUsed\":[\"KHR_materials_emissive_strength\",\"KHR_materials_unlit\"]")
            append(",\"scene\":0,\"scenes\":[{\"nodes\":[").append(parts.indices.joinToString(",")).append("]}]")
            append(",\"nodes\":[").append(nodes).append(']')
            append(",\"meshes\":[").append(meshes).append(']')
            append(",\"materials\":").append(materials)
            append(",\"accessors\":[").append(accessors).append(']')
            append(",\"bufferViews\":[").append(views).append(']')
            append(",\"buffers\":[{\"byteLength\":").append(bin.size()).append("}]")
            append(image)
            append('}')
        }
        return pack(json, bin.toByteArray())
    }

    private fun material(look: TwinMesh.Look, hasPhoto: Boolean): String {
        fun emissive(rgb: FloatArray, strength: Float) =
            ",\"emissiveFactor\":[${rgb.joinToString(",") { num(it) }}],\"extensions\":{\"KHR_materials_emissive_strength\":{\"emissiveStrength\":${num(strength)}}}"
        return when (look) {
            TwinMesh.Look.SOLID -> "{\"name\":\"solid\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":0.72}}"
            TwinMesh.Look.FLOOR -> "{\"name\":\"floor\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":0.55}}"
            TwinMesh.Look.GLOW -> "{\"name\":\"glow\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":0.5}" + emissive(GLOW_RGB, 0.55f) + "}"
            TwinMesh.Look.PIN -> "{\"name\":\"pin\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":0.3}" + emissive(GLOW_RGB, 3f) + "}"
            TwinMesh.Look.ROUTE -> "{\"name\":\"route\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":0.4}" + emissive(ROUTE_RGB, 1.6f) + "}"
            TwinMesh.Look.PHOTO -> if (hasPhoto) {
                "{\"name\":\"photo\",\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0.0,\"roughnessFactor\":1.0}," +
                    "\"extensions\":{\"KHR_materials_unlit\":{}}}"
            } else {
                "{\"name\":\"photo\",\"pbrMetallicRoughness\":{\"metallicFactor\":0.0,\"roughnessFactor\":1.0}}"
            }
        }
    }

    private fun num(f: Float): String = if (f.isFinite()) f.toString() else "0"

    /** GLB container: 12-byte header, JSON chunk (space padded), BIN chunk (zero padded). */
    private fun pack(json: String, bin: ByteArray): ByteArray {
        var j = json.toByteArray(Charsets.UTF_8)
        if (j.size % 4 != 0) j += ByteArray(4 - j.size % 4) { ' '.code.toByte() }
        val total = 12 + 8 + j.size + 8 + bin.size
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(total)
        b.putInt(j.size).putInt(0x4E4F534A).put(j)
        b.putInt(bin.size).putInt(0x004E4942).put(bin)
        return b.array()
    }
}
