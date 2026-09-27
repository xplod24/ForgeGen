package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gallery's top folder, worked out from Forge's settings as the Infinite Image Browsing extension reports them. */
class GalleryOutputFolderTest {
    private fun folder(
        cwd: String,
        samples: String? = null,
        txt2img: String? = null,
    ) = ForgeGalleryManager.outputFolder(cwd, GlobalSettingInnerDto(outdirSamples = samples, outdirTxt2ImgSamples = txt2img))

    @Test
    fun `a relative txt2img folder is under Forge's working folder, with its separator`() {
        assertEquals("/srv/forge/outputs/txt2img-images", folder("/srv/forge", txt2img = "outputs/txt2img-images"))
        assertEquals("C:\\forge\\outputs\\txt2img-images", folder("C:\\forge\\", txt2img = "outputs/txt2img-images"))
        assertEquals("/srv/forge/out", folder("/srv/forge", txt2img = "./out/"))
    }

    @Test
    fun `an absolute folder is used as it is`() {
        assertEquals("/data/images", folder("/srv/forge", txt2img = "/data/images/"))
        assertEquals("D:\\AI\\images", folder("C:\\forge", txt2img = "D:\\AI\\images"))
        assertEquals("E:/pics", folder("", txt2img = "E:/pics"))
        assertEquals("\\\\nas\\share", folder("C:\\forge", txt2img = "\\\\nas\\share"))
    }

    @Test
    fun `the common output folder wins when it is set`() {
        assertEquals("/srv/forge/all", folder("/srv/forge", samples = "all", txt2img = "outputs/txt2img-images"))
        assertEquals("/srv/forge/outputs/txt2img-images", folder("/srv/forge", samples = " ", txt2img = "outputs/txt2img-images"))
    }

    @Test
    fun `without settings Forge's default folder is used`() {
        assertEquals("/srv/forge/outputs/txt2img-images", ForgeGalleryManager.outputFolder("/srv/forge", null))
        assertEquals("/srv/forge/outputs/txt2img-images", folder("/srv/forge", txt2img = ""))
    }

    @Test
    fun `a relative folder without the working folder is unknown`() {
        assertNull(folder("", txt2img = "outputs/txt2img-images"))
    }
}
