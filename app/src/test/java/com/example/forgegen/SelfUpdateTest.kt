package com.example.forgegen

import com.example.forgegen.SelfUpdate.Action
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
