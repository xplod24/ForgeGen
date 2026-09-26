package com.example.forgegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

class MetadataStripperTest {
    private fun pngChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val d = DataOutputStream(out)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size)
        d.write(t)
        d.write(data)
        d.writeInt(
            CRC32()
                .apply {
                    update(t)
                    update(data)
                }.value
                .toInt(),
        )
    }

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        pngChunk(out, "IHDR", ByteArray(13) { 1 })
        chunks.forEach { (type, data) -> pngChunk(out, type, data) }
        pngChunk(out, "IDAT", byteArrayOf(1, 2, 3))
        pngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun String.ascii() = toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `PNG text chunks are removed, the picture stays`() {
        val withData = png("tEXt" to "parameters\u0000nude, secret prompt".ascii(), "iTXt" to "x\u0000\u0000\u0000\u0000\u0000y".ascii())
        val stripped = MetadataStripper.strip(withData)
        assertFalse(String(stripped, Charsets.ISO_8859_1).contains("secret prompt"))
        assertArrayEquals(png(), stripped)
        assertTrue(String(stripped, Charsets.ISO_8859_1).contains("IDAT"))
    }

    @Test
    fun `JPEG EXIF, IPTC and comments are removed, JFIF and the image data stay`() {
        fun segment(
            marker: Int,
            data: String,
        ): ByteArray {
            val length = data.length + 2
            return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + data.ascii()
        }
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val jfif = segment(0xE0, "JFIF\u0000")
        val scan = segment(0xDA, "scan") + "IMAGEDATA".ascii() + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val jpeg = soi + jfif + segment(0xE1, "Exif\u0000\u0000UserComment: secret") + segment(0xFE, "secret comment") + scan
        assertArrayEquals(soi + jfif + scan, MetadataStripper.strip(jpeg))
    }

    @Test
    fun `WebP EXIF and XMP chunks are removed and the header is corrected`() {
        fun chunk(
            type: String,
            data: ByteArray,
        ): ByteArray {
            val n = data.size
            val padded = if (n % 2 == 1) data + byteArrayOf(0) else data
            return type.ascii() + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + padded
        }

        fun webp(vararg chunks: ByteArray): ByteArray {
            val body = chunks.fold("WEBP".ascii()) { acc, c -> acc + c }
            val n = body.size
            return "RIFF".ascii() + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + body
        }
        val flagsWithMeta = byteArrayOf(0x0C, 0, 0, 0, 1, 0, 0, 1, 0, 0)
        val flagsClean = byteArrayOf(0x00, 0, 0, 0, 1, 0, 0, 1, 0, 0)
        val image = chunk("VP8L", "pixels".ascii())
        val withData = webp(chunk("VP8X", flagsWithMeta), image, chunk("EXIF", "secret exif".ascii()), chunk("XMP ", "secret".ascii()))
        assertArrayEquals(webp(chunk("VP8X", flagsClean), image), MetadataStripper.strip(withData))
    }

    @Test
    fun `other and damaged files are left as they are`() {
        val text = "not an image".ascii()
        assertArrayEquals(text, MetadataStripper.strip(text))
        val broken = png("tEXt" to "parameters\u0000x".ascii()).copyOf(30)
        assertEquals(broken.size, MetadataStripper.strip(broken).size)
    }
}
