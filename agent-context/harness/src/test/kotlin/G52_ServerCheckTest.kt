package com.example.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * 3.6.0: Forge's report is slow (it lists its Python packages), so the app asks for it only on "Check Now" and keeps
 * the last check for each server; the light parts of the server page (flags, extensions) still load by themselves.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G52_ServerCheckTest {
    companion object {
        val vm get() = TestApp.vm
        lateinit var server: String

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> MockServer.route(ex, path, body) })
            server = GalleryKey.serverOf(TestApp.forge.url)
        }

        fun reports() = TestApp.forge.calls("/internal/sysinfo").size
    }

    @Test fun `01 the server page reads no report by itself`() {
        // What the page does when it shows.
        onMain { vm.loadServerInfo() }
        onMain { vm.loadServerCheck() }
        awaitUntil("flags and extensions") { vm.serverInfo.value?.extensions != null }
        Thread.sleep(2_000)
        assertEquals("never asked", 0, reports())
        assertEquals(null, vm.lastServerCheck.value)
        assertTrue(TestApp.forge.calls("/sdapi/v1/cmd-flags").isNotEmpty())
    }

    @Test fun `02 Check Now reads it once and keeps it for this server`() {
        val memory = TestApp.forge.calls("/sdapi/v1/memory").size
        onMain { vm.checkServer() }
        assertTrue("checking", vm.checkingSince.value > 0)
        onMain { vm.checkServer() } // a second tap while it runs asks nothing more
        awaitUntil("checked", 10_000) { vm.checkingSince.value == 0L && vm.lastServerCheck.value != null }
        assertEquals(1, reports())
        assertTrue("the VRAM's counters with it", TestApp.forge.calls("/sdapi/v1/memory").size > memory)
        val check = vm.lastServerCheck.value!!
        assertEquals("neo-2.1", check.version)
        assertTrue(System.currentTimeMillis() - check.checkedAt < 10_000)
        val saved = TestApp.db.settings["server_check:$server"]
        assertNotNull("saved for this server", saved)
        assertTrue(saved!!.contains("neo-2.1"))
    }

    @Test fun `03 the kept check is there after a restart of the app, without asking again`() {
        // As after a new start: nothing in memory.
        val f = ForgeRepository::class.java.getDeclaredField("checkedServer").apply { isAccessible = true }
        f.set(ForgeRepository, null)
        @Suppress("UNCHECKED_CAST")
        (ForgeRepository::class.java.getDeclaredField("_serverCheck").apply { isAccessible = true }.get(ForgeRepository) as MutableStateFlow<ServerCheck?>).value = null
        onMain { vm.loadServerCheck() }
        awaitUntil("read from the settings") { vm.lastServerCheck.value?.version == "neo-2.1" }
        assertEquals(1, reports())
        // Share Server Report sends the kept report.
        assertNotNull(vm.lastServerCheck.value!!.report)
    }
}
