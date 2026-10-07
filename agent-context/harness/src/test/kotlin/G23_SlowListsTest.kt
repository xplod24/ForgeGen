package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** A server that answers the ping but not the model list: connected, but not "Ready". */
class G23_SlowListsTest {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            TestApp.start(
                custom = { ex, path, _ ->
                    if (path == "/sdapi/v1/sd-models") {
                        ex.sendResponseHeaders(500, -1); ex.close(); true
                    } else {
                        false
                    }
                },
                awaitServer = false,
            )
        }
    }

    @Test fun `failed model list`() {
        val a = TestApp.afterInit
        println("[G23] $a ${TestApp.statuses}")
        assertEquals("Connected, but the model list failed to load", a.status)
        assertTrue(a.initialized)
        assertTrue(a.connected)
    }
}
