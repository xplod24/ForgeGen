package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Model changes in the queue (3.6.0): their costs from the history, Group by Model and the timeline with changes. */
class QueueGroupingTest {
    private fun job(
        id: String,
        model: String,
        modules: List<String>? = null,
        status: GenerationStatus = GenerationStatus.QUEUED,
    ) = QueuedGeneration(
        id = id,
        positivePrompt = "p",
        status = status,
        payload =
            Txt2ImgPayloadDto(
                prompt = "p",
                negative_prompt = "",
                steps = 20,
                cfg_scale = 7f,
                width = 1000,
                height = 1000,
                n_iter = 1,
                batch_size = 1,
                seed = -1,
                sampler_name = "Euler",
                scheduler = "Automatic",
                override_settings = OverrideSettingsDto(clipSkip = 1, sdModelCheckpoint = model, forgeAdditionalModules = modules),
                enable_hr = false,
                hr_scale = 2f,
                hr_upscaler = "Latent",
                denoising_strength = 0.5f,
            ),
    )

    private fun start(
        kind: JobStart,
        model: String,
        firstStepMs: Long,
        previous: String? = null,
        firstHash: Boolean = false,
    ) = JobStartTime(model, previous, kind.name, firstHash, firstStepMs)

    private val history =
        listOf(
            start(JobStart.SAME, "a", 500),
            start(JobStart.SAME, "b", 700),
            start(JobStart.SAME, "a", 600),
            // a → b twice, b → a once, c only cold.
            start(JobStart.SWAP, "b", 20_600, previous = "a"),
            start(JobStart.SWAP, "b", 22_600, previous = "a"),
            start(JobStart.SWAP, "a", 10_600, previous = "b"),
            start(JobStart.COLD, "c", 40_600),
            // A checkpoint's first load (its hash) is not a usual one.
            start(JobStart.SWAP, "c", 90_000, previous = "a", firstHash = true),
        )

    private val costs = ModelChangeCosts.of(history)
    private val loadedA = LoadedModel(LoadedModel.KNOWN, "a", "")

    @Test
    fun `a change costs the median for its pair of models, else for the model swapped in, else any swap`() {
        assertEquals("the same model's start", 600L, costs.sameMs)
        assertEquals(21_000L, costs.swapMs("a", "b"))
        assertEquals(10_000L, costs.swapMs("b", "a"))
        assertEquals("no c → b yet: any swap into b", 21_000L, costs.swapMs("c", "b"))
        assertEquals("nothing into c but its first load: any swap", 20_000L, costs.swapMs("a", "c"))
        assertEquals(40_000L, costs.coldMs("c"))
        assertEquals("a cold start of a model never loaded cold: any cold start", 40_000L, costs.coldMs("a"))
        assertNull(ModelChangeCosts.none.swapMs("a", "b"))
        assertNull(ModelChangeCosts.none.coldMs("a"))
    }

    @Test
    fun `the speed is learned without the loading`() {
        fun run(
            kind: JobStart,
            first: Long?,
            load: Long? = null,
        ) = JobRunEntity(
            "r",
            "s",
            null,
            0L,
            "a",
            "",
            null,
            kind.name,
            false,
            1000,
            1000,
            1,
            20,
            "Euler",
            "Automatic",
            null,
            null,
            load,
            null,
            first,
            null,
            null,
            null,
            30_000L,
            null,
            null,
            null,
            null,
            null,
            null,
            "DONE",
            null,
            null,
        )
        assertEquals(20_000L, costs.loadingMs(run(JobStart.SWAP, 20_600)))
        assertEquals(40_000L, costs.loadingMs(run(JobStart.COLD, 40_600)))
        assertEquals("the same model: nothing to take off", 0L, costs.loadingMs(run(JobStart.SAME, 600)))
        assertEquals("an unknown start that waited for a load", 15_000L, costs.loadingMs(run(JobStart.UNKNOWN, 15_600, load = 9_000)))
        assertEquals(0L, costs.loadingMs(run(JobStart.UNKNOWN, 900)))
        assertEquals(0L, costs.loadingMs(run(JobStart.SWAP, null)))
    }

    @Test
    fun `the changes are found from the model in memory`() {
        val jobs = listOf(job("1", "a"), job("2", "b"), job("3", "b"), job("4", "a"))
        val changes = QueueGrouping.changes(loadedA, jobs, costs)
        assertEquals(listOf(null, 21_000L, null, 10_000L), changes.map { it?.ms })
        assertEquals("a", changes[1]!!.from)
        // An unloaded server: the first job loads its model cold.
        val cold = QueueGrouping.changes(LoadedModel.empty, jobs, costs)
        assertEquals(true, cold[0]!!.cold)
        assertEquals(40_000L, cold[0]!!.ms)
        // Not known what the server has: the first job's change cannot be told.
        assertNull(QueueGrouping.changes(LoadedModel.unknown, jobs, costs)[0])
        // Another VAE on the same checkpoint is a change too.
        val vae = QueueGrouping.changes(loadedA, listOf(job("1", "a", listOf("vae.safetensors"))), costs)
        assertEquals("a", vae[0]!!.from)
    }

