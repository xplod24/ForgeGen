package com.example.forgegen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Android 17 gates LAN access; traffic routed through a VPN is not gated by this permission. */
internal object LocalNetworkPermission {
    const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    private const val PREFERENCES = "permission_requests"
    private const val REQUESTED = "local_network_requested"

    fun isRequired(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 37

    fun isGranted(
        context: Context,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean = !isRequired(sdk) || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun wasRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(REQUESTED, false)

    fun markRequested(context: Context) {
        context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(REQUESTED, true)
            .apply()
    }

    // Check every launch, but leave another request after a denial to the user's settings action.
    fun shouldRequestAtStartup(
        context: Context,
        sdk: Int = Build.VERSION.SDK_INT,
    ): Boolean = !isGranted(context, sdk) && !wasRequested(context)

    fun canRequest(
        context: Context,
        activity: Activity?,
    ): Boolean = activity != null && (!wasRequested(context) || ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION))
}
