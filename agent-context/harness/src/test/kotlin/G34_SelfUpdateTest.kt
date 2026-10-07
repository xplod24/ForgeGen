package com.example.forgegen

import android.app.ActivityManager
import android.app.Notification
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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

/** 2.0.2: the background update check against a GitHub stand-in: download, digest, the decision, the notices. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G34_SelfUpdateTest {
    companion object {
        val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
        @Volatile var tag = "v2.0.2"
        @Volatile var digest: String? = "sha256:" + MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

        fun releases() = TestApp.forge.calls("/repos/xplod24/ForgeGen/releases/latest").size
        fun downloads() = TestApp.forge.calls("/dl/app-debug.apk").size
        val app get() = TestApp.app
        val prefs get() = app.getSharedPreferences("updates", 0)

        /** The background check as on a new day: today's automatic check not taken yet (3.6.1: once a day). */
        fun backgroundCheck() {
            prefs.edit().remove("auto_check_day").apply()
            runBlocking { SelfUpdate.checkInBackground(app) }
        }

        fun today() = SelfUpdate.dayOf(System.currentTimeMillis())

        /** A manager of its own, as at a later start of the app (its offer starts empty). */
        fun freshManager() = ForgeUpdateManager(app, GitHubApi.create(), {}, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))

        fun updateNotices(): List<Notification> =
            synchronized(NotificationManagerCompat.posted) {
                NotificationManagerCompat.posted.filter { it[0] == SelfUpdate.ID_UPDATE_NOTIFICATION }.map { it[1] as Notification }
            }

        private fun send(ex: com.sun.net.httpserver.HttpExchange, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }

        @BeforeClass @JvmStatic fun init() {
            // Before the start: its automatic check asks the stand-in too.
            TestApp.startHook = { url -> GitHubApi.baseUrl = "$url/" }
            TestApp.start(custom = { ex, path, _ ->
                when (path) {
                    "/repos/xplod24/ForgeGen/releases/latest" -> {
                        val asset = """{"name": "app-debug.apk", "browser_download_url": "${TestApp.forge.url}/dl/app-debug.apk", "size": ${apk.size}, "digest": ${digest?.let { "\"$it\"" } ?: "null"}}"""
                        send(ex, "application/json", """{"tag_name": "$tag", "name": "$tag", "body": "- Better", "published_at": "2026-09-27T10:00:00Z", "assets": [$asset]}""".toByteArray())
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
        }
    }

    @Before fun reset() {
        ActivityManager.testImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        prefs.edit().remove("installing_version").remove("notified_version").apply()
        android.content.Context.testJobScheduler = null
        SelfUpdate.setAutoInstall(app, true)
        NotificationManagerCompat.posted.clear()
        tag = "v2.0.2"
    }

    @Test fun `01 one automatic check a day - the start takes it, later starts offer what it found, a manual check asks`() {
        awaitUntil("the start's check") { releases() >= 1 }
        awaitUntil("offered") { TestApp.vm.updateManifest.value?.versionName == "2.0.2" }
        assertEquals("the day is taken", today(), prefs.getString("auto_check_day", null))
        Thread.sleep(500)
        val afterStart = releases()
        // A later start the same day: GitHub is not asked, the release found at the first start is offered.
        val later = freshManager()
        later.checkForUpdates(manual = false)
        awaitUntil("the saved release offered") { later.updateManifest.value?.versionName == "2.0.2" }
        onMain { TestApp.vm.checkForUpdates(manual = false) }
        // Nor the background job.
        runBlocking { SelfUpdate.checkInBackground(app) }
        Thread.sleep(800)
        assertEquals("once a day", afterStart, releases())
        onMain { TestApp.vm.checkForUpdates(manual = true) }
        awaitUntil("manual check") { releases() > afterStart }
    }

    @Test fun `02 closed app, idle queue - downloaded, checked and installed without asking`() {
        SelfUpdate.apkFile(app).delete()
        backgroundCheck()
        assertArrayEquals(apk, SelfUpdate.apkFile(app).readBytes())
        assertEquals("the install session was committed", "2.0.2", prefs.getString("installing_version", null))
        // A second check reuses the verified download.
        val before = downloads()
        prefs.edit().remove("installing_version").apply()
        backgroundCheck()
        assertEquals(before, downloads())
        assertEquals("2.0.2", prefs.getString("installing_version", null))
    }

    @Test fun `03 a file that does not match GitHub's digest is deleted and not installed`() {
        SelfUpdate.apkFile(app).delete()
        digest = "sha256:" + "0".repeat(64)
        try {
            backgroundCheck()
        } finally {
            digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        }
        assertFalse(SelfUpdate.apkFile(app).exists())
        assertNull(prefs.getString("installing_version", null))
    }

    @Test fun `04 never under the user's hands`() {
        ActivityManager.testImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val before = downloads()
        backgroundCheck()
        assertEquals(before, downloads())
        assertNull(prefs.getString("installing_version", null))
        assertTrue(updateNotices().isEmpty())
    }

    @Test fun `05 a working queue - one notice, installed later`() {
        TestApp.forge.generationMs = 4_000
        onMain { TestApp.vm.updateState { it.copy(positivePrompt = "long job", batchCount = 1) } }
        onMain { TestApp.vm.queueGeneration() }
        awaitUntil("generating", 10_000) { TestApp.vm.isGenerating.value }
        backgroundCheck()
        backgroundCheck()
        assertNull(prefs.getString("installing_version", null))
        val notices = updateNotices()
        println("[G34-05] ${notices.map { "${it.title}: ${it.text}" }}")
        assertEquals("once per version", 1, notices.size)
        assertTrue(notices.single().text.toString().contains("once the queue is done"))
        awaitUntil("job done", 20_000) { TestApp.vm.generationQueue.value.isEmpty() && !TestApp.vm.isGenerating.value }
        TestApp.forge.generationMs = 300
    }

    @Test fun `06 turned off - a notice only, nothing newer - nothing at all`() {
        SelfUpdate.setAutoInstall(app, false)
        backgroundCheck()
        assertNull(prefs.getString("installing_version", null))
        assertEquals("ForgeGen 2.0.2 is available", updateNotices().single().title.toString())
        NotificationManagerCompat.posted.clear()
        SelfUpdate.setAutoInstall(app, true)
        tag = "v0.0.1" // older than the installed build
        backgroundCheck()
        assertTrue(updateNotices().isEmpty())
        assertNull(prefs.getString("installing_version", null))
    }

    @Test fun `07 the setting reaches the background check through the saved settings`() {
        onMain { TestApp.vm.saveConfig(TestApp.vm.config.value.copy(autoInstallUpdates = false)) }
        assertFalse(prefs.getBoolean("auto_install", true))
        onMain { TestApp.vm.saveConfig(TestApp.vm.config.value.copy(autoInstallUpdates = true)) }
        assertTrue(prefs.getBoolean("auto_install", false))
    }

    @Test fun `08 the install's result and the notice after the update`() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        UpdateStatusReceiver().onReceive(
            app,
            Intent(SelfUpdate.ACTION_INSTALL_STATUS).apply {
                putExtra("version", "2.0.2")
                extras[PackageInstaller.EXTRA_STATUS] = PackageInstaller.STATUS_PENDING_USER_ACTION
                extras[Intent.EXTRA_INTENT] = confirm
            },
        )
        assertEquals("Install ForgeGen 2.0.2", updateNotices().last().title.toString())
        UpdateStatusReceiver().onReceive(
            app,
            Intent(SelfUpdate.ACTION_INSTALL_STATUS).apply {
                extras[PackageInstaller.EXTRA_STATUS] = PackageInstaller.STATUS_FAILURE
                putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE")
            },
        )
        assertEquals("ForgeGen update failed", updateNotices().last().title.toString())
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", updateNotices().last().text.toString())

        prefs.edit().putString("installing_version", "2.0.2").apply()
        UpdatedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals("ForgeGen updated to 2.0.2", updateNotices().last().title.toString())
        assertNull(prefs.getString("installing_version", null))
        val count = updateNotices().size
        UpdatedReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)) // an update by someone else
        assertEquals(count, updateNotices().size)
    }

    @Test fun `09 a check without a connection leaves the next one to the next day`() {
        val github = GitHubApi.baseUrl
        prefs.edit().remove("auto_check_day").apply()
        GitHubApi.baseUrl = "http://127.0.0.1:9/" // nothing listens there
        try {
            runCatching { runBlocking { SelfUpdate.checkInBackground(app) } }
        } finally {
            GitHubApi.baseUrl = github
        }
        assertEquals("taken before asking", today(), prefs.getString("auto_check_day", null))
        val before = releases()
        runBlocking { SelfUpdate.checkInBackground(app) }
        val start = freshManager()
        start.checkForUpdates(manual = false)
        Thread.sleep(800)
        assertEquals("not again today", before, releases())
        // The next day the first one asks, and only it.
        val tomorrow = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
        assertTrue(SelfUpdate.claimDailyCheck(app, tomorrow))
        assertFalse(SelfUpdate.claimDailyCheck(app, tomorrow))
        assertTrue(SelfUpdate.autoCheckDue("2026-10-02", "2026-10-03"))
        assertFalse(SelfUpdate.autoCheckDue("2026-10-03", "2026-10-03"))
    }

    @Test fun `10 one notification for a new version, whichever check finds it`() {
        prefs.edit().remove("auto_check_day").remove("offered_update").apply()
        SelfUpdate.setAutoInstall(app, false)
        ActivityManager.testImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val start = freshManager()
        start.checkForUpdates(manual = false)
        awaitUntil("offered at the start") { start.updateManifest.value?.versionName == "2.0.2" }
        awaitUntil("the notification") { updateNotices().isNotEmpty() }
        assertEquals("ForgeGen 2.0.2 is available", updateNotices().single().title.toString())
        assertTrue(prefs.getString("offered_update", null).orEmpty().contains("2.0.2"))
        // The next days: the background job and later starts find the same version and say nothing more.
        ActivityManager.testImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        backgroundCheck()
        prefs.edit().remove("auto_check_day").apply()
        val next = freshManager()
        next.checkForUpdates(manual = false)
        awaitUntil("offered the next day") { next.updateManifest.value?.versionName == "2.0.2" }
        Thread.sleep(300)
        assertEquals("one notification", 1, updateNotices().size)
        // A newer version gets its own.
        tag = "v2.0.3"
        backgroundCheck()
        assertEquals("ForgeGen 2.0.3 is available", updateNotices().last().title.toString())
        assertEquals(2, updateNotices().size)
        // The saved release is offered only while it is newer than the installed build.
        assertNull(SelfUpdate.savedOffer(app, Int.MAX_VALUE))
        assertEquals("2.0.3", SelfUpdate.savedOffer(app, 0)?.versionName)
    }

    @Test fun `11 off by default - a new install only gets the notification`() {
        assertFalse(AppConfig().autoInstallUpdates)
        prefs.edit().remove("auto_install").apply() // never saved: the default
        SelfUpdate.apkFile(app).delete()
        val before = downloads()
        backgroundCheck()
        assertEquals("nothing downloaded", before, downloads())
        assertNull(prefs.getString("installing_version", null))
        assertEquals("ForgeGen 2.0.2 is available", updateNotices().single().title.toString())
    }

    @Test fun `12 the daily job replaces the 6-hour one of older versions, once`() {
        class Scheduler : android.app.job.JobScheduler() {
            var pending: android.app.job.JobInfo? = null
            val log = mutableListOf<String>()

            override fun getPendingJob(id: Int) = pending

            override fun schedule(job: android.app.job.JobInfo): Int {
                log += "schedule ${job.intervalMillis}"
                pending = job
                return 1
            }

            override fun cancel(id: Int) {
                log += "cancel"
                pending = null
            }
        }
        val scheduler = Scheduler()
        scheduler.pending = android.app.job.JobInfo.Builder(4_201, null).setPeriodic(6 * 60 * 60 * 1000L, 60 * 60 * 1000L).build()
        android.content.Context.testJobScheduler = scheduler
        SelfUpdate.scheduleChecks(app)
        assertEquals(listOf("cancel", "schedule ${24 * 60 * 60 * 1000L}"), scheduler.log)
        SelfUpdate.scheduleChecks(app) // the next start keeps it
        assertEquals(2, scheduler.log.size)
        scheduler.pending = null
        SelfUpdate.scheduleChecks(app) // none planned (a fresh install): planned, nothing to cancel
        assertEquals("schedule ${24 * 60 * 60 * 1000L}", scheduler.log.last())
        assertEquals(3, scheduler.log.size)
    }
}
