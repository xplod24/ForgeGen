package com.example.forgegen

import com.google.gson.stream.MalformedJsonException
import java.io.InputStream
import java.io.Reader
import java.util.Base64

/* ============================================================================
 * TXT2IMG IMAGES
 * Reads the images of a txt2img answer ({"images": ["<base64>", ...], "parameters": {...}, "info": "..."}) straight
 * from the connection, one image at a time, decoding the base64 text while it arrives. Gson can only hand over a
 * whole string, so each image used to sit in memory as text first (about 1.3 times its size, plus the decoded
 * bytes): tens of MB for a big image. Every other field of the answer is skipped without being kept.
 * ============================================================================ */
object Txt2ImgImages {
    /**
     * Calls [onImage] for each image of the answer with its decoded bytes as a stream (only valid during the call)
     * and returns how many there were. A broken answer throws [MalformedJsonException]; a broken connection throws
     * the reader's IOException.
     */
    fun read(
        reader: Reader,
        onImage: (index: Int, image: InputStream) -> Unit,
    ): Int {
        return readWithInfo(reader, onImage) {}
    }

    fun readWithInfo(reader: Reader, onImage: (Int, InputStream) -> Unit, onInfo: (String) -> Unit): Int {
        val json = Scanner(reader)
        var count = 0
        json.expect('{')
        if (json.peekNonSpace() == '}'.code) return 0
        while (true) {
            json.expect('"')
            val key = json.readShortString()
            json.expect(':')
            if (key == "images" && json.peekNonSpace() == '['.code) {
                json.next()
                count += readImages(json, onImage, count)
            } else if (key == "info" && json.peekNonSpace() == '"'.code) {
                json.next()
                onInfo(json.readShortString(512 * 1024))
            } else {
                json.skipValue() // for "images": null (no images)
            }
            when (json.nextNonSpace()) {
                ','.code -> continue
                '}'.code -> return count
                else -> throw MalformedJsonException("Expected ',' or '}' in the answer")
            }
        }
    }

    private fun readImages(
        json: Scanner,
        onImage: (Int, InputStream) -> Unit,
        first: Int,
    ): Int {
        var count = 0
        if (json.peekNonSpace() == ']'.code) {
            json.next()
            return 0
        }
        while (true) {
            if (json.nextNonSpace() != '"'.code) throw MalformedJsonException("An image is not a string")
            val text = json.stringStream()
            text.skipDataUrlPrefix()
            onImage(first + count, Base64.getMimeDecoder().wrap(text))
            text.drain() // the part the caller did not read
            count++
            when (json.nextNonSpace()) {
                ','.code -> continue
                ']'.code -> return count
                else -> throw MalformedJsonException("Expected ',' or ']' in the images")
            }
        }
    }

