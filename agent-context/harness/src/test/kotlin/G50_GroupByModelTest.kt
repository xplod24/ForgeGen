package com.example.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.ConcurrentHashMap

/**
 * 3.6.0: the queue knows about model changes. Its timeline adds the swaps the history measured, Group by Model is
 * offered when it spares at least two of them ("Group" with "Undo", "Not Now" until a job is added), and the speed is
 * learned without the time the model took to load. The stand-in server is G49's: it loads a checkpoint inside the job.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G50_GroupByModelTest {
    companion object {
        val vm get() = TestApp.vm
        lateinit var server: String

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(config = { copy(timeout = 6) }, custom = { ex, path, body -> G49_JobPhasesTest.StandIn.route(ex, path, body) })
            server = GalleryKey.serverOf(TestApp.forge.url)
            G49_JobPhasesTest.StandIn.loaded = "model"
            G49_JobPhasesTest.StandIn.oom = false
            G49_JobPhasesTest.StandIn.loadMs = 5_000L
            G49_JobPhasesTest.StandIn.vramMs = 1_000L
        }

        fun title(name: String) = vm.models.value.first { it.title.startsWith(name) }.title

        private fun field(owner: Any, name: String) =
            owner::class.java.getDeclaredField(name).apply { isAccessible = true }.let { f ->
                f.get(if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else owner)
            }

        /** What the app believes the server has (as a finished job would leave it). */
        @Suppress("UNCHECKED_CAST")
        fun believeLoaded(model: String) {
            val state = LoadedModel(LoadedModel.KNOWN, title(model), "")
            (field(JobRecorder, "loaded") as ConcurrentHashMap<String, LoadedModel>)[server] = state
            (field(JobRecorder, "_loadedState") as MutableStateFlow<Pair<String, LoadedModel>?>).value = server to state
        }

        /** A history of swaps: "model" → "other" 20 s more than the same model's start, back 10 s. */
        @Suppress("UNCHECKED_CAST")
        fun seedHistory() {
            TestApp.db.jobRuns.clear()

            fun run(i: Int, kind: JobStart, model: String, first: Long, previous: String? = null) {
                val r =
                    JobRunEntity(
                        "seed$i", server, null, 1_000L * i, title(model), "", previous?.let { title(it) }, kind.name, false, 512, 512,
                        1, 20, "Euler", "Automatic", null, null, null, null, first, null, null, null, first + 2_000, null, null,
                        null, null, null, null, "DONE", null, null,
                    )
                TestApp.db.jobRuns[r.id] = r
            }
            (1..3).forEach { run(it, JobStart.SAME, "model", 600) }
            (4..6).forEach { run(it, JobStart.SWAP, "other", 20_600, previous = "model") }
            (7..9).forEach { run(it, JobStart.SWAP, "model", 10_600, previous = "other") }
            // The history was read again (as after a recorded job).
            (field(JobRecorder, "_recorded") as MutableStateFlow<Int>).value += 1
        }

        @Suppress("UNCHECKED_CAST")
        fun rates() = (field(ForgeQueueManager, "speedRates") as MutableStateFlow<Map<String, Double>>)

        fun add(model: String) {
            onMain { vm.changeCheckpoint(title(model)) }
            onMain { vm.updateState { it.copy(positivePrompt = "a $model", batchCount = 1, steps = 20, hiresFix = false) } }
            val before = vm.generationQueue.value.size
            onMain { vm.queueGeneration() }
            awaitUntil("job added") { vm.generationQueue.value.size == before + 1 }
        }

        fun models() = vm.generationQueue.value.map { GenerationStatistics.nameOf(it.payload.override_settings.sdModelCheckpoint.orEmpty()) }

        var original: List<String> = emptyList()
    }

    @Test fun `01 the timeline adds the measured swaps and Group by Model says what it spares`() {
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        believeLoaded("model")
        seedHistory()
        rates().value = mapOf(QueueEstimate.ANY_MODEL to 0.1) // 512×512×20: about 0.5 s a job
        listOf("model", "other", "model", "other", "model").forEach { add(it) }
        awaitUntil("the swaps on the timeline") { vm.queueTimeline.value.changes.count { it != null } == 4 }
        val timeline = vm.queueTimeline.value
        assertEquals(listOf(null, 20_000L, 10_000L, 20_000L, 10_000L), timeline.changes.map { it?.ms })
        assertEquals(60.0, timeline.changeSeconds, 1e-6)
        val jobs = vm.generationQueue.value.sumOf { QueueEstimate.seconds(rates().value, it.payload)!! }
        assertEquals("the end counts the swaps", jobs + 60.0, timeline.ends.last()!!, 1e-6)
        awaitUntil("the suggestion") { vm.groupingSuggestion.value != null }
        val plan = vm.groupingSuggestion.value!!
        assertEquals(3, plan.spared)
        assertEquals("three swaps less: 10 + 20 + 10 s", 40_000L, plan.savedMs)
        original = vm.generationQueue.value.map { it.id }
    }

    @Test fun `02 Group puts each model's jobs together, Undo puts them back and keeps the card away`() {
        val before = vm.groupQueueByModel()
        assertEquals(original, before)
        assertEquals(listOf("model", "model", "model", "other", "other"), models())
        awaitUntil("one swap left") { vm.queueTimeline.value.changes.count { it != null } == 1 }
        assertEquals(20.0, vm.queueTimeline.value.changeSeconds, 1e-6)
        awaitUntil("nothing more to spare") { vm.groupingSuggestion.value == null }
        vm.restoreQueueOrder(before!!)
        assertEquals(original, vm.generationQueue.value.map { it.id })
        Thread.sleep(300)
        assertNull("Undo does not bring the card back at once", vm.groupingSuggestion.value)
        awaitUntil("put off in the settings") { TestApp.db.settings["queue_grouping_dismissed"].orEmpty().split(',').toSet() == original.toSet() }
    }

    @Test fun `03 a new job brings the card back, Not Now puts it off again`() {
        add("other")
        awaitUntil("the card again") { vm.groupingSuggestion.value != null }
        vm.dismissGrouping()
        awaitUntil("put off") { vm.groupingSuggestion.value == null }
    }

    @Test fun `04 the grouped queue runs in its new order and learns its speed without the load`() {
        vm.groupQueueByModel()
        assertEquals(listOf("model", "model", "model", "other", "other", "other"), models())
        rates().value = emptyMap()
        val otherPayload = vm.generationQueue.value.last().payload
        val before = TestApp.db.jobRuns.size
        onMain { ForgeQueueManager.startScheduledQueueNow() }
        awaitUntil("all six done", 120_000) { TestApp.db.jobRuns.size == before + 6 && vm.generationQueue.value.isEmpty() }
        val runs = TestApp.db.jobRuns.values.filter { !it.id.startsWith("seed") }.sortedBy { it.startedAt }
        assertEquals(listOf("SAME", "SAME", "SAME", "SWAP", "SAME", "SAME"), runs.map { it.startKind })
        val swap = runs[3]
        assertNotNull(swap.firstStepMs)
        assertTrue("the swap took its load: ${swap.firstStepMs}", swap.firstStepMs!! > 3_500)
        // The estimate for "other" is about its jobs without the load (three learned, the first one with the swap).
        val learned = QueueEstimate.seconds(rates().value, otherPayload)!! * 1000
        val plain = runs.drop(4).map { it.totalMs }.average()
        println("[G50] learned $learned ms, a plain job $plain ms, the swap ${swap.totalMs} ms (first step ${swap.firstStepMs})")
        assertTrue("learned $learned ms against $plain ms a job without a load (the swap took ${swap.totalMs} ms)", learned < plain + 1_500)
    }
}
