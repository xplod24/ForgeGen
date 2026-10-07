package com.example.forgegen

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import androidx.room.Room
import com.google.gson.Gson
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

// ------------------------------------------------------------------ main thread + waiting

object TestMain {
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "android-main").apply { isDaemon = true } }.asCoroutineDispatcher()
    init { Dispatchers.setMain(dispatcher) }
    fun ensure() = Unit
}

fun <T> onMain(block: () -> T): T = runBlocking(TestMain.dispatcher) { block() }

/**
 * Reads the gallery index into memory as the app's start does: for rows a test put straight into the database (the app
 * itself keeps the index in memory up to date, 3.6.0-1).
 */
fun reloadGalleryIndex() {
    // 3.6.1: reloadIndex is an internal extension (gallery/GallerySync.kt), visible to the tests.
    runBlocking { ForgeGalleryManager.reloadIndex() }
}

fun awaitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(50)
    }
    throw AssertionError("Timeout (${timeoutMs}ms): $what")
}

// ------------------------------------------------------------------ in-memory Room database

class FakeDb : ForgeDatabase() {
    val settings = ConcurrentHashMap<String, String>()
    val wildcardRows = ConcurrentHashMap<String, WildcardEntity>()
    val favorites = ConcurrentHashMap<String, FavoriteImageEntity>()
    val gallery = ConcurrentHashMap<String, GalleryImageEntity>()

    // How many times the whole index and all its paths were read (3.6.0-1: a sync no longer reads them).
    val indexReads = java.util.concurrent.atomic.AtomicInteger()
    val pathReads = java.util.concurrent.atomic.AtomicInteger()

    /** How many times each setting was written. */
    val writes = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    override fun appSettingDao() = object : AppSettingDao {
        override suspend fun getSetting(key: String) = settings[key]?.let { AppSettingEntity(key, it) }
        override suspend fun putSetting(setting: AppSettingEntity) {
            settings[setting.key] = setting.value
            writes.getOrPut(setting.key) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        }
        override suspend fun removeSetting(key: String) { settings.remove(key) }
    }

    override fun wildcardDao() = object : WildcardDao {
        override suspend fun insertWildcard(wildcard: WildcardEntity) { wildcardRows[wildcard.name] = wildcard }
        override suspend fun deleteWildcard(wildcard: WildcardEntity) { wildcardRows.remove(wildcard.name) }
        override suspend fun getAllWildcards() = wildcardRows.values.sortedBy { it.name }
        override suspend fun clearAll() = wildcardRows.clear()
    }


    override fun favoriteImageDao() = object : FavoriteImageDao {
        override suspend fun getAllFavorites() = favorites.values.sortedByDescending { it.savedAt }
        override suspend fun isFavorite(path: String) = favorites.containsKey(path)
        override suspend fun insertFavorite(favorite: FavoriteImageEntity) { favorites[favorite.fullpath] = favorite }
        override suspend fun deleteFavorite(path: String) { favorites.remove(path) }
        override suspend fun deleteFavorites(paths: List<String>) { paths.forEach { favorites.remove(it) } }
        override suspend fun moveFavorite(oldPath: String, newPath: String) {
            favorites.remove(oldPath)?.let { favorites[newPath] = it.copy(fullpath = newPath) }
        }
    }

