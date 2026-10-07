package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

/** IIB with the endpoints that change files (3.2.0), over MockIib's folders. */
object MockIibFiles {
    /** What global_setting says (is_readonly). */
    @Volatile var saysReadOnly = false

    /** Whether the write endpoints answer 403 (IIB_ACCESS_CONTROL_PERMISSION=read-only). */
    @Volatile var refusesWrites = false

    /** Paths move_files and copy_files report as failed. */
    val failOn: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val bodies = CopyOnWriteArrayList<Pair<String, String>>()

    fun reset() {
        saysReadOnly = false
        refusesWrites = false
        failOn.clear()
        bodies.clear()
    }

    private fun entries(folder: String) = MockIib.tree[folder].orEmpty()

    private fun exists(path: String) = MockIib.tree.values.any { list -> list.any { it.path == path } }

    fun route(
        ex: HttpExchange,
        path: String,
        body: String,
    ): Boolean {
        val endpoint = path.removePrefix("/infinite_image_browsing/")
        if (endpoint in WRITES) bodies += endpoint to body
        if (endpoint in WRITES && refusesWrites) return send(ex, 403, """{"detail":"User is not authorized to perform this action."}""")
        val json = if (body.isNotBlank()) org.json.JSONObject(body) else org.json.JSONObject()
        fun list(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
        when (endpoint) {
            "global_setting" ->
                return send(ex, 200, """{"sd_cwd":"/srv/forge","global_setting":{"outdir_samples":"","outdir_txt2img_samples":"/out"},"is_readonly":$saysReadOnly}""")
            "files" -> {
                // With the files' sizes, as IIB lists them.
                val folder = java.net.URLDecoder.decode(ex.requestURI.rawQuery.substringAfter("folder_path=").substringBefore('&'), "UTF-8")
                val items =
                    entries(folder).map { e ->
                        val name = e.path.substringAfterLast('/')
                        val type = if (e.isDir) "dir" else "file"
                        """{"type":"$type","name":"$name","fullpath":"${e.path}","date":"${e.date}","size":"1 KB","bytes":${if (e.isDir) 0 else 1000 + name.length}}"""
                    }
                return send(ex, 200, """{"files":[${items.joinToString(",")}]}""")
            }
            "delete_files" -> {
                list("file_paths").forEach { MockIib.remove(it) }
                return send(ex, 200, """{"ok":true}""")
            }
            "mkdirs" -> {
                val dest = json.getString("dest_folder")
                if (!MockIib.tree.containsKey(dest)) MockIib.dir(dest.substringBeforeLast('/'), dest.substringAfterLast('/'), MockIib.now())
                return send(ex, 200, "null")
            }
            "move_files", "copy_files" -> {
                val dest = json.getString("dest")
                if (endpoint == "move_files" && !MockIib.tree.containsKey(dest)) {
                    return send(ex, 400, """{"detail":"Destination folder $dest does not exist."}""")
                }
                val errors = org.json.JSONArray()
                for (file in list("file_paths")) {
                    if (file in failOn) {
                        errors.put("Error ${if (endpoint == "move_files") "moving" else "copying"} file $file to $dest: in use")
                        continue
                    }
                    val name = file.substringAfterLast('/')
                    val info = MockIib.infos[file].orEmpty()
                    if (endpoint == "move_files") MockIib.remove(file)
                    MockIib.file(dest, name, "x", date = MockIib.now())
                    MockIib.infos["$dest/$name"] = info
                }
                return send(ex, 200, """{"errors":$errors}""")
            }
            "batch_top_4_media_info" -> {
                val out = org.json.JSONObject()
                for (folder in list("paths")) {
                    val newest = entries(folder).filter { !it.isDir }.sortedByDescending { it.date }.take(4)
                    out.put(
                        folder,
                        org.json.JSONArray(
                            newest.map { org.json.JSONObject(mapOf("fullpath" to it.path, "name" to it.path.substringAfterLast('/'), "type" to "file", "date" to it.date)) },
                        ),
                    )
                }
                return send(ex, 200, out.toString())
            }
            "check_path_exists" -> {
                val out = org.json.JSONObject()
                list("paths").forEach { out.put(it, exists(it)) }
                return send(ex, 200, out.toString())
            }
        }
        return MockIib.route(ex, path, body)
    }

    private val WRITES = setOf("delete_files", "mkdirs", "move_files", "copy_files")

    private fun send(
        ex: HttpExchange,
        code: Int,
        text: String,
    ): Boolean {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        return true
    }
}

@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G43_GalleryFilesTest {
    companion object {
        val toasts = CopyOnWriteArrayList<String>()
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex, path, body -> MockIibFiles.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.folderView.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.favoriteImages.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
            // Made only while the gallery shows them (3.4.0).
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.folderImageCounts.collect { } }
            awaitUntil("gallery extension", 20_000) { vm.galleryExtension.value.state == ForgeGalleryManager.Extension.READY }
        }

