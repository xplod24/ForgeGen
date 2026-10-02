package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sync's changes put into the index in memory keep it as the database would read it (3.6.0-1). */
class GalleryIndexTest {
    private fun image(
        path: String,
        date: String,
        size: Long = 0,
    ) = IndexedImage(path, path.substringAfterLast('/'), date, "model", "", size)

    private val a = image("/out/d1/00001.png", "2026-10-01 10:00:00")
    private val b = image("/out/d1/00002.png", "2026-10-01 11:00:00")
    private val c = image("/out/d2/00003.png", "2026-10-02 09:00:00")

    // As the database sorts it: the newest first.
    private val index = listOf(c, b, a)

    @Test
    fun `new images go in by date, the newest first`() {
        val d = image("/out/d2/00004.png", "2026-10-02 12:00:00")
        val e = image("/out/d1/00000.png", "2026-09-30 08:00:00")
        assertEquals(listOf(d, c, b, a, e), GalleryIndex.merge(index, emptyList(), listOf(e, d), emptyMap()))
    }

    @Test
    fun `images gone from the server leave it`() {
        assertEquals(listOf(c, a), GalleryIndex.merge(index, listOf(b.fullpath), emptyList(), emptyMap()))
    }

    @Test
    fun `an image read again replaces the one of its path`() {
        val read = b.copy(model = "other")
        val merged = GalleryIndex.merge(index, emptyList(), listOf(read), emptyMap())
        assertEquals(listOf(c, read, a), merged)
        assertEquals("other", merged[1].model)
    }

    @Test
    fun `the last image of a path wins, as the database's REPLACE does`() {
        val first = b.copy(model = "first")
        val second = b.copy(model = "second")
        assertEquals(listOf(c, second, a), GalleryIndex.merge(index, emptyList(), listOf(first, second), emptyMap()))
    }

    @Test
    fun `sizes the listing told are set`() {
        val merged = GalleryIndex.merge(index, emptyList(), emptyList(), mapOf(a.fullpath to 1234L))
        assertEquals(1234L, merged.last().size)
        assertSame("the others stay as they were", c, merged.first())
    }

    @Test
    fun `the same date sorts by name, both descending`() {
        val x = image("/out/d1/a.png", "2026-10-01 10:00:00")
        val y = image("/out/d1/b.png", "2026-10-01 10:00:00")
        assertEquals(listOf(y, x), GalleryIndex.merge(emptyList(), emptyList(), listOf(x, y), emptyMap()))
    }

    @Test
    fun `nothing changed keeps the index as it was`() {
        assertEquals(index, GalleryIndex.merge(index, emptyList(), emptyList(), emptyMap()))
    }

    @Test
    fun `text compares as SQLite does, by code point`() {
        // U+FF5E (a fullwidth tilde) is one UTF-16 unit above a surrogate pair's first unit, but below U+1F600 (an
        // emoji) as a code point and in UTF-8, which SQLite compares.
        val fullwidth = "～"
        val emoji = String(Character.toChars(0x1F600))
        assertTrue("Kotlin alone sorts them the other way", fullwidth > emoji)
        assertTrue(GalleryIndex.byCodePoint(fullwidth, emoji) < 0)
        assertTrue(GalleryIndex.byCodePoint("ab", "abc") < 0)
        assertTrue(GalleryIndex.byCodePoint("abc", "ab") > 0)
        assertEquals(0, GalleryIndex.byCodePoint("2026-10-01", "2026-10-01"))
        assertTrue(GalleryIndex.byCodePoint("2026-10-02", "2026-10-01") > 0)
    }

    @Test
    fun `an entity becomes the index's row`() {
        val entity =
            GalleryImageEntity(
                fullpath = "/out/x.png",
                name = "x.png",
                date = "2026-10-02 10:00:00",
                positivePrompt = "a cat",
                negativePrompt = "",
                model = "m",
                sampler = "Euler",
                seed = "1",
                loras = "detail",
                savedAt = 5,
                size = 99,
            )
        assertEquals(IndexedImage("/out/x.png", "x.png", "2026-10-02 10:00:00", "m", "detail", 99), GalleryIndex.of(entity))
    }
}
