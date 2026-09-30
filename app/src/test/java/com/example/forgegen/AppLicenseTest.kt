package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLicenseTest {
    private fun words(text: String) = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")

    @Test
    fun `paragraphs join the wrapped lines`() {
        val text = "  TITLE\n  Version 3\n\n  0. First line\nsecond line.  Next.\n\n\n   \n  a) Item\n  goes on.\r\n"
        assertEquals(
            listOf("TITLE Version 3", "0. First line second line. Next.", "a) Item goes on."),
            AppLicense.paragraphs(text),
        )
    }

    @Test
    fun `the license file is the GPL version 3`() {
        val license = java.io.File("../LICENSE").takeIf { it.exists() } ?: return
        val paragraphs = AppLicense.paragraphs(license.readText())
        assertEquals("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", paragraphs.first())
        assertTrue(paragraphs.any { it == "TERMS AND CONDITIONS" })
        assertTrue(paragraphs.any { it.startsWith("15. Disclaimer of Warranty.") })
    }

    @Test
    fun `the app shows the notice of the README`() {
        val readme = java.io.File("../README.md").takeIf { it.exists() } ?: return
        val license = words(readme.readText().substringAfter("## License"))
        assertTrue(license.contains("ForgeGen ${AppLicense.COPYRIGHT}"))
        AppLicense.NOTICE.forEach { assertTrue(it, license.contains(words(it))) }
    }
}
