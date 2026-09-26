package com.example.forgegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The password itself is never in the repository; these tests check the mechanism around it.
class DebugModeTest {
    @Test
    fun `the hash is PBKDF2 with HMAC-SHA256`() {
        // RFC 7914, section 11: PBKDF2-HMAC-SHA256("passwd", "salt", 1), its first 32 bytes.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            DebugMode.toHex(DebugMode.hash("passwd", "salt".toByteArray(), 1)),
        )
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        assertArrayEquals(bytes, DebugMode.fromHex(DebugMode.toHex(bytes)))
    }

    @Test
    fun `wrong passwords are refused, and after five the next ones wait a minute`() {
        DebugMode.lock()
        val t = 1_000_000L
        repeat(5) { assertEquals(DebugMode.UnlockResult.WRONG_PASSWORD, DebugMode.unlock("debug", now = t)) }
        assertEquals(DebugMode.UnlockResult.TOO_MANY_ATTEMPTS, DebugMode.unlock("anything", now = t + 59_000))
        assertEquals(DebugMode.UnlockResult.WRONG_PASSWORD, DebugMode.unlock("DEBUG", now = t + 60_001))
        assertFalse(DebugMode.unlocked.value)
        assertFalse(DebugMode.matches(""))
    }

    @Test
    fun `nothing can be forced while the debug mode is locked`() {
        DebugMode.lock()
        DebugMode.setForceNowBar(true)
        assertFalse(DebugMode.forceNowBar.value)
    }
}
