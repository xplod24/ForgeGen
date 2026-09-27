package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Gallery tab never leaves the gallery's top folder: its path bar and Back stop there (2.2.0). */
class GalleryFoldersTest {
    private val gallery = ForgeGalleryManager
    private val windowsRoot = "C:\\forge\\outputs\\txt2img-images"

    @Test
    fun `the path bar starts at the gallery's folder`() {
        assertEquals(listOf("Gallery" to "/out"), gallery.breadcrumb("/out", "/out"))
        assertEquals(
            listOf("Gallery" to "/out", "2026-09-27" to "/out/2026-09-27", "a" to "/out/2026-09-27/a"),
            gallery.breadcrumb("/out/2026-09-27/a", "/out"),
        )
        assertEquals(listOf("Gallery" to windowsRoot, "2026-09-27" to "$windowsRoot\\2026-09-27"), gallery.breadcrumb("$windowsRoot\\2026-09-27", windowsRoot))
    }

    @Test
    fun `the path bar writes folders as the server does`() {
        val path = "c:/forge/outputs/txt2img-images/x/y"
        assertEquals(
            listOf("Gallery" to windowsRoot, "x" to "c:/forge/outputs/txt2img-images/x", "y" to path),
            gallery.breadcrumb(path, windowsRoot),
        )
    }

    @Test
    fun `nothing above or beside the gallery's folder is offered`() {
        assertEquals(listOf("Gallery" to "/out"), gallery.breadcrumb("/srv/forge", "/out"))
        assertEquals(listOf("Gallery" to "/out"), gallery.breadcrumb("/output/x", "/out"))
        assertEquals(listOf("Gallery" to windowsRoot), gallery.breadcrumb("C:\\forge\\outputs", windowsRoot))
    }

    @Test
    fun `Back goes up inside the gallery and stops at its folder`() {
        assertNull(gallery.parentFolder("/out", "/out"))
        assertNull(gallery.parentFolder("/out/", "/out"))
        assertEquals("/out", gallery.parentFolder("/out/2026", "/out"))
        assertEquals("/out/2026", gallery.parentFolder("/out/2026/a", "/out"))
        assertNull(gallery.parentFolder("/srv/forge", "/out"))
        assertEquals(windowsRoot, gallery.parentFolder("$windowsRoot\\2026", windowsRoot))
        assertEquals("$windowsRoot\\2026", gallery.parentFolder("$windowsRoot\\2026\\b", windowsRoot))
    }
}
