package com.example.forgegen

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class G7_PngMetadataTest {
    companion object {
        lateinit var vm: ForgeViewModel
        @BeforeClass @JvmStatic fun init() { vm = TestApp.start() }
    }

    private fun read(png: ByteArray): String? {
        val uri = Uri("content://test/${System.nanoTime()}")
        TestApp.app.resolver.inputs[uri] = png
        return runBlocking { vm.extractMetadataFromUri(uri) }
    }

    @Test fun `ASCII w tEXt`() = assertEquals(INFOTEXT, read(Png.withParameters(INFOTEXT)))

    @Test fun `znaki Latin-1 w tEXt`() {
        val info = "café, Pokémon, 2×2\nSteps: 20, Seed: 1"
        assertEquals(info, read(Png.withParameters(info)))
    }

    @Test fun `polskie znaki w iTXt`() {
        val info = "zażółć gęślą jaźń\nSteps: 20, Seed: 1"
        assertEquals(info, read(Png.withParameters(info)))
    }

    @Test fun `skompresowany iTXt`() {
        val info = "łódź nocą\nSteps: 20, Seed: 1"
        assertEquals(info, read(Png.build(listOf(Png.iTXt("parameters", info, zip = true)))))
    }
}
