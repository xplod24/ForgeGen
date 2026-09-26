package com.example.forgegen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/* ============================================================================
 * SAMSUNG NOW BAR
 * The pill at the bottom of the lock screen of Samsung phones. Since One UI 8 (Samsung's Android 16) it shows the
 * "Live Updates" of any app: ongoing notifications that ask to be promoted. ForgeGen shows the generation progress
 * there only when the user turns on "Show Progress in Now Bar", and only on One UI 8 or newer.
 * ============================================================================ */
object NowBar {
    // Present on phones running Samsung's One UI (the "lite" one on some tablets and budget models), absent on other
    // brands and on Samsung phones with another system.
    private val ONE_UI_FEATURES =
        listOf("com.samsung.feature.samsung_experience_mobile", "com.samsung.feature.samsung_experience_mobile_lite")

    /** A phone with Samsung's One UI 8 or newer; One UI 8 is the first One UI on Android 16. */
    fun isSupported(context: Context): Boolean =
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

    /** The system page where live notifications of this app are turned on or off. */
    fun openSystemSettings(context: Context) {
        val promoted =
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try {
            context.startActivity(promoted)
        } catch (e: Exception) {
            // Where that page is missing, the app's notification settings have the switch.
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
}
