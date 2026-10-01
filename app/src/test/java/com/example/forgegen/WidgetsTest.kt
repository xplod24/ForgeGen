package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen widgets (3.6.0): what they say, and how seldom the app redraws them. */
class WidgetsTest {
    private val time: (Long) -> String = { "T$it" }

    private val running =
        WidgetState(
            connected = true,
            waiting = 4,
            done = 1,
            total = 5,
            percent = 42,
            endsAt = 1542L,
            imagesToday = 48,
            gpuTodayMs = 14 * 60_000L,
            vramUsedGb = 7.6,
            vramTotalGb = 12.0,
        )

    @Test
    fun `a running queue`() {
        assertEquals("42%", WidgetText.queueTitle(running))
        assertEquals("4 left · about T1542", WidgetText.queueLine(running, time))
        assertEquals("Job 2 of 5 · 42%", WidgetText.jobLine(running))
        assertEquals("done about T1542", WidgetText.endLine(running, time))
        assertEquals("48", WidgetText.images(running))
        assertEquals("14 min", WidgetText.gpu(running))
        assertEquals("7.6 GB" to "VRAM of 12", WidgetText.vram(running))
        assertEquals("Pause", WidgetText.pauseLabel(running))
    }

    @Test
    fun `paused, waiting and idle`() {
        val paused = running.copy(pausedByUser = true, paused = true, percent = null)
        assertEquals("paused", WidgetText.queueTitle(paused))
        assertEquals("Paused · 4 left", WidgetText.queueLine(paused, time))
        assertEquals("Paused · 4 left", WidgetText.jobLine(paused))
        assertEquals("Resume", WidgetText.pauseLabel(paused))
        val waiting = running.copy(percent = null, endsAt = null)
        assertEquals("4 waiting", WidgetText.queueTitle(waiting))
        assertEquals("4 left", WidgetText.queueLine(waiting, time))
        assertEquals("4 waiting", WidgetText.jobLine(waiting))
        val idle = WidgetState(waiting = 0, lastDoneAt = 1431L, imagesToday = 48)
        assertEquals("idle", WidgetText.queueTitle(idle))
        assertEquals("All done at T1431", WidgetText.queueLine(idle, time))
        assertEquals("No jobs waiting", WidgetText.jobLine(idle))
        assertEquals("All done at T1431", WidgetText.endLine(idle, time))
        assertNull("no Pause without jobs", WidgetText.pauseLabel(idle))
        assertEquals("No jobs", WidgetText.queueLine(WidgetState(), time))
        // Without the history and before the app read the VRAM.
        assertEquals("–", WidgetText.images(WidgetState()))
        assertEquals("–", WidgetText.gpu(WidgetState()))
        assertEquals("–" to "VRAM", WidgetText.vram(WidgetState()))
        assertEquals("1 h 05 min", WidgetText.gpu(WidgetState(gpuTodayMs = 65 * 60_000L)))
        assertEquals("40 s", WidgetText.gpu(WidgetState(gpuTodayMs = 40_000L)))
    }

    @Test
    fun `the progress is pushed every 10 percent or every 10 seconds, everything else at once`() {
        val t = 1_000_000L
        assertTrue("the first state", WidgetThrottle.shouldPush(null, 0L, running, t))
        assertFalse("nothing new", WidgetThrottle.shouldPush(running, t, running, t + 60_000))
        assertFalse("43% a second later", WidgetThrottle.shouldPush(running, t, running.copy(percent = 43), t + 1_000))
        assertFalse("a new end time a second later", WidgetThrottle.shouldPush(running, t, running.copy(endsAt = 1543L), t + 1_000))
        assertTrue("50%: the next step", WidgetThrottle.shouldPush(running, t, running.copy(percent = 50), t + 1_000))
        assertTrue("43% after 10 s", WidgetThrottle.shouldPush(running, t, running.copy(percent = 43), t + 10_000))
        assertTrue("a job done", WidgetThrottle.shouldPush(running, t, running.copy(done = 2, waiting = 3, percent = 0), t + 500))
        assertTrue("paused", WidgetThrottle.shouldPush(running, t, running.copy(pausedByUser = true), t + 500))
        assertTrue("connection lost", WidgetThrottle.shouldPush(running, t, running.copy(connected = false), t + 500))
    }

    @Test
    fun `today starts at midnight`() {
        val now =
            java.util.Calendar
                .getInstance()
                .apply { set(2026, 9, 1, 15, 4, 30) }
                .timeInMillis
        val start =
            java.util.Calendar
                .getInstance()
                .apply { timeInMillis = ForgeWidgets.startOfToday(now) }
        assertEquals(0, start.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(java.util.Calendar.MINUTE))
        assertEquals(1, start.get(java.util.Calendar.DAY_OF_MONTH))
    }
}
