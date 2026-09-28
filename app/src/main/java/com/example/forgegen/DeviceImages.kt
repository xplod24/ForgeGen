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
 * Images saved on the phone go to Pictures/ForgeGen, the "ForgeGen" album of the phone's gallery, or with "Save to
 * Phone Privately" to the app's own folder, which gallery apps and their cloud backup do not see (it is removed with
 * the app). Shared images are temporary copies in the app's cache, so sharing no longer leaves files behind.
 * ============================================================================ */
object DeviceImages {
    private val RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/ForgeGen/"
    private const val SHARED_DIR = "shared" // must match res/xml/filepaths.xml

    private val savesPrivately get() = ForgeSettingsManager.config.value.savePrivately

    /** Where saved images go, for messages to the user. */
    fun locationName(private: Boolean = savesPrivately) = if (private) "the app's private folder" else "Pictures/ForgeGen"

    /** The app's own folder for "Save to Phone Privately" (Android/data/<app>/files/Pictures/ForgeGen). */
    private fun privateDir(context: Context) =
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "ForgeGen").apply { mkdirs() }

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
        if (savesPrivately) return privateDir(context).list()?.toSet().orEmpty()
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
        if (savesPrivately) return savePrivately(context, displayName, write)
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

    /** Into the app's own folder; written under a temporary name, so a failed write leaves no half image. */
    private fun savePrivately(
        context: Context,
        displayName: String,
        write: (OutputStream) -> Unit,
    ): Uri {
        val dir = privateDir(context)
        val file = File(dir, displayName.substringAfterLast('/').substringAfterLast('\\'))
        val partial = File(dir, ".${file.name}.part")
        try {
            partial.outputStream().use(write)
            if (!partial.renameTo(file)) throw IOException("Cannot save ${file.name}")
            return Uri.fromFile(file)
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    /** Where a ZIP of gallery images goes (3.2.0): Downloads, or the private folder with "Save to Phone Privately". */
    fun archiveLocationName(private: Boolean = savesPrivately) = if (private) "the app's private folder" else "Downloads"

    /**
     * Saves a ZIP archive that [write] fills (it downloads the images, so it may take a while). Like an image, it
     * stays hidden until complete and is removed again if writing fails.
     */
    suspend fun saveArchive(
        context: Context,
        displayName: String,
        write: suspend (OutputStream) -> Unit,
    ) {
        if (savesPrivately) {
            val dir = privateDir(context)
            val partial = File(dir, ".$displayName.part")
            try {
                partial.outputStream().use { write(it) }
                if (!partial.renameTo(File(dir, displayName))) throw IOException("Cannot save $displayName")
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
            return
        }
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("The phone refused to create $displayName")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Cannot write $displayName")
            stream.use { write(it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** A share sheet for a temporary copy of an image; [write] fills the copy. */
    fun shareIntent(
        context: Context,
        name: String,
        write: (OutputStream) -> Unit,
    ): Intent {
        val file = sharedCopy(context, name, write)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share Image")
    }

    /** A temporary copy for a share sheet ("Share Without Generation Data" applied); [write] fills it. */
    fun sharedCopy(
        context: Context,
        name: String,
        write: (OutputStream) -> Unit,
    ): File {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, name.substringAfterLast('/').substringAfterLast('\\'))
        file.outputStream().use(write)
        // "Share Without Generation Data": the prompt and settings stay behind, the picture is untouched.
        if (ForgeSettingsManager.config.value.shareWithoutMetadata) file.writeBytes(MetadataStripper.strip(file.readBytes()))
        return file
    }

    /** One share sheet for several copies made by [sharedCopy]. */
    fun shareManyIntent(
        context: Context,
        files: List<File>,
    ): Intent {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val send =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = files.map { mimeType(it.name) }.distinct().singleOrNull() ?: "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(send, "Share ${files.size} Images")
    }

    /** Removes the copies of earlier shares; called at start, when no share sheet can still be reading them. */
    fun clearSharedCopies(context: Context) {
        File(context.cacheDir, SHARED_DIR).listFiles()?.forEach { it.delete() }
    }
}
