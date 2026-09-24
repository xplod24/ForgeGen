package com.example.forgegen

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.InflaterInputStream

/* ============================================================================
 * PNG METADATA READER
 * Extracts the A1111/Forge "parameters" infotext from a PNG. Pillow (used by the
 * server) writes it as tEXt (Latin-1) when possible and falls back to iTXt (UTF-8,
 * optionally zlib-compressed) for anything else, e.g. Polish characters or emoji.
 * ============================================================================ */
object PngMetadata {
    private const val KEYWORD = "parameters"
    private const val MAX_CHUNK_SIZE = 5 * 1024 * 1024 // Protects against corrupted/malicious chunk sizes (OOM).

    fun readParameters(bytes: ByteArray): String = readParameters(bytes.inputStream())

    /**
     * Streams through the PNG chunks and stops at the image data, so network callers
     * only download the header part of the file. Returns "" when nothing is found.
     */
    fun readParameters(input: InputStream): String {
        try {
            val data = DataInputStream(input)
            data.readFully(ByteArray(8)) // PNG signature
            while (true) {
                val length = data.readInt()
                val typeBytes = ByteArray(4)
                data.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)

                if (length < 0 || length > MAX_CHUNK_SIZE) return ""
                if (type == "IDAT" || type == "IEND") return ""

                if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                    val chunk = ByteArray(length)
                    data.readFully(chunk)
                    skipFully(data, 4) // CRC
                    decodeTextChunk(type, chunk)?.let { return it }
                } else {
                    skipFully(data, length.toLong() + 4)
                }
            }
        } catch (_: EOFException) {
            return ""
        } catch (_: Exception) {
            return ""
        }
    }

    /** Returns the text of a textual chunk when its keyword is "parameters", otherwise null. */
    internal fun decodeTextChunk(
        type: String,
        chunk: ByteArray,
    ): String? {
        val keywordEnd = chunk.indexOf(0.toByte())
        if (keywordEnd <= 0) return null
        if (String(chunk, 0, keywordEnd, Charsets.ISO_8859_1) != KEYWORD) return null

        return when (type) {
            "tEXt" -> String(chunk, keywordEnd + 1, chunk.size - keywordEnd - 1, Charsets.ISO_8859_1)
            "zTXt" -> {
                // keyword \0 compressionMethod compressedText
                if (chunk.size < keywordEnd + 2) return null
                String(inflate(chunk.copyOfRange(keywordEnd + 2, chunk.size)), Charsets.ISO_8859_1)
            }
            "iTXt" -> {
                // keyword \0 compressionFlag compressionMethod languageTag \0 translatedKeyword \0 text
                if (chunk.size < keywordEnd + 3) return null
                val compressed = chunk[keywordEnd + 1].toInt() == 1
                val languageEnd = indexOfZero(chunk, keywordEnd + 3)
                if (languageEnd < 0) return null
                val translatedEnd = indexOfZero(chunk, languageEnd + 1)
                if (translatedEnd < 0) return null
                val text = chunk.copyOfRange(translatedEnd + 1, chunk.size)
                String(if (compressed) inflate(text) else text, Charsets.UTF_8)
            }
            else -> null
        }
    }

    private fun indexOfZero(
        bytes: ByteArray,
        from: Int,
    ): Int {
        for (i in from until bytes.size) if (bytes[i] == 0.toByte()) return i
        return -1
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        InflaterInputStream(bytes.inputStream()).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun skipFully(
        input: InputStream,
        count: Long,
    ) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() == -1) throw EOFException()
                remaining--
            }
        }
    }
}
