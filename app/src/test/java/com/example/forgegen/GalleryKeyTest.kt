package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryKeyTest {
    @Test
    fun `the fingerprint is IIB's cookie for the key`() {
        // hashlib.sha256(b"secret_ciallo").hexdigest(), as scripts/iib/api.py computes it
        assertEquals("b5e7724843ea08bdcc2ae023d25bba73f2ac7f6ff1f3d83103c29d23df3e4bea", GalleryKey.fingerprint("secret"))
        assertEquals("386f24e565fa22844536e843b95e544867a69c6ee6ff86be3b4fc58a52978f26", GalleryKey.fingerprint("my key"))
        assertEquals("spaces around it, as .env reads them", GalleryKey.fingerprint("secret"), GalleryKey.fingerprint("  secret\n"))
    }

    @Test
    fun `keys are kept per server`() {
        val config = AppConfig(apiUrl = "http://192.168.1.90:7860/")
        assertNull(GalleryKey.savedFor(config))
        val saved = GalleryKey.withFingerprint(config, "abc")
        assertEquals(mapOf("http://192.168.1.90:7860" to "abc"), saved.galleryKeys)
        assertEquals("abc", GalleryKey.savedFor(saved.copy(apiUrl = "HTTP://192.168.1.90:7860")))
        assertNull("another server", GalleryKey.savedFor(saved.copy(apiUrl = "http://10.0.0.2:7860")))
        val two = GalleryKey.withFingerprint(saved.copy(apiUrl = "http://10.0.0.2:7860"), "def")
        assertEquals(2, two.galleryKeys.size)
        assertEquals(mapOf("http://192.168.1.90:7860" to "abc"), GalleryKey.withFingerprint(two, null).galleryKeys)
    }

    @Test
    fun `IIB's error types are read from its answers`() {
        assertEquals(GalleryKey.LOCKED_TYPE, GalleryKey.errorType("""{"detail":{"type":"secret_verification_failed"}}"""))
        assertEquals(GalleryKey.KEY_REQUIRED_TYPE, GalleryKey.errorType("""{"detail":{"type":"secret_key_required"}}"""))
        assertNull(GalleryKey.errorType("""{"detail":"User is not authorized to perform this action."}"""))
        assertNull(GalleryKey.errorType("Not Found"))
        assertNull(GalleryKey.errorType(null))
    }

    @Test
    fun `backups leave the keys out`() {
        val config = AppConfig(galleryKeys = mapOf("http://192.168.1.90:7860" to GalleryKey.fingerprint("secret")))
        val json = Backup.write(config, emptyList(), "3.5.0")
        assertFalse(json.contains(GalleryKey.fingerprint("secret")))
        assertTrue(Backup.read(json)!!.config.galleryKeys.isEmpty())
    }
}
