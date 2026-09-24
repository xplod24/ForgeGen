package com.example.forgegen.ui.components

import com.example.forgegen.ActiveLora
import com.example.forgegen.parseActiveLoras
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptTagsTest {
    @Test
    fun `commas inside brackets do not split a tag`() {
        assertEquals(listOf("a", "(b, c:1.2)", "[d, e]", "<lora:x:0.5>"), parseTags("a, (b, c:1.2), [d, e], <lora:x:0.5>"))
    }

    @Test
    fun `weight is read from every attention syntax`() {
        assertEquals(TagWeight("cat", "1.3"), splitTagWeight(" (cat:1.3) "))
        assertEquals(TagWeight("cat", "1.1"), splitTagWeight("(cat)"))
        assertEquals(TagWeight("red, hat", "1.1"), splitTagWeight("(red, hat)"))
        assertEquals(TagWeight("cat", "1.0"), splitTagWeight("cat"))
        // Two separate groups are not one wrapped tag.
        assertEquals(TagWeight("(a) (b)", "1.0"), splitTagWeight("(a) (b)"))
    }

    @Test
    fun `plain parentheses are treated as weight 1_1 instead of being nested`() {
        assertEquals("(cat:1.2)", adjustTagStrength("(cat)", 0.1f))
        assertEquals("cat", adjustTagStrength("(cat)", -0.1f))
    }

    @Test
    fun `adjusting weight clamps and drops the syntax at 1_0`() {
        assertEquals("(cat:1.1)", adjustTagStrength("cat", 0.1f))
        assertEquals("cat", adjustTagStrength("(cat:1.1)", -0.1f))
        assertEquals("(cat:3.0)", adjustTagStrength("(cat:2.95)", 0.1f))
        assertEquals("(cat:0.1)", adjustTagStrength("(cat:0.1)", -0.1f))
    }

    @Test
    fun `slider value that is almost 1_0 gives the bare tag`() {
        assertEquals("cat", withTagWeight("cat", 0.99999994f))
        assertEquals("(cat:0.8)", withTagWeight("cat", 0.8f))
    }

    @Test
    fun `lora tags with negative weights are recognised`() {
        assertEquals(
            listOf(ActiveLora("detail", 0.8f), ActiveLora("flat", -0.5f)),
            parseActiveLoras("a cat, <lora:detail:0.8>, <lora:flat:-0.5>"),
        )
    }
}
