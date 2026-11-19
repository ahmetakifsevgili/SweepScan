package com.sweepscan.scan

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal 8-bit grayscale PNG writer for saving analysed luma frames: opens in the phone gallery
 * and on Windows, no Bitmap round trip. Fast deflate (level 1), filter type 0 on every row.
 */
object GrayPng {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun write(out: OutputStream, pixels: ByteArray, width: Int, height: Int) {
        val data = DataOutputStream(out)
        data.write(SIGNATURE)

        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(width)
            writeInt(height)
            writeByte(8) // bit depth
            writeByte(0) // colour type: grayscale
            writeByte(0) // compression
            writeByte(0) // filter
            writeByte(0) // interlace
        }
        chunk(data, "IHDR", ihdr.toByteArray())

        val compressed = ByteArrayOutputStream(width * height / 2)
        val deflater = Deflater(1)
        try {
            DeflaterOutputStream(compressed, deflater, 64 * 1024).use { z ->
                for (y in 0 until height) {
                    z.write(0)
                    z.write(pixels, y * width, width)
                }
            }
        } finally {
            deflater.end()
        }
        chunk(data, "IDAT", compressed.toByteArray())
        chunk(data, "IEND", ByteArray(0))
        data.flush()
    }

    private fun chunk(out: DataOutputStream, type: String, body: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(body.size)
        out.write(typeBytes)
        out.write(body)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(body)
        out.writeInt(crc.value.toInt())
    }
}
