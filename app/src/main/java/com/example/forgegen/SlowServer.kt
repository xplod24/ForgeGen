package com.example.forgegen

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/* ============================================================================
 * SLOW SERVER (3.5.3)
 * Forge loads a job's checkpoint inside the job's own txt2img request (process_images -> forge_model_reload: unload,
 * gc, read the file, the first time also its SHA-256), and the loading holds Python's GIL for long stretches, so even
 * the light /sdapi/v1/progress answers only after it. The ping timed out and the app showed "Connection lost" until the
 * model was in, at every change of checkpoint (owner's report 2026-10-01). A ping whose connection was made but whose
 * answer did not come in time, while the server's port still takes new connections (the system accepts them however
 * busy Forge is), is a busy server, not a lost one: ForgeRepository stays connected and says what it waits for. A
 * refused or unreachable server still counts as lost at once, and so does a busy one after [maxSlowMs].
 * ============================================================================ */
object SlowServer {
    // How long the server may stay too busy to answer before it counts as gone after all (shorter in tests).
    @Volatile internal var maxSlowMs = 5 * 60_000L

    private const val PROBE_TIMEOUT_MS = 3_000

    /** [e] is an answer that did not come in time on a connection that was made, not a failed or refused connect. */
    fun isSlowAnswer(e: Throwable): Boolean = e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) != true

    /** Whether the server's port takes a new TCP connection: the server's system does that even while Forge is busy. */
    fun stillListening(apiUrl: String): Boolean {
        val url = apiUrl.trim().toHttpUrlOrNull() ?: return false
        return try {
            Socket().use { it.connect(InetSocketAddress(url.host, url.port), PROBE_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * What the queue says while the server is too busy to answer: our job's checkpoint is being loaded when it is not
     * the one of the last job the server finished (or no job has run yet).
     */
    fun statusText(
        generating: Boolean,
        jobModel: String?,
        lastJobModel: String?,
    ): String =
        when {
            !generating -> "The server is busy"
            jobModel != null && jobModel != lastJobModel -> "Loading model ${ModelSettingsRules.key(jobModel)}…"
            else -> "The server is busy, waiting for its answer…"
        }
}
