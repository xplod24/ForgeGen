package com.example.forgegen

import java.io.ByteArrayOutputStream

/* ============================================================================
 * METADATA STRIPPER
 * "Share Without Generation Data": removes the generation data (prompt, seed, model...) that Forge writes into its
 * images, without touching the picture: PNG text chunks, JPEG EXIF/XMP/IPTC and comments, WebP EXIF/XMP.
 * Other files (and damaged ones) are returned unchanged.
 * ============================================================================ */
object MetadataStripper {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // tEXt/iTXt/zTXt hold A1111's "parameters"; eXIf may hold a copy.
    private val PNG_DATA_CHUNKS = setOf("tEXt", "iTXt", "zTXt", "eXIf")

    fun strip(bytes: ByteArray): ByteArray =
        try {
            when {
                bytes.startsWith(PNG_SIGNATURE) -> stripPng(bytes)
                bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> stripJpeg(bytes)
                bytes.size > 12 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> stripWebp(bytes)
                else -> bytes
            }
        } catch (e: IndexOutOfBoundsException) {
            bytes // a damaged file: better shared as it is than not at all
        }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.int32(at: Int) =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private fun ByteArray.int32le(at: Int) =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)

    /** PNG: the chunks are copied except the text ones (each chunk: length, type, data, CRC). */
    private fun stripPng(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, PNG_SIGNATURE.size)
        var at = PNG_SIGNATURE.size
        while (at + 8 <= bytes.size) {
            val length = bytes.int32(at)
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val end = at + 12 + length
            if (length < 0 || end > bytes.size) return bytes
            if (type !in PNG_DATA_CHUNKS) out.write(bytes, at, end - at)
            at = end
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    /**
     * JPEG: the segments before the image data are copied except APP1 (EXIF, XMP), APP13 (IPTC) and comments;
     * everything from the start of the image data on is copied as it is.
     */
    private fun stripJpeg(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        var at = 2
        while (at + 4 <= bytes.size && bytes[at] == 0xFF.toByte()) {
            val marker = bytes[at + 1].toInt() and 0xFF
            if (marker == 0xDA) break // start of scan: the image data follows
            val length = ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val end = at + 2 + length
            if (length < 2 || end > bytes.size) return bytes
            if (marker != 0xE1 && marker != 0xED && marker != 0xFE) out.write(bytes, at, end - at)
            at = end
        }
        out.write(bytes, at, bytes.size - at)
        return out.toByteArray()
    }

    /** WebP: the EXIF and XMP chunks are left out, the RIFF size and the VP8X flags are corrected. */
    private fun stripWebp(bytes: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(bytes.size)
        var at = 12
        while (at + 8 <= bytes.size) {
            val type = String(bytes, at, 4, Charsets.US_ASCII)
            val length = bytes.int32le(at + 4)
            val end = at + 8 + length + (length and 1) // chunks are padded to an even size
            if (length < 0 || end > bytes.size) return bytes
            if (type != "EXIF" && type != "XMP ") {
                val chunk = bytes.copyOfRange(at, end)
                if (type == "VP8X" && chunk.size > 8) chunk[8] = (chunk[8].toInt() and 0x04.inv() and 0x08.inv()).toByte()
                body.write(chunk)
            }
            at = end
        }
        val size = body.size() + 4
        val header =
            byteArrayOf(
                'R'.code.toByte(),
                'I'.code.toByte(),
                'F'.code.toByte(),
                'F'.code.toByte(),
                size.toByte(),
                (size shr 8).toByte(),
                (size shr 16).toByte(),
                (size shr 24).toByte(),
                'W'.code.toByte(),
                'E'.code.toByte(),
                'B'.code.toByte(),
                'P'.code.toByte(),
            )
        return header + body.toByteArray()
    }
}
