package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extra check before a job is sent (BlockingApi); everything else is up to the server. */
class BlockingApiTest {
    @Test
    fun `adult content and violence are the server's business`() {
        assertNull(BlockingApi.check("1girl, nude, beach"))
        assertNull(BlockingApi.check("1girl, (lingerie:1.2), bedroom, <lora:nsfw_poses:0.8>"))
        assertNull(BlockingApi.check("knight, battle, gore"))
        assertNull("a minor without a sexual word", BlockingApi.check("child, playground, sunny"))
    }

    @Test
    fun `sexual content with a minor is refused`() {
        val verdict = BlockingApi.check("loli, nude")!!
        assertTrue(verdict.reason.contains("minor"))
        assertEquals(listOf("loli", "nude"), verdict.terms)
        assertTrue(BlockingApi.check("1girl, 15 years old, lingerie") != null)
        assertTrue(BlockingApi.check("1girl, 12yo, nsfw") != null)
        assertTrue(BlockingApi.check("teenager, sex") != null)
        assertTrue("underscores count as spaces", BlockingApi.check("young_girl, spread_legs") != null)
    }

    @Test
    fun `words inside other words and adult ages do not count`() {
        assertNull(BlockingApi.check("glass bottle, button, breastplate, analog photo, Dickens, unisex, childish drawing"))
        assertNull(BlockingApi.check("18yo woman, 25 years old, nude"))
        assertEquals(listOf("child", "12yo"), BlockingApi.minorTerms("child, 12yo, 25 years old, 18yo"))
    }

    @Test
    fun `nudity with a real person's LoRA is refused, the same LoRA clothed is not`() {
        val people = setOf("celebrity_x")
        val verdict = BlockingApi.check("<lora:celebrity_x:1>, nude", people)!!
        assertEquals(listOf("celebrity_x", "nude"), verdict.terms)
        assertTrue(verdict.reason.contains("real person"))
        assertNull(BlockingApi.check("<lora:celebrity_x:1>, suit, office", people))
        assertNull("not a real person", BlockingApi.check("<lora:elf_style:1>, nude", people))
    }

    @Test
    fun `a Civitai image of a minor is a preview only when rated PG`() {
        assertTrue(BlockingApi.allowsCivitaiImage(CivitaiImage("pg", 1, minor = true)))
        assertFalse(BlockingApi.allowsCivitaiImage(CivitaiImage("r", 4, minor = true)))
        assertTrue(BlockingApi.allowsCivitaiImage(CivitaiImage("xxx", 16)))
        val images =
            listOf(
                CivitaiImage("blocked", 32),
                CivitaiImage("minor", 8, minor = true),
                CivitaiImage("x", 8),
                CivitaiImage("pg", 1),
            )
        assertEquals("the first one neither Civitai nor rule 3 refuses", "x", CivitaiImage.preview(images)?.url)
        assertNull(CivitaiImage.preview(listOf(CivitaiImage("blocked", 32), CivitaiImage("minor", 4, true))))
    }
}
