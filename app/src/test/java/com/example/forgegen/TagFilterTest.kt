package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFilterTest {
    private val prompt = "masterpiece, (Long_Hair:1.2), catgirl, artist \\(style\\), <lora:detail:0.8> blue eyes, BREAK, 1girl"

    @Test
    fun `whole tags as the statistics count them`() {
        assertEquals(
            setOf("masterpiece", "long hair", "catgirl", "artist (style)", "blue eyes", "1girl"),
            TagFilter.tagsOf(prompt),
        )
        assertEquals("long hair", TagFilter.normalize(" (Long_Hair:1.2) "))
        assertEquals("", TagFilter.normalize("BREAK"))
        assertEquals("a new line parts tags", setOf("ugly", "line two"), TagFilter.tagsOf("ugly\nline two"))
    }

    @Test
    fun `exact tags match whole tags only, any case, without weights or underscores`() {
        assertTrue(TagFilter.holdsAll(prompt, listOf("long hair", "Masterpiece"), exact = true))
        assertTrue(TagFilter.holdsAll(prompt, listOf("long_hair", "artist (style)"), exact = true))
        assertFalse("cat is not catgirl", TagFilter.holdsAll(prompt, listOf("cat"), exact = true))
        assertFalse("all of them, not any", TagFilter.holdsAll(prompt, listOf("masterpiece", "red eyes"), exact = true))
        assertFalse("a LoRA is not a tag", TagFilter.holdsAll(prompt, listOf("<lora:detail:0.8>"), exact = true))
    }

    @Test
    fun `without exact a tag is a part of the text, as before`() {
        assertTrue(TagFilter.holdsAll(prompt, listOf("cat"), exact = false))
        assertTrue(TagFilter.holdsAll(prompt, listOf("CAT", "blue"), exact = false))
        assertFalse(TagFilter.holdsAll(prompt, listOf("cat", "dog"), exact = false))
    }

    @Test
    fun `typed text becomes tags`() {
        assertEquals(listOf("1girl", "long hair"), TagFilter.split(" 1girl, long hair,, 1girl ,"))
        assertTrue(TagFilter.split(" , ").isEmpty())
    }

    @Test
    fun `the database narrows the search with a pattern that never misses a match`() {
        assertEquals("%cat%", TagFilter.likePattern(" cat ", exact = false))
        assertEquals("escaped for LIKE", "%100\\% \\_x%", TagFilter.likePattern("100% _x", exact = false))
        // A whole tag looks for its longest word: the prompt may write "long_hair", "(long hair:1.2)" or "\(style\)".
        assertEquals("%artist%", TagFilter.likePattern("artist (style)", exact = true))
        assertEquals("%long%", TagFilter.likePattern("long_hair", exact = true))
        assertEquals("%%", TagFilter.likePattern("^_^", exact = true))
    }
}
