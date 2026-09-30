package com.example.forgegen

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/* ============================================================================
 * LIVE UPDATES (1.2.0 as the Samsung Now Bar, on every Android 16 phone since 3.5.0)
 * Android 16 shows "Live Updates": ongoing notifications that ask to be promoted, as a chip in the status bar, at the
 * top of the notifications, expanded on the lock screen and on the always-on display; Samsung's One UI 8 puts them in
 * the Now Bar, the pill at the bottom of its lock screen. ForgeGen shows the generation progress (and an update's
 * download) that way only when the user turns on "Show Progress as Live Update". The notification meets Android's
 * rules (GenerationService.createNotification): ProgressStyle, ongoing, a title, not colorized, no custom views, a
 * channel above IMPORTANCE_MIN, POST_PROMOTED_NOTIFICATIONS, setRequestPromotedOngoing.
 * Makers may add their own rules: Samsung shows other companies' Live Updates only for the apps on its own list,
 * unless "Live notifications for all apps" is on in the developer options. So the app reads whether the system
 * promoted its notification (FLAG_PROMOTED_ONGOING, which only the system sets) and the settings show it.
 * ============================================================================ */
object LiveUpdates {
    // Present on phones running Samsung's One UI (the "lite" one on some tablets and budget models), absent on other
    // brands and on Samsung phones with another system.
    private val ONE_UI_FEATURES =
        listOf("com.samsung.feature.samsung_experience_mobile", "com.samsung.feature.samsung_experience_mobile_lite")

    private const val PREFS = "ui"
    private const val PROMOTED_KEY = "live_update_promoted"
    private const val CHECK_EVERY_MS = 5_000L

    @Volatile private var lastCheckMs = 0L

    /** Android 16 or newer, of any maker (Samsung needed One UI 8 up to 3.4.2), or forced in debug mode. */
    fun isSupported(context: Context): Boolean = DebugMode.forceLiveUpdates.value || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /** A phone with Samsung's One UI: its Live Updates also need "Live notifications for all apps". */
    fun isSamsung(context: Context): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            ONE_UI_FEATURES.any { context.packageManager.hasSystemFeature(it) }

    /** Whether the progress notification asks to be a Live Update. */
    fun shouldPromote(
        context: Context,
        config: AppConfig,
    ): Boolean = config.nowBarProgress && config.notificationMode != "Disabled" && isSupported(context)

    /** The system also lets the user turn live notifications off for the app; then nothing is promoted. */
    fun isAllowedBySystem(context: Context): Boolean =
        try {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        } catch (e: Throwable) {
            false // an Android 16 without the call
        }

    /** Whether the app's notifications are allowed at all ("Allow notifications"). */
    fun areNotificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Notes whether the system shows the posted notification [id] as a Live Update (at most every few seconds, while
     * a job runs). Read by the settings, also after a restart.
     */
    fun notePromotion(
        context: Context,
        id: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckMs < CHECK_EVERY_MS) return
        val posted =
            try {
                context
                    .getSystemService(NotificationManager::class.java)
                    ?.activeNotifications
                    ?.firstOrNull { it.id == id }
                    ?.notification
            } catch (e: Exception) {
                null
            } ?: return // not shown yet: tried again with the next update
        lastCheckMs = now
        val promoted = posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PROMOTED_KEY, if (promoted) 1 else 0).apply()
    }

    /** Whether the progress was shown as a Live Update during the last job; null when not seen yet. */
    fun promotedLastTime(context: Context): Boolean? =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PROMOTED_KEY, -1)) {
            1 -> true
            0 -> false
            else -> null
        }

    /** Turning the option on again starts a new check. */
    fun forgetPromotion(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PROMOTED_KEY).apply()
        lastCheckMs = 0L
    }

    /** The app's notification settings: notifications, on the lock screen, with their content. */
    fun openNotificationSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    /** Whether the phone's developer options are turned on. */
    fun areDeveloperOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    /**
     * The developer options, for Samsung's "Live notifications for all apps"; while they are off, the phone's
     * software information, where tapping the build number 7 times turns them on.
     */
    fun openDeveloperOptions(context: Context) {
        val action =
            if (areDeveloperOptionsOn(context)) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        try {
            context.startActivity(Intent(action))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** The system page where live notifications of this app are turned on or off. */
    fun openSystemSettings(context: Context) {
        val promoted =
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(promoted)
        } catch (e: Exception) {
            // Where that page is missing (One UI 8 has none), the app's notification settings are the closest.
            openNotificationSettings(context)
        }
    }
}
