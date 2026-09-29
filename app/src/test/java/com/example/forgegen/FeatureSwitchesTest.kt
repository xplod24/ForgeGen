package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 3.4.0: the switches of Settings > Features and the image cache's size. */
class FeatureSwitchesTest {
    @Test
    fun `the Features page says how many switches are on`() {
        // Server Styles is off unless turned on (3.1.0), the others are on.
        assertEquals("10 of 11 on", FeatureSwitches.summary(AppConfig()))
        assertEquals("8 of 11 on", FeatureSwitches.summary(AppConfig(livePreview = false, folderCovers = false, serverStyles = false)))
        assertEquals(11, FeatureSwitches.of(AppConfig()).size)
    }

    @Test
    fun `the image cache is offered in three sizes up to 2_5 GB`() {
        assertEquals(listOf("512 MB", "1 GB", "2.5 GB"), ImageCache.SIZES_MB.map { ImageCache.label(it) })
        assertEquals(2560, ImageCache.sizeOf(null))
        assertEquals(2560, ImageCache.sizeOf(4096))
        assertEquals(1024, ImageCache.sizeOf(1024))
        assertEquals("0 MB", ImageCache.formatBytes(0))
        assertEquals("812 MB", ImageCache.formatBytes(812L * 1024 * 1024))
        assertEquals("1.4 GB", ImageCache.formatBytes(1434L * 1024 * 1024))
    }
}

/** 3.4.0: what the app learned about the models' pictures is kept across starts. */
class ResourcePreviewsSavedTest {
    private val server = "http://192.168.1.20:7860"

    @Before
    fun clean() = ResourcePreviews.forget()

    @Test
    fun `a picture found and a model without one come back after a start`() {
        val a = ResourcePreviews.candidates(server, "/m/a.safetensors")
        val b = ResourcePreviews.candidates(server, "/m/b.safetensors")
        val before = ResourcePreviews.changes.value
        ResourcePreviews.loaded(a, 2)
        var index = 0
        repeat(ResourcePreviews.SUFFIXES.size) { index = ResourcePreviews.missing(b, index) }
        assertEquals("each thing learned is counted, to be saved", before + 2, ResourcePreviews.changes.value)
        ResourcePreviews.loaded(a, 2)
        assertEquals("the same again is not", before + 2, ResourcePreviews.changes.value)

        val saved = ResourcePreviews.saved()
        ResourcePreviews.forget()
        assertNull(ResourcePreviews.known(a))
        ResourcePreviews.restore(saved + ("broken" to 99))
        assertEquals(a[2], ResourcePreviews.known(a))
        assertEquals(ResourcePreviews.NONE, ResourcePreviews.startIndex(b))
        assertEquals("an index no candidate has is left out", 2, ResourcePreviews.saved().size)
    }

    @Test
    fun `what this start learned wins over what was saved`() {
        val a = ResourcePreviews.candidates(server, "/m/a.safetensors")
        ResourcePreviews.loaded(a, 1)
        ResourcePreviews.restore(mapOf(a.first() to ResourcePreviews.NONE))
        assertEquals(a[1], ResourcePreviews.known(a))
    }
}
