package com.example.forgegen

import com.example.forgegen.ui.components.IndicatorState
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Mock Infinite Image Browsing with folders, dates and generation data. */
object MockIib {
    class Entry(val path: String, val isDir: Boolean, @Volatile var date: String)

    val tree = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>>()
    val infos = ConcurrentHashMap<String, String>()
    @Volatile var batch = true
    @Volatile var single = true
    @Volatile var slowFolder = ""
    @Volatile var dropFolder = ""
    @Volatile var infoDelayMs = 0L
    /** false: the server has no gallery extension (every route answers 404). */
    @Volatile var installed = true
    /** Images the batch leaves out, as IIB does for files it cannot read. */
    val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    const val OLD = "2026-01-01 10:00:00"

    fun now(offsetMs: Long = 0) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(System.currentTimeMillis() + offsetMs))

    fun reset() {
        tree.clear(); infos.clear()
        batch = true; single = true; slowFolder = ""; dropFolder = ""; infoDelayMs = 0
        installed = true; unreadable.clear()
    }

    fun dir(parent: String, name: String, date: String = OLD): String {
        val path = "$parent/$name"
        tree.getOrPut(parent) { CopyOnWriteArrayList() }.add(Entry(path, true, date))
        tree.getOrPut(path) { CopyOnWriteArrayList() }
        return path
    }

    fun file(folder: String, name: String, model: String, date: String = OLD, lora: String = ""): String {
        val path = "$folder/$name"
        tree.getOrPut(folder) { CopyOnWriteArrayList() }.add(Entry(path, false, date))
        infos[path] = "a cat $lora\nNegative prompt: ugly\nline two\nSteps: 20, Sampler: Euler a, Seed: 7, Model: $model"
        return path
    }

    fun remove(path: String) = tree.values.forEach { list -> list.removeIf { it.path == path } }

    private fun json(folder: String): String {
        val items = tree[folder].orEmpty().map { e ->
            val name = e.path.substringAfterLast('/')
            """{"type":"${if (e.isDir) "dir" else "file"}","name":"$name","fullpath":"${e.path}","date":"${e.date}","size":"1 KB"}"""
        }
        return """{"files":[${items.joinToString(",")}]}"""
    }

    fun route(ex: HttpExchange, path: String, body: String): Boolean {
        val query = ex.requestURI.rawQuery.orEmpty()
        fun param(key: String) =
            query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
        if (!installed && path.startsWith("/infinite_image_browsing/")) return false
        when (path) {
            "/infinite_image_browsing/global_setting" ->
                send(ex, 200, """{"sd_cwd":"/srv/forge","global_setting":{"outdir_samples":"","outdir_txt2img_samples":"/out"}}""".toByteArray(), "application/json")
            "/infinite_image_browsing/files" -> {
                val folder = param("folder_path")
                if (folder == dropFolder) { ex.close(); return true }
                if (folder == slowFolder) Thread.sleep(800)
                send(ex, 200, json(folder).toByteArray(), "application/json")
            }
            "/infinite_image_browsing/image_geninfo_batch" -> {
                if (!batch) { send(ex, 404, """{"detail":"Not Found"}""".toByteArray(), "application/json"); return true }
                Thread.sleep(infoDelayMs)
                val paths = org.json.JSONObject(body).getJSONArray("paths")
                val out = org.json.JSONObject()
                for (i in 0 until paths.length()) if (paths.getString(i) !in unreadable) out.put(paths.getString(i), infos[paths.getString(i)] ?: "")
                send(ex, 200, out.toString().toByteArray(), "application/json")
            }
            "/infinite_image_browsing/image_geninfo" -> {
                if (!single) { send(ex, 404, """{"detail":"Not Found"}""".toByteArray(), "application/json"); return true }
                send(ex, 200, TestApp.gson.toJson(infos[param("path")] ?: "").toByteArray(), "application/json")
            }
            "/infinite_image_browsing/file", "/infinite_image_browsing/image-thumbnail" -> {
                send(ex, 200, Png.withParameters(infos[param("path")] ?: ""), "image/png")
            }
            else -> return false
        }
        return true
    }

    private fun send(ex: HttpExchange, code: Int, bytes: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G17_GalleryTest {
    companion object {
        val toasts = CopyOnWriteArrayList<String>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            // No gallery folder in the settings: it must come from the server's extension.
            TestApp.start(custom = { ex, path, body -> MockIib.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
            // The tabs' lists only compute while someone collects them (WhileSubscribed), like the gallery screen.
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.folderView.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.favoriteImages.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.allImages.collect { } }
            GlobalScope.launch(Dispatchers.IO) { ForgeGalleryManager.indexedImageCount.collect { } }
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        }

        fun calls(path: String) = TestApp.forge.calls("/infinite_image_browsing/$path")

        fun open(path: String) {
            onMain { vm.fetchGalleryFolder(path) }
            awaitUntil("folder $path") { vm.currentGalleryPath.value == path && !vm.isGalleryLoading.value }
        }

        /**
         * The automatic update of the index, run now: [full] lists every folder (as once a day), otherwise only
         * changed or recent ones. Returns its result (only logged by the app).
         */
        fun sync(full: Boolean = true): String {
            awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
            TestApp.db.settings["gallery_full_sync_at"] = if (full) "0" else System.currentTimeMillis().toString()
            val field = ForgeGalleryManager::class.java.getDeclaredField("lastAutoSyncAt").apply { isAccessible = true }
            field.setLong(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else ForgeGalleryManager, 0L)
            val before = ForgeGalleryManager.syncsDone.get()
            onMain { vm.autoSyncGallery() }
            awaitUntil("sync", 20_000) { ForgeGalleryManager.syncsDone.get() > before && !vm.isGalleryIndexing.value }
            return ForgeGalleryManager.lastSyncMessage.value
        }

        fun quietSync() = sync(full = false)

        fun folder() = vm.galleryFolder.value.items
        fun all() = vm.allImages.value.items
        fun favorites() = vm.favoriteImages.value.items

        fun indexed() = TestApp.db.gallery.values.toList().associateBy { it.fullpath }
    }

    @Before fun reset() {
        MockIib.reset()
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters()) } // the sort order too
        onMain { vm.setAutoSaveMode(AUTO_SAVE_OFF) }
        toasts.clear()
        onMain { vm.wipeGalleryIndex() }
        awaitUntil("index wiped") { toasts.contains("Gallery Index Wiped") }
        toasts.clear()
    }

    private fun galleryWithThreeImages() {
        val today = MockIib.dir("/out", "2026-09-24", MockIib.now())
        val old = MockIib.dir("/out", "2026-01-01")
        MockIib.file(today, "00001-1.png", "alpha", date = MockIib.now(-60_000), lora = "<lora:detail:0.8>")
        MockIib.file(today, "00002-2.png", "alpha", date = MockIib.now(-60_000))
        MockIib.file(old, "00001-3.png", "beta")
        MockIib.file("/out", "notes.txt", "x") // not an image
    }

    @Test fun `02 a full sync reads generation data in batches without downloading images`() {
        galleryWithThreeImages()
        val filesBefore = calls("file").size
        val message = sync()
        println("[G17-02] $message")
        val index = indexed()
        assertEquals(setOf("/out/2026-09-24/00001-1.png", "/out/2026-09-24/00002-2.png", "/out/2026-01-01/00001-3.png"), index.keys)
        assertEquals("ugly\nline two", index.getValue("/out/2026-01-01/00001-3.png").negativePrompt)
        assertEquals("detail", index.getValue("/out/2026-09-24/00001-1.png").loras)
        assertEquals("no image was downloaded", filesBefore, calls("file").size)
        assertEquals(1, calls("image_geninfo_batch").size)
        assertEquals("Gallery indexed: 3 new", message)
    }

    @Test fun `03 search covers the whole gallery, All Images lists everything newest first`() {
        galleryWithThreeImages()
        sync()
        open("/out")
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(models = setOf("alpha"))) }
        awaitUntil("search results") { folder().size == 2 }
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(loras = setOf("detail"))) }
        awaitUntil("lora search") { folder().map { it.name } == listOf("00001-1.png") }
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(negativeTags = listOf("LINE TWO"))) }
        awaitUntil("tag search in the multi-line negative prompt") { folder().size == 3 && all().size == 3 }
        // 3.6.2-1: the positive and the negative prompt apart, all tags at once, whole tags with Exact Tags.
        fun found(filters: ForgeGalleryManager.GalleryFilters, expected: Int) {
            onMain { vm.applyGalleryFilters(filters) }
            awaitUntil("$expected found for $filters") { vm.galleryFolder.value.filters == filters && folder().size == expected }
        }
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("line two")), 0)
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("cat")), 3)
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("cat", "dog")), 0)
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("cat"), exactTags = true), 0) // the tag is "a cat"
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("A Cat"), exactTags = true), 3)
        found(ForgeGalleryManager.GalleryFilters(positiveTags = listOf("a cat"), negativeTags = listOf("line two"), exactTags = true), 3)
        found(ForgeGalleryManager.GalleryFilters(negativeTags = listOf("line"), exactTags = true), 0)
        found(ForgeGalleryManager.GalleryFilters(models = setOf("beta"), negativeTags = listOf("ugly"), exactTags = true), 1)
        onMain { vm.clearGalleryFilters() }

        // The sort panel says oldest first: folders follow it, "All Images" stays newest first (2.1.0).
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(sortOrder = ForgeGalleryManager.SortOrder.OLDEST)) }
        open("/out/2026-09-24")
        awaitUntil("folder oldest first") { folder().map { it.name } == listOf("00001-1.png", "00002-2.png") }

        awaitUntil("all images") { all().size == 3 }
        println("[G17-03] all images: ${all().map { it.fullpath }}")
        assertEquals(
            "newest first whatever the sort order",
            listOf("/out/2026-09-24/00002-2.png", "/out/2026-09-24/00001-1.png", "/out/2026-01-01/00001-3.png"),
            all().map { it.fullpath },
        )
    }

    @Test fun `04 the top folder hides non-images and has no virtual folders`() {
        galleryWithThreeImages()
        open("/out")
        val names = folder().map { it.name }
        println("[G17-04] $names")
        assertEquals("no Favorites / All Images folders any more (they are tabs)", listOf("2026-09-24", "2026-01-01"), names)
    }

    @Test fun `05 a quiet sync only lists changed or recent folders and forgets deleted images`() {
        galleryWithThreeImages()
        sync()
        val oldListings = calls("files").count { it.query.orEmpty().contains("2026-01-01") }
        // A new image today, one deleted in the old folder (which changes that folder's date too).
        MockIib.file("/out/2026-09-24", "00003-4.png", "gamma", date = MockIib.now())
        quietSync()
        assertEquals("unchanged old folder not listed again", oldListings, calls("files").count { it.query.orEmpty().contains("2026-01-01") })
        assertTrue("/out/2026-09-24/00003-4.png" in indexed())

        MockIib.remove("/out/2026-01-01/00001-3.png")
        MockIib.tree.getValue("/out").first { it.path.endsWith("2026-01-01") }.date = "2026-01-02 10:00:00"
        quietSync()
        assertFalse("deleted image forgotten", "/out/2026-01-01/00001-3.png" in indexed())
    }

    @Test fun `06 older gallery extensions fall back to single requests, then to the image files`() {
        galleryWithThreeImages()
        MockIib.batch = false
        sync()
        assertEquals(3, indexed().size)
        assertTrue(calls("image_geninfo").size >= 3)

        onMain { vm.wipeGalleryIndex() }
        awaitUntil("wiped") { vm.galleryIndexedImageCount.value == 0 }
        MockIib.single = false
        val filesBefore = calls("file").size
        sync()
        assertEquals(3, indexed().size)
        assertEquals("model read from the PNG", "beta", indexed().getValue("/out/2026-01-01/00001-3.png").model)
        assertEquals(filesBefore + 3, calls("file").size)
    }

    @Test fun `07 an image whose data cannot be read is still in All Images and is read again later`() {
        galleryWithThreeImages()
        val bad = "/out/2026-01-01/00001-3.png"
        MockIib.unreadable += bad
        val message = sync()
        println("[G17-07] $message")
        assertTrue(message, message.contains("1 without readable generation data"))
        assertEquals(0L, indexed().getValue(bad).savedAt)
        awaitUntil("all three shown") { all().size == 3 }

        MockIib.unreadable.clear()
        quietSync()
        assertEquals("read on the next sync", "beta", indexed().getValue(bad).model)
        assertTrue(indexed().getValue(bad).savedAt > 0)
    }

    @Test fun `08 the extension is found on connecting and gives the folders, a server without it says so`() {
        assertEquals(ForgeGalleryManager.Extension.READY, vm.galleryExtension.value.state)
        assertEquals("/out", vm.config.value.galleryPath)
        assertEquals("/srv/forge", vm.config.value.serverBasePath)

        MockIib.installed = false
        onMain { vm.checkGalleryExtension() }
        awaitUntil("missing") { vm.galleryExtension.value.state == ForgeGalleryManager.Extension.MISSING }
        val listings = calls("files").size
        onMain { vm.openGallery(GalleryMode.NORMAL) }
        Thread.sleep(300)
        assertEquals("nothing to browse", "", vm.currentGalleryPath.value)
        assertEquals(null, vm.readyGalleryRoot())
        assertEquals("nothing asked", listings, calls("files").size)
        onMain { vm.autoSyncGallery() }
        Thread.sleep(300)
        assertFalse("no index update without the extension", vm.isGalleryIndexing.value)

        MockIib.installed = true
        onMain { vm.checkGalleryExtension() }
        awaitUntil("found again") { vm.galleryExtension.value.state == ForgeGalleryManager.Extension.READY }
        onMain { vm.openGallery(GalleryMode.NORMAL) }
        awaitUntil("top folder") { vm.currentGalleryPath.value == "/out" && !vm.isGalleryLoading.value }
    }

    @Test fun `09 a slow folder answer does not replace the folder opened after it`() {
        MockIib.dir("/out", "slow"); MockIib.dir("/out", "fast")
        MockIib.slowFolder = "/out/slow"
        onMain { vm.fetchGalleryFolder("/out/slow") }
        Thread.sleep(100)
        onMain { vm.fetchGalleryFolder("/out/fast") }
        Thread.sleep(1500)
        assertEquals("/out/fast", vm.currentGalleryPath.value)
        assertFalse(vm.isGalleryLoading.value)
    }

    @Test fun `10 a dropped connection fails the sync instead of crashing`() {
        galleryWithThreeImages()
        MockIib.dropFolder = "/out/2026-01-01"
        uncaught.clear()
        val message = sync()
        println("[G17-10] $message")
        assertTrue(message.startsWith("Indexing failed"))
        assertTrue("uncaught: $uncaught", uncaught.isEmpty())
    }

    @Test fun `11 viewer metadata comes from the server without downloading the image`() {
        galleryWithThreeImages()
        val filesBefore = calls("file").size
        val item = GalleryItem(name = "00001-3.png", fullpath = "/out/2026-01-01/00001-3.png", type = "file", date = MockIib.OLD)
        onMain { vm.loadMetadataForImage(item) }
        awaitUntil("metadata") { vm.currentImageMetadata.value?.startsWith("a cat") == true }
        assertEquals(filesBefore, calls("file").size)
    }

    @Test fun `12 urls always carry t, thumbnails ask for 512 px`() {
        val item = GalleryItem(name = "a.png", fullpath = "/out/a.png", type = "file", date = null)
        val image = vm.getGalleryImageUrl(item)
        val thumb = vm.getGalleryThumbnailUrl(item)
        println("[G17-12] $image | $thumb")
        assertTrue(image.contains("/infinite_image_browsing/file?path=%2Fout%2Fa.png&t="))
        assertTrue(thumb.contains("/infinite_image_browsing/image-thumbnail?") && thumb.contains("size=512x512") && thumb.contains("t="))
    }

    @Test fun `13 saving to the phone goes to Pictures-ForgeGen once, sharing leaves nothing behind`() {
        galleryWithThreeImages()
        val resolver = TestApp.app.contentResolver as FakeResolver
        resolver.rows.clear()
        toasts.clear()
        val item = GalleryItem(name = "00001-3.png", fullpath = "/out/2026-01-01/00001-3.png", type = "file", date = MockIib.OLD)
        onMain { vm.downloadImage(item) }
        awaitUntil("saved") { toasts.contains("Saved to Pictures/ForgeGen") }
        onMain { vm.downloadImage(item) }
        awaitUntil("second tap") { toasts.contains("Already saved in Pictures/ForgeGen") }
        assertEquals(listOf("2026-01-01_00001-3.png"), resolver.saved().map { it.name })
        assertEquals("Pictures/ForgeGen/", resolver.saved().single().relativePath)

        var shared: android.content.Intent? = null
        onMain { vm.shareImage(item) { shared = it } }
        awaitUntil("share sheet") { shared != null }
        assertEquals("no extra media file for sharing", 1, resolver.rows.size)
        assertTrue(java.io.File(TestApp.app.cacheDir, "shared/00001-3.png").exists())

        resolver.failWrites = true
        onMain { vm.downloadImage(item.copy(fullpath = "/out/2026-09-24/00002-2.png", name = "00002-2.png")) }
        awaitUntil("failure toast") { toasts.any { it.startsWith("Download Failed") } }
        resolver.failWrites = false
        assertEquals("a failed save leaves no empty image behind", 1, resolver.rows.size)
    }

    @Test fun `14 auto-save of all new images saves only images made after switching it on, once`() {
        galleryWithThreeImages()
        val resolver = TestApp.app.contentResolver as FakeResolver
        resolver.rows.clear()
        sync()
        onMain { vm.setAutoSaveMode(AUTO_SAVE_ALL) }
        awaitUntil("mode") { vm.config.value.autoSaveMode == AUTO_SAVE_ALL }
        Thread.sleep(1100) // the new image must be later than the switch, at second resolution
        MockIib.file("/out/2026-09-24", "00009-9.png", "alpha", date = MockIib.now(5_000))
        quietSync()
        assertEquals(listOf("2026-09-24_00009-9.png"), resolver.saved().map { it.name })
        quietSync()
        assertEquals("no duplicates", 1, resolver.saved().size)

        TestApp.app.connectivity.metered = true
        MockIib.file("/out/2026-09-24", "00010-9.png", "alpha", date = MockIib.now(6_000))
        quietSync()
        assertEquals("nothing on mobile data", 1, resolver.saved().size)
        TestApp.app.connectivity.metered = false
        quietSync()
        assertEquals("saved once back on Wi-Fi", 2, resolver.saved().size)
        onMain { vm.setAutoSaveMode(AUTO_SAVE_OFF) }
    }

    @Test fun `15 auto-save of favorites saves a starred image`() {
        galleryWithThreeImages()
        val resolver = TestApp.app.contentResolver as FakeResolver
        resolver.rows.clear()
        onMain { vm.setAutoSaveMode(AUTO_SAVE_FAVORITES) }
        awaitUntil("mode") { vm.config.value.autoSaveMode == AUTO_SAVE_FAVORITES }
        val item = GalleryItem(name = "00002-2.png", fullpath = "/out/2026-09-24/00002-2.png", type = "file", date = MockIib.OLD)
        onMain { vm.toggleFavorite(item) }
        awaitUntil("saved") { resolver.saved().size == 1 }
        onMain { vm.toggleFavorite(item) }
        awaitUntil("unstarred") { item.fullpath !in vm.favoritePaths.value }
        onMain { vm.toggleFavorite(item) }
        awaitUntil("starred again") { item.fullpath in vm.favoritePaths.value }
        Thread.sleep(500)
        assertEquals(1, resolver.saved().size)
        onMain { vm.setAutoSaveMode(AUTO_SAVE_OFF) }
    }

    @Test fun `16 images made by the app reach the index on their own`() {
        galleryWithThreeImages()
        sync()
        MockIib.file("/out/2026-09-24", "00005-5.png", "alpha", date = MockIib.now())
        vm.setGalleryVisible(true) // at once only while the gallery shows (3.4.0; closed: G45)
        onMain { vm.updateState { it.copy(positivePrompt = "gallery follows", batchCount = 1) } }
        onMain { vm.queueGeneration() }
        awaitUntil("indexed without a tap", 25_000) { "/out/2026-09-24/00005-5.png" in indexed() }
        awaitUntil("queue done", 20_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        vm.setGalleryVisible(false)
    }

    @Test fun `17 the list shows each image's prompt from the index`() {
        galleryWithThreeImages()
        sync()
        val prompt = runBlocking { vm.galleryPositivePrompt("/out/2026-01-01/00001-3.png") }
        println("[G17-17] '$prompt'")
        assertTrue(prompt, prompt.startsWith("a cat"))
        assertEquals("not indexed", "", runBlocking { vm.galleryPositivePrompt("/out/none.png") })
    }

    @Test fun `18 refresh lists the folder again and updates the index at once`() {
        galleryWithThreeImages()
        sync()
        open("/out/2026-09-24")
        awaitUntil("no sync running", 20_000) { !vm.isGalleryIndexing.value }
        MockIib.file("/out/2026-09-24", "00007-7.png", "alpha", date = MockIib.now())
        onMain { vm.refreshGallery() }
        awaitUntil("listed") { folder().any { it.name == "00007-7.png" } }
        awaitUntil("indexed", 20_000) { "/out/2026-09-24/00007-7.png" in indexed() }
    }

    @Test fun `19 nothing above the gallery's folder can be opened`() {
        galleryWithThreeImages()
        MockIib.dir("/", "srv")
        open("/out/2026-09-24")
        onMain { vm.fetchGalleryFolder("/") }
        awaitUntil("the top folder instead") { vm.currentGalleryPath.value == "/out" && !vm.isGalleryLoading.value }
        onMain { vm.fetchGalleryFolder("/srv/forge") }
        Thread.sleep(300)
        awaitUntil("still the gallery") { vm.currentGalleryPath.value == "/out" && !vm.isGalleryLoading.value }
        assertFalse("the parent was never listed", calls("files").any { it.query.orEmpty().let { q -> q == "folder_path=/" || q == "folder_path=" } })
        assertEquals(listOf("Gallery" to "/out", "2026-09-24" to "/out/2026-09-24"), vm.galleryBreadcrumb("/out/2026-09-24"))
        assertEquals("/out", vm.galleryParentFolder("/out/2026-09-24"))
        assertEquals("Back closes the gallery from its top folder", null, vm.galleryParentFolder("/out"))
    }

    @Test fun `20 favorites are there from any folder, in the sort order, narrowed by a search`() {
        galleryWithThreeImages()
        sync()
        open("/out/2026-01-01")
        val old = GalleryItem(name = "00001-3.png", fullpath = "/out/2026-01-01/00001-3.png", type = "file", date = MockIib.OLD)
        val new = GalleryItem(name = "00001-1.png", fullpath = "/out/2026-09-24/00001-1.png", type = "file", date = MockIib.now(-60_000))
        onMain { vm.toggleFavorite(old) }
        onMain { vm.toggleFavorite(new) }
        awaitUntil("both starred") { favorites().map { it.fullpath }.containsAll(listOf(old.fullpath, new.fullpath)) }
        open("/out/2026-09-24")
        assertTrue("still there in another folder", favorites().any { it.fullpath == old.fullpath })
        println("[G17-20] ${favorites().map { it.fullpath }}")
        assertEquals("newest first by default", new.fullpath, favorites().first().fullpath)

        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(sortOrder = ForgeGalleryManager.SortOrder.OLDEST)) }
        awaitUntil("oldest first") { favorites().map { it.fullpath }.filter { it == old.fullpath || it == new.fullpath } == listOf(old.fullpath, new.fullpath) }
        onMain { vm.applyGalleryFilters(ForgeGalleryManager.GalleryFilters(models = setOf("beta"))) }
        awaitUntil("search narrows them") { favorites().map { it.fullpath } == listOf(old.fullpath) }

        onMain { vm.toggleFavorite(old) }
        awaitUntil("unstarred") { favorites().isEmpty() }
        onMain { vm.toggleFavorite(new) }
        awaitUntil("unstarred too") { new.fullpath !in vm.favoritePaths.value }
    }

    @Test fun `21 the gallery opens on the tab used last, the prompt picker on All Images, in the last folder`() {
        galleryWithThreeImages()
        onMain { vm.openGallery(GalleryMode.NORMAL) }
        onMain { vm.selectGalleryTab(GalleryTab.FAVORITES) }
        awaitUntil("saved") { vm.config.value.galleryTab == GalleryTab.FAVORITES.name }
        open("/out/2026-09-24")

        onMain { vm.openGallery(GalleryMode.PROMPT_PICKER) }
        assertEquals(GalleryTab.ALL_IMAGES, vm.galleryTab.value)
        onMain { vm.selectGalleryTab(GalleryTab.GALLERY) }
        Thread.sleep(200)
        assertEquals("a tab chosen while picking a prompt is not remembered", GalleryTab.FAVORITES.name, vm.config.value.galleryTab)

        onMain { vm.openGallery(GalleryMode.NORMAL) }
        assertEquals(GalleryTab.FAVORITES, vm.galleryTab.value)
        awaitUntil("the folder open last") { vm.currentGalleryPath.value == "/out/2026-09-24" && !vm.isGalleryLoading.value }

        onMain { vm.selectGalleryTab(GalleryTab.GALLERY) }
        awaitUntil("back to the default") { vm.config.value.galleryTab == GalleryTab.GALLERY.name }
    }
}
