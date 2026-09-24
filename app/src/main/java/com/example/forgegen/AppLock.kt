package com.example.forgegen

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/* ============================================================================
 * APP LOCK
 * The app is guarded by the phone's own lock: its PIN, pattern or password and,
 * if allowed in the settings, fingerprint or face. The lock state itself lives in
 * ForgeViewModel so it survives rotation but not a restart of the process.
 * ============================================================================ */
object AppLock {
    /** False when the phone has no PIN, pattern or password; the app lock cannot be enforced then. */
    fun isAvailable(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Shows the system prompt; [onSuccess] runs only after the user proved they can unlock the phone. */
    fun authenticate(
        activity: Activity,
        allowBiometrics: Boolean,
        title: String,
        onSuccess: () -> Unit,
    ) {
        val authenticators =
            if (allowBiometrics) {
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
            } else {
                Authenticators.DEVICE_CREDENTIAL
            }
        BiometricPrompt
            .Builder(activity)
            .setTitle(title)
            .setAllowedAuthenticators(authenticators)
            .build()
            .authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                // Failed attempts and cancelling only close the prompt; the old code killed the app on them.
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }
                },
            )
    }
}