    override fun galleryImageDao() = object : GalleryImageDao {
        override suspend fun insertImages(images: List<GalleryImageEntity>) { images.forEach { gallery[it.fullpath] = it } }
        override suspend fun deleteImages(paths: List<String>) { paths.forEach { gallery.remove(it) } }
        override suspend fun clearAll() = gallery.clear()
        // ORDER BY date DESC, name DESC, as the real query (3.4.0).
        override suspend fun getIndexedImages() =
            gallery.values.also { indexReads.incrementAndGet() }
                .map { IndexedImage(it.fullpath, it.name, it.date, it.model, it.loras, it.size) }
                .sortedWith(compareByDescending<IndexedImage> { it.date }.thenByDescending { it.name })
        override suspend fun updateSizes(sizes: List<GalleryImageSize>) {
            sizes.forEach { s -> gallery[s.fullpath]?.let { gallery[s.fullpath] = it.copy(size = s.size) } }
        }
        override suspend fun movePath(oldPath: String, newPath: String) {
            gallery.remove(oldPath)?.let { gallery[newPath] = it.copy(fullpath = newPath) }
        }
        override suspend fun getPromptPairs(limit: Int, offset: Int) =
            gallery.values.sortedBy { it.fullpath }.drop(offset).take(limit).map { GalleryPromptPair(it.fullpath, it.positivePrompt, it.negativePrompt) }
        override suspend fun getStatsRows(limit: Int, offset: Int) =
            gallery.values.sortedBy { it.fullpath }.drop(offset).take(limit).map {
                GalleryStatsRow(it.fullpath, it.date, it.model, it.sampler, it.loras, it.size, it.width, it.height, it.steps, it.cfg,
                    it.distilledCfg, it.scheduler, it.hiresScale, it.hiresUpscaler, it.hiresSteps, it.denoising, it.modules, it.embeddings, it.details)
            }
        override suspend fun findPathsBySize(width: Int, height: Int) = gallery.values.filter { it.width == width && it.height == height }.map { it.fullpath }
        override suspend fun findPathsBySampler(sampler: String, scheduler: String) =
            gallery.values.filter { it.details > 0 && it.sampler == sampler && it.scheduler?.trim().orEmpty() == scheduler }.map { it.fullpath }
        override suspend fun findPathsByModules(modules: String) =
            gallery.values.filter { it.details > 0 && ((modules.isEmpty() && it.modules == null) || it.modules == modules) }.map { it.fullpath }
        override suspend fun findPathsWithHires() = gallery.values.filter { it.hiresScale != null }.map { it.fullpath }
        override suspend fun findPathsByEmbedding(pattern: String): List<String> {
            val name = pattern.removePrefix("%,").removeSuffix(",%").replace("\\%", "%").replace("\\_", "_").replace("\\\\", "\\")
            return gallery.values.filter { e -> e.embeddings?.split(',')?.contains(name) == true }.map { it.fullpath }
        }
        override suspend fun findPathsBySteps(steps: Int) = gallery.values.filter { it.steps == steps }.map { it.fullpath }
        override suspend fun findPathsByCfg(cfg: Float) = gallery.values.filter { it.cfg == cfg }.map { it.fullpath }
        override suspend fun getAllPaths() = gallery.keys.toList().also { pathReads.incrementAndGet() }
        override suspend fun getUnreadPaths() = gallery.values.filter { it.savedAt == 0L }.map { it.fullpath }
        override suspend fun getPositivePrompt(path: String) = gallery[path]?.positivePrompt
        // SQLite's LIKE with '\' escaping: '%' any text (new lines too), '_' one character, ASCII in any case (3.6.2-1).
        private fun like(pattern: String, text: String): Boolean {
            val regex = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' && i + 1 < pattern.length -> { regex.append(Regex.escape(pattern[i + 1].toString())); i++ }
                    c == '%' -> regex.append(".*")
                    c == '_' -> regex.append(".")
                    else -> regex.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(regex.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matches(text)
        }
        override suspend fun findByPositivePrompt(pattern: String) =
            gallery.values.filter { like(pattern, it.positivePrompt) }.map { GalleryPromptPair(it.fullpath, it.positivePrompt, it.negativePrompt) }
        override suspend fun findByNegativePrompt(pattern: String) =
            gallery.values.filter { like(pattern, it.negativePrompt) }.map { GalleryPromptPair(it.fullpath, it.positivePrompt, it.negativePrompt) }
        override suspend fun getDetailsBacklog(version: Int, limit: Int) =
            gallery.values.filter { it.details < version && it.savedAt != 0L }.take(limit)
                .map { GalleryDetailsBacklog(it.fullpath, it.name, it.date, it.size) }
        override suspend fun countDetailsBacklog(version: Int) = gallery.values.count { it.details < version && it.savedAt != 0L }
        override suspend fun updateDetails(details: List<GalleryImageDetails>) {
            details.forEach { d ->
                gallery[d.fullpath]?.let {
                    gallery[d.fullpath] = it.copy(
                        width = d.width, height = d.height, steps = d.steps, cfg = d.cfg, distilledCfg = d.distilledCfg,
                        scheduler = d.scheduler, hiresScale = d.hiresScale, hiresUpscaler = d.hiresUpscaler,
                        hiresSteps = d.hiresSteps, denoising = d.denoising, modules = d.modules, embeddings = d.embeddings,
                        clipSkip = d.clipSkip, forgeVersion = d.forgeVersion, details = d.details,
                    )
                }
            }
        }
    }

    // 3.6.0: the jobs the app sent, with their phases.
    val jobRuns = ConcurrentHashMap<String, JobRunEntity>()

    override fun jobRunDao() = object : JobRunDao {
        override suspend fun insert(run: JobRunEntity) { jobRuns[run.id] = run }
        override suspend fun insertMissing(runs: List<JobRunEntity>) =
            runs.map { if (jobRuns.putIfAbsent(it.id, it) == null) 1L else -1L }
        override suspend fun getAllWithoutCurves() = jobRuns.values.sortedByDescending { it.startedAt }.map { it.copy(vramCurve = null) }
        override suspend fun getStartTimes(limit: Int) =
            jobRuns.values.filter { it.firstStepMs != null }.sortedByDescending { it.startedAt }.take(limit)
                .map { JobStartTime(it.model, it.previousModel, it.startKind, it.firstHash, it.firstStepMs!!) }
        override suspend fun getAll() = jobRuns.values.sortedByDescending { it.startedAt }
        override suspend fun totalsSince(since: Long) =
            jobRuns.values.filter { it.startedAt >= since }.let { runs ->
                JobTotals(runs.filter { it.outcome != "FAILED" }.sumOf { it.images }, runs.sumOf { it.totalMs })
            }
        override suspend fun get(id: String) = jobRuns[id]
        override suspend fun count() = jobRuns.size
        override suspend fun clearAll() = jobRuns.clear()
    }
}

// ------------------------------------------------------------------ fake Android application

class FakeResolver(private val root: File) : ContentResolver() {
    val inputs = ConcurrentHashMap<Uri, ByteArray>()
    /** Media rows by uri: display name, relative path, pending flag, and the bytes written. */
    data class Row(val name: String, val relativePath: String, @Volatile var pending: Boolean, val file: File)
    val rows = ConcurrentHashMap<String, Row>()
    @Volatile var failWrites = false
    override fun insert(url: Uri, values: ContentValues): Uri {
        val uri = Uri("$url/${System.nanoTime()}")
        rows[uri.toString()] = Row(
            values.values["_display_name"] as String,
            (values.values["relative_path"] as? String).orEmpty(),
            values.values["is_pending"] == 1,
            File(root.apply { mkdirs() }, "out_${System.nanoTime()}"),
        )
        return uri
    }
    /** Files written to a document the user picked (e.g. an exported backup), by uri. */
    val documents = ConcurrentHashMap<String, File>()
    override fun openOutputStream(uri: Uri): OutputStream {
        if (failWrites) throw java.io.FileNotFoundException("disk full")
        return rows[uri.toString()]?.file?.outputStream()
            ?: File(root.apply { mkdirs() }, "out_${System.nanoTime()}").also { documents[uri.toString()] = it }.outputStream()
    }
    override fun openInputStream(uri: Uri): InputStream? = inputs[uri]?.inputStream()
    override fun update(uri: Uri, values: ContentValues, where: String?, selectionArgs: Array<String>?): Int {
        val row = rows[uri.toString()] ?: return 0
        if (values.values["is_pending"] == 0) row.pending = false
        return 1
    }
    override fun delete(uri: Uri, where: String?, selectionArgs: Array<String>?): Int = if (rows.remove(uri.toString()) != null) 1 else 0
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor {
        val names = rows.values.filter { selectionArgs == null || it.relativePath == selectionArgs[0] }.map { it.name }
        return object : android.database.Cursor {
            var i = -1
            override fun moveToNext() = ++i < names.size
            override fun getString(columnIndex: Int) = names[i]
            override fun close() {}
        }
    }
    fun saved(): List<Row> = rows.values.filter { !it.pending }
}

class FakeApp(dir: File) : Application() {
    companion object {
        /** Later than firstInstallTime (1) means "updated". */
        @Volatile var lastUpdateTime = 1L
    }
    private val cache = File(dir, "cache").apply { mkdirs() }
    private val external = File(dir, "external").apply { mkdirs() }
    val resolver = FakeResolver(File(dir, "media"))
    val notifications = NotificationManager()
    val activityManager = android.app.ActivityManager()
    val power = PowerManager()
    val startedServices = CopyOnWriteArrayList<Intent>()
    private val pm = object : PackageManager() {
        override fun getPackageInfo(packageName: String, flags: Int) = PackageInfo().apply { versionCode = 277; firstInstallTime = 1; lastUpdateTime = FakeApp.lastUpdateTime }
    }

