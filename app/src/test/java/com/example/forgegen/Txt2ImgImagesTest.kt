package com.example.forgegen

import com.google.gson.stream.MalformedJsonException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.util.Base64
import kotlin.random.Random

class Txt2ImgImagesTest {
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun read(json: String): List<ByteArray> {
        val images = mutableListOf<ByteArray>()
        val count =
            Txt2ImgImages.read(StringReader(json)) { index, stream ->
                assertEquals(images.size, index)
                images += stream.readBytes()
            }
        assertEquals(images.size, count)
        return images
    }

    @Test
    fun `images before the other fields, which are skipped whatever they hold`() {
        val a = Random(1).nextBytes(3000)
        val b = Random(2).nextBytes(10)
        val json =
            """{ "images" : ["${b64(a)}", "${b64(b)}"], "parameters": {"prompt": "a \"quoted\" {brace} [bracket]",
            |"nested": [1, 2.5, -3e2, true, false, null, {"x": ["]"]}]}, "info": "line\nnext \\ é"}
            """.trimMargin()
        val images = read(json)
        assertEquals(2, images.size)
        assertArrayEquals(a, images[0])
        assertArrayEquals(b, images[1])
    }

    @Test
    fun `images after the other fields`() {
        val a = Random(3).nextBytes(500)
        val images = read("""{"info":"{\"seed\": 1}","parameters":{},"images":["${b64(a)}"]}""")
        assertArrayEquals(a, images.single())
    }

    @Test
    fun `escaped slashes, line breaks and a data URL prefix`() {
        val a = Random(4).nextBytes(2000)
        val text = b64(a)
        val escaped = text.replace("/", "\\/").chunked(76).joinToString("\\n")
        val images = read("""{"images":["$escaped", "data:image/png;base64,$text"]}""")
        assertArrayEquals(a, images[0])
        assertArrayEquals(a, images[1])
    }

    @Test
    fun `no images`() {
        assertTrue(read("""{"images": [], "info": ""}""").isEmpty())
        assertTrue(read("""{"images": null}""").isEmpty())
        assertTrue(read("""{"info": "x"}""").isEmpty())
        assertTrue(read("""{}""").isEmpty())
    }

    @Test
    fun `a big image across many buffers, and a caller that stops reading early`() {
        val big = Random(5).nextBytes(1_500_000)
        val small = Random(6).nextBytes(100)
        val seen = mutableListOf<ByteArray>()
        Txt2ImgImages.read(StringReader("""{"images":["${b64(big)}","${b64(small)}"]}""")) { index, stream ->
            // The first image is only peeked at; the rest of it must not end up in the second one.
            seen += if (index == 0) stream.readNBytes(10) else stream.readBytes()
        }
        assertArrayEquals(big.copyOf(10), seen[0])
        assertArrayEquals(small, seen[1])
    }

    @Test
    fun `a broken answer is not taken for a broken connection`() {
        for (broken in listOf("""{"images":["abc""", """{"images":["abc"] "info": 1}""", "", """["x"]""", """{"images":[1]}""")) {
            try {
                read(broken)
                fail("accepted: $broken")
            } catch (e: MalformedJsonException) {
                // expected
            }
        }
    }
}
