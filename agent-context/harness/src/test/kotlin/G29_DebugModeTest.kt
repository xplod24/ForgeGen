package com.example.forgegen

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 1.4.0: the hidden debug mode. The password is never in the repository (only its PBKDF2 hash is in the app): set
 * FORGEGEN_DEBUG_PASSWORD to run this class, otherwise it is skipped. Ask the owner for it; never write it down here.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G29_DebugModeTest {
    companion object {
        val vm get() = TestApp.vm
        val toasts = CopyOnWriteArrayList<String>()
        val PASSWORD: String = System.getenv("FORGEGEN_DEBUG_PASSWORD").orEmpty()

        @BeforeClass @JvmStatic fun init() {
            org.junit.Assume.assumeTrue("FORGEGEN_DEBUG_PASSWORD is not set", PASSWORD.isNotEmpty())
            TestApp.start()
            CoroutineScope(Dispatchers.IO).launch { vm.toastMessage.collect { toasts += it } }
        }
    }

    @Test fun `01 only the right password unlocks, and the state stays on the device`() {
        assertFalse(vm.debugUnlocked.value)
        assertEquals(DebugMode.UnlockResult.WRONG_PASSWORD, runBlocking { vm.debugUnlock(PASSWORD.lowercase().takeIf { it != PASSWORD } ?: (PASSWORD + "x")) })
        assertFalse(vm.debugUnlocked.value)
        val t0 = System.currentTimeMillis()
        assertEquals(DebugMode.UnlockResult.UNLOCKED, runBlocking { vm.debugUnlock(PASSWORD) })
        println("[G29-01] PBKDF2 check took ${System.currentTimeMillis() - t0} ms")
        assertTrue(vm.debugUnlocked.value)
        assertEquals("true", android.content.Context.PREFS["debug"]?.get("unlocked"))
    }

    @Test fun `03 raw settings are applied, checked like stored settings`() {
        val json = vm.debugConfigJson()
        assertTrue(json.contains("\"themeMode\""))
        assertTrue(vm.debugApplyConfigJson("{not json").orEmpty().startsWith("Not valid JSON"))
        val edited = json.replace(Regex("\"timeout\": \\d+"), "\"timeout\": 77").replace("\"enableLogging\": false", "\"enableLogging\": true")
        assertNull(vm.debugApplyConfigJson(edited))
        assertEquals(77 to true, vm.config.value.timeout to vm.config.value.enableLogging)
        assertNull(vm.debugApplyConfigJson(json.replace(Regex("\"themeMode\": \"\\w+\""), "\"themeMode\": \"Purple\"")))
        assertEquals("a wrong value falls back to its default", THEME_SYSTEM, vm.config.value.themeMode)
        assertEquals("the server stays", TestApp.forge.url, vm.config.value.apiUrl)
    }

    @Test fun `04 test notifications look like the real ones`() {
        fun titles() = synchronized(NotificationManagerCompat.posted) { NotificationManagerCompat.posted.map { (it[1] as Notification).title } }
        val before = titles().size
        onMain { vm.debugTestNotification("batch"); vm.debugTestNotification("queue"); vm.debugTestNotification("failed") }
        println("[G29-04] ${titles().drop(before)}")
        assertEquals(listOf("Batch Completed", "Queue Completed", "Queue finished with errors"), titles().drop(before))
    }

    @Test fun `05 the full log goes to Downloads, without asking for the OOM consent`() {
        onMain { vm.saveConfig(vm.config.value.copy(saveOomLogs = false)) }
        toasts.clear()
        vm.debugSaveFullLog()
        awaitUntil("saved", 10_000) { TestApp.app.resolver.rows.values.any { it.name.startsWith("ForgeGen-Debug-") && !it.pending } }
        val row = TestApp.app.resolver.rows.values.single { it.name.startsWith("ForgeGen-Debug-") }
        val text = row.file.readText()
        println("[G29-05] ${row.relativePath}${row.name}\n${text.take(400)}")
        assertEquals("Download/", row.relativePath)
        assertTrue(text.startsWith("ForgeGen debug log"))
        assertTrue(text.contains("complete, with the content of HTTP requests"))
        awaitUntil("toast") { toasts.any { it == "Debug log saved to Downloads: ${row.name}" } }
    }

    @Test fun `07 Live Updates can be offered on any phone, and wiping the settings turns everything off`() {
        android.os.Build.VERSION.SDK_INT = 35
        assertFalse(LiveUpdates.isSupported(TestApp.app))
        onMain { vm.debugSetForceLiveUpdates(true) }
        assertTrue(LiveUpdates.isSupported(TestApp.app))
        onMain { vm.wipeSettings() }
        assertFalse(vm.debugUnlocked.value)
        assertFalse(LiveUpdates.isSupported(TestApp.app))
        assertNull(android.content.Context.PREFS["debug"]?.get("unlocked"))
        onMain { vm.debugSetForceLiveUpdates(true) }
        assertFalse("locked again: debug actions do nothing", LiveUpdates.isSupported(TestApp.app))
    }
}