    @Test
    fun `group by model keeps each model's jobs together, the loaded model first`() {
        val queue =
            listOf(
                job("1", "b"),
                job("2", "a"),
                job("3", "b"),
                job("4", "c"),
                job("5", "a"),
                job("6", "c"),
            )
        val plan = QueueGrouping.plan(queue, loadedA, costs)!!
        assertEquals(listOf("2", "5", "1", "3", "4", "6"), plan.order)
        // Before: a→b, b→a, a→b, b→c, c→a, a→c. After: a→b, b→c.
        assertEquals(6, plan.before)
        assertEquals(2, plan.after)
        assertEquals(4, plan.spared)
        // Spared: b→a 10 s, a→b 21 s, c→a (any into a: 10 s), a→c (any swap: 20 s).
        assertEquals(61_000L, plan.savedMs)
    }

    @Test
    fun `the running job stays first and jobs set aside stay where they are`() {
        val queue =
            listOf(
                job("run", "b", status = GenerationStatus.GENERATING),
                job("1", "a"),
                job("2", "b"),
                job("3", "a"),
                job("4", "b"),
                job("x", "a", status = GenerationStatus.FAILED),
            )
        val plan = QueueGrouping.plan(queue, loadedA, costs)!!
        // After the running job the server has b: its jobs go first.
        assertEquals(listOf("run", "2", "4", "1", "3", "x"), plan.order)
        assertEquals(listOf("run", "2", "4", "1", "3", "x"), QueueGrouping.reorder(queue, plan.order).map { it.id })
        // A job whose connection broke is sent again first: it stays too.
        val suspended = queue.map { if (it.id == "run") it.copy(status = GenerationStatus.SUSPENDED) else it }
        assertEquals("run", QueueGrouping.plan(suspended, loadedA, costs)!!.order.first())
    }

    @Test
    fun `nothing is suggested when it spares nothing or could change a job's images`() {
        assertNull(QueueGrouping.plan(listOf(job("1", "a"), job("2", "a"), job("3", "b")), loadedA, costs))
        // A job without a checkpoint runs on whatever the server has: moving it could change its images.
        assertNull(QueueGrouping.plan(listOf(job("1", "a"), job("2", "b"), job("3", "a"), job("4", ""), job("5", "b")), loadedA, costs))
        // Without the history the changes are counted, but their time is not known.
        val plan = QueueGrouping.plan(listOf(job("1", "a"), job("2", "b"), job("3", "a"), job("4", "b")), loadedA, ModelChangeCosts.none)!!
        assertEquals(2, plan.spared)
        assertNull(plan.savedMs)
    }

    @Test
    fun `undo puts the old order back, with the jobs added meanwhile last`() {
        val queue = listOf(job("1", "b"), job("2", "a"), job("3", "b"), job("4", "a"))
        val before = queue.map { it.id }
        val grouped = QueueGrouping.reorder(queue, QueueGrouping.plan(queue, loadedA, costs)!!.order)
        val withNew = grouped + job("5", "c")
        assertEquals(listOf("1", "2", "3", "4", "5"), QueueGrouping.reorder(withNew, before).map { it.id })
    }

    @Test
    fun `the timeline adds each change's cost and a cold start after an unload`() {
        val rates = mapOf(QueueEstimate.ANY_MODEL to 1.0) // 20 s a job
        val queue = listOf(job("1", "a"), job("2", "b"), job("3", "b"))
        val timeline = QueueEstimate.timeline(queue, rates, 0.0, loadedA, costs)
        assertEquals(listOf(20.0, 61.0, 81.0), timeline.ends)
        assertEquals(21.0, timeline.changeSeconds, 1e-9)
        assertEquals(81.0, timeline.remaining!!, 1e-9)
        val cold = QueueEstimate.timeline(queue, rates, 0.0, LoadedModel.empty, costs)
        assertEquals(listOf(60.0, 101.0, 121.0), cold.ends)
        // The running job's own load is behind it (or in the server's estimate); the next one starts from its model.
        val running = listOf(job("r", "b", status = GenerationStatus.GENERATING), job("1", "b"), job("2", "a"))
        val withRunning = QueueEstimate.timeline(running, rates, 5.0, loadedA, costs)
        assertEquals(listOf(5.0, 25.0, 55.0), withRunning.ends)
        assertNull(withRunning.changes[0])
        // Without the history nothing is added.
        assertEquals(listOf(20.0, 40.0, 60.0), QueueEstimate.timeline(queue, rates, 0.0, loadedA, ModelChangeCosts.none).ends)
    }

    @Test
    fun `the times are written for the queue`() {
        assertEquals("45 s", QueueEstimate.formatSpan(45))
        assertEquals("49 min 30 s", QueueEstimate.formatSpan(2970))
        assertEquals("2 h 05 min", QueueEstimate.formatSpan(7500))
        assertEquals("about 26 s", QueueEstimate.formatAbout(25_600))
        assertEquals("about 2 min", QueueEstimate.formatAbout(125_000))
    }
}
