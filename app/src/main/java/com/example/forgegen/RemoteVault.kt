package com.example.forgegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class VaultConnection(val url: String = "", val token: String = "", val certificate: String = "")
data class VaultLocal(val connection: VaultConnection = VaultConnection(), val key: String = "", val enabled: Boolean = false, val automatic: Boolean = false, val recoveryEnvelope: String = "")
data class VaultEntry(val id: String = "", val deleted: Double? = null, val expires: Double? = null, val size: Long = 0)
data class VaultListing(val objects: List<VaultEntry> = emptyList(), val used: Long = 0, val quota: Long = 0)
data class VaultImport(val server: String = "", val items: List<GalleryItem> = emptyList(), val position: Int = 0, val prefix: String = "", val folders: List<String> = emptyList(), val visited: List<String> = emptyList(), val root: String = "")

/** Independent opt-in storage. All durable files live outside both Android backup and the session cache. */
object RemoteVault {
    private const val ALIAS = "forgegen-remote-vault-local-v1"
    private const val WORK = "forgegen-vault-transfer"
    private const val PERIODIC = "forgegen-vault-retry"
    private lateinit var context: Context
    private val gson = Gson()
    private val guard = Any()
    private val transferMutex = Mutex()
    private val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    val enabled = MutableStateFlow(false)
    val automatic = MutableStateFlow(false)
    val status = MutableStateFlow("Not configured")
    @Volatile private var local = VaultLocal()
    private val directory get() = File(context.noBackupFilesDir, "remote-vault").apply { mkdirs() }
    val ready get() = synchronized(guard) { local.key.isNotBlank() }
    val address get() = synchronized(guard) { local.connection.url }

    fun init(app: Context) {
        synchronized(guard) {
            context = app.applicationContext
            directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            try {
                val file = File(directory, "settings.enc")
                if (file.exists()) local = gson.fromJson(String(readPrivate(file)), VaultLocal::class.java)
                enabled.value = local.enabled
                automatic.value = local.automatic
                status.value = if (ready) "Ready" else "Not configured"
            } catch (_: Exception) {
                local = VaultLocal()
                status.value = "Local key unavailable. Restore with your recovery code."
            }
        }
        if (enabled.value && ready) schedule()
    }

