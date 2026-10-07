package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** IIB locked with a secret key (IIB_SECRET_KEY), as scripts/iib/api.py verify_secret checks it. */
object MockIibKey {
    /** The server's IIB_SECRET_KEY, null for none. */
    @Volatile var key: String? = null

    /** Forge with a login (--gradio-auth) and IIB without a key: every request is refused with 400. */
    @Volatile var loginWithoutKey = false

    /** The Cookie header of every IIB request. */
    val cookies = CopyOnWriteArrayList<String>()

    fun reset() {
        key = null
        loginWithoutKey = false
        cookies.clear()
    }

    fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun route(
        ex: HttpExchange,
        path: String,
        body: String,
    ): Boolean {
        if (path.startsWith("/infinite_image_browsing/")) {
            val cookie = ex.requestHeaders.getFirst("Cookie")
            cookies += cookie ?: "(none)"
            if (loginWithoutKey) return send(ex, 400, """{"detail":{"type":"secret_key_required"}}""")
            val secret = key
            if (secret != null) {
                val token = cookie?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("IIB_S=") }?.removePrefix("IIB_S=")
                if (token != sha256(secret + "_ciallo")) return send(ex, 401, """{"detail":{"type":"secret_verification_failed"}}""")
            }
        }
        return MockIibFiles.route(ex, path, body)
    }

    private fun send(
        ex: HttpExchange,
        code: Int,
        text: String,
    ): Boolean {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        return true
    }
}

/** 3.5.0: no key written in the code; the app asks for IIB's key once and sends only its fingerprint. */
@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G46_GalleryKeyTest {
    companion object {
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> MockIibKey.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
            awaitUntil("gallery extension", 20_000) { state() == ForgeGalleryManager.Extension.READY }
        }

        fun state() = vm.galleryExtension.value.state

        fun server() = GalleryKey.serverOf(vm.config.value.apiUrl)

        fun sync() {
            awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
            TestApp.db.settings["gallery_full_sync_at"] = "0"
            val field = ForgeGalleryManager::class.java.getDeclaredField("lastAutoSyncAt").apply { isAccessible = true }
            field.setLong(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else ForgeGalleryManager, 0L)
            onMain { vm.autoSyncGallery() }
            Thread.sleep(300)
            awaitUntil("sync done", 20_000) { !vm.isGalleryIndexing.value }
        }

        /** "Unlock" with [key]; true when the server took it. */
        fun unlock(key: String): Boolean {
            val result = AtomicReference<Boolean?>(null)
            onMain { vm.saveGalleryKey(key) { result.set(it) } }
            awaitUntil("key checked", 20_000) { result.get() != null }
            return result.get()!!
        }
    }

    @Before fun reset() {
        MockIib.reset()
        MockIibFiles.reset()
        val day = MockIib.dir("/out", "2026-09-30")
        MockIib.file(day, "a1.png", "alpha", date = "2026-09-30 10:01:00")
    }

    @Test fun `01 a server without a key - nothing is sent and nothing asked`() {
        MockIibKey.reset()
        onMain { vm.checkGalleryExtension() }
        awaitUntil("ready", 10_000) { state() == ForgeGalleryManager.Extension.READY }
        sync()
        println("[G46-01] cookies=${MockIibKey.cookies.toSet()}")
        assertTrue("no cookie written in the code any more", MockIibKey.cookies.isNotEmpty() && MockIibKey.cookies.all { it == "(none)" })
        assertTrue(vm.config.value.galleryKeys.isEmpty())
    }

    @Test fun `02 the server gets a key - the next gallery request locks the gallery`() {
        MockIibKey.reset()
        MockIibKey.key = "secret"
        sync()
        awaitUntil("locked", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED }
        println("[G46-02] ${vm.galleryExtension.value}")
        assertNull("no key saved: nothing to blame", vm.galleryExtension.value.message)
    }

    @Test fun `03 a wrong key is refused and not kept`() {
        MockIibKey.reset()
        MockIibKey.key = "secret"
        onMain { vm.checkGalleryExtension() }
        awaitUntil("locked", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED }
        assertEquals(false, unlock("wrong"))
        assertEquals(ForgeGalleryManager.Extension.LOCKED, state())
        assertEquals("The server did not accept this key.", vm.galleryExtension.value.message)
        assertTrue(vm.config.value.galleryKeys.isEmpty())
    }

    @Test fun `04 the right key opens the gallery and only its fingerprint is kept and sent`() {
        MockIibKey.reset()
        MockIibKey.key = "secret"
        onMain { vm.checkGalleryExtension() }
        awaitUntil("locked", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED }
        assertEquals(true, unlock("  secret \n"))
        awaitUntil("ready", 10_000) { state() == ForgeGalleryManager.Extension.READY }
        val fingerprint = MockIibKey.sha256("secret_ciallo")
        assertEquals(mapOf(server() to fingerprint), vm.config.value.galleryKeys)
        MockIibKey.cookies.clear()
        sync()
        println("[G46-04] cookies=${MockIibKey.cookies.toSet()}")
        assertTrue(MockIibKey.cookies.isNotEmpty() && MockIibKey.cookies.all { it == "IIB_S=$fingerprint" })
        assertTrue("the gallery works", TestApp.db.gallery.containsKey("/out/2026-09-30/a1.png"))
    }

    @Test fun `05 the key changed on the server - the saved one is named as no longer working`() {
        MockIibKey.reset()
        MockIibKey.key = "secret"
        onMain { vm.checkGalleryExtension() }
        awaitUntil("locked or ready", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED || state() == ForgeGalleryManager.Extension.READY }
        if (state() == ForgeGalleryManager.Extension.LOCKED) assertEquals(true, unlock("secret"))
        MockIibKey.key = "new secret"
        sync()
        awaitUntil("locked", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED }
        assertEquals("The key saved for this server no longer opens the gallery.", vm.galleryExtension.value.message)
        assertEquals(true, unlock("new secret"))
        assertEquals(ForgeGalleryManager.Extension.READY, state())
    }

    @Test fun `06 removing the key - the gallery asks again`() {
        MockIibKey.reset()
        MockIibKey.key = "secret"
        onMain { vm.checkGalleryExtension() }
        awaitUntil("locked or ready", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED || state() == ForgeGalleryManager.Extension.READY }
        if (state() == ForgeGalleryManager.Extension.LOCKED) assertEquals(true, unlock("secret"))
        onMain { vm.forgetGalleryKey() }
        awaitUntil("locked", 10_000) { state() == ForgeGalleryManager.Extension.LOCKED }
        assertTrue(vm.config.value.galleryKeys.isEmpty())
        assertNull(vm.galleryExtension.value.message)
    }

    @Test fun `07 Forge with a login and IIB without a key - the server must set one`() {
        MockIibKey.reset()
        MockIibKey.loginWithoutKey = true
        onMain { vm.checkGalleryExtension() }
        awaitUntil("key not set", 10_000) { state() == ForgeGalleryManager.Extension.KEY_NOT_SET }
        MockIibKey.reset()
        onMain { vm.checkGalleryExtension() }
        awaitUntil("ready", 10_000) { state() == ForgeGalleryManager.Extension.READY }
    }
}
