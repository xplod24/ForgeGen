package com.example.forgegen

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** 1.2.0 as Samsung's Now Bar, 3.5.0 on every Android 16 phone: the progress as a Live Update, only when turned on. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G27_LiveUpdatesTest {
    companion object {
        val vm get() = TestApp.vm
        const val ONE_UI = "com.samsung.feature.samsung_experience_mobile"

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            android.content.ContextWrapper.base = TestApp.app
        }

        fun phone(manufacturer: String, oneUi: Boolean, sdk: Int) {
            Build.MANUFACTURER = manufacturer
            Build.VERSION.SDK_INT = sdk
            if (oneUi) PackageManager.FEATURES += ONE_UI else PackageManager.FEATURES -= ONE_UI
        }

        fun serviceNotifications(): List<Notification> =
            synchronized(NotificationManagerCompat.posted) {
                NotificationManagerCompat.posted.filter { it[0] == ForgeNotifications.ID_SERVICE }.map { it[1] as Notification }
            }

        /** Runs one slow job with the service and returns what it posted while generating. */
        fun generate(prompt: String): List<Notification> {
            TestApp.forge.generationMs = 2500
            NotificationManagerCompat.posted.clear()
            val service = GenerationService()
            service.onCreate()
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
            awaitUntil("generating") { vm.isGenerating.value }
            service.onStartCommand(Intent(GenerationService.ACTION_START_GENERATION), 0, 1)
            awaitUntil("progress posted", 10_000) { serviceNotifications().any { it.title?.startsWith("Image") == true } }
            awaitUntil("done", 20_000) { !ForgeQueueManager.isQueueActive.value }
            service.onDestroy()
            return serviceNotifications().filter { it.title?.startsWith("Image") == true }
        }
    }

    @Before fun reset() {
        phone("google", oneUi = false, sdk = 35)
        NotificationManagerCompat.promotedAllowed = true
        onMain { vm.saveConfig(vm.config.value.copy(nowBarProgress = false, notificationMode = "Simple")) }
    }

    @Test fun `01 every phone with Android 16 or newer, Samsung told apart for its own rule`() {
        assertFalse("Android 15", LiveUpdates.isSupported(TestApp.app))
        phone("google", oneUi = false, sdk = 36)
        assertTrue("a Pixel with Android 16", LiveUpdates.isSupported(TestApp.app))
        assertFalse(LiveUpdates.isSamsung(TestApp.app))
        phone("samsung", oneUi = true, sdk = 35)
        assertFalse("One UI 7 (Android 15)", LiveUpdates.isSupported(TestApp.app))
        phone("samsung", oneUi = true, sdk = 36)
        assertTrue("One UI 8", LiveUpdates.isSupported(TestApp.app))
        assertTrue(LiveUpdates.isSamsung(TestApp.app))
        phone("samsung", oneUi = false, sdk = 37)
        assertTrue("a Samsung phone without One UI, Android 17", LiveUpdates.isSupported(TestApp.app))
        assertFalse(LiveUpdates.isSamsung(TestApp.app))
    }

    @Test fun `02 only when the user turned it on, and not with the Disabled notification mode`() {
        phone("google", oneUi = false, sdk = 36)
        assertFalse("off by default", AppConfig().nowBarProgress)
        assertFalse(LiveUpdates.shouldPromote(TestApp.app, vm.config.value))
        val on = vm.config.value.copy(nowBarProgress = true)
        assertTrue(LiveUpdates.shouldPromote(TestApp.app, on))
        assertFalse(LiveUpdates.shouldPromote(TestApp.app, on.copy(notificationMode = "Disabled")))
        phone("google", oneUi = false, sdk = 35)
        assertFalse("turned on, but Android 15", LiveUpdates.shouldPromote(TestApp.app, on))
    }

    @Test fun `03 turned on - the progress notification is a live update with a progress style`() {
        phone("google", oneUi = false, sdk = 36)
        onMain { vm.saveConfig(vm.config.value.copy(nowBarProgress = true)) }
        val progress = generate("now bar")
        val last = progress.first { it.styleProgress == 50 } // while the server reports 50 %
        println("[G27-03] ${progress.map { "${it.title}|ongoing=${it.ongoing}|promoted=${it.requestPromotedOngoing}|chip=${it.shortCriticalText}|style=${it.styleProgress}|bar=${it.progressBar}" }}")
        assertTrue(last.ongoing)
        assertTrue(last.requestPromotedOngoing)
        assertEquals(50, last.styleProgress)
        assertEquals("50%", last.shortCriticalText)
        assertNull("no classic bar next to the progress style", last.progressBar)
        assertTrue("every progress update is a live update", progress.all { it.ongoing && it.requestPromotedOngoing })
        // Samsung needs the content on the lock screen: public, with nothing but the image number and the progress.
        assertTrue(progress.all { it.visibility == androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC })
        assertTrue(progress.none { "now bar" in it.title.toString() || "now bar" in it.text.toString() })
    }

    @Test fun `04 turned off - an ordinary notification, as before`() {
        phone("samsung", oneUi = true, sdk = 36)
        val progress = generate("plain")
        val last = progress.first { it.progressBar == "100/50" }
        println("[G27-04] ${progress.map { "${it.title}|ongoing=${it.ongoing}|promoted=${it.requestPromotedOngoing}|bar=${it.progressBar}" }}")
        assertFalse(last.ongoing)
        assertFalse(last.requestPromotedOngoing)
        assertEquals(-1, last.styleProgress)
        assertTrue(progress.none { it.ongoing || it.requestPromotedOngoing })
        assertTrue("without the Now Bar the app keeps the system's default", progress.all { it.visibility == androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE })
    }

    @Test fun `05 the system switch is read for the settings screen`() {
        NotificationManagerCompat.promotedAllowed = false
        assertFalse(LiveUpdates.isAllowedBySystem(TestApp.app))
        NotificationManagerCompat.promotedAllowed = true
        assertTrue(LiveUpdates.isAllowedBySystem(TestApp.app))
    }

    @Test fun `06 the checklist reads what the app can check`() {
        assertTrue(LiveUpdates.areNotificationsAllowed(TestApp.app))
        android.provider.Settings.Global.developmentSettings = 0
        assertFalse(LiveUpdates.areDeveloperOptionsOn(TestApp.app))
        android.provider.Settings.Global.developmentSettings = 1
        assertTrue(LiveUpdates.areDeveloperOptionsOn(TestApp.app))
        android.provider.Settings.Global.developmentSettings = 0
    }

    @Test fun `07 whether the system showed it as a Live Update is read back for the settings`() {
        phone("google", oneUi = false, sdk = 36)
        LiveUpdates.forgetPromotion(TestApp.app)
        android.app.NotificationManager.active = emptyArray()
        LiveUpdates.notePromotion(TestApp.app, ForgeNotifications.ID_SERVICE)
        assertNull("nothing shown yet", LiveUpdates.promotedLastTime(TestApp.app))

        val shown = Notification("forge_low", "Image 1/1", "Progress: 50%")
        shown.flags = Notification.FLAG_PROMOTED_ONGOING
        android.app.NotificationManager.active = arrayOf(android.service.notification.StatusBarNotification(ForgeNotifications.ID_SERVICE, shown))
        LiveUpdates.notePromotion(TestApp.app, ForgeNotifications.ID_SERVICE)
        assertEquals(true, LiveUpdates.promotedLastTime(TestApp.app))

        shown.flags = 0
        LiveUpdates.notePromotion(TestApp.app, ForgeNotifications.ID_SERVICE)
        assertEquals("read at most every few seconds", true, LiveUpdates.promotedLastTime(TestApp.app))
        LiveUpdates.forgetPromotion(TestApp.app)
        assertNull("turned on again: a new check", LiveUpdates.promotedLastTime(TestApp.app))
        LiveUpdates.notePromotion(TestApp.app, ForgeNotifications.ID_SERVICE)
        assertEquals("the phone kept it as a normal notification", false, LiveUpdates.promotedLastTime(TestApp.app))

        phone("google", oneUi = false, sdk = 35)
        LiveUpdates.forgetPromotion(TestApp.app)
        LiveUpdates.notePromotion(TestApp.app, ForgeNotifications.ID_SERVICE)
        assertNull("below Android 16 there is nothing to read", LiveUpdates.promotedLastTime(TestApp.app))
        android.app.NotificationManager.active = emptyArray()
    }
}
