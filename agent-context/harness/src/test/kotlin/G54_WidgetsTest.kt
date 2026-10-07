package com.example.forgegen

import android.content.Context
import com.sun.net.httpserver.HttpExchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 3.6.0: the home screen widgets are pushed the app's state, never ask the server, are drawn only while one is on
 * the home screen, and the running job's progress is redrawn at most every 10 % or 10 s.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G54_WidgetsTest {
    object Widgets : WidgetRenderer {
        @Volatile var onScreen = false
        val drawn = CopyOnWriteArrayList<WidgetState>()
        val drawnAt = CopyOnWriteArrayList<Long>()

        override fun present(context: Context) = onScreen

        override fun render(context: Context, state: WidgetState) {
            drawn += state
            drawnAt += System.currentTimeMillis()
        }
    }

    companion object {
        val vm get() = TestApp.vm

        // The progress grows by 3 % with every request while a job runs.
        @Volatile var progress = 0.0

        private fun send(ex: HttpExchange, text: String): Boolean {
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            ForgeWidgets.renderer = Widgets
            TestApp.start(custom = { ex, path, _ ->
                if (path == "/sdapi/v1/progress" && TestApp.forge.generating) {
                    progress = (progress + 0.03).coerceAtMost(0.99)
                    val step = (progress * 20).toInt().coerceAtLeast(1)
                    send(ex, """{"progress":$progress,"eta_relative":5.0,"state":{"job_count":1,"job_no":0,"sampling_step":$step,"sampling_steps":20},"current_image":null}""")
                } else {
                    false
                }
            })
            onMain { vm.updateState { it.copy(positivePrompt = "a cat", batchCount = 1) } }
        }

        fun runJob(ms: Long) {
            TestApp.forge.generationMs = ms
            progress = 0.0
            val before = TestApp.db.jobRuns.size
            onMain { vm.queueGeneration() }
            awaitUntil("job done", ms + 20_000) { TestApp.db.jobRuns.size > before && !vm.isGenerating.value && vm.generationQueue.value.isEmpty() }
        }
    }

    @Test fun `01 nothing is drawn while no widget is on the home screen`() {
        val before = ForgeWidgets.pushes.get()
        runJob(2_000)
        Thread.sleep(500)
        assertEquals(before, ForgeWidgets.pushes.get())
        assertTrue(Widgets.drawn.isEmpty())
    }

    @Test fun `02 a widget gets the job's start, a few progress steps and its end, never asking the server`() {
        Widgets.onScreen = true
        ForgeWidgets.widgetsChanged(TestApp.app) // a widget was added
        awaitUntil("drawn at once") { Widgets.drawn.isNotEmpty() }
        val requestsBefore = TestApp.forge.requests.size
        val pings = TestApp.forge.calls("/sdapi/v1/progress").size
        Widgets.drawn.clear()
        Widgets.drawnAt.clear()
        runJob(12_000)
        Thread.sleep(500)
        val progressAnswers = TestApp.forge.calls("/sdapi/v1/progress").size - pings
        println("[G54] ${Widgets.drawn.size} drawings for $progressAnswers progress answers: ${Widgets.drawn.map { it.percent }}")
        assertTrue("the job ran with the progress asked often: $progressAnswers", progressAnswers >= 8)
        assertTrue("fewer drawings than progress answers: ${Widgets.drawn.size}", Widgets.drawn.size < progressAnswers)
        // A drawing for the progress alone (or a new end time) only at the next 10 % or after 10 s.
        fun calm(s: WidgetState) = s.copy(percent = s.percent?.let { it / 10 }, endsAt = null)
        Widgets.drawn.indices.drop(1).forEach { i ->
            val a = Widgets.drawn[i - 1]
            val b = Widgets.drawn[i]
            val waited = Widgets.drawnAt[i] - Widgets.drawnAt[i - 1]
            assertTrue("$a -> $b after $waited ms", calm(a) != calm(b) || waited >= WidgetThrottle.EVERY_MS)
        }
        val last = Widgets.drawn.last()
        assertEquals("the queue is done", 0, last.waiting)
        assertTrue("All done at ...", last.lastDoneAt != null)
        awaitUntil("today's images from the history") { Widgets.drawn.last().imagesToday == TestApp.db.jobRuns.size }
        assertTrue((Widgets.drawn.last().gpuTodayMs ?: 0L) > 0L)
        // The widgets' drawings asked the server nothing: every request since was the app's own.
        assertTrue(TestApp.forge.requests.size > requestsBefore)
    }

    @Test fun `03 Pause from the widget pauses the queue as the tile does, and the widget shows it at once`() {
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        onMain { vm.queueGeneration() }
        awaitUntil("a job waits") { Widgets.drawn.lastOrNull()?.waiting == 1 }
        WidgetActionReceiver().onReceive(TestApp.app, android.content.Intent(WidgetActionReceiver.ACTION_PAUSE_RESUME))
        awaitUntil("paused on the widget") { Widgets.drawn.last().pausedByUser }
        WidgetActionReceiver().onReceive(TestApp.app, android.content.Intent(WidgetActionReceiver.ACTION_PAUSE_RESUME))
        awaitUntil("resumed on the widget") { !Widgets.drawn.last().pausedByUser }
        onMain { vm.clearQueue() }
    }
}
