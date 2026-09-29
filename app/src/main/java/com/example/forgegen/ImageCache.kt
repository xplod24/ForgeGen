package com.example.forgegen

import android.content.Context
import java.io.File
import java.util.Locale

/* ============================================================================
 * IMAGE CACHE (3.4.0)
 * Thumbnails, gallery images and model pictures kept on the phone (Coil's disk cache). Its size was fixed at 2.5 GB;
 * now it is chosen in Settings > Backup & Data (up to 2.5 GB, the owner's decision). The image loader is built
 * before the settings are read from the database, so the size is also kept in SharedPreferences and a new size is
 * used from the next start.
 * ============================================================================ */
object ImageCache {
    val SIZES_MB = listOf(512, 1024, 2560)
    const val DEFAULT_MB = 2560

    private const val PREFS = "ui"
    private const val SIZE_KEY = "image_cache_mb"

    /** [mb] when it is one of the offered sizes, else the default. */
    fun sizeOf(mb: Int?): Int = mb?.takeIf { it in SIZES_MB } ?: DEFAULT_MB

    /** "512 MB", "1 GB", "2.5 GB". */
    fun label(mb: Int): String = if (mb < 1024) "$mb MB" else formatGb(mb / 1024.0)

    /** A number of bytes as "0 MB", "812 MB" or "1.4 GB". */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1024) String.format(Locale.US, "%.0f MB", mb) else formatGb(mb / 1024.0)
    }

    private fun formatGb(gb: Double) = String.format(Locale.US, "%.1f GB", gb).replace(".0 GB", " GB")

    fun directory(context: Context): File = context.cacheDir.resolve("image_cache")

    /** The size the image loader was built with (0 before its first image): a new one is used from the next start. */
    @Volatile var builtWithMb = 0

    /** The size the image loader is built with. */
    fun savedSizeMb(context: Context): Int = sizeOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(SIZE_KEY, DEFAULT_MB))

    fun saveSizeMb(
        context: Context,
        mb: Int,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(SIZE_KEY, sizeOf(mb))
            .apply()
    }

    /** Bytes the cache takes now (reads the folder; call it off the main thread). */
    fun usedBytes(context: Context): Long =
        directory(context)
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
}
