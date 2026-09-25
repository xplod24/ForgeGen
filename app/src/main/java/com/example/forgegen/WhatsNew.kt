package com.example.forgegen

/* ============================================================================
 * WHAT'S NEW
 * After an update the app shows the CHANGELOG.md sections of the versions the user has not seen yet.
 * The build copies CHANGELOG.md into the app's assets (see copyChangelog in app/build.gradle.kts).
 * ============================================================================ */
object WhatsNew {
    const val CHANGELOG_ASSET = "CHANGELOG.md"
    const val LAST_SEEN_KEY = "whats_new_last_version"

    private val SECTION_HEADING = Regex("""^##\s+(.+?)\s*$""")

    /**
     * The changelog of the versions after [lastSeen] up to [current] (both "major.minor.patch"), or null when
     * there is nothing new. Without [lastSeen] the app never showed this before: after an update only the
     * [current] version's notes are shown, after a fresh install nothing.
     */
    fun notesFor(
        changelog: String,
        current: String,
        lastSeen: String?,
        wasUpdated: Boolean,
    ): String? {
        val currentCode = versionCodeFromTag("v$current") ?: return null
        val lastSeenCode = lastSeen?.let { versionCodeFromTag("v$it") }
        if (lastSeenCode == null && !wasUpdated) return null
        if (lastSeenCode != null && lastSeenCode >= currentCode) return null

        val notes =
            sections(changelog)
                .filter { (code, _) ->
                    code != null && code <= currentCode && if (lastSeenCode != null) code > lastSeenCode else code == currentCode
                }.joinToString("\n\n") { it.second }
        return notes.ifBlank { null }
    }

    /** The "## <version>" sections, newest first as in the file, with their version code (null if not a version). */
    private fun sections(changelog: String): List<Pair<Int?, String>> {
        val sections = mutableListOf<Pair<Int?, String>>()
        var code: Int? = null
        var text: StringBuilder? = null
        for (line in changelog.replace("\r\n", "\n").split("\n")) {
            val heading = SECTION_HEADING.find(line)
            if (heading != null) {
                text?.let { sections += code to it.toString().trim() }
                code = versionCodeFromTag("v${heading.groupValues[1]}")
                text = StringBuilder(line).append('\n')
            } else {
                text?.append(line)?.append('\n')
            }
        }
        text?.let { sections += code to it.toString().trim() }
        return sections
    }
}
