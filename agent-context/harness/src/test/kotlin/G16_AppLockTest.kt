package com.example.forgegen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class G16_AppLockTest {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
        }
    }

    private val vm get() = TestApp.vm

    @Test fun `blokada wlacza sie z ustawieniem i znika tylko po odblokowaniu`() {
        onMain { vm.lockApp() } // "unlocked" lasts until the app is left; start from a left app
        onMain { vm.saveConfig(vm.config.value.copy(useNativeSecurity = false)) }
        awaitUntil("brak blokady bez ustawienia") { !vm.isLocked.value }

        onMain { vm.saveConfig(vm.config.value.copy(useNativeSecurity = true)) }
        awaitUntil("zablokowana po włączeniu") { vm.isLocked.value }

        onMain { vm.markUnlocked() }
        awaitUntil("odblokowana") { !vm.isLocked.value }

        onMain { vm.lockApp() } // the app went to the background
        awaitUntil("znów zablokowana") { vm.isLocked.value }
    }

    @Test fun `nowy ViewModel przy wlaczonej blokadzie jest zablokowany od pierwszej klatki`() {
        onMain { vm.saveConfig(vm.config.value.copy(useNativeSecurity = true)) }
        onMain { vm.markUnlocked() }
        val second = onMain { ForgeViewModel(TestApp.app) }
        // Read immediately: a false initial value would show the app's content for a frame before the lock.
        assertTrue(second.isLocked.value)
        onMain { vm.saveConfig(vm.config.value.copy(useNativeSecurity = false)) }
        awaitUntil("wyłączenie zdejmuje blokadę") { !second.isLocked.value }
        assertFalse(vm.isLocked.value)
    }
}
