package com.example.forgegen

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class BridgePrivateStoreTest {
    @Test fun deviceCredentialIsEncryptedBoundToProfileAndExcludedFromNormalBackups() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidBridgeStore(context)
        val base = "https://synthetic-${UUID.randomUUID()}.invalid/forge/"
        val other = base + "other/"
        val token = UUID.randomUUID().toString()
        try {
            store.write(base, BridgeSession(enabled = true, token = token, serverId = UUID.randomUUID().toString()))
            assertTrue(store.read(base).token == token)
            assertEquals("", store.read(other).token)
            val directory = File(context.noBackupFilesDir, "forgegen-bridge")
            assertTrue(directory.isDirectory)
            assertTrue(directory.listFiles().orEmpty().none { it.readBytes().toString(Charsets.UTF_8).contains(token) })
            assertFalse(File(context.filesDir, "forgegen-bridge").exists())
        } finally {
            store.forget(base)
            store.forget(other)
        }
    }
}
