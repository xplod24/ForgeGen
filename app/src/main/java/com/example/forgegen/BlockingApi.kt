package com.example.forgegen

import com.example.forgegen.ContentFilter.terms

/* ============================================================================
 * BLOCKING API
 * The rules that apply in every content mode, Unrestricted too. The content mode (ContentFilter) is the user's choice
 * about what they see themselves; these rules protect third parties instead, so no mode turns them off. Everything
 * they do is in this file, to manage them in one place; a rule some country requires on top belongs here too.
 *  1. Sexual content with a minor: a prompt with a word that makes a person a minor, or an age under 18, together
 *     with any sexual word is never sent, and an image made with such a prompt stays blurred and cannot be shown.
 *  2. A real person: nudity or sex with the LoRA of a real person (Civitai marks such models) is never sent.
 *  3. Civitai: a sample image Civitai marks as showing a minor is never a preview unless it is rated PG.
 * ContentFilter finds the sexual words (its word lists) and asks these rules. The rules work on words, so they are a
 * safeguard, not an enforcement: the server accepts whatever it is sent.
 * ============================================================================ */
object BlockingApi {
    // Words that make the person a minor.
    private val MINOR =
        listOf(
            "child",
            "children",
            "kid",
            "kids",
            "toddler",
            "infant",
            "newborn",
            "loli",
            "lolicon",
            "shota",
            "shotacon",
            "underage",
            "preteen",
            "pre teen",
            "teen",
            "teens",
            "teenage",
            "teenager",
            "young girl",
            "young boy",
            "little girl",
            "little boy",
            "schoolchild",
            "elementary school",
            "middle school",
            "kindergarten",
            "jailbait",
        )

    // "12 years old", "12yo", "12-year-old": any age under 18.
    private val MINOR_AGE =
        Regex("""(?<![a-z0-9])(1[0-7]|[1-9])[^a-z0-9]*(y[^a-z0-9]*o|years?[^a-z0-9]+old)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    private val minorRegex = ContentFilter.wordsRegex(MINOR)

    /** The words in [text] that make a person a minor, ages under 18 included. */
    fun minorTerms(text: String) = minorRegex.terms(text) + MINOR_AGE.findAll(text).map { it.value.lowercase() }

    /**
     * Why [prompt] is never sent, in any content mode, or null. [sexual] are its sexual words of every level, [nudity]
     * only those of nudity and sex (ContentFilter's explicit and extreme words); [realPersonLoras] are the LoRAs
     * Civitai marks as a real person.
     */
    fun check(
        prompt: String,
        sexual: List<String>,
        nudity: List<String>,
        realPersonLoras: Set<String>,
    ): ContentFilter.Verdict? {
        // Rule 1: sexual content with a minor.
        val minors = minorTerms(prompt)
        if (sexual.isNotEmpty() && minors.isNotEmpty()) {
            return ContentFilter.Verdict(minors + sexual, "Sexual content with a minor is never sent, in any content mode.")
        }
        // Rule 2: nudity or sex with a real person.
        val people = parseActiveLoras(prompt).map { it.name }.filter { it in realPersonLoras }
        if (people.isNotEmpty() && nudity.isNotEmpty()) {
            return ContentFilter.Verdict(
                people + nudity,
                "Nudity or sex with the LoRA of a real person is never sent, in any content mode.",
            )
        }
        return null
    }

    /** Rule 1 for images: made with a prompt that has sexual words ([sexual]) and a minor; rated FORBIDDEN. */
    fun isForbidden(
        prompt: String,
        sexual: Boolean,
    ) = sexual && minorTerms(prompt).isNotEmpty()

    /** Rule 1 for images: a blurred image the user may still show; the FORBIDDEN ones stay hidden in every mode. */
    fun canReveal(rating: ContentFilter.Rating) = rating != ContentFilter.Rating.FORBIDDEN

    /** Rule 3: whether a Civitai sample image may be a preview at all. */
    fun allowsCivitaiImage(image: CivitaiImage) = !(image.minor && image.level > 1)
}
