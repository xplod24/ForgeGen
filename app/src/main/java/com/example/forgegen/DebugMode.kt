package com.example.forgegen

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/* ============================================================================
 * DEBUG MODE
 * A hidden panel for the owner (the "Debug" section of the settings): tap "App Version" in App Updates 8 times, then
 * enter the password. The app holds only a salted PBKDF2 hash of the password. This hides the panel from ordinary
 * users; it is no protection against someone who rebuilds the app from its source.
 * The rules of BlockingApi (the extra check before a job is sent) apply in the debug mode too.
 * To change the password: put a new random SALT and DebugMode.toHex(DebugMode.hash("new password",
 * DebugMode.fromHex(SALT), ITERATIONS)) in PASSWORD_HASH (never commit the password itself).
 * The state is kept on the device (SharedPreferences "debug"), outside AppConfig, so it never travels with the
 * settings; wiping "App Settings & State" locks the debug mode again.
 * ============================================================================ */
object DebugMode {
    const val TAPS_TO_UNLOCK = 8

    // A pause longer than this between taps starts the count again.
    const val TAP_WINDOW_MS = 2_000L

    private const val ITERATIONS = 120_000
    private const val SALT = "bdb9feefe684ef53f114f417fe8fcf90"
    private const val PASSWORD_HASH = "f5b36321537634666f808821f6b2a4ff3af2cfc62199faca57d81b528cbef30e"

    // After this many wrong passwords the next ones are refused for a minute.
    private const val MAX_ATTEMPTS = 5
    private const val LOCKOUT_MS = 60_000L

    private const val PREFS = "debug"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_FORCE_NOW_BAR = "force_now_bar"

    enum class UnlockResult { UNLOCKED, WRONG_PASSWORD, TOO_MANY_ATTEMPTS }

    private var prefs: SharedPreferences? = null
    private var failedAttempts = 0
    private var lockedUntil = 0L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    // Offers the Now Bar option on any phone (it is meant for One UI 8+), to test it elsewhere.
    private val _forceNowBar = MutableStateFlow(false)
    val forceNowBar: StateFlow<Boolean> = _forceNowBar.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _unlocked.value = p.getBoolean(KEY_UNLOCKED, false)
        _forceNowBar.value = _unlocked.value && p.getBoolean(KEY_FORCE_NOW_BAR, false)
    }

    /** PBKDF2 with HMAC-SHA256, 32 bytes. Slow on purpose: call it off the main thread. */
    fun hash(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Whether [password] is the debug password (compared in constant time). */
    fun matches(password: String) = MessageDigest.isEqual(hash(password, fromHex(SALT), ITERATIONS), fromHex(PASSWORD_HASH))

    /** Unlocks the debug mode when [password] is right; after [MAX_ATTEMPTS] wrong ones, waits a minute. */
    @Synchronized
    fun unlock(
        password: String,
        now: Long = System.currentTimeMillis(),
    ): UnlockResult {
        if (now < lockedUntil) return UnlockResult.TOO_MANY_ATTEMPTS
        if (!matches(password)) {
            failedAttempts++
            if (failedAttempts >= MAX_ATTEMPTS) {
                failedAttempts = 0
                lockedUntil = now + LOCKOUT_MS
            }
            return UnlockResult.WRONG_PASSWORD
        }
        failedAttempts = 0
        _unlocked.value = true
        prefs?.edit()?.putBoolean(KEY_UNLOCKED, true)?.apply()
        return UnlockResult.UNLOCKED
    }

    /** Hides the debug mode again and undoes its overrides. */
    fun lock() {
        _unlocked.value = false
        _forceNowBar.value = false
        prefs?.edit()?.clear()?.apply()
    }

    fun setForceNowBar(on: Boolean) {
        if (!_unlocked.value) return
        _forceNowBar.value = on
        prefs?.edit()?.putBoolean(KEY_FORCE_NOW_BAR, on)?.apply()
    }
}
