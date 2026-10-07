package com.example.forgegen

import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G18_WhatsNewTest {
    companion object {
        @BeforeClass @JvmStatic fun init() {
            AssetManager.files["CHANGELOG.md"] = "## 1.1.0\n- New gallery\n\n## 1.0.2\n- Lock fixes\n"
            FakeApp.lastUpdateTime = 2 // the app was updated, and never showed the notes before
            TestApp.start()
        }
    }

    @Test fun `01 after an update a bar offers the notes once, and Show opens them`() {
        val vm = TestApp.vm
        awaitUntil("notes") { vm.whatsNew.value != null }
        assertEquals("## 1.1.0\n- New gallery", vm.whatsNew.value)
        assertTrue("the bar shows", vm.whatsNewBar.value)
        assertFalse("the notes wait for Show", vm.whatsNewOpen.value)
        assertEquals("1.1.0", vm.whatsNewVersion)
        assertNull("not seen before the bar is on screen", TestApp.db.settings[WhatsNew.LAST_SEEN_KEY])

        onMain { vm.markWhatsNewSeen() }
        awaitUntil("seen as soon as the bar shows") { TestApp.db.settings[WhatsNew.LAST_SEEN_KEY] == "1.1.0" }

        onMain { vm.openWhatsNew() }
        assertFalse("the bar leaves", vm.whatsNewBar.value)
        assertTrue(vm.whatsNewOpen.value)
        onMain { vm.hideWhatsNewBar() } // its time runs out while the notes are open: they stay
        assertEquals("## 1.1.0\n- New gallery", vm.whatsNew.value)

        onMain { vm.dismissWhatsNew() }
        assertNull(vm.whatsNew.value)
        assertFalse(vm.whatsNewOpen.value)
    }

    @Test fun `02 a bar whose time is up takes the notes with it`() {
        val vm = TestApp.vm
        onMain { vm.debugShowWhatsNew() }
        awaitUntil("bar again") { vm.whatsNewBar.value && vm.whatsNew.value != null }
        onMain { vm.hideWhatsNewBar() }
        assertFalse(vm.whatsNewBar.value)
        assertNull(vm.whatsNew.value)
    }
}
