package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.6.0: the statistics' new parts, from the index's details and the favorites, and from the recorded jobs. */
class StatisticsTest {
    private fun row(
        i: Int,
        model: String = "animagineXL31",
        sampler: String = "Euler a",
        scheduler: String? = "Karras",
        size: Pair<Int, Int> = 832 to 1216,
        hires: Boolean = false,
        lora: String = "",
        modules: String? = "sdxl_vae",
        details: Int = 1,
    ) = GalleryStatsRow(
        fullpath = "/out/$i.png",
        date = "2026-09-01 10:00:00",
        model = model,
        sampler = sampler,
        loras = lora,
        size = 1000,
        width = size.first,
        height = size.second,
        steps = if (i % 4 == 0) 20 else 28,
        cfg = if (i % 5 == 0) 7f else 5f,
        distilledCfg = null,
        scheduler = scheduler,
        hiresScale = if (hires) 1.5f else null,
        hiresUpscaler = if (hires) "4x-UltraSharp" else null,
        hiresSteps = if (hires) 15 else null,
        denoising = if (hires) 0.35f else null,
        modules = modules,
        embeddings = if (i % 2 == 0) "easynegative" else null,
        details = details,
    )

    @Test
    fun `the details count the settings used most and open the gallery with them`() {
        val insights = GalleryInsights(emptySet())
        (1..60).forEach { i -> insights.add(row(i, hires = i <= 24, size = if (i <= 40) 832 to 1216 else 1024 to 1024)) }
        (61..70).forEach { i -> insights.add(row(i, details = 0)) } // read before 3.6.0: counted only once read
        insights.addPrompts("/out/1.png", "1girl, smile", "worst quality, lowres")
        insights.addPrompts("/out/2.png", "1girl", "worst quality")
        val d = insights.details()
        assertEquals(60, d.known)
        assertEquals("Euler a · Karras", d.samplers.single().label)
        assertEquals(60, d.samplers.single().count)
        assertEquals(listOf("832×1216" to 40, "1024×1024" to 20), d.sizes.map { it.label to it.count })
        assertEquals(StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.SIZE, "832x1216")), d.sizes.first().target)
        assertEquals(28, d.typicalSteps)
        assertEquals(listOf(20, 28), d.steps.map { it.first })
        assertEquals(5f, d.typicalCfg)
        assertEquals(24, d.hiresCount)
        assertEquals(1.5f, d.hiresScale)
        assertEquals("4x-UltraSharp", d.hiresUpscaler)
        assertEquals(0.35f, d.hiresDenoising)
        assertEquals("sdxl_vae", d.modules.single().label)
        assertEquals("easynegative", d.embeddings.single().label)
        assertEquals(30, d.embeddings.single().count)
        assertEquals(listOf("worst quality" to 2, "lowres" to 1), d.negativeTags.map { it.label to it.count })
        assertEquals(mapOf("1girl" to 2, "smile" to 1), insights.tagCounts())
    }

    @Test
    fun `what you like compares how often images become favorites`() {
        // 40 images with hires fix, 12 of them favorites; 40 without, 4 favorites. Two models of 40.
        val favorites = ((1..12) + (41..44)).map { "/out/$it.png" }.toSet()
        val insights = GalleryInsights(favorites)
        (1..80).forEach { i ->
            insights.add(
                row(
                    i,
                    model =
                        if (i <=
                            40
                        ) {
                            "flux1-dev"
                        } else {
                            "ponyDiffusionV6XL"
                        },
                    hires = i <= 40,
                    lora = if (i % 2 == 0) "detail" else "",
                ),
            )
            insights.addPrompts("/out/$i.png", if (i <= 12) "1girl, smile" else "1girl, simple background", "")
        }
        val liked = insights.liked()
        assertNotNull(liked)
        liked!!
        assertEquals(16, liked.favorites)
        assertEquals(80, liked.total)
        assertEquals(listOf("flux1-dev" to 12, "ponyDiffusionV6XL" to 4), liked.byModel.map { it.label to it.favorites })
        assertEquals(0.3f, liked.byModel.first().rate, 0.001f)
        assertEquals(StatTarget.Model("flux1-dev"), liked.byModel.first().target)
        val hires = liked.bySetting.first { it.label == "Hires fix" }
        assertEquals(0.3f, hires.withRate, 0.001f)
        assertEquals(0.1f, hires.withoutRate, 0.001f)
        assertTrue("smile is in every favorite with it: ${liked.tagsMore}", liked.tagsMore.none { it.tag == "smile" }) // 12 < 20 images
        assertEquals("simple background", liked.tagsLess.single().tag) // in 68 images, 4 favorites
        assertTrue(liked.tagsLess.single().lift < 0.75f)
    }

    @Test
    fun `what you like waits for enough favorites`() {
        val insights = GalleryInsights(setOf("/out/1.png"))
        (1..50).forEach { insights.add(row(it)) }
        assertNull(insights.liked())
    }

    @Test
    fun `the months run from the first image to now, empty ones included`() {
        val images =
            listOf("2026-07-02 10:00:00", "2026-07-03 10:00:00", "2026-09-01 10:00:00").mapIndexed { i, date ->
                IndexedImage("/out/$i.png", "$i.png", date, "m", "")
            }
        val stats = GalleryStatistics.compute(images, java.time.LocalDate.of(2026, 10, 1))
        assertEquals(listOf("2026-07" to 2, "2026-08" to 0, "2026-09" to 1, "2026-10" to 0), stats.perMonth)
    }

    private fun run(
        id: String,
        startedAt: Long,
        model: String,
        kind: JobStart,
        firstStepMs: Long,
        previous: String? = null,
        loadMs: Long? = null,
        vramMs: Long? = null,
        total: Long = 20_000,
        firstHash: Boolean = false,
        outcome: JobOutcome = JobOutcome.DONE,
        failure: JobFailure? = null,
        itPerSec: Float? = 7.4f,
        size: Pair<Int, Int> = 832 to 1216,
    ) = JobRunEntity(
        id,
        "http://pc:7860",
        null,
        startedAt,
        model,
        "",
        previous,
        kind.name,
        firstHash,
        size.first,
        size.second,
        2,
        28,
        "Euler a",
        "Karras",
        null,
        null,
        loadMs,
        vramMs,
        firstStepMs,
        10_000,
        null,
        900,
        total,
        itPerSec,
        null,
        7.6f,
        9.1f,
        12f,
        null,
        outcome.name,
        failure?.name,
        null,
    )

    @Test
    fun `the generation statistics take the medians of each start and leave first loads out`() {
        val a = "animagineXL31.safetensors [abc]"
        val f = "flux1-dev.safetensors [def]"
        val minute = 60_000L
        val runs =
            listOf(
                run("1", 0, a, JobStart.COLD, 24_000, loadMs = 19_000, vramMs = 5_000),
                run("2", 1 * minute, a, JobStart.SAME, 700),
                run("3", 2 * minute, f, JobStart.SWAP, 90_000, previous = a, firstHash = true), // its first load
                run("4", 4 * minute, a, JobStart.SWAP, 11_000, previous = f, loadMs = 8_000, vramMs = 3_000),
                run("5", 5 * minute, f, JobStart.SWAP, 26_000, previous = a, loadMs = 20_000, vramMs = 6_000),
                run("6", 6 * minute, a, JobStart.SWAP, 13_000, previous = f, loadMs = 9_000, vramMs = 4_000),
                run("7", 7 * minute, a, JobStart.SAME, 900),
                run("8", 8 * minute, a, JobStart.SAME, 0, outcome = JobOutcome.FAILED, failure = JobFailure.OUT_OF_VRAM),
            )
        val stats = GenerationStatistics.compute(runs)
        assertEquals(8, stats.jobs)
        assertEquals(1, stats.failed)
        assertEquals(14, stats.images)
        assertEquals(160_000L, stats.gpuMs)
        val byKind = stats.loading.associateBy { it.kind }
        assertEquals(24_000L, byKind.getValue(JobStart.COLD).firstStepMs)
        assertEquals("the first load is left out", 3, byKind.getValue(JobStart.SWAP).count)
        assertEquals(13_000L, byKind.getValue(JobStart.SWAP).firstStepMs)
        assertEquals(9_000L, byKind.getValue(JobStart.SWAP).loadMs)
        assertEquals(700L, byKind.getValue(JobStart.SAME).firstStepMs)
        // One sitting with two models and four swaps: one swap was needed, three could have been spared.
        assertEquals(3, stats.avoidableSwaps)
        assertEquals((13_000L - 700L) * 3, stats.savableMs)
        val anim = stats.byModel.first { it.name == "animagineXL31" }
        assertEquals(24_000L, anim.coldMs)
        assertEquals(12_000L, anim.swapMs)
        val flux = stats.byModel.first { it.name == "flux1-dev" }
        assertEquals(26_000L, flux.swapMs)
        assertEquals(64_000L, flux.firstHashExtraMs)
        assertEquals(listOf("animagineXL31 → flux1-dev", "flux1-dev → animagineXL31"), stats.swaps.map { "${it.from} → ${it.to}" }.sorted())
        assertEquals(listOf(JobFailure.OUT_OF_VRAM to 1), stats.failures)
        assertEquals("8", stats.recent.first().id)
        assertEquals("animagineXL31", stats.speedModel)
    }

    @Test
    fun `swaps are avoidable only within a sitting`() {
        val a = "a"
        val b = "b"
        val hour = 3_600_000L
        val runs =
            listOf(
                run("1", 0, a, JobStart.SAME, 500),
                run("2", 60_000, b, JobStart.SWAP, 10_000, previous = a),
                // An hour later: a new sitting, its swap back is needed.
                run("3", hour, a, JobStart.SWAP, 10_000, previous = b),
            )
        assertEquals(0, GenerationStatistics.avoidableSwaps(runs))
    }

    @Test
    fun `a job's phases as it went`() {
        val swap =
            run("1", 0, "animagineXL31", JobStart.SWAP, 10_000, previous = "pony", loadMs = 7_900, vramMs = 2_100)
                .copy(vramCurve = "0:7.8,7900:0.9,9000:4.3,10100:7.6", sendMs = 1_100, hiresMs = null)
        val phases = GenerationStatistics.phases(swap)
        assertEquals(listOf(JobPhase.Kind.LOAD, JobPhase.Kind.VRAM, JobPhase.Kind.SAMPLING, JobPhase.Kind.SEND), phases.map { it.kind })
        assertEquals("Forge freed pony and read animagineXL31 from disk", phases[0].note)
        assertEquals("VRAM 0.9 → 7.6 GB, until the first step", phases[1].note)
        assertEquals("28 steps, 2 images · 7.4 it/s", phases[2].note)

        val same = run("2", 0, "m", JobStart.SAME, 700)
        assertEquals(JobPhase.Kind.PROMPT, GenerationStatistics.phases(same).first().kind)
        val unsplit = run("3", 0, "m", JobStart.COLD, 15_000)
        assertEquals(JobPhase.Kind.LOADING, GenerationStatistics.phases(unsplit).first().kind)
    }

    @Test
    fun `durations read the way people say them`() {
        assertEquals("0.7 s", GenerationStatistics.duration(700))
        assertEquals("24.6 s", GenerationStatistics.duration(24_600))
        assertEquals("1 min 15 s", GenerationStatistics.duration(75_000))
        assertEquals("6 h 12 m", GenerationStatistics.duration(22_320_000))
        assertEquals("5", GalleryInsights.number(5f))
        assertEquals("6.5", GalleryInsights.number(6.5f))
        assertEquals("0.35", GalleryInsights.number(0.35f))
    }

    @Test
    fun `a statistics row opens the gallery with only its own filter`() {
        val current = ForgeGalleryManager.GalleryFilters(prompt = "cat", sortOrder = ForgeGalleryManager.SortOrder.OLDEST)
        val size = StatTarget.filters(StatTarget.Detail(GalleryDetailFilter(GalleryDetailFilter.Kind.SIZE, "832x1216")), current)
        assertEquals("", size.prompt)
        assertEquals(ForgeGalleryManager.SortOrder.OLDEST, size.sortOrder)
        assertEquals("Size 832×1216", size.detail?.label)
        assertTrue(size.isSearch)
        assertEquals(setOf("flux1-dev"), StatTarget.filters(StatTarget.Model("flux1-dev"), current).models)
        assertEquals("smile", StatTarget.filters(StatTarget.Tag("smile"), current).prompt)
        assertEquals("Euler a · Karras", GalleryDetailFilter(GalleryDetailFilter.Kind.SAMPLER, "Euler a", "Karras").label)
        assertEquals("The model's own VAE", GalleryDetailFilter(GalleryDetailFilter.Kind.MODULES, "").label)
    }
}
