package com.example.forgegen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/* ============================================================================
 * SAMSUNG NOW BAR
 * The pill at the bottom of the lock screen of Samsung phones. Since One UI 8 (Samsung's Android 16) it shows the
 * "Live Updates" of Android 16: ongoing notifications that ask to be promoted. ForgeGen shows the generation progress
 * there only when the user turns on "Show Progress in Now Bar", and only on One UI 8 or newer.
 * Samsung shows the Live Updates of other companies' apps only for the apps on its own list, unless the user turns on
 * "Live notifications for all apps" in the developer options; and the app's notifications must be allowed, also on
 * the lock screen with their content. The app can read only whether notifications and live notifications are
 * allowed, so the settings list the rest and open the pages where they are changed.
 * ============================================================================ */
object NowBar {
    // Present on phones running Samsung's One UI (the "lite" one on some tablets and budget models), absent on other
    // brands and on Samsung phones with another system.
    private val ONE_UI_FEATURES =
        listOf("com.samsung.feature.samsung_experience_mobile", "com.samsung.feature.samsung_experience_mobile_lite")

    /** A phone with Samsung's One UI 8 or newer (One UI 8 is the first One UI on Android 16), or forced in debug mode. */
    fun isSupported(context: Context): Boolean =
        DebugMode.forceNowBar.value ||
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            ONE_UI_FEATURES.any { context.packageManager.hasSystemFeature(it) } &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /** Whether the progress notification asks for a place in the Now Bar. */
    fun shouldPromote(
        context: Context,
        config: AppConfig,
    ): Boolean = config.nowBarProgress && config.notificationMode != "Disabled" && isSupported(context)

    /** The system also lets the user turn live notifications off for the app; then the Now Bar stays empty. */
    fun isAllowedBySystem(context: Context): Boolean =
        try {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        } catch (e: Throwable) {
            false // an Android 16 without the call
        }

    /** Whether the app's notifications are allowed at all ("Allow notifications"). */
    fun areNotificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The app's notification settings: notifications, on the lock screen, with their content. */
    fun openNotificationSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }

    /** Whether the phone's developer options are turned on. */
    fun areDeveloperOptionsOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    /**
     * The developer options, for "Live notifications for all apps"; while they are off, the phone's software
     * information, where tapping the build number 7 times turns them on.
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