    /** Test hook: a cache directory nothing can be written to (its parent is a file). */
    @Volatile var brokenCache = false
    override fun getCacheDir(): File = if (brokenCache) File(File(cache, "blocker.txt").apply { writeText("x") }, "cache") else cache
    override fun getExternalFilesDir(type: String?) = File(external, type ?: "").apply { mkdirs() }
    override fun getContentResolver(): ContentResolver = resolver
    override fun getPackageManager(): PackageManager = pm
    override fun getSystemService(name: String): Any? = when (name) {
        NOTIFICATION_SERVICE -> notifications
        ACTIVITY_SERVICE -> activityManager
        POWER_SERVICE -> power
        else -> null
    }
    @Suppress("UNCHECKED_CAST")
    val connectivity = android.net.ConnectivityManager()
    val alarms = android.app.AlarmManager()
    override fun <T : Any?> getSystemService(serviceClass: Class<T>): T? =
        when (serviceClass) {
            NotificationManager::class.java -> notifications as T
            android.net.ConnectivityManager::class.java -> connectivity as T
            android.app.AlarmManager::class.java -> alarms as T
            android.os.PowerManager::class.java -> power as T
            android.app.job.JobScheduler::class.java -> android.content.Context.testJobScheduler as T
            else -> null
        }
    override fun startForegroundService(intent: Intent): ComponentName { startedServices += intent; return ComponentName() }
    override fun startService(intent: Intent): ComponentName { startedServices += intent; return ComponentName() }
}

// ------------------------------------------------------------------ PNG with A1111-style metadata

object Png {
    fun build(chunks: List<Pair<String, ByteArray>> = emptyList(), w: Int = 4, h: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) } }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunks.forEach { (t, d) -> chunk(out, t, d) }
        val raw = ByteArrayOutputStream().apply { repeat(h) { write(0); repeat(w) { write(byteArrayOf(1, 2, 3)) } } }
        chunk(out, "IDAT", ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(raw.toByteArray()) } }.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val d = DataOutputStream(out)
        val t = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size); d.write(t); d.write(data)
        d.writeInt(CRC32().apply { update(t); update(data) }.value.toInt())
    }

    fun tEXt(key: String, value: String) = "tEXt" to (key.toByteArray(Charsets.ISO_8859_1) + 0 + value.toByteArray(Charsets.ISO_8859_1))

    /** Pillow's PngInfo.add_itxt(key, value, zip=False/True). */
    fun iTXt(key: String, value: String, zip: Boolean = false): Pair<String, ByteArray> {
        val text = value.toByteArray(Charsets.UTF_8)
        val body = if (zip) ByteArrayOutputStream().also { DeflaterOutputStream(it).use { d -> d.write(text) } }.toByteArray() else text
        return "iTXt" to (key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, if (zip) 1 else 0, 0) + byteArrayOf(0) + byteArrayOf(0) + body)
    }

    /** What A1111/Forge write: tEXt when Latin-1 encodable, otherwise iTXt. */
    fun pillowText(key: String, value: String) =
        if (Charsets.ISO_8859_1.newEncoder().canEncode(value)) tEXt(key, value) else iTXt(key, value)

    fun withParameters(infotext: String) = build(listOf(pillowText("parameters", infotext)))
}

