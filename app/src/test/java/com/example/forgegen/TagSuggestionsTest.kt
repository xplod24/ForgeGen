package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** 2.4.2: tag suggestions above the keyboard (the owner's idea 1). */
class TagSuggestionsTest {
    // Lines as in tagcomplete's danbooru.csv, most used first.
    private val csv =
        """
        1girl,0,6008644,"1girls,sole_female"
        highres,5,5256195,"high_res,high_resolution,hires"
        long_hair,0,4350743,"/lh,longhair"
        blonde_hair,0,1537942,"blond,blonde,blondehair"
        hair_ornament,0,1419302,"hair_accessories,hair_accessory"
        hatsune_miku,4,106634,
        chen_(touhou),4,30000,
        ^_^,0,110141,^^
        red_hair,0,520146,"redhair,red_head,redhead"
        """.trimIndent()

    private val extra = "masterpiece,5,Quality tag,,\nbest_quality,5,Quality tag,,"

    private fun list(extraFirst: Boolean = true) = TagList.parse(StringReader(csv), StringReader(extra), extraFirst)

    @Test
    fun `the tag file is read with its categories, counts and aliases`() {
        val tags = list()
        assertEquals(11, tags.size)
        assertEquals(TagMatch("1girl", 0, 6008644), tags.search("1gi").first())
        assertEquals(listOf("a", "b,c", "", "d \"x\""), TagList.csvFields("a,\"b,c\",,\"d \"\"x\"\"\""))
        // The extra file's third column is text, not a count.
        assertEquals(TagMatch("masterpiece", 5, 0), tags.search("master").single())
    }

    @Test
    fun `tags starting with the text come first, then later words, then aliases, each most used first`() {
        assertEquals(
            // Starting with it first, then those with a later word starting with it, each most used first.
            listOf("hair_ornament", "long_hair", "blonde_hair", "red_hair"),
            list().search("hair").map { it.name },
        )
        // Found by its alias: the alias it matched is shown with it.
        assertEquals(listOf(TagMatch("red_hair", 0, 520146, "redhair")), list().search("redh"))
        assertEquals("sole_female", list().search("sole").single().alias)
        // A tag found by its name is not found again by its alias.
        assertEquals(listOf(TagMatch("blonde_hair", 0, 1537942)), list().search("blond"))
        assertEquals(1, list().search("h", limit = 1).size)
    }

    @Test
    fun `what is typed is searched as the list writes it`() {
        assertEquals("long_h", TagList.normalizeQuery(" Long  H "))
        assertEquals("chen_(tou", TagList.normalizeQuery("chen \\(tou"))
        assertEquals(listOf("long_hair"), list().search("long h").map { it.name })
        assertEquals(listOf("chen_(touhou)"), list().search("chen \\(to").map { it.name })
        assertTrue(list().search("  ").isEmpty())
    }

    @Test
    fun `the extra tags go first or last as the server's tagcomplete says`() {
        val first = TagList.parse(StringReader(csv), StringReader("hat,0,x,"), extraFirst = true)
        assertEquals("hat", first.search("hat").first().name)
        val last = TagList.parse(StringReader(csv), StringReader("hat,0,x,"), extraFirst = false)
        assertEquals(listOf("hatsune_miku", "hat"), last.search("hat").map { it.name })
    }

    @Test
    fun `an unsorted file is sorted by use`() {
        val tags = TagList.parse(StringReader("rare_tag,0,5,\nhot_tag,0,500,\nsome_tag,0,50,"))
        assertEquals(listOf("hot_tag", "some_tag", "rare_tag"), tags.search("tag").map { it.name })
    }

    @Test
    fun `a tag is written as Forge reads it`() {
        val rules = TagInsertRules()
        assertEquals("long hair", rules.format("long_hair"))
        assertEquals("chen \\(touhou\\)", rules.format("chen_(touhou)"))
        assertEquals("chen (touhou)", rules.label("chen_(touhou)"))
        assertEquals("^_^", rules.format("^_^"))
        assertEquals(";_;", rules.format(";_;")) // no letters: an emoticon, even if not listed
        assertEquals("o_o", rules.format("o_o")) // listed by tagcomplete
        val server = TagInsertRules.of(replaceUnderscores = false, keepUnderscores = null, escapeBrackets = false)
        assertEquals("chen_(touhou)", server.format("chen_(touhou)"))
        assertEquals(setOf("a_b"), TagInsertRules.of(null, " a_b ,", null).keepUnderscores)
        assertEquals("a_b", TagInsertRules.of(null, "a_b", null).format("a_b"))
    }

    @Test
    fun `the tag being typed is the text from the last comma to the caret`() {
        val text = "masterpiece, 1girl, long h"
        assertEquals(TypedFragment(TypedFragment.Kind.TAG, 20, text.length, "long h"), PromptTypingRules.fragmentAt(text, text.length))
        assertNull("one character is not a tag yet", PromptTypingRules.fragmentAt("a, b", 4))
        assertNull(PromptTypingRules.fragmentAt("a, ", 3))
        // The brackets of a weight stay; the rest of the word under the caret is replaced too.
        assertEquals(TypedFragment(TypedFragment.Kind.TAG, 1, 4, "lo"), PromptTypingRules.fragmentAt("(lon:1.2)", 3))
        assertEquals(TypedFragment(TypedFragment.Kind.TAG, 7, 10, "sm"), PromptTypingRules.fragmentAt("a, b\n  smi", 9))
        assertNull(PromptTypingRules.fragmentAt("abc", 7))
    }

    @Test
    fun `wildcards are suggested after two underscores and LoRAs after lora`() {
        assertEquals(TypedFragment(TypedFragment.Kind.WILDCARD, 3, 8, "hai"), PromptTypingRules.fragmentAt("a, __hai", 8))
        assertEquals(TypedFragment(TypedFragment.Kind.WILDCARD, 3, 5, ""), PromptTypingRules.fragmentAt("a, __", 5))
        assertEquals("a closed wildcard is not typed any more", TypedFragment.Kind.TAG, PromptTypingRules.fragmentAt("__a__ bl", 8)?.kind)
        assertEquals(TypedFragment(TypedFragment.Kind.LORA, 3, 12, "det"), PromptTypingRules.fragmentAt("a, <lora:det", 12))
        assertNull("its strength is being typed", PromptTypingRules.fragmentAt("<lora:detail:0.", 15))
        assertEquals(TypedFragment.Kind.TAG, PromptTypingRules.fragmentAt("<lora:detail:0.5> sm", 20)?.kind)
    }

    @Test
    fun `a tapped suggestion replaces what was typed and ends with a comma`() {
        val text = "1girl, long h"
        val edit = PromptTypingRules.insert(text, PromptTypingRules.fragmentAt(text, text.length)!!, "long hair")
        assertEquals(TypedEdit("1girl, long hair, ", 18), edit)
        // In the middle: the comma already there is used, the rest stays.
        val middle = "1girl, bl, smile"
        val inside = PromptTypingRules.insert(middle, PromptTypingRules.fragmentAt(middle, 9)!!, "blonde hair")
        assertEquals(TypedEdit("1girl, blonde hair, smile", 20), inside)
        // Inside a weight no comma is added.
        val weight = "(lo:1.2)"
        assertEquals(
            TypedEdit("(long hair:1.2)", 10),
            PromptTypingRules.insert(weight, PromptTypingRules.fragmentAt(weight, 3)!!, "long hair"),
        )
        val lora = "a, <lora:det"
        assertEquals(
            "a, <lora:detail:1.0>, ",
            PromptTypingRules.insert(lora, PromptTypingRules.fragmentAt(lora, 12)!!, "<lora:detail:1.0>").text,
        )
        // A LoRA renamed before its strength keeps the strength.
        val renamed = "<lora:de:0.8>"
        assertEquals(
            TypedEdit("<lora:detail:0.8>", 12),
            PromptTypingRules.insert(renamed, PromptTypingRules.fragmentAt(renamed, 8)!!, "<lora:detail:1.0>"),
        )
    }

    @Test
    fun `the chips are tags, wildcards or LoRAs`() {
        val rules = TagInsertRules()
        val tag = Suggestions.forFragment(PromptTypingRules.fragmentAt("chen \\(", 7), list(), rules, emptyList(), emptyList())
        assertEquals(Suggestion("chen (touhou)", "chen \\(touhou\\)", TypedFragment.Kind.TAG, 4, 30000), tag.single())
        assertTrue(Suggestions.forFragment(PromptTypingRules.fragmentAt("lo", 2), null, rules, emptyList(), emptyList()).isEmpty())
        val wildcards =
            Suggestions.forFragment(
                PromptTypingRules.fragmentAt("__h", 3),
                null,
                rules,
                listOf("pose", "Hair", "shirt"),
                emptyList(),
            )
        assertEquals(listOf("__Hair__", "__shirt__"), wildcards.map { it.insertion })
        val loras = Suggestions.forFragment(PromptTypingRules.fragmentAt("<lora:", 6), null, rules, emptyList(), listOf("b", "a"))
        assertEquals(listOf("<lora:a:1.0>", "<lora:b:1.0>"), loras.map { it.insertion })
        assertTrue(Suggestions.forFragment(null, list(), rules, listOf("x"), listOf("y")).isEmpty())
    }

    @Test
    fun `counts are short`() {
        assertEquals("950", Suggestions.compactCount(950))
        assertEquals("12.3k", Suggestions.compactCount(12_345))
        assertEquals("1k", Suggestions.compactCount(1_000))
        assertEquals("4.4M", Suggestions.compactCount(4_350_743))
        assertEquals("6M", Suggestions.compactCount(6_008_644))
        assertEquals("1M", Suggestions.compactCount(999_999))
    }

    @Test
    fun `the screen gives up the preview, then the top bar, then all but the strip, then the status bar`() {
        assertEquals(TypingLayout(), TypingLayout.of(freeDp = 500f, statusBarDp = 32f))
        assertEquals(TypingLayout(hidePreview = true), TypingLayout.of(339f, 32f))
        assertFalse(TypingLayout.of(340f, 32f).hidePreview)
        assertEquals(TypingLayout(hidePreview = true, hideTopBar = true), TypingLayout.of(179f, 32f))
        assertFalse(TypingLayout.of(180f, 32f).hideTopBar)
        val oneBar = TypingLayout.of(115f, 32f)
        assertTrue(oneBar.oneBar && oneBar.hideTopBar && oneBar.hidePreview && !oneBar.hideStatusBar)
        assertEquals(44f, oneBar.stripDp)
        assertFalse(TypingLayout.of(116f, 32f).oneBar)
        // Below the strip's height the status bar goes too, and the strip gets what is left, at least 36 dp.
        val noStatus = TypingLayout.of(10f, 32f)
        assertTrue(noStatus.hideStatusBar && noStatus.oneBar)
        assertEquals(42f, noStatus.stripDp)
        assertEquals(36f, TypingLayout.of(-20f, 24f).stripDp)
    }

    @Test
    fun `server file addresses are encoded like a browser does`() {
        assertEquals("file=tmp/tagAutocompletePath.txt", ForgeTagManager.fileUrl(ForgeTagManager.PATH_FILE))
        assertEquals(
            "file=C:/Stable%20Diffusion/extensions/tagcomplete/tags/danbooru.csv",
            ForgeTagManager.fileUrl("C:\\Stable Diffusion/extensions/tagcomplete/tags/danbooru.csv"),
        )
        assertEquals("file=/home/z%C4%85b/a%23b%3F.csv", ForgeTagManager.fileUrl("/home/ząb/a#b?.csv"))
    }
}
