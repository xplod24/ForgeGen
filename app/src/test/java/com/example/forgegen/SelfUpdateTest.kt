package com.example.forgegen

import com.example.forgegen.SelfUpdate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SelfUpdateTest {
    private fun decide(
        latest: Int?,
        auto: Boolean = true,
        onScreen: Boolean = false,
        queue: Boolean = false,
    ) =
        SelfUpdate.decide(installedCode = 200_000_100, latestCode = latest, autoInstall = auto, appOnScreen = onScreen, queueWorking = queue)

    @Test
    fun `a newer release installs itself in the background`() {
        assertEquals(Action.INSTALL, decide(200_000_200))
    }

    @Test
    fun `nothing newer, nothing to do`() {
        assertEquals(Action.NONE, decide(200_000_100))
        assertEquals(Action.NONE, decide(100_600_200))
        assertEquals(Action.NONE, decide(null))
    }

    @Test
    fun `never under the user's hands or during a queue`() {
        assertEquals("the app's own dialog offers it", Action.NONE, decide(200_000_200, onScreen = true))
        assertEquals("installing would end the running queue", Action.WAIT, decide(200_000_200, queue = true))
    }

    @Test
    fun `turned off - only a notification`() {
        assertEquals(Action.NOTIFY, decide(200_000_200, auto = false))
        assertEquals(Action.NOTIFY, decide(200_000_200, auto = false, queue = true))
    }

    @Test
    fun `one automatic check a day, by the phone's own calendar day`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        // 2026-10-03 23:30 and 2026-10-04 00:30 in Warsaw (UTC+2): an hour apart, two days.
        val lateEvening = 1_791_063_000_000L
        assertEquals("2026-10-03", SelfUpdate.dayOf(lateEvening, warsaw))
        assertEquals("2026-10-04", SelfUpdate.dayOf(lateEvening + 60 * 60 * 1000L, warsaw))
        assertTrue("none ran yet", SelfUpdate.autoCheckDue(null, "2026-10-03"))
        assertFalse("ran today, also without a connection", SelfUpdate.autoCheckDue("2026-10-03", "2026-10-03"))
        assertTrue("the next day", SelfUpdate.autoCheckDue("2026-10-03", "2026-10-04"))
    }

    @Test
    fun `the daily job replaces the 6-hour one of older versions`() {
        assertTrue("none planned", SelfUpdate.needsScheduling(null))
        assertTrue("3.6.0-2 and older", SelfUpdate.needsScheduling(6 * 60 * 60 * 1000L))
        assertFalse("already daily", SelfUpdate.needsScheduling(24 * 60 * 60 * 1000L))
    }

    @Test
    fun `installing updates by itself is off unless turned on`() {
        assertFalse(AppConfig().autoInstallUpdates)
    }
}
