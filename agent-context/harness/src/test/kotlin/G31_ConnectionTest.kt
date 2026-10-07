package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 2.0.0: the start does not wait for the server; a minute of pings (3 s here), then the app stops asking (OFFLINE)
 * until another try; an active queue never gives up; nothing is asked in the background with nothing to do.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G31_ConnectionTest {
    companion object {
        @Volatile var down = true
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            ForgeRepository.searchWindowMs = 3_000
            ForgeRepository.searchPingMs = 300
            TestApp.start(
                custom = { ex, path, _ -> if (down && path.startsWith("/sdapi/")) { ex.close(); true } else false },
                awaitServer = false,
            )
        }

        fun pings() = TestApp.forge.calls("/sdapi/v1/progress").size
        fun state() = vm.connection.value
    }

    @Test fun `01 an unreachable server is tried for the window, then not any more`() {
        println("[G31-01] start: local=${TestApp.afterInit.localMs} ms, ${state()}, pings=${pings()}")
        assertTrue("the screen did not wait: ${TestApp.afterInit.localMs} ms", TestApp.afterInit.localMs < 2_000)
        awaitUntil("offline", 8_000) { state() == ServerConnection.OFFLINE }
        val tried = pings()
        println("[G31-01] offline after $tried pings")
        assertTrue("about every 300 ms for 3 s: $tried", tried in 5..15)
        Thread.sleep(2_000)
        assertEquals("no pings while offline", tried, pings())
    }

    @Test fun `02 another try, and the server that came back is used at once`() {
        val before = pings()
        onMain { vm.reconnect() }
        assertEquals(ServerConnection.SEARCHING, state())
        assertTrue(vm.searchEndsAt.value > System.currentTimeMillis() + 2_000)
        awaitUntil("pinging again", 2_000) { pings() > before + 1 }
        down = false
        awaitUntil("connected", 5_000) { state() == ServerConnection.CONNECTED && vm.isConnected.value }
        awaitUntil("lists", 10_000) { vm.models.value.isNotEmpty() }
    }

    @Test fun `03 a lost connection gets a new window, then goes offline`() {
        down = true
        val lostAt = System.currentTimeMillis()
        awaitUntil("searching", 5_000) { state() == ServerConnection.SEARCHING }
        assertTrue("a new window from the loss", vm.searchEndsAt.value >= lostAt + 2_500)
        awaitUntil("offline", 8_000) { state() == ServerConnection.OFFLINE }
    }

    @Test fun `04 an active queue never gives up`() {
        TestApp.forge.generationMs = 300
        onMain { vm.updateState { it.copy(positivePrompt = "waiting job", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("queued", 5_000) { vm.generationQueue.value.isNotEmpty() }
        awaitUntil("searching for the queue", 5_000) { state() == ServerConnection.SEARCHING }
        Thread.sleep(6_000) // twice the window
        println("[G31-04] after 6 s: ${state()} queue=${vm.generationQueue.value.map { it.status }}")
        assertEquals(ServerConnection.SEARCHING, state())
        down = false
        onMain { vm.reconnect() }
        awaitUntil("job sent and done", 15_000) { vm.generationQueue.value.isEmpty() && vm.sessionImages.value.isNotEmpty() }
        assertEquals(ServerConnection.CONNECTED, state())
    }

    @Test fun `05 nothing is asked in the background with nothing to do, and the return pings at once`() {
        awaitUntil("connected", 5_000) { state() == ServerConnection.CONNECTED }
        onMain { vm.setAppForegroundState(false) }
        Thread.sleep(2_500)
        val idle = pings()
        Thread.sleep(3_000)
        assertEquals("no pings in the background", idle, pings())
        onMain { vm.setAppForegroundState(true) }
        awaitUntil("ping on return", 1_500) { pings() > idle }
    }

    @Test fun `06 back on screen while offline starts a new window`() {
        down = true
        awaitUntil("offline", 12_000) { state() == ServerConnection.OFFLINE }
        onMain { vm.setAppForegroundState(false) }
        onMain { vm.setAppForegroundState(true) }
        assertEquals(ServerConnection.SEARCHING, state())
        down = false
        awaitUntil("connected", 5_000) { state() == ServerConnection.CONNECTED }
    }

    @Test fun `07 the dialog's Test answers, and Connect uses a new address at once`() {
        awaitUntil("connected", 8_000) { state() == ServerConnection.CONNECTED }
        val ok = kotlinx.coroutines.runBlocking { vm.testServer(TestApp.forge.url) }
        val none = kotlinx.coroutines.runBlocking { vm.testServer("127.0.0.1:1") }
        val bad = kotlinx.coroutines.runBlocking { vm.testServer("http://exa mple") }
        println("[G31-07] ok=$ok none=$none bad=$bad")
        assertTrue(ok, ok.startsWith("Answered in"))
        assertTrue(none, none.startsWith("No answer"))
        assertTrue(bad, bad == "Not a valid address" || bad.startsWith("No answer"))
        // A dead address: searching, then offline; the right one again: connected.
        onMain { vm.connectTo("http://127.0.0.1:1") }
        awaitUntil("saved", 2_000) { vm.config.value.apiUrl == "http://127.0.0.1:1" }
        awaitUntil("lost", 5_000) { state() != ServerConnection.CONNECTED }
        onMain { vm.connectTo(TestApp.forge.url) }
        awaitUntil("connected again", 5_000) { state() == ServerConnection.CONNECTED && vm.config.value.apiUrl == TestApp.forge.url }
    }

    @Test fun `08 a queue waiting for its start time does not ask the server from the background`() {
        awaitUntil("connected", 8_000) { state() == ServerConnection.CONNECTED }
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        onMain { vm.updateState { it.copy(positivePrompt = "later", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("queued") { vm.generationQueue.value.isNotEmpty() }
        onMain { vm.setAppForegroundState(false) }
        Thread.sleep(2_500)
        val idle = pings()
        Thread.sleep(3_000)
        assertEquals("no pings while the queue only waits", idle, pings())
        onMain { vm.startScheduledQueueNow() } // the alarm
        awaitUntil("the job runs from the background", 15_000) { vm.generationQueue.value.isEmpty() && pings() > idle }
        onMain { vm.setAppForegroundState(true) }
    }
}
