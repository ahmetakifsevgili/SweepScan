package com.sweepscan.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

class GrayPngTest {

    @Test
    fun `written png has valid chunks and decodes to the same pixels`() {
        val w = 4
        val h = 3
        val pixels = ByteArray(w * h) { (it * 20).toByte() }
        val out = ByteArrayOutputStream()
        GrayPng.write(out, pixels, w, h)

        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        val signature = ByteArray(8).also { input.readFully(it) }
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10), signature)

        val idat = ByteArrayOutputStream()
        val types = ArrayList<String>()
        while (true) {
            val length = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }
            val body = ByteArray(length).also { input.readFully(it) }
            val crc = CRC32().apply { update(type); update(body) }
            assertEquals(crc.value.toInt(), input.readInt())
            val name = String(type, Charsets.US_ASCII)
            types += name
            if (name == "IHDR") {
                val ihdr = DataInputStream(ByteArrayInputStream(body))
                assertEquals(w, ihdr.readInt())
                assertEquals(h, ihdr.readInt())
                assertEquals(8, ihdr.readUnsignedByte())
                assertEquals(0, ihdr.readUnsignedByte())
            }
            if (name == "IDAT") idat.write(body)
            if (name == "IEND") break
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), types)

        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
        assertEquals(h * (w + 1), raw.size)
        for (y in 0 until h) {
            assertEquals(0, raw[y * (w + 1)].toInt()) // filter type 0
            for (x in 0 until w) assertEquals(pixels[y * w + x], raw[y * (w + 1) + 1 + x])
        }
    }
}
