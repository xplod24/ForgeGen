package com.example.forgegen

/* ============================================================================
 * LICENSE (3.4.2)
 * ForgeGen is free software under the GNU GPL v3.0 or later (the owner's choice). Settings > Updates > License shows
 * this notice, the whole license and a link to the source code. The build copies LICENSE into the app's assets
 * (copyAppAssets in app/build.gradle.kts), so the text is there offline. README.md's License section carries the
 * same notice; AppLicenseTest checks that they agree.
 * ============================================================================ */
object AppLicense {
    const val ASSET = "LICENSE"
    const val NAME = "GNU GPL v3.0 or later"
    const val AUTHOR = "xplod24 (Szymon Tempiński)"
    const val COPYRIGHT = "Copyright (C) 2026 $AUTHOR"
    const val SOURCE_URL = "https://github.com/xplod24/ForgeGen"

    /** The notice the GPL asks a program to show, after "ForgeGen" and [COPYRIGHT]. */
    val NOTICE =
        listOf(
            "This program is free software: you can redistribute it and/or modify it under the terms of the GNU " +
                "General Public License as published by the Free Software Foundation, either version 3 of the " +
                "License, or (at your option) any later version.",
            "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even " +
                "the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General " +
                "Public License for more details.",
        )

    /**
     * The license's paragraphs for a narrow screen. The file is wrapped at 72 columns and indented, which would
     * break every line again on a phone, so the lines of each paragraph are joined (and its double spaces after a
     * full stop made single).
     */
    fun paragraphs(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .split(Regex("""\n[ \t]*\n"""))
            .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim() }
            .filter { it.isNotEmpty() }
}
