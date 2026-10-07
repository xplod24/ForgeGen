package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class G13_CleanupFixesTest {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            TestApp.start(seed = { favorites["/out/fav.png"] = FavoriteImageEntity("/out/fav.png", "fav.png", null) })
        }
    }

    @Test fun `ulubione maja gwiazdke zaraz po starcie`() {
        awaitUntil("ulubione wczytane przy starcie") { TestApp.vm.favoritePaths.value == setOf("/out/fav.png") }
    }

    @Test fun `zmiana nazwy presetu na pusta lub zajeta zostawia stara nazwe`() {
        val vm = TestApp.vm
        onMain { vm.savePreset("A") }
        onMain { vm.savePreset("B") }
        val b = vm.config.value.presets.first { it.name == "B" }

        onMain { vm.updatePreset("B", b.copy(name = "A", includePrompts = false)) }
        assertEquals(listOf("A", "B"), vm.config.value.presets.map { it.name })
        assertFalse("pozostałe zmiany i tak zapisane", vm.config.value.presets.first { it.name == "B" }.includePrompts)

        onMain { vm.updatePreset("B", b.copy(name = "   ")) }
        assertEquals(listOf("A", "B"), vm.config.value.presets.map { it.name })

        onMain { vm.updatePreset("B", b.copy(name = "  C  ")) }
        assertEquals(listOf("A", "C"), vm.config.value.presets.map { it.name })
    }

    @Test fun `timeout spoza zakresu jest przycinany przy zapisie`() {
        val vm = TestApp.vm
        onMain { vm.saveConfig(vm.config.value.copy(timeout = 0)) }
        assertEquals(1, vm.config.value.timeout)
        onMain { vm.saveConfig(vm.config.value.copy(timeout = -3)) }
        assertEquals(1, vm.config.value.timeout)
        onMain { vm.saveConfig(vm.config.value.copy(timeout = 10)) }
    }

    @Test fun `reset ustawien zachowuje polaczenie, presety i profile`() {
        val vm = TestApp.vm
        val url = vm.config.value.apiUrl
        val profiles = listOf(ServerProfile("Dom", url))
        onMain {
            vm.saveConfig(
                vm.config.value.copy(keepScreenOn = true, notificationMode = "Verbose", timeout = 45, serverProfiles = profiles, galleryPath = "/srv/out"),
            )
        }
        onMain { vm.savePreset("Zostaje") }
        onMain { vm.updateState { it.copy(steps = 77) } }

        onMain { vm.wipeSettings() }

        val c = vm.config.value
        assertFalse(c.keepScreenOn)
        assertEquals("Simple", c.notificationMode)
        assertEquals(10, c.timeout)
        assertEquals(url, c.apiUrl)
        assertEquals("/srv/out", c.galleryPath)
        assertEquals(profiles, c.serverProfiles)
        assertTrue(c.presets.any { it.name == "Zostaje" })
        assertEquals(AppState().steps, vm.appState.value.steps)
    }
}
