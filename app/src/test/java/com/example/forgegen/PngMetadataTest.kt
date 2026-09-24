package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * A1111/Forge store the infotext with Pillow: tEXt (Latin-1) when the text fits Latin-1,
 * otherwise iTXt (UTF-8, optionally zlib-compressed).
 */
class PngMetadataTest {
    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(
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
        chunk("IHDR", ByteArray(13))
        chunks.forEach { (type, data) -> chunk(type, data) }
        chunk("IDAT", ByteArray(4))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun tEXt(
        key: String,
        value: String,
    ) = "tEXt" to (key.toByteArray(Charsets.ISO_8859_1) + 0 + value.toByteArray(Charsets.ISO_8859_1))

    private fun iTXt(
        key: String,
        value: String,
        zip: Boolean = false,
    ): Pair<String, ByteArray> {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val text = if (zip) ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(utf8) } }.toByteArray() else utf8
        // keyword \0 compressionFlag compressionMethod languageTag \0 translatedKeyword \0 text
        return "iTXt" to (key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, if (zip) 1 else 0, 0, 0, 0) + text)
    }

    @Test
    fun `reads ASCII parameters from tEXt`() {
        val info = "a cat\nNegative prompt: blurry\nSteps: 20, Seed: 1"
        assertEquals(info, PngMetadata.readParameters(png(tEXt("parameters", info))))
    }

    @Test
    fun `decodes tEXt as Latin-1`() {
        val info = "café, Pokémon, 2×2\nSteps: 20"
        assertEquals(info, PngMetadata.readParameters(png(tEXt("parameters", info))))
    }

    @Test
    fun `reads Polish text from iTXt without the header bytes`() {
        val info = "zażółć gęślą jaźń\nSteps: 20"
        assertEquals(info, PngMetadata.readParameters(png(iTXt("parameters", info))))
    }

    @Test
    fun `reads compressed iTXt`() {
        val info = "łódź nocą\nSteps: 20"
        assertEquals(info, PngMetadata.readParameters(png(iTXt("parameters", info, zip = true))))
    }

    @Test
    fun `skips other text chunks and returns empty string when parameters are missing`() {
        assertEquals("x", PngMetadata.readParameters(png(tEXt("Software", "Forge"), tEXt("parameters", "x"))))
        assertEquals("", PngMetadata.readParameters(png(tEXt("Software", "Forge"))))
        assertEquals("", PngMetadata.readParameters(ByteArray(3)))
    }
}
