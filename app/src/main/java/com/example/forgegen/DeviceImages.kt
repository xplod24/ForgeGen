package com.example.forgegen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream

/* ============================================================================
 * DEVICE IMAGES
 * Images saved on the phone go to Pictures/ForgeGen, the "ForgeGen" album of the phone's gallery.
 * Shared images are temporary copies in the app's cache, so sharing no longer leaves files behind.
 * ============================================================================ */
object DeviceImages {
    private val RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/ForgeGen/"
    private const val SHARED_DIR = "shared" // must match res/xml/filepaths.xml

    fun mimeType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            else -> "image/png"
        }

    /**
     * Name on the phone for an image of the server gallery: its folder in front, e.g. "2026-09-24_00012-123.png",
     * since Forge starts numbering files again in every date folder.
     */
    fun nameFor(serverPath: String): String {
        val path = serverPath.replace('\\', '/').trimEnd('/')
        val name = path.substringAfterLast('/')
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        return if (folder.isEmpty() || folder.endsWith(':')) name else "${folder}_$name"
    }

    /** Names of the images in Pictures/ForgeGen saved by this app (others' files need a permission we do not ask for). */
    fun savedNames(context: Context): Set<String> {
        val names = mutableSetOf<String>()
        context.contentResolver
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(RELATIVE_PATH),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { names += it }
            }
        return names
    }

    /** Saves an image to Pictures/ForgeGen. It stays hidden until complete and is removed again if writing fails. */
    fun save(
        context: Context,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(displayName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("The phone refused to create $displayName")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot write $displayName")
            stream.use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // no empty or half-written image in the phone's gallery
            throw e
        }
    }

    /** A share sheet for a temporary copy of an image; [write] fills the copy. */
    fun shareIntent(
        context: Context,
        name: String,
        write: (OutputStream) -> Unit,
    ): Intent {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, name.substringAfterLast('/').substringAfterLast('\\'))
        file.outputStream().use(write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share Image")
    }

    /** Removes the copies of earlier shares; called at start, when no share sheet can still be reading them. */
    fun clearSharedCopies(context: Context) {
        File(context.cacheDir, SHARED_DIR).listFiles()?.forEach { it.delete() }
    }
}
