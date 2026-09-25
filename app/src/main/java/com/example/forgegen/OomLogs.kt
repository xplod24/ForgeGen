package com.example.forgegen

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================================
 * OUT-OF-MEMORY LOGS
 * When the app or the server runs out of memory, a report with the app's log is written to Downloads, if the
 * user allowed it ("Save Logs on Out of Memory" in the Permissions settings). Since Android 10 a file the app
 * creates in Downloads needs no storage permission, so the setting is the only consent asked for.
 * ============================================================================ */
object OomLogs {
    private const val TAG = "OomLogs"
    private const val MB = 1024L * 1024L

    private lateinit var application: Application
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Released when the app itself runs out of memory, so writing the report still has some room.
    @Volatile private var reserve: ByteArray? = ByteArray(256 * 1024)

    private val allowed get() = ::application.isInitialized && ForgeSettingsManager.config.value.saveOomLogs

    /** Also reports out-of-memory crashes nothing else caught; the crash then goes on as before. */
    fun install(app: Application) {
        application = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            error: Throwable,
        ) {
            if (generateSequence(error) { it.cause }.any { it is OutOfMemoryError }) {
                reserve = null
                // Written right here: the process ends as soon as this handler returns.
                write("The app ran out of memory and closed (thread \"${thread.name}\").", error.stackTraceToString())
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Reports an out-of-memory error the app survived, in the background; says where the report was saved. */
    fun report(
        reason: String,
        details: String,
    ) {
        if (!allowed) return
        scope.launch {
            write(reason, details)?.let { name -> ForgeSettingsManager.showToast("Out-of-memory log saved to Downloads: $name") }
        }
    }

    /** Writes the report to Downloads; returns its file name, or null when not allowed or not possible. */
    fun write(
        reason: String,
        details: String,
    ): String? {
        if (!allowed) return null
        val now = Date()
        val name = "ForgeGen-OOM-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)}.txt"
        return try {
            val resolver = application.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("The phone refused to create $name")
            try {
                val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot write $name")
                stream.use { out ->
                    out.write(header(now, reason, details).toByteArray())
                    copyLog(out)
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                name
            } catch (e: Throwable) {
                resolver.delete(uri, null, null) // no empty or half-written file in Downloads
                throw e
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot save the out-of-memory log", e)
            null
        }
    }

    private fun header(
        time: Date,
        reason: String,
        details: String,
    ): String {
        val runtime = Runtime.getRuntime()
        val phone = ActivityManager.MemoryInfo()
        (application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(phone)
        return buildString {
            appendLine("ForgeGen out-of-memory report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(time)}")
            appendLine("Reason: $reason")
            appendLine()
            appendLine("App: ${BuildConfig.VERSION_NAME}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App memory: ${(runtime.totalMemory() - runtime.freeMemory()) / MB} MB used of ${runtime.maxMemory() / MB} MB")
            if (phone.totalMem > 0) {
                appendLine(
                    "Phone memory: ${phone.availMem / MB} MB free of ${phone.totalMem / MB} MB" +
                        if (phone.lowMemory) " (low on memory)" else "",
                )
            }
            ForgeRepository.vramUsage.value?.let { appendLine("Server memory (last reading): $it") }
            appendLine()
            appendLine("Details:")
            appendLine(details)
            appendLine()
            appendLine("Log of the app:")
        }
    }

    /** The app's own log (logcat), streamed straight into the file instead of being held in memory. */
    private fun copyLog(out: OutputStream) {
        try {
            val process =
                ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.use { it.copyTo(out) }
            process.waitFor()
        } catch (e: Exception) {
            out.write("The log could not be read: $e\n".toByteArray())
        }
    }
}
