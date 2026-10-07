package com.example.forgegen

import android.app.Notification
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 2.3.0-1: the update downloads in UpdateDownloadService (a foreground service), not in the screen's coroutine that a
 * locked screen froze: progress for Settings and the notification; no modal dialog.
 * 3.0.0-3: two steps, "Download" (checked file, ready) and "Install" (the app steps aside, one install at a time).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G35_UpdateDownloadTest {
    companion object {
        val apk = ByteArray(400_000) { (it * 17 % 251).toByte() }
        val sha: String = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        @Volatile var slowMs = 0L
        val seen = CopyOnWriteArrayList<SelfUpdate.DownloadProgress?>()
        val app get() = TestApp.app
        val prefs get() = app.getSharedPreferences("updates", 0)

        private fun send(ex: com.sun.net.httpserver.HttpExchange, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { out ->
                // In pieces, so the progress has steps.
                body.toList().chunked(50_000).forEach { part ->
                    out.write(part.toByteArray())
                    out.flush()
                    if (slowMs > 0) Thread.sleep(slowMs)
                }
            }
        }

        fun notices(): List<Pair<Int, Notification>> =
            synchronized(NotificationManagerCompat.posted) {
                NotificationManagerCompat.posted.map { (it[0] as Int) to (it[1] as Notification) }
            }

        @BeforeClass @JvmStatic fun init() {
            TestApp.startHook = { url -> GitHubApi.baseUrl = "$url/" }
            TestApp.start(custom = { ex, path, _ ->
                when (path) {
                    "/repos/xplod24/ForgeGen/releases/latest" -> {
                        val asset = """{"name": "app-debug.apk", "browser_download_url": "${TestApp.forge.url}/dl/app-debug.apk", "size": ${apk.size}, "digest": "sha256:$sha"}"""
                        send(ex, "application/json", """{"tag_name": "v9.9.9", "name": "v9.9.9", "body": "- Better", "published_at": "2026-09-27T10:00:00Z", "assets": [$asset]}""".toByteArray())
                        true
                    }
                    "/dl/app-debug.apk" -> {
                        send(ex, "application/vnd.android.package-archive", apk)
                        true
                    }
                    else -> false
                }
            })
            android.content.ContextWrapper.base = TestApp.app
            CoroutineScope(Dispatchers.Unconfined).launch { SelfUpdate.downloadProgress.collect { seen += it } }
        }

        fun manifest(sha256: String? = sha) =
            UpdateManifest(versionCode = versionCodeFromTag("v9.9.9")!!, versionName = "9.9.9", url = "${TestApp.forge.url}/dl/app-debug.apk", sha256 = sha256, size = apk.size.toLong())

        /** What UpdateDownloadService.start sends, delivered to a service created here (the fake app starts none). */
        fun startService(manifest: UpdateManifest): UpdateDownloadService {
            app.startedServices.clear()
            UpdateDownloadService.start(app, manifest)
            val intent: Intent = app.startedServices.single { it.getComponentClass() == UpdateDownloadService::class.java }
            return UpdateDownloadService().also { it.onStartCommand(intent, 0, 1) }
        }
    }

    @Before fun reset() {
        prefs.edit().remove("installing_version").remove("ready_update").apply()
        SelfUpdate.apkFile(app).delete()
        SelfUpdate.setInstalling(null)
        SelfUpdate.refreshReady(app, null)
        NotificationManagerCompat.posted.clear()
        seen.clear()
        slowMs = 0
    }

    @Test fun `01 Download only downloads, checked, then ready to install, nothing installed`() {
        slowMs = 60
        val service = startService(manifest())
        awaitUntil("ready", 15_000) { SelfUpdate.readyUpdate.value?.versionName == "9.9.9" }
        awaitUntil("done", 10_000) { SelfUpdate.downloadProgress.value == null && service.stops > 0 }
        val steps = seen.filterNotNull()
        println("[G35-01] ${steps.size} steps, last: ${steps.lastOrNull()}")
        assertTrue("progress in steps", steps.count { it.done in 1 until apk.size } >= 2)
        assertTrue("the size is known", steps.all { it.total == apk.size.toLong() })
        assertNull("not installed by the download (3.0.0-3)", prefs.getString("installing_version", null))
        assertNull(SelfUpdate.installing.value)
        assertEquals("${versionCodeFromTag("v9.9.9")}:${apk.size}", prefs.getString("ready_update", null))
        val progress = notices().filter { it.first == UpdateDownloadService.ID_DOWNLOAD_NOTIFICATION }.map { it.second }
        println("[G35-01] ${progress.map { "${it.title}: ${it.text}" }} / ${notices().map { it.second.title }}")
        assertTrue(progress.any { it.title.toString() == "Downloading ForgeGen 9.9.9" && it.text.toString().contains("% · ") })
        assertTrue(progress.all { it.ongoing })
        assertTrue(progress.none { it.title.toString().startsWith("Installing") })
        // After a restart the file is still known as ready.
        SelfUpdate.refreshReady(app, null)
        assertNull(SelfUpdate.readyUpdate.value)
        SelfUpdate.refreshReady(app, manifest())
        assertEquals("9.9.9", SelfUpdate.readyUpdate.value?.versionName)
    }

    @Test fun `02 a file that does not match the release is not kept and says so`() {
        startService(manifest(sha256 = "0".repeat(64)))
        awaitUntil("over", 15_000) { SelfUpdate.downloadProgress.value == null && seen.isNotEmpty() && seen.last() == null }
        Thread.sleep(200)
        assertNull(SelfUpdate.readyUpdate.value)
        assertNull(prefs.getString("ready_update", null))
        assertFalse(SelfUpdate.apkFile(app).exists())
        val failed = notices().filter { it.first == SelfUpdate.ID_UPDATE_NOTIFICATION }.map { it.second.title.toString() }
        assertEquals(listOf("ForgeGen update failed"), failed)
    }

    @Test fun `03 Download hands the release to the service, once, and not again once it is ready`() {
        onMain { TestApp.vm.checkForUpdates(manual = true) }
        awaitUntil("offered") { TestApp.vm.updateManifest.value?.versionName == "9.9.9" }
        app.startedServices.clear()
        onMain { TestApp.vm.downloadUpdate() }
        val started = app.startedServices.filter { it.getComponentClass() == UpdateDownloadService::class.java }
        assertEquals(1, started.size)
        assertEquals("9.9.9", started.single().getStringExtra("version_name"))
        // While a download runs, a second tap starts nothing.
        SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress("9.9.9", 1, 2))
        app.startedServices.clear()
        onMain { TestApp.vm.downloadUpdate() }
        assertTrue(app.startedServices.isEmpty())
        SelfUpdate.setDownloadProgress(null)
        // Ready: "Install" is offered instead, "Download" starts nothing.
        SelfUpdate.markReady(app, manifest(), SelfUpdate.apkFile(app).apply { writeBytes(apk) })
        onMain { TestApp.vm.downloadUpdate() }
        assertTrue(app.startedServices.isEmpty())
    }

    @Test fun `04 Install sends the app to the background, then installs the checked file, once`() {
        val service = startService(manifest())
        awaitUntil("ready", 15_000) { SelfUpdate.readyUpdate.value != null && service.stops > 0 }
        onMain { TestApp.vm.checkForUpdates(manual = true) }
        awaitUntil("offered and ready") { TestApp.vm.updateManifest.value?.versionName == "9.9.9" && TestApp.vm.readyUpdate.value != null }
        var sentBack = 0
        onMain { TestApp.vm.installUpdate { sentBack++ } }
        assertEquals("9.9.9", TestApp.vm.installingUpdate.value)
        awaitUntil("installing", 10_000) { prefs.getString("installing_version", null) == "9.9.9" }
        assertEquals(1, sentBack)
        // A second tap while it installs does nothing (the loop of 3.0.0-2).
        onMain { TestApp.vm.installUpdate { sentBack++ } }
        onMain { TestApp.vm.downloadUpdate() }
        Thread.sleep(300)
        assertEquals(1, sentBack)
        // The system refused: "Install" can be tapped again, the file stays ready.
        SelfUpdate.notifyFailed(app, "INSTALL_FAILED_TEST")
        assertNull(TestApp.vm.installingUpdate.value)
        assertEquals("9.9.9", TestApp.vm.readyUpdate.value?.versionName)
        onMain { TestApp.vm.installUpdate { sentBack++ } }
        awaitUntil("again", 10_000) { sentBack == 2 }
        SelfUpdate.setInstalling(null)
    }

    @Test fun `05 a damaged file is not installed, download again`() {
        val file = SelfUpdate.apkFile(app).apply {
            parentFile?.mkdirs()
            writeBytes(apk)
        }
        onMain { TestApp.vm.checkForUpdates(manual = true) }
        awaitUntil("offered") { TestApp.vm.updateManifest.value?.versionName == "9.9.9" }
        SelfUpdate.markReady(app, manifest(), file)
        file.writeBytes(ByteArray(apk.size)) // same size, other bytes
        var sentBack = 0
        onMain { TestApp.vm.installUpdate { sentBack++ } }
        awaitUntil("refused", 10_000) { TestApp.vm.installingUpdate.value == null && TestApp.vm.readyUpdate.value == null }
        assertEquals(0, sentBack)
        // Written right after the state changes: waited for, not read at once.
        awaitUntil("prefs cleared") { prefs.getString("installing_version", null) == null && prefs.getString("ready_update", null) == null }
    }
}