    private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun writePrivate(file: File, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        cipher.updateAAD(file.name.toByteArray())
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(cipher.iv + cipher.doFinal(value))
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw error
        }
    }

    private fun readPrivate(file: File): ByteArray {
        val data = AtomicFile(file).readFully()
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(file.name.toByteArray())
        return cipher.doFinal(data, 12, data.size - 12)
    }

    private fun save(value: VaultLocal) = synchronized(guard) {
        writePrivate(File(directory, "settings.enc"), gson.toJson(value).toByteArray())
        local = value
        enabled.value = value.enabled
        automatic.value = value.automatic
    }

    private fun update(change: (VaultLocal) -> VaultLocal) = synchronized(guard) { save(change(local)) }

    fun setEnabled(value: Boolean) {
        update { it.copy(enabled = value) }
        if (value && ready) schedule() else {
            calls.forEach { it.cancel() }
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        }
    }
    fun setAutomatic(value: Boolean) = update { it.copy(automatic = value) }

    suspend fun connect(text: String) = withContext(Dispatchers.IO) {
        val connection = gson.fromJson(text, VaultConnection::class.java)
        val url = connection.url.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "Use an HTTPS server address without a path" }
        require(connection.token.length in 16..4096 && connection.certificate.length <= 16384) { "Invalid connection file" }
        require(!ready || local.connection.url == connection.url.trimEnd('/')) { "This phone already has a vault on another server. Restore it on a fresh installation to change hosts." }
        val candidate = connection.copy(url = connection.url.trimEnd('/'))
        execute(client(candidate).newCall(Request.Builder().url(candidate.url + "/v1/recovery").header("Authorization", "Bearer " + candidate.token).build())).use { require(it.code == 200 || it.code == 404) { "Server access denied or unavailable" } }
        update { it.copy(connection = candidate) }
        status.value = if (ready) "Connected" else "Connected. Create or restore your vault."
    }

    private fun client(connection: VaultConnection = local.connection): OkHttpClient {
        val builder = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        if (connection.certificate.isNotBlank()) {
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(connection.certificate.byteInputStream())
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("vault", cert) }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
            builder.sslSocketFactory(ssl.socketFactory, manager)
        }
        return builder.build()
    }

    private fun request(path: String, method: String = "GET", body: okhttp3.RequestBody? = null): okhttp3.Response {
        check(enabled.value) { "Remote vault is disabled" }
        return execute(client().newCall(Request.Builder().url(local.connection.url + path).header("Authorization", "Bearer " + local.connection.token)
            .method(method, body).build()))
    }

    /** Keep calls cancellable until their response bodies close, including downloads after response headers. */
    private fun execute(call: okhttp3.Call): okhttp3.Response {
        synchronized(guard) {
            check(enabled.value) { "Remote vault is disabled" }
            calls.add(call)
        }
        try {
            val response = call.execute()
            val body = response.body
            val source = object : okio.ForwardingSource(body.source()) {
                override fun close() { try { super.close() } finally { calls.remove(call) } }
            }
            val buffered = source.buffer()
            return response.newBuilder().body(object : okhttp3.ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = buffered
            }).build()
        } catch (error: Exception) {
            calls.remove(call)
            throw error
        }
    }

    /** Returns a new code only here, once; neither the code nor a reversible copy is persisted. */
    suspend fun create(): String = withContext(Dispatchers.IO) {
        check(!ready && enabled.value)
        request("/v1/recovery").use { check(it.code == 404) { "A vault already exists. Restore it with its recovery code." } }
        val key = VaultCrypto.randomKey()
        val recovery = VaultCrypto.randomKey()
        val envelope = VaultCrypto.encode(VaultCrypto.seal(recovery, key, "forgegen-vault-recovery-v1"))
        // Preserve the device key before committing the server envelope; do not lose it on a dropped reply.
        update { it.copy(key = VaultCrypto.encode(key), recoveryEnvelope = envelope) }
        runCatching { schedule() }
        val code = VaultCrypto.encode(recovery)
        recovery.fill(0)
        key.fill(0)
        code
    }

    suspend fun recover(code: String) = withContext(Dispatchers.IO) {
        val recovery = VaultCrypto.decode(code)
        require(recovery.size == 32) { "Invalid recovery code" }
        try {
            val envelope = request("/v1/recovery").use { response ->
                check(response.isSuccessful) { "Recovery envelope unavailable" }
                gson.fromJson(response.body!!.string(), Map::class.java)["envelope"] as String
            }
            val key = VaultCrypto.open(recovery, VaultCrypto.decode(envelope), "forgegen-vault-recovery-v1")
            require(key.size == 32)
            update { it.copy(key = VaultCrypto.encode(key)) }
            key.fill(0)
            schedule()
            status.value = "Vault restored"
        } finally { recovery.fill(0) }
    }

    private fun thumbnail(image: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ByteArray(0)
        val options = BitmapFactory.Options().apply { inSampleSize = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= 384 } }
        val bitmap = BitmapFactory.decodeFile(image.path, options) ?: return ByteArray(0)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    fun enqueue(file: File, name: String = file.name, metadata: String = ""): Boolean {
        if (!enabled.value || !ready) return false
        require(file.length() in 1..120L * 1024 * 1024) { "Image exceeds vault upload limit" }
        synchronized(guard) {
            if (!enabled.value || !ready) return false
            val key = VaultCrypto.decode(local.key)
            try {
                val id = VaultCrypto.identifier(key, file)
                if (File(directory, "$id.saved").exists() || File(directory, "$id.pending").exists()) return false
                val partial = File(directory, "$id.part")
                try {
                    val info = metadata.ifEmpty { file.inputStream().use(PngMetadata::readParameters) }
                    VaultCrypto.encrypt(key, id, file, partial, name, info, thumbnail(file))
                    java.io.FileOutputStream(partial, true).use { it.fd.sync() }
                    check(partial.renameTo(File(directory, "$id.pending"))) { "Cannot stage encrypted image" }
                } finally { partial.delete() }
            } finally { key.fill(0) }
        }
        schedule()
        return true
    }

    fun generated(file: File, metadata: String) {
        if (!enabled.value || !automatic.value || !ready) return
        try { enqueue(file, metadata = metadata) } catch (_: Exception) { status.value = "Could not prepare an image for the vault. Save it manually from the gallery." }
    }

    suspend fun queueGallery(items: List<GalleryItem>) = withContext(Dispatchers.IO) {
        check(enabled.value && ready)
        synchronized(guard) {
            val file = File(directory, "import.enc")
            check(!file.exists()) { "A gallery transfer is already pending. Resume or cancel it first." }
            writePrivate(file, gson.toJson(VaultImport(ForgeRepository.config.value.apiUrl, items.filter { !it.isDir && ForgeGalleryManager.isImage(it.name) }, prefix = ForgeGalleryManager.prefix())).toByteArray())
        }
        schedule()
    }

    suspend fun queueWholeGallery() = withContext(Dispatchers.IO) {
        check(enabled.value && ready)
        val config = ForgeRepository.config.value
        check(config.galleryPath.isNotBlank()) { "Configure the Forge gallery folder first" }
        synchronized(guard) {
            val file = File(directory, "import.enc")
            check(!file.exists()) { "A gallery transfer is already pending. Resume or cancel it first." }
            val task = VaultImport(config.apiUrl, prefix = ForgeGalleryManager.prefix(), folders = listOf(config.galleryPath), root = config.galleryPath)
            writePrivate(file, gson.toJson(task).toByteArray())
        }
        schedule()
        status.value = "Gallery import queued"
    }

    fun cancelImport() = synchronized(guard) { File(directory, "import.enc").delete() }
    fun schedule() {
        if (!enabled.value || !ready) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<VaultTransferWorker>().setConstraints(constraints).build())
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<VaultTransferWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }

    suspend fun transfer(): Boolean = withContext(Dispatchers.IO) {
        transferMutex.withLock { transferLocked() }
    }

    private suspend fun transferLocked(): Boolean {
        if (!enabled.value || !ready) return true
        return try {
            val envelope = local.recoveryEnvelope
            if (envelope.isNotBlank()) {
                request("/v1/recovery", "PUT", gson.toJson(mapOf("envelope" to envelope)).toRequestBody("application/json".toMediaType())).use {
                    if (it.code == 409) {
                        request("/v1/recovery").use { existing ->
                            check(existing.isSuccessful && gson.fromJson(existing.body!!.string(), Map::class.java)["envelope"] == envelope) { "Different vault exists on server" }
                        }
                    } else check(it.isSuccessful) { "Recovery setup failed" }
                }
                update { it.copy(recoveryEnvelope = "") }
            }
            val importing = File(directory, "import.enc")
            if (importing.exists()) {
                var task = gson.fromJson(String(readPrivate(importing)), VaultImport::class.java)
                check(task.server == ForgeRepository.config.value.apiUrl) { "Switch back to the source Forge server to resume import" }
                while (task.folders.isNotEmpty() && enabled.value && importing.exists()) {
                    currentCoroutineContext().ensureActive()
                    val folder = task.folders.first()
                    val response = ForgeRepository.forgeApi!!.getGalleryFilesDynamic(
                        url = "${task.prefix}/files", folderPath = ForgeGalleryManager.encodeFolderPath(folder))
                    check(response.isSuccessful) { "Source gallery unavailable" }
                    val entries = ForgeGalleryManager.parseGalleryItems(response.body()?.string().orEmpty())
                    val visited = task.visited + folder
                    task = task.copy(
                        items = (task.items + entries.filter { !it.isDir && ForgeGalleryManager.isImage(it.name) }).distinctBy { it.fullpath },
                        folders = (task.folders.drop(1) + entries.filter { it.isDir && ForgeGalleryManager.isUnder(it.fullpath, task.root) }.map { it.fullpath })
                            .distinct().filterNot { it in visited },
                        visited = visited,
                    )
                    synchronized(guard) { if (importing.exists()) writePrivate(importing, gson.toJson(task).toByteArray()) }
                    status.value = "Listing Forge gallery: ${task.items.size} images"
                }
                while (task.position < task.items.size && enabled.value && importing.exists()) {
                    currentCoroutineContext().ensureActive()
                    val item = task.items[task.position]
                    status.value = "Import ${task.position + 1} / ${task.items.size}"
                    val source = File(directory, "source.part")
                    try {
                        val url = task.server.trimEnd('/').toHttpUrl().newBuilder()
                            .addPathSegment(task.prefix).addPathSegment("file")
                            .addQueryParameter("path", item.fullpath).addQueryParameter("t", item.date.orEmpty()).build()
                        execute(ForgeSettingsManager.client.newCall(Request.Builder().url(url).build())).use { response ->
                            check(response.isSuccessful) { "Source image unavailable" }
                            response.body!!.byteStream().use { input -> source.outputStream().use { output ->
                                val buffer = ByteArray(65536)
                                var total = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    check(total <= 120L * 1024 * 1024) { "Image exceeds vault upload limit" }
                                    output.write(buffer, 0, count)
                                }
                            } }
                        }
                        enqueue(source, DeviceImages.nameFor(item.fullpath))
                    } finally { source.delete() }
                    uploadPending()
                    task = task.copy(position = task.position + 1)
                    synchronized(guard) { if (importing.exists()) writePrivate(importing, gson.toJson(task).toByteArray()) }
                }
                if (task.position == task.items.size && task.folders.isEmpty() && enabled.value) importing.delete()
            }
            uploadPending()
            status.value = if (enabled.value) "All pending images saved" else "Disabled; pending images kept"
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status.value = "Transfer waiting. Check VPN, server access, free space and the source Forge server."
            false
        }
    }

    private suspend fun uploadPending() {
        for (file in directory.listFiles()?.filter { it.name.endsWith(".pending") }.orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!enabled.value) return
            val id = file.name.removeSuffix(".pending")
            val req = Request.Builder().url(local.connection.url + "/v1/objects/$id").header("Authorization", "Bearer " + local.connection.token)
                .header("X-Content-SHA256", VaultCrypto.digest(file)).put(file.asRequestBody("application/octet-stream".toMediaType())).build()
            execute(client().newCall(req)).use { response -> check(response.isSuccessful) { "Upload failed" } }
            // Keep only an opaque receipt; no per-image decryption key or original survives in this queue.
            check(File(directory, "$id.saved").createNewFile() || File(directory, "$id.saved").exists())
            file.delete()
        }
    }

    suspend fun list(): VaultListing = withContext(Dispatchers.IO) {
        request("/v1/objects").use { check(it.isSuccessful); gson.fromJson(it.body!!.string(), VaultListing::class.java) }
    }
    suspend fun header(id: String): VaultHeader = withContext(Dispatchers.IO) {
        request("/v1/objects/$id/header").use { check(it.isSuccessful); it.body!!.byteStream().use { input ->
            val key = VaultCrypto.decode(local.key)
            try { VaultCrypto.header(key, id, input) } finally { key.fill(0) }
        } }
    }
    suspend fun trash(id: String, restore: Boolean = false) = withContext(Dispatchers.IO) {
        if (!restore) calls.filter { it.request().method == "PUT" && it.request().url.encodedPath == "/v1/objects/$id" }.forEach { it.cancel() }
        request("/v1/objects/$id" + if (restore) "/restore" else "", if (restore) "POST" else "DELETE", if (restore) ByteArray(0).toRequestBody() else null).use { check(it.isSuccessful) }
        if (!restore) synchronized(guard) {
            // An opaque receipt prevents an unfinished import from silently recreating a deleted image.
            File(directory, "$id.pending").delete()
            File(directory, "$id.saved").createNewFile()
        }
    }
    suspend fun download(id: String) = withContext(Dispatchers.IO) {
        val partial = File(directory, "download.part")
        try {
            val header = request("/v1/objects/$id").use { response ->
                check(response.isSuccessful)
                response.body!!.byteStream().use { input -> partial.outputStream().use { output ->
                    val key = VaultCrypto.decode(local.key)
                    try { VaultCrypto.decrypt(key, id, input, output) } finally { key.fill(0) }
                } }
            }
            // Expose the plaintext only after the complete authenticated stream has been verified.
            DeviceImages.save(context, header.name.substringAfterLast('/').substringAfterLast('\\')) { output -> partial.inputStream().use { it.copyTo(output) } }
        } finally { partial.delete() }
    }
}

class VaultTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!RemoteVault.enabled.value || !RemoteVault.ready) return Result.success()
        ForgeRepository.prepareApi(applicationContext as android.app.Application)
        return if (RemoteVault.transfer()) Result.success() else Result.retry()
    }
}