    /** A JSON reader over characters with one character of look-back. */
    private class Scanner(
        private val reader: Reader,
    ) {
        private val buffer = CharArray(16 * 1024)
        private var pos = 0
        private var len = 0

        fun next(): Int {
            if (pos >= len) {
                len = reader.read(buffer, 0, buffer.size)
                pos = 0
                if (len <= 0) {
                    len = 0
                    return -1
                }
            }
            return buffer[pos++].code
        }

        /** Steps back over the character just read (always still in the buffer); never after the end (-1). */
        fun back() {
            pos--
        }

        /** The next character that is not white space, without reading it; -1 at the end. */
        fun peekNonSpace(): Int {
            val c = nextNonSpace()
            if (c != -1) back()
            return c
        }

        fun nextNonSpace(): Int {
            while (true) {
                val c = next()
                if (c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code) return c
            }
        }

        fun expect(char: Char) {
            val c = nextNonSpace()
            if (c != char.code) throw MalformedJsonException("Expected '$char' in the answer")
        }

        /** The rest of a string whose opening quote was read, for keys and other short strings. */
        fun readShortString(limit: Int = Int.MAX_VALUE): String {
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    -1 -> throw MalformedJsonException("Unterminated string")
                    '"'.code -> return sb.toString()
                    '\\'.code -> { val decoded = escaped(); if (sb.length < limit) sb.append(decoded) }
                    else -> if (sb.length < limit) sb.append(c.toChar())
                }
            }
        }

        /** The character an escape stands for; the backslash was read. */
        fun escaped(): Char =
            when (val c = next()) {
                'n'.code -> '\n'
                't'.code -> '\t'
                'r'.code -> '\r'
                'b'.code -> '\b'
                'f'.code -> '\u000C'
                'u'.code -> {
                    var code = 0
                    repeat(4) {
                        val digit = Character.digit(next(), 16)
                        if (digit < 0) throw MalformedJsonException("Broken \\u escape")
                        code = code * 16 + digit
                    }
                    code.toChar()
                }
                -1 -> throw MalformedJsonException("Unterminated string")
                else -> c.toChar() // \" \\ \/
            }

        fun skipValue() {
            when (val c = nextNonSpace()) {
                '"'.code -> skipString()
                '{'.code, '['.code -> skipNested()
                -1 -> throw MalformedJsonException("A value is missing")
                else -> {
                    // A number, true, false or null: up to the next separator.
                    if (c == ','.code || c == '}'.code || c == ']'.code) throw MalformedJsonException("A value is missing")
                    while (true) {
                        val d = next()
                        if (d == -1) return
                        val separator = d == ','.code || d == '}'.code || d == ']'.code
                        val space = d == ' '.code || d == '\n'.code || d == '\r'.code || d == '\t'.code
                        if (separator || space) {
                            back()
                            return
                        }
                    }
                }
            }
        }

        private fun skipString() {
            while (true) {
                when (next()) {
                    -1 -> throw MalformedJsonException("Unterminated string")
                    '"'.code -> return
                    '\\'.code -> escaped()
                }
            }
        }

        /** Skips an object or array whose opening bracket was read, with everything inside it. */
        private fun skipNested() {
            var depth = 1
            while (depth > 0) {
                when (next()) {
                    -1 -> throw MalformedJsonException("Unterminated object")
                    '"'.code -> skipString()
                    '{'.code, '['.code -> depth++
                    '}'.code, ']'.code -> depth--
                }
            }
        }

        /** The rest of a string (its opening quote was read) as ASCII bytes, ending at its closing quote. */
        fun stringStream() = StringStream()

        inner class StringStream : InputStream() {
            private var ended = false
            private var pending = -1 // a byte read ahead by skipDataUrlPrefix

            override fun read(): Int {
                if (pending >= 0) return pending.also { pending = -1 }
                if (ended) return -1
                return when (val c = next()) {
                    -1 -> throw MalformedJsonException("Unterminated image")
                    '"'.code -> {
                        ended = true
                        -1
                    }
                    '\\'.code -> escaped().code and 0xFF
                    else -> c and 0xFF
                }
            }

            override fun read(
                b: ByteArray,
                off: Int,
                length: Int,
            ): Int {
                if (length == 0) return 0
                var n = 0
                if (pending >= 0) {
                    b[off] = pending.toByte()
                    pending = -1
                    n = 1
                }
                // Straight from the buffer while it holds plain characters; quotes and escapes go through read().
                while (n < length && !ended) {
                    if (pos < len) {
                        val c = buffer[pos]
                        if (c == '"' || c == '\\') {
                            val single = read()
                            if (single < 0) break
                            b[off + n++] = single.toByte()
                        } else {
                            b[off + n++] = c.code.toByte()
                            pos++
                        }
                    } else {
                        val single = read()
                        if (single < 0) break
                        b[off + n++] = single.toByte()
                    }
                }
                return if (n == 0 && ended) -1 else n
            }

            /** A "data:image/png;base64," prefix, if the server adds one, is not part of the image. */
            fun skipDataUrlPrefix() {
                val first = read()
                if (first != 'd'.code) {
                    pending = first
                    if (first < 0) pending = -1
                    return
                }
                val prefix = StringBuilder("d")
                while (true) {
                    val c = read()
                    if (c < 0 || c == ','.code) return
                    prefix.append(c.toChar())
                    if (prefix.length > 64) throw MalformedJsonException("An image is not base64")
                }
            }

            fun drain() {
                while (read() >= 0) {
                    // skip
                }
            }
        }
    }
}