        fun calls(endpoint: String) = MockIibFiles.bodies.filter { it.first == endpoint }.map { it.second }

        fun open(path: String) {
            onMain { vm.fetchGalleryFolder(path) }
            awaitUntil("folder $path") { vm.currentGalleryPath.value == path && !vm.isGalleryLoading.value }
        }

        fun sync() {
            awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
            TestApp.db.settings["gallery_full_sync_at"] = "0"
            val field = ForgeGalleryManager::class.java.getDeclaredField("lastAutoSyncAt").apply { isAccessible = true }
            field.setLong(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else ForgeGalleryManager, 0L)
            val before = ForgeGalleryManager.syncsDone.get()
            onMain { vm.autoSyncGallery() }
            awaitUntil("sync", 20_000) { ForgeGalleryManager.syncsDone.get() > before && !vm.isGalleryIndexing.value }
        }

        fun folder() = vm.galleryFolder.value.items.map { it.name }

        fun item(path: String) = GalleryItem(path.substringAfterLast('/'), path, "file", MockIib.OLD)
    }

    @Before fun reset() {
        MockIib.reset()
        MockIibFiles.reset()
        toasts.clear()
        // /out: 2026-09-27 (a1, a2, a3, a4, a5), keep (k1), b (b1, b2, k1)
        val day = MockIib.dir("/out", "2026-09-27")
        (1..5).forEach { MockIib.file(day, "a$it.png", "alpha", date = "2026-09-27 10:0$it:00", lora = "<lora:detail:0.8>") }
        val keep = MockIib.dir("/out", "keep")
        MockIib.file(keep, "k1.png", "beta")
        val b = MockIib.dir("/out", "b")
        MockIib.file(b, "b1.png", "gamma")
        MockIib.file(b, "b2.png", "gamma")
        MockIib.file(b, "k1.png", "gamma")
        sync()
    }

    @Test fun `01 the index keeps the files' sizes, and the server may change files`() {
        assertTrue(vm.galleryExtension.value.canWrite)
        val index = TestApp.db.gallery
        assertEquals(1000L + "a1.png".length, index.getValue("/out/2026-09-27/a1.png").size)
        // Rows indexed before sizes were kept, as the app finds them at its start, get theirs from the next listing.
        index.replaceAll { _, row -> row.copy(size = 0) }
        reloadGalleryIndex()
        sync()
        assertEquals(1000L + "b2.png".length, index.getValue("/out/b/b2.png").size)
        // ...in the index in memory too, which the sync no longer reads again (3.6.0-1).
        awaitUntil("sizes in memory") { kotlinx.coroutines.runBlocking { vm.galleryStatistics() }.sizesKnown }
    }

    @Test fun `02 a delete leaves the lists at once, and Undo brings it back without asking the server`() {
        open("/out/2026-09-27")
        onMain { vm.deleteGalleryImages(listOf(item("/out/2026-09-27/a1.png"))) }
        awaitUntil("a1 hidden") { "a1.png" !in folder() }
        assertEquals(1, vm.galleryPendingDelete.value?.items?.size)
        onMain { vm.undoGalleryDelete() }
        awaitUntil("a1 back") { "a1.png" in folder() }
        assertNull(vm.galleryPendingDelete.value)
        Thread.sleep(6_800)
        assertTrue("nothing was sent", calls("delete_files").isEmpty())
        assertTrue(TestApp.db.gallery.containsKey("/out/2026-09-27/a1.png"))
    }

    @Test fun `03 a delete is sent when the time is up, and leaves the index and the favorites`() {
        open("/out/2026-09-27")
        onMain { vm.toggleFavorite(item("/out/2026-09-27/a2.png")) }
        awaitUntil("favorite") { "/out/2026-09-27/a2.png" in vm.favoritePaths.value }
        val start = System.currentTimeMillis()
        onMain { vm.deleteGalleryImages(listOf(item("/out/2026-09-27/a2.png"))) }
        awaitUntil("sent", 10_000) { calls("delete_files").isNotEmpty() }
        val waited = System.currentTimeMillis() - start
        assertTrue("sent after about 6 s, not at once ($waited ms)", waited >= 5_500)
        assertTrue(calls("delete_files").single().contains("/out/2026-09-27/a2.png"))
        awaitUntil("forgotten") { !TestApp.db.gallery.containsKey("/out/2026-09-27/a2.png") && "/out/2026-09-27/a2.png" !in vm.favoritePaths.value }
        assertFalse("a2.png" in folder())
        assertNull(vm.galleryPendingDelete.value)
    }

    @Test fun `04 deleting again sends the earlier delete at once, only the last can be undone`() {
        open("/out/2026-09-27")
        onMain { vm.deleteGalleryImages(listOf(item("/out/2026-09-27/a3.png"))) }
        awaitUntil("a3 hidden") { "a3.png" !in folder() }
        onMain { vm.deleteGalleryImages(listOf(item("/out/2026-09-27/a4.png"))) }
        awaitUntil("the first sent at once", 2_000) { calls("delete_files").size == 1 }
        assertTrue(calls("delete_files").single().contains("a3.png"))
        onMain { vm.undoGalleryDelete() }
        awaitUntil("a4 back, a3 gone") { "a4.png" in folder() && "a3.png" !in folder() }
    }

    @Test fun `05 a move keeps generation data and favorites, and leaves out names taken there`() {
        open("/out/b")
        onMain { vm.toggleFavorite(item("/out/b/b1.png")) }
        awaitUntil("favorite") { "/out/b/b1.png" in vm.favoritePaths.value }
        val prompt = TestApp.db.gallery.getValue("/out/b/b1.png").positivePrompt
        val items = listOf("/out/b/b1.png", "/out/b/b2.png", "/out/b/k1.png").map { item(it) }
        onMain { vm.transferGalleryImages(items, "/out/keep", ForgeGalleryManager.Transfer.MOVE) }
        awaitUntil("message") { toasts.any { it.startsWith("Moved") } }
        assertEquals("Moved 2 images to keep, 1 image left out: the name is taken there", toasts.first { it.startsWith("Moved") })
        val sent = org.json.JSONObject(calls("move_files").single())
        assertEquals(listOf("/out/b/b1.png", "/out/b/b2.png"), (0 until 2).map { sent.getJSONArray("file_paths").getString(it) })
        assertEquals("/out/keep", sent.getString("dest"))
        assertEquals(prompt, TestApp.db.gallery["/out/keep/b1.png"]?.positivePrompt)
        assertFalse(TestApp.db.gallery.containsKey("/out/b/b1.png"))
        assertTrue("/out/keep/b1.png" in vm.favoritePaths.value)
        awaitUntil("the open folder listed again") { folder() == listOf("k1.png") }
        assertEquals("/out/keep", vm.galleryLastFolder.value)
    }

    @Test fun `06 a copy makes the folder first and reports what failed`() {
        MockIibFiles.failOn += "/out/b/b2.png"
        val items = listOf("/out/b/b1.png", "/out/b/b2.png").map { item(it) }
        onMain { vm.transferGalleryImages(items, "/out/new", ForgeGalleryManager.Transfer.COPY) }
        awaitUntil("message") { toasts.any { it.startsWith("Copied") } }
        assertEquals("Copied 1 image to new, 1 failed", toasts.first { it.startsWith("Copied") })
        val order = MockIibFiles.bodies.map { it.first }
        assertTrue(order.indexOf("mkdirs") < order.indexOf("copy_files"))
        assertTrue(MockIib.tree["/out/new"].orEmpty().any { it.path == "/out/new/b1.png" })
        assertTrue("the original stays", MockIib.tree["/out/b"].orEmpty().any { it.path == "/out/b/b1.png" })
    }

    @Test fun `07 a server that refuses changes - the images come back and changes are no longer offered`() {
        // An IIB that does not say it is read-only answers 403 to the change itself.
        MockIibFiles.refusesWrites = true
        onMain { vm.transferGalleryImages(listOf(item("/out/b/b1.png")), "/out/keep", ForgeGalleryManager.Transfer.MOVE) }
        awaitUntil("read-only message") { toasts.contains(ForgeGalleryManager.READ_ONLY_MESSAGE) }
        assertFalse(vm.galleryExtension.value.canWrite)
        // One that says so is known at once.
        MockIibFiles.refusesWrites = false
        onMain { vm.checkGalleryExtension() }
        awaitUntil("writable again") { vm.galleryExtension.value.canWrite }
        MockIibFiles.saysReadOnly = true
        onMain { vm.checkGalleryExtension() }
        awaitUntil("read-only from global_setting") {
            vm.galleryExtension.value.state == ForgeGalleryManager.Extension.READY && !vm.galleryExtension.value.canWrite
        }
        // A delete refused by the server brings the image back.
        MockIibFiles.saysReadOnly = false
        MockIibFiles.refusesWrites = true
        open("/out/2026-09-27")
        toasts.clear()
        onMain { vm.deleteGalleryImages(listOf(item("/out/2026-09-27/a5.png"))) }
        awaitUntil("hidden") { "a5.png" !in folder() }
        awaitUntil("refused and back", 10_000) { toasts.contains(ForgeGalleryManager.READ_ONLY_MESSAGE) && "a5.png" in folder() }
        MockIibFiles.refusesWrites = false
        onMain { vm.checkGalleryExtension() }
        awaitUntil("writable for the next tests") { vm.galleryExtension.value.canWrite }
    }

    @Test fun `08 folders get covers of their newest images and their numbers of images`() {
        open("/out")
        awaitUntil("covers") { vm.galleryFolderCovers.value.size == 3 }
        val covers = vm.galleryFolderCovers.value
        assertEquals(listOf("a5.png", "a4.png", "a3.png", "a2.png"), covers.getValue("/out/2026-09-27").map { it.name })
        assertEquals(listOf("k1.png"), covers.getValue("/out/keep").map { it.name })
        assertEquals(5, vm.galleryFolderImageCounts.value["/out/2026-09-27"])
        assertEquals(3, vm.galleryFolderImageCounts.value["/out/b"])
    }

    @Test fun `09 favorites gone from the server are found and removed`() {
        onMain { vm.toggleFavorite(item("/out/b/b2.png")) }
        onMain { vm.toggleFavorite(item("/out/gone/x.png")) }
        awaitUntil("favorites") { vm.favoritePaths.value.containsAll(listOf("/out/b/b2.png", "/out/gone/x.png")) }
        val checkedAt = ForgeGalleryManager::class.java.getDeclaredField("favoritesCheckedAt").apply { isAccessible = true }
        checkedAt.setLong(if (java.lang.reflect.Modifier.isStatic(checkedAt.modifiers)) null else ForgeGalleryManager, 0L)
        onMain { vm.checkFavorites() }
        awaitUntil("missing found") { "/out/gone/x.png" in vm.missingFavorites.value }
        val missing = vm.missingFavorites.value
        assertFalse("/out/b/b2.png" in missing)
        onMain { vm.removeMissingFavorites() }
        awaitUntil("removed") { vm.favoritePaths.value.none { it in missing } }
        assertTrue("/out/b/b2.png" in vm.favoritePaths.value)
        awaitUntil("message") { toasts.contains(if (missing.size == 1) "Removed 1 favorite" else "Removed ${missing.size} favorites") }
    }

    @Test fun `10 a new folder - a bad name is refused without asking, a good one is made`() {
        val bad = runBlocking { vm.createGalleryFolder("/out", "a/b") }
        assertTrue(bad.isFailure)
        assertTrue(calls("mkdirs").isEmpty())
        val made = runBlocking { vm.createGalleryFolder("/out", " fresh ") }
        assertEquals("/out/fresh", made.getOrNull())
        assertEquals("/out/fresh", org.json.JSONObject(calls("mkdirs").single()).getString("dest_folder"))
    }

    @Test fun `11 a ZIP on the phone holds every image, named by its folder`() {
        val items = listOf("/out/b/k1.png", "/out/keep/k1.png", "/out/b/b1.png").map { item(it) }
        onMain { vm.downloadGalleryZip(items) }
        awaitUntil("saved", 10_000) { toasts.any { it.startsWith("Saved ForgeGen_") } }
        val row = TestApp.app.resolver.saved().single { it.name.endsWith(".zip") }
        val names = ZipInputStream(row.file.inputStream()).use { zip -> generateSequence { zip.nextEntry?.name }.toList() }
        assertEquals(listOf("b_k1.png", "keep_k1.png", "b_b1.png"), names)
        assertTrue("the server wrote nothing", MockIibFiles.bodies.isEmpty())
    }

    @Test fun `12 statistics come from the index`() {
        val stats = runBlocking { vm.galleryStatistics() }
        assertEquals(9, stats.images)
        assertTrue(stats.sizesKnown)
        assertEquals(listOf("detail" to 5), stats.topLoras)
        assertEquals("alpha", stats.topModels.first().first)
        assertEquals(9, stats.topTags.first { it.first == "a cat" }.second)
    }

    @Test fun `13 All Images shuffled, and back to the newest first`() {
        awaitUntil("all images") { vm.allImages.value.items.size == 9 }
        val newest = vm.allImages.value.items.map { it.fullpath }
        onMain { vm.setAllImagesRandom(true) }
        awaitUntil("random") { vm.allImages.value.order.random }
        val shuffled = vm.allImages.value.items.map { it.fullpath }
        assertEquals(newest.toSet(), shuffled.toSet())
        val seed = vm.allImages.value.order.seed
        onMain { vm.setAllImagesRandom(true) }
        awaitUntil("shuffled again") { vm.allImages.value.order.seed != seed }
        onMain { vm.setAllImagesRandom(false) }
        awaitUntil("newest first") { !vm.allImages.value.order.random }
        assertEquals(newest, vm.allImages.value.items.map { it.fullpath })
        assertNotEquals(0, newest.size)
    }
}
