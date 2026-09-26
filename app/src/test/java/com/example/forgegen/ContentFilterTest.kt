package com.example.forgegen

import com.example.forgegen.ContentFilter.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {
    @Test
    fun `prompts are rated by their most explicit word`() {
        assertEquals(Rating.SAFE, ContentFilter.rate("masterpiece, 1girl, castle, sunset"))
        assertEquals(Rating.SUGGESTIVE, ContentFilter.rate("1girl, (lingerie:1.2), bedroom"))
        assertEquals(Rating.EXPLICIT, ContentFilter.rate("1girl, NUDE, beach"))
        assertEquals(Rating.EXPLICIT, ContentFilter.rate("1girl, spread_legs"))
        assertEquals(Rating.EXTREME, ContentFilter.rate("1girl, nude, guro"))
        assertEquals(Rating.UNKNOWN, ContentFilter.rate(null))
    }

    @Test
    fun `words inside other words do not count`() {
        assertEquals(Rating.SAFE, ContentFilter.rate("glass bottle, button, breastplate, analog photo, Dickens, unisex, cumulus clouds"))
        assertEquals(Rating.SAFE, ContentFilter.rate("childish drawing, badass knight, 18yo woman, 25 years old"))
    }

    @Test
    fun `SFW blocks every NSFW tag, NSFW only the extreme ones, Unrestricted none`() {
        val suggestive = "1girl, cleavage"
        val explicit = "1girl, nude"
        val extreme = "1girl, gore"
        assertEquals(listOf("cleavage"), ContentFilter.check(suggestive, CONTENT_SFW)?.terms)
        assertEquals(listOf("nude"), ContentFilter.check(explicit, CONTENT_SFW)?.terms)
        assertNotNull(ContentFilter.check(extreme, CONTENT_SFW))

        assertNull(ContentFilter.check(suggestive, CONTENT_NSFW))
        assertNull(ContentFilter.check(explicit, CONTENT_NSFW))
        assertEquals(listOf("gore"), ContentFilter.check(extreme, CONTENT_NSFW)?.terms)

        assertNull(ContentFilter.check(extreme, CONTENT_UNRESTRICTED))
        assertNull(ContentFilter.check("masterpiece, landscape", CONTENT_SFW))
    }

    @Test
    fun `a LoRA name counts like a word`() {
        assertNotNull(ContentFilter.check("1girl, <lora:nsfw_poses_v2:0.8>", CONTENT_SFW))
        assertNull(ContentFilter.check("1girl, <lora:detail_tweaker:0.8>", CONTENT_SFW))
    }

    @Test
    fun `sexual content with a minor is refused in every mode`() {
        for (mode in listOf(CONTENT_SFW, CONTENT_NSFW, CONTENT_UNRESTRICTED)) {
            val verdict = ContentFilter.check("loli, nude", mode)
            assertNotNull(mode, verdict)
            assertTrue(verdict!!.reason.contains("minor"))
            assertNotNull(mode, ContentFilter.check("1girl, 15 years old, lingerie", mode))
            assertNotNull(mode, ContentFilter.check("1girl, 12yo, nsfw", mode))
            assertNotNull(mode, ContentFilter.check("teenager, sex", mode))
        }
        assertEquals(Rating.FORBIDDEN, ContentFilter.rate("child, naked"))
        assertNull("no sexual word", ContentFilter.check("child, playground, sunny", CONTENT_UNRESTRICTED))
    }

    @Test
    fun `nudity with a real person's LoRA is refused in every mode`() {
        val people = setOf("celebrity_x")
        assertNotNull(ContentFilter.check("<lora:celebrity_x:1>, nude", CONTENT_UNRESTRICTED, people))
        assertNull("the same LoRA, clothed", ContentFilter.check("<lora:celebrity_x:1>, suit, office", CONTENT_UNRESTRICTED, people))
        assertNull("not a real person", ContentFilter.check("<lora:elf_style:1>, nude", CONTENT_UNRESTRICTED, people))
    }

    @Test
    fun `images are blurred by mode, forbidden ones always`() {
        assertTrue("SFW blurs even a safe prompt", ContentFilter.blurs(Rating.SAFE, CONTENT_SFW))
        assertTrue(ContentFilter.blurs(Rating.UNKNOWN, CONTENT_SFW))
        assertFalse(ContentFilter.blurs(Rating.EXPLICIT, CONTENT_NSFW))
        assertTrue(ContentFilter.blurs(Rating.EXTREME, CONTENT_NSFW))
        assertFalse(ContentFilter.blurs(Rating.EXTREME, CONTENT_UNRESTRICTED))
        assertTrue(ContentFilter.blurs(Rating.FORBIDDEN, CONTENT_UNRESTRICTED))
        assertFalse(ContentFilter.canReveal(Rating.FORBIDDEN))
        assertTrue(ContentFilter.canReveal(Rating.EXTREME))
    }

    @Test
    fun `hidden words are masked in text`() {
        assertEquals("1girl, ••••, beach", ContentFilter.mask("1girl, nude, beach", CONTENT_SFW))
        assertEquals("1girl, nude, beach", ContentFilter.mask("1girl, nude, beach", CONTENT_NSFW))
        assertEquals("1girl, nude, ••••", ContentFilter.mask("1girl, nude, gore", CONTENT_NSFW))
        assertEquals("1girl, nude, gore", ContentFilter.mask("1girl, nude, gore", CONTENT_UNRESTRICTED))
        assertTrue(ContentFilter.isHidden("(large_breasts:1.2)", CONTENT_SFW))
        assertFalse(ContentFilter.isHidden("(large_breasts:1.2)", CONTENT_NSFW))
    }

    @Test
    fun `Civitai previews follow the mode`() {
        val images =
            listOf(
                CivitaiImage("xxx", 16),
                CivitaiImage("r", 4),
                CivitaiImage("minor", 4, minor = true),
                CivitaiImage("blocked", 32),
                CivitaiImage("pg", 1),
            )
        assertEquals("pg", ContentFilter.pickCivitaiPreview(images, CONTENT_SFW)?.url)
        assertEquals("r", ContentFilter.pickCivitaiPreview(images, CONTENT_NSFW)?.url)
        assertEquals("xxx", ContentFilter.pickCivitaiPreview(images, CONTENT_UNRESTRICTED)?.url)
        assertNull(
            ContentFilter.pickCivitaiPreview(listOf(CivitaiImage("blocked", 32), CivitaiImage("minor", 8, true)), CONTENT_UNRESTRICTED),
        )
        assertNull("SFW without a PG image", ContentFilter.pickCivitaiPreview(listOf(CivitaiImage("r", 4)), CONTENT_SFW))

        assertTrue("unknown preview in SFW", ContentFilter.blursPreview(0, CONTENT_SFW))
        assertFalse(ContentFilter.blursPreview(1, CONTENT_SFW))
        assertFalse(ContentFilter.blursPreview(0, CONTENT_NSFW))
        assertEquals("https://civitai.com/", ContentFilter.civitaiBaseUrl(CONTENT_SFW))
        assertEquals("https://civitai.red/", ContentFilter.civitaiBaseUrl(CONTENT_NSFW))
    }
}
