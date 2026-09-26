package com.example.forgegen

/* ============================================================================
 * BLOCKING API
 * The server decides what it generates (a prompt-checking extension answers a refused prompt with HTTP 403, which
 * the queue reports as "The prompt does not comply with the server's rules"). On top of that, as an extra check
 * before a job is sent, the app refuses what no server may be sent; such a job goes the same way as a server's 403
 * (ForgeQueueManager). Everything these rules do is in this file, to manage them in one place; a rule some
 * country requires on top belongs here too.
 *  1. Sexual content with a minor: a word that makes a person a minor, or an age under 18, together with any
 *     sexual word.
 *  2. A real person: nudity or sex with the LoRA of a real person (Civitai marks such models).
 *  3. Civitai: a sample image Civitai marks as showing a minor is never a preview unless it is rated PG.
 * The rules work on whole words ("_" and "-" count as spaces), so they are a safeguard, not an enforcement.
 * ============================================================================ */
object BlockingApi {
    /** Why a prompt is refused, and the words that caused it. */
    data class Verdict(
        val terms: List<String>,
        val reason: String,
    )

    // Suggestive words: with a minor they are refused too.
    private val SUGGESTIVE =
        listOf(
            "nsfw",
            "ecchi",
            "lewd",
            "sexy",
            "seductive",
            "erotic",
            "erotica",
            "suggestive",
            "fetish",
            "lingerie",
            "underwear",
            "panties",
            "panty",
            "pantyshot",
            "bra",
            "thong",
            "g string",
            "see through",
            "cleavage",
            "breasts",
            "breast",
            "boobs",
            "boob",
            "sideboob",
            "underboob",
            "ass",
            "butt",
            "buttocks",
            "cameltoe",
            "bulge",
            "stripping",
            "undressing",
            "partially undressed",
        )

    // Nudity and sex, sexual violence included.
    private val NUDITY =
        listOf(
            "nude",
            "nudity",
            "naked",
            "topless",
            "bottomless",
            "nipple",
            "nipples",
            "areola",
            "areolae",
            "pussy",
            "vagina",
            "vulva",
            "clitoris",
            "labia",
            "penis",
            "cock",
            "dick",
            "testicles",
            "erection",
            "genitals",
            "genital",
            "pubic hair",
            "sex",
            "sexual",
            "intercourse",
            "penetration",
            "vaginal",
            "anal",
            "oral sex",
            "fellatio",
            "blowjob",
            "handjob",
            "footjob",
            "paizuri",
            "titjob",
            "cunnilingus",
            "masturbation",
            "masturbating",
            "fingering",
            "orgasm",
            "cum",
            "cumshot",
            "semen",
            "ejaculation",
            "creampie",
            "bukkake",
            "missionary",
            "cowgirl position",
            "doggystyle",
            "doggy style",
            "threesome",
            "gangbang",
            "orgy",
            "hentai",
            "porn",
            "pornographic",
            "xxx",
            "spread legs",
            "spread pussy",
            "uncensored",
            "ahegao",
            "bdsm",
            "bondage",
            "dildo",
            "vibrator",
            "sex toy",
            "strap on",
            "lactation",
            "futanari",
            "futa",
            "rape",
            "raped",
            "raping",
            "non consensual",
            "noncon",
            "forced sex",
            "sexual assault",
            "molestation",
            "molested",
            "molest",
            "sexual abuse",
            "child abuse",
            "guro",
            "ryona",
            "snuff",
            "necrophilia",
            "bestiality",
            "zoophilia",
            "incest",
            "scat",
            "coprophilia",
            "drugged",
        )

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

    private val suggestiveRegex = wordsRegex(SUGGESTIVE)
    private val nudityRegex = wordsRegex(NUDITY)
    private val minorRegex = wordsRegex(MINOR)

    /** Whole words (a space in a term also matches "_", "-" and the like), longest first, any case. */
    private fun wordsRegex(terms: List<String>): Regex {
        val alternatives =
            terms.sortedByDescending { it.length }.joinToString("|") { term ->
                term.split(" ").joinToString("[^a-z0-9]+") { Regex.escape(it) }
            }
        return Regex("(?<![a-z0-9])(?:$alternatives)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    }

    private fun Regex.terms(text: String) = findAll(text).map { it.value.lowercase() }.distinct().toList()

    /** The words in [text] that make a person a minor, ages under 18 included. */
    fun minorTerms(text: String) = minorRegex.terms(text) + MINOR_AGE.findAll(text).map { it.value.lowercase() }

    /**
     * Why [prompt] is never sent, or null. [realPersonLoras] are the LoRAs Civitai marks as a real person (their
     * names in `<lora:name:weight>`).
     */
    fun check(
        prompt: String,
        realPersonLoras: Set<String> = emptySet(),
    ): Verdict? {
        val nudity = nudityRegex.terms(prompt)
        val sexual = nudity + suggestiveRegex.terms(prompt)
        // Rule 1: sexual content with a minor.
        val minors = minorTerms(prompt)
        if (sexual.isNotEmpty() && minors.isNotEmpty()) {
            return Verdict(minors + sexual, "Sexual content with a minor is never sent.")
        }
        // Rule 2: nudity or sex with a real person.
        val people = parseActiveLoras(prompt).map { it.name }.filter { it in realPersonLoras }
        if (people.isNotEmpty() && nudity.isNotEmpty()) {
            return Verdict(people + nudity, "Nudity or sex with the LoRA of a real person is never sent.")
        }
        return null
    }

    /** Rule 3: whether a Civitai sample image may be a preview at all. */
    fun allowsCivitaiImage(image: CivitaiImage) = !(image.minor && image.level > 1)
}
