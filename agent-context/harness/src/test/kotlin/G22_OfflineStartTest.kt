package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** An unreachable server does not hold the start forever, and the status does not claim "Ready". */
class G22_OfflineStartTest {
    companion object {
        @Volatile var down = true

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(
                custom = { ex, path, _ ->
                    if (down && path.startsWith("/sdapi/")) { ex.close(); true } else false
                },
                awaitServer = false,
            )
        }
    }

    @Test fun `offline start`() {
        val a = TestApp.afterInit
        println("[G22] $a ${TestApp.statuses}")
        assertEquals("Server not reachable", a.status)
        assertTrue(a.initialized)
        assertFalse(a.connected)
        assertTrue("did not wait long: ${a.ms} ms", a.ms < 5000)
        assertFalse("Ready" in TestApp.statuses)
        // The app works on: once the server is back, it connects and loads the lists.
        down = false
        awaitUntil("connected later", 40_000) { TestApp.vm.isConnected.value && TestApp.vm.models.value.isNotEmpty() }
    }
}