const val INFOTEXT = "masterpiece, best quality, <lora:detail:0.8>\n" +
    "Negative prompt: lowres\n" +
    "Steps: 28, Sampler: DPM++ 2M, Schedule type: Karras, CFG scale: 6.5, Seed: 1234567890, Size: 832x1216, Model hash: abc123, Model: model, Clip skip: 2"

// ------------------------------------------------------------------ mock Forge server (JDK HttpServer)

data class Req(val method: String, val path: String, val query: String?, val body: String)

class MockForge {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<Req>()
    @Volatile var generationMs = 300L
    @Volatile var generating = false
    @Volatile var txt2imgStatus = 200
    @Volatile var txt2imgBody: String? = null
    /** Extra routes (e.g. the gallery extension); returns true when it answered the request. */
    @Volatile var custom: ((HttpExchange, String, String) -> Boolean)? = null
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() = apply {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() = server.stop(0)

    fun calls(path: String) = requests.filter { it.path == path }
    fun txt2imgPayloads() = calls("/sdapi/v1/txt2img").map { org.json.JSONObject(it.body) }

    private fun respond(ex: HttpExchange, code: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    private fun images(n: Int): String {
        val b64 = (1..n).map { java.util.Base64.getEncoder().encodeToString(Png.withParameters(INFOTEXT)) }
        return """{"images":[${b64.joinToString(",") { "\"$it\"" }}],"parameters":{},"info":"{}"}"""
    }

    private fun progress(): String {
        val busy = generating
        return """{"progress":${if (busy) 0.5 else 0.0},"eta_relative":${if (busy) 3.5 else 0.0},"state":{"job_count":${if (busy) 1 else 0},"job_no":0,"sampling_step":${if (busy) 10 else 0},"sampling_steps":${if (busy) 20 else 0}},"current_image":null}"""
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        requests += Req(ex.requestMethod, path, ex.requestURI.rawQuery, body)
        if (custom?.invoke(ex, path, body) == true) return
        when {
            path == "/sdapi/v1/progress" -> respond(ex, 200, progress())
            path == "/sdapi/v1/memory" -> respond(ex, 200, """{"ram":{"used":8589934592,"total":34359738368},"cuda":{"system":{"used":6442450944,"total":12884901888}}}""")
            path == "/sdapi/v1/samplers" -> respond(ex, 200, """[{"name":"Euler a"},{"name":"DPM++ 2M"}]""")
            path == "/sdapi/v1/schedulers" -> respond(ex, 200, """[{"name":"automatic"},{"name":"karras"}]""")
            path == "/sdapi/v1/upscalers" -> respond(ex, 200, """[{"name":"Latent"}]""")
            path == "/sdapi/v1/sd-models" -> respond(ex, 200, """[{"title":"model.safetensors [abc123]","model_name":"model","filename":"/m/model.safetensors"},{"title":"other.safetensors [def456]","model_name":"other","filename":"/m/other.safetensors"}]""")
            path == "/sdapi/v1/loras" -> respond(ex, 200, """[{"name":"detail","path":"/l/detail.safetensors","metadata":{}}]""")
            path == "/sdapi/v1/options" && ex.requestMethod == "GET" -> respond(ex, 200, """{"sd_model_checkpoint":"model.safetensors [abc123]"}""")
            path == "/sdapi/v1/options" -> respond(ex, 200, "null")
            path == "/sdapi/v1/interrupt" -> respond(ex, 200, "null")
            path == "/sdapi/v1/txt2img" -> {
                generating = true
                try { Thread.sleep(generationMs) } finally { generating = false }
                respond(ex, txt2imgStatus, txt2imgBody ?: images(1))
            }
            else -> respond(ex, 404, """{"detail":"Not Found"}""")
        }
    }
}

// ------------------------------------------------------------------ one initialised app per JVM (forkEvery = 1)

object TestApp {
    lateinit var forge: MockForge
    lateinit var app: FakeApp
    lateinit var db: FakeDb
    lateinit var vm: ForgeViewModel
    val gson = Gson()

    /** What was loaded at the moment initializeApp() returned. */
    data class AfterInit(val status: String, val initialized: Boolean, val connected: Boolean, val models: Int, val samplers: Int,
                         val loras: Int, val queue: Int, val wildcards: Int, val favorites: Int, val galleryPrefixProbed: Boolean, val ms: Long,
                         val localMs: Long)
    lateinit var afterInit: AfterInit
    val statuses = CopyOnWriteArrayList<String>()

    /** Runs with the mock server's address before the app starts (e.g. to point GitHub at it). */
    var startHook: (String) -> Unit = {}

    fun start(
        config: AppConfig.(String) -> AppConfig = { this },
        custom: ((HttpExchange, String, String) -> Boolean)? = null,
        awaitServer: Boolean = true,
        seed: FakeDb.() -> Unit = {},
        // Runs with the new app (its cache dir) and database before the app starts (G56).
        prepare: FakeApp.() -> Unit = {},
    ): ForgeViewModel {
        TestMain.ensure()
        forge = MockForge().apply { this.custom = custom }.start()
        // GitHub is the mock server too (it has no release unless a test adds one): the start's update check never
        // reaches the real GitHub, whose newer release would post a notification since 3.6.1.
        GitHubApi.baseUrl = "${forge.url}/"
        startHook(forge.url)
        app = FakeApp(Files.createTempDirectory("forgegen2").toFile())
        db = FakeDb().apply(seed)
        app.prepare()
        db.settings["config"] = gson.toJson(AppConfig(apiUrl = forge.url).config(forge.url))
        Room.testFactory = { db }
        vm = ForgeViewModel(app)
        // Every status as it is set (Unconfined runs the collector in the thread that sets it).
        val recorder = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            ForgeSettingsManager.initStatus.collect { if (statuses.lastOrNull() != it) statuses += it }
        }
        val t0 = System.currentTimeMillis()
        runBlocking { vm.initializeApp() } // the phone's part: the screen shows after it
        val localMs = System.currentTimeMillis() - t0
        runBlocking { vm.awaitServerCheck() } // the server's part, which the screen does not wait for (2.0.0)
        val ms = System.currentTimeMillis() - t0
        recorder.cancel()
        ForgeSettingsManager.initStatus.value.let { if (statuses.lastOrNull() != it) statuses += it } // sampled: the last one may be missed
        afterInit = AfterInit(
            ForgeSettingsManager.initStatus.value, ForgeSettingsManager.isInitialized.value, vm.isConnected.value,
            vm.models.value.size, vm.samplers.value.size, vm.availableLoras.value.size, vm.generationQueue.value.size,
            vm.wildcards.value.size, vm.favoritePaths.value.size,
            forge.requests.any { it.path.endsWith("/global_setting") }, ms, localMs,
        )
        println("[TestApp] afterInit=$afterInit statuses=$statuses")
        if (!awaitServer) return vm
        awaitUntil("połączenie z mockiem Forge") { vm.isConnected.value }
        awaitUntil("listy modeli z API") { vm.models.value.isNotEmpty() && vm.selectedModel.value.isNotEmpty() }
        return vm
    }
}
