package com.example.forgegen

import com.example.forgegen.ContentFilter.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of every content mode (BlockingApi), through ContentFilter as the app uses them. */
class BlockingApiTest {
    private val modes = listOf(CONTENT_SFW, CONTENT_NSFW, CONTENT_UNRESTRICTED)

    @Test
    fun `sexual content with a minor is refused in every mode`() {
        for (mode in modes) {
            val verdict = ContentFilter.check("loli, nude", mode)
            assertNotNull(mode, verdict)
            assertTrue(verdict!!.reason.contains("minor"))
            assertNotNull(mode, ContentFilter.check("1girl, 15 years old, lingerie", mode))
            assertNotNull(mode, ContentFilter.check("1girl, 12yo, nsfw", mode))
            assertNotNull(mode, ContentFilter.check("teenager, sex", mode))
        }
        assertNull("no sexual word", ContentFilter.check("child, playground, sunny", CONTENT_UNRESTRICTED))
        assertEquals(listOf("child", "12yo"), BlockingApi.minorTerms("child, 12yo, 25 years old, 18yo"))
    }

    @Test
    fun `nudity with a real person's LoRA is refused in every mode`() {
        val people = setOf("celebrity_x")
        for (mode in modes) {
            val verdict = ContentFilter.check("<lora:celebrity_x:1>, nude", mode, people)
            assertEquals(mode, listOf("celebrity_x", "nude"), verdict?.terms)
            assertTrue(verdict!!.reason.contains("real person"))
        }
        assertNull("the same LoRA, clothed", ContentFilter.check("<lora:celebrity_x:1>, suit, office", CONTENT_UNRESTRICTED, people))
        assertNull("not a real person", ContentFilter.check("<lora:elf_style:1>, nude", CONTENT_UNRESTRICTED, people))
    }

    @Test
    fun `images made with sexual content with a minor stay hidden in every mode`() {
        assertEquals(Rating.FORBIDDEN, ContentFilter.rate("child, naked"))
        for (mode in modes) assertTrue(mode, ContentFilter.blurs(Rating.FORBIDDEN, mode))
        assertFalse(BlockingApi.canReveal(Rating.FORBIDDEN))
        assertTrue(BlockingApi.canReveal(Rating.EXTREME))
    }

    @Test
    fun `a Civitai image of a minor is a preview only when rated PG`() {
        assertTrue(BlockingApi.allowsCivitaiImage(CivitaiImage("pg", 1, minor = true)))
        assertFalse(BlockingApi.allowsCivitaiImage(CivitaiImage("r", 4, minor = true)))
        assertTrue(BlockingApi.allowsCivitaiImage(CivitaiImage("xxx", 16)))
        assertNull(ContentFilter.pickCivitaiPreview(listOf(CivitaiImage("minor", 8, minor = true)), CONTENT_UNRESTRICTED))
    }
}
