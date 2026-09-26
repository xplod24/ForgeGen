package com.example.forgegen

/* ============================================================================
 * CONTENT FILTER
 * The content mode the user chose in the settings (Content & Privacy):
 *  - SFW (default): prompts with NSFW tags are not sent, every image is blurred until tapped, NSFW tags are hidden.
 *  - NSFW: adult content is allowed; the extreme tags (non-consent, gore, bestiality...) are not sent, and images
 *    made with them are blurred.
 *  - Unrestricted: nothing is blocked or blurred.
 * In every mode, also Unrestricted, the rules of BlockingApi apply on top: they protect third parties, not the user,
 * and they are all kept in that one file. The filter works on words, so it is a safeguard, not an enforcement: the
 * server accepts whatever it is sent.
 * Only the positive prompt is checked; the negative prompt usually lists exactly these words.
 * ============================================================================ */
object ContentFilter {
    /** How explicit a prompt is; UNKNOWN when there is no prompt (e.g. an image without generation data). */
    enum class Rating { SAFE, SUGGESTIVE, EXPLICIT, EXTREME, FORBIDDEN, UNKNOWN }

    /** Why a prompt is not sent, and the words that caused it. */
    data class Verdict(
        val terms: List<String>,
        val reason: String,
    )

    // Suggestive: not nudity, but not safe for work either.
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

    // Nudity and sex.
    private val EXPLICIT =
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
        )

    // The part of hardcore that NSFW mode still does not send: non-consent, violence, animals, and the like.
    private val EXTREME =
        listOf(
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
            "gore",
            "guro",
            "ryona",
            "dismemberment",
            "decapitation",
            "disembowelment",
            "mutilation",
            "snuff",
            "necrophilia",
            "bestiality",
            "zoophilia",
            "incest",
            "scat",
            "coprophilia",
            "vore",
            "torture",
            "cannibalism",
            "drugged",
        )

    private val suggestiveRegex = wordsRegex(SUGGESTIVE)
    private val explicitRegex = wordsRegex(EXPLICIT)
    private val extremeRegex = wordsRegex(EXTREME)

    /** Whole words (a space in a term also matches "_", "-" and the like), longest first, any case. */
    internal fun wordsRegex(terms: List<String>): Regex {
        val alternatives =
            terms.sortedByDescending { it.length }.joinToString("|") { term ->
                term.split(" ").joinToString("[^a-z0-9]+") { Regex.escape(it) }
            }
        return Regex("(?<![a-z0-9])(?:$alternatives)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    }

    internal fun Regex.terms(text: String) = findAll(text).map { it.value.lowercase() }.distinct().toList()

    fun rate(prompt: String?): Rating {
        if (prompt == null) return Rating.UNKNOWN
        val extreme = extremeRegex.containsMatchIn(prompt)
        val explicit = explicitRegex.containsMatchIn(prompt)
        val suggestive = suggestiveRegex.containsMatchIn(prompt)
        return when {
            BlockingApi.isForbidden(prompt, sexual = extreme || explicit || suggestive) -> Rating.FORBIDDEN
            extreme -> Rating.EXTREME
            explicit -> Rating.EXPLICIT
            suggestive -> Rating.SUGGESTIVE
            else -> Rating.SAFE
        }
    }

    /**
     * Why [prompt] must not be sent in [mode], or null when it may. [realPersonLoras] are the LoRAs Civitai marks as
     * a real person; with nudity or sex they are refused in every mode.
     */
    fun check(
        prompt: String,
        mode: String,
        realPersonLoras: Set<String> = emptySet(),
    ): Verdict? {
        val extreme = extremeRegex.terms(prompt)
        val explicit = explicitRegex.terms(prompt)
        val suggestive = suggestiveRegex.terms(prompt)
        val sexual = extreme + explicit + suggestive
        // The rules of every mode first (BlockingApi), then the content mode's own.
        BlockingApi.check(prompt, sexual = sexual, nudity = extreme + explicit, realPersonLoras = realPersonLoras)?.let { return it }
        val blocked =
            when (mode) {
                CONTENT_UNRESTRICTED -> emptyList()
                CONTENT_NSFW -> extreme
                else -> sexual
            }
        return if (blocked.isEmpty()) null else Verdict(blocked, "The $mode content mode does not send these tags.")
    }

    /** Whether a tag or word is hidden in [mode]: all NSFW tags in SFW, the extreme ones in NSFW. */
    fun isHidden(
        text: String,
        mode: String,
    ): Boolean =
        when (mode) {
            CONTENT_UNRESTRICTED -> false
            CONTENT_NSFW -> extremeRegex.containsMatchIn(text)
            else -> extremeRegex.containsMatchIn(text) || explicitRegex.containsMatchIn(text) || suggestiveRegex.containsMatchIn(text)
        }

    /** [text] with the words hidden in [mode] replaced by dots, for prompts shown on screen. */
    fun mask(
        text: String,
        mode: String,
    ): String {
        val hidden =
            when (mode) {
                CONTENT_UNRESTRICTED -> return text
                CONTENT_NSFW -> listOf(extremeRegex)
                else -> listOf(extremeRegex, explicitRegex, suggestiveRegex)
            }
        return hidden.fold(text) { masked, regex -> regex.replace(masked) { "•".repeat(it.value.length.coerceAtMost(6)) } }
    }

    /** Whether an image made with a prompt of [rating] is blurred in [mode]. SFW blurs every image: a safe prompt
     *  does not guarantee a safe image. */
    fun blurs(
        rating: Rating,
        mode: String,
    ): Boolean =
        when {
            !BlockingApi.canReveal(rating) -> true
            mode == CONTENT_UNRESTRICTED -> false
            mode == CONTENT_NSFW -> rating == Rating.EXTREME
            else -> true
        }

    // --- Civitai ---
    // Civitai rates each image: 1 PG, 2 PG-13, 4 R, 8 X, 16 XXX, 32 blocked by Civitai.

    /** The most explicit Civitai image shown as a preview in [mode]. */
    fun civitaiMaxLevel(mode: String): Int =
        when (mode) {
            CONTENT_UNRESTRICTED -> 16
            CONTENT_NSFW -> 8
            else -> 1
        }

    /** The first Civitai image allowed in [mode]; never one Civitai blocked, nor one BlockingApi refuses. */
    fun pickCivitaiPreview(
        images: List<CivitaiImage>,
        mode: String,
    ): CivitaiImage? =
        images.firstOrNull { image ->
            image.level in 1..civitaiMaxLevel(mode) && BlockingApi.allowsCivitaiImage(image)
        }

    /** A model preview of Civitai level [level] (0: not from Civitai, content unknown) is blurred in [mode]. */
    fun blursPreview(
        level: Int,
        mode: String,
    ): Boolean = mode == CONTENT_SFW && level != 1

    /** Civitai's API: civitai.com shows only safe content since April 2026, civitai.red everything. */
    fun civitaiBaseUrl(mode: String) = if (mode == CONTENT_SFW) "https://civitai.com/" else "https://civitai.red/"
}

/** A Civitai sample image of a model, with Civitai's rating. */
data class CivitaiImage(
    val url: String,
    val level: Int,
    val minor: Boolean = false,
)
