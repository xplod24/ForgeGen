package com.example.forgegen

import android.graphics.Bitmap
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.forgegen.ui.components.*
import com.example.forgegen.ui.screens.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotTest {
    @get:Rule val compose = createComposeRule()

    private val out = File(System.getProperty("shot.dir") ?: "/tmp/shots").apply { mkdirs() }

    private fun save(
        name: String,
        @Suppress("UNUSED_PARAMETER") index: Int = 0,
    ) {
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { }
        compose.runOnUiThread {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = (f.get(inst) as List<android.view.View>).filter { it.isShown && it.width > 0 }
            val first = views.first()
            val bmp = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            views.forEach { v ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                canvas.save()
                canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                v.draw(canvas)
                canvas.restore()
            }
            println("[shot] $name views=${views.size} ${views.map { it.width.toString() + "x" + it.height }}")
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun vm(): ForgeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = ForgeViewModel(app)
        runBlocking { withTimeoutOrNull(30_000) { vm.initializeApp() } }
        // The server's lists, as if connected.
        val nm = ForgeViewModel::class.java.getDeclaredField("networkManager").apply { isAccessible = true }.get(vm)
        fun setFlow(name: String, value: Any) {
            val f = nm.javaClass.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(nm) as MutableStateFlow<Any>).value = value
        }
        setFlow("_models", listOf(ApiResource(title = "animagineXL31.safetensors [1449e5b0b9]", path = "/m/animagineXL31.safetensors", name = "animagineXL31")))
        setFlow("_availableLoras", listOf(ApiResource(title = "detail", path = "/l/detail.safetensors", name = "detail")))
        ForgeModelManager.updateState("animagineXL31.safetensors [1449e5b0b9]")
        ForgeModelManager.updateModules(
            listOf(ServerModule("ae.safetensors", ServerModule.Kind.VAE), ServerModule("clip_l.safetensors", ServerModule.Kind.TEXT_ENCODER), ServerModule("t5xxl_fp8.safetensors", ServerModule.Kind.TEXT_ENCODER)),
            ModuleSupport.FORGE,
        )
        vm.updateState {
            it.copy(
                positivePrompt = "masterpiece, best quality, 1girl, long hair, blonde hair, smile, looking at viewer, outdoors, cherry blossoms, <lora:detail:0.6>, <lora:flat_color:0.8>",
                negativePrompt = "lowres, bad anatomy, worst quality, low quality, watermark",
                width = 832, height = 1216, steps = 28, cfgScale = 6.5f, sampler = "Euler a", scheduler = "Karras", clipSkip = 2,
            )
        }
        return vm
    }

    private fun main(vm: ForgeViewModel, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(dark), typography = forgeTypography(), shapes = forgeShapes()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(viewModel = vm, navController = rememberNavController())
                }
            }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun main_dark() {
        val vm = vm()
        vm.saveConfig(vm.config.value.copy(mainOpenRows = emptyList()))
        main(vm, dark = true)
        compose.mainClock.advanceTimeBy(2_000)
        save("01_main_dark")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h2000dp-xhdpi")
    fun main_dark_open() {
        val vm = vm()
        vm.saveConfig(vm.config.value.copy(mainOpenRows = listOf(MainRows.NEGATIVE, MainRows.SAMPLING, MainRows.SIZE)))
        vm.updateModelSettings("animagineXL31") { it.copy(type = ModelType.FLUX.name, vae = "ae.safetensors", textEncoders = listOf("clip_l.safetensors")) }
        vm.updateState { it.copy(hiresFix = true) }
        main(vm, dark = true)
        compose.mainClock.advanceTimeBy(2_000)
        save("02_main_dark_open")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1500dp-xhdpi")
    fun main_light() {
        val vm = vm()
        vm.saveConfig(vm.config.value.copy(mainOpenRows = listOf(MainRows.SAMPLING)))
        main(vm, dark = false)
        compose.mainClock.advanceTimeBy(2_000)
        save("03_main_light")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun model_sheet() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(true), typography = forgeTypography(), shapes = forgeShapes()) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    ModelSettingsSheet(
                        model = "flux1-dev.safetensors [abc]",
                        settings = ModelSettings(type = ModelType.FLUX.name, vae = "ae.safetensors", textEncoders = listOf("clip_l.safetensors", "t5xxl_fp8.safetensors"), defaults = ModelDefaults(896, 1152, 28, 1f, "Euler", "Simple", 1)),
                        modules = listOf(ServerModule("ae.safetensors", ServerModule.Kind.VAE)),
                        support = ModuleSupport.FORGE,
                        onChange = {},
                        onSaveDefaults = {},
                        onApplyDefaults = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        println("roots=$roots")
        save("04_model_sheet", roots - 1)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h300dp-xhdpi")
    fun whats_new_bar() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(true), typography = forgeTypography(), shapes = forgeShapes()) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxWidth().height(80.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                        Text("Forge Generator", style = MaterialTheme.typography.titleMedium)
                        Text("Connected · 38 ms")
                    }
                    WhatsNewBar(visible = true, version = "3.0.0", onShown = {}, onShow = {}, onClose = {}, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("05_whats_new_bar")
    }

    private fun setField(owner: Any, name: String, value: Any?) {
        val f = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(owner) as MutableStateFlow<Any?>).value = value
    }

    private fun screen(dark: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = forgeColorScheme(dark), typography = forgeTypography(), shapes = forgeShapes()) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun job(n: Int, prompt: String, status: GenerationStatus = GenerationStatus.QUEUED, error: String? = null, label: String? = null) =
        QueuedGeneration(
            id = "job$n", positivePrompt = prompt,
            payload = Txt2ImgPayloadDto(prompt = prompt, negative_prompt = "lowres", steps = 28, cfg_scale = 6.5f, width = 832, height = 1216, n_iter = 2, batch_size = 1,
                seed = -1L, sampler_name = "Euler a", scheduler = "Karras", override_settings = OverrideSettingsDto(clipSkip = 2, sdModelCheckpoint = "animagineXL31"),
                enable_hr = false, hr_scale = 2f, hr_upscaler = "Latent", denoising_strength = 0.7f),
            status = status, error = error, label = label,
        )

    private fun seedQueue(paused: Boolean) {
        setField(ForgeQueueManager, "_generationQueue", listOf(
            job(1, "masterpiece, 1girl, long hair, cherry blossoms, <lora:detail:0.6>", GenerationStatus.GENERATING),
            job(2, "a cat on a windowsill, rain outside, cozy", label = "Upscale ×2"),
            job(3, "portrait of an old sailor, dramatic lighting", label = "Variance · 4242 · detail 0.7 · CFG 7"),
            job(4, "city at night, neon, reflections", GenerationStatus.FAILED, error = "The server returned HTTP 500. RuntimeError: Sizes of tensors must match"),
        ))
        if (paused) {
            setField(ForgeQueueManager, "_isQueuePaused", true)
            setField(ForgeQueueManager, "_queuePauseReason", "The server returned HTTP 500. RuntimeError: Sizes of tensors must match")
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o1_queue() {
        val vm = vm()
        seedQueue(paused = false)
        screen { QueueScreen(vm, rememberNavController()) }
        save("o1_queue")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun o2_main_paused() {
        val vm = vm()
        seedQueue(paused = true)
        main(vm, dark = true)
        compose.mainClock.advanceTimeBy(2_000)
        save("o2_main_paused")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o3_presets() {
        val vm = vm()
        vm.savePreset("Portrait SDXL")
        vm.savePreset("Landscape 16:9", includePrompts = false)
        vm.savePreset("FLUX quick")
        compose.mainClock.autoAdvance = false
        screen { PresetsScreen(vm, rememberNavController()) }
        save("o3_presets")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o4_wildcards() {
        val vm = vm()
        setField(ForgePromptManager, "_wildcards", listOf(
            WildcardEntity("hair", "long hair\nshort hair\nponytail\ntwintails"),
            WildcardEntity("place", "beach\nforest\ncity at night\nlibrary"),
            WildcardEntity("mood", "happy\ncalm\nmelancholic"),
        ))
        screen { WildcardsScreen(vm, rememberNavController()) }
        save("o4_wildcards")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o5_metadata() {
        screen {
            AppMetadataAlertDialog(
                metadata = "masterpiece, 1girl, long hair, <lora:detail:0.6>\nNegative prompt: lowres, bad anatomy\nSteps: 28, Sampler: Euler a, Schedule type: Karras, CFG scale: 6.5, Seed: 4242, Size: 832x1216, Model hash: 1449e5b0b9, Model: animagineXL31, Clip skip: 2, Version: f2.0.1",
                fileInfo = "00012-4242.png • 1532 KB",
                onDismiss = {}, onApplyPrompt = { _, _ -> }, onApplyModel = {}, onApplyLoras = {}, onApplyAll = {},
            )
        }
        save("o5_metadata")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o6_variance_dialog() {
        val vm = vm()
        val models = listOf(ApiResource(title = "animagineXL31", path = "/m/a.safetensors", name = "animagineXL31", hash = "1449e5b0b9"))
        val info = Infotext.parse("1girl, <lora:detail:0.6>, <lora:flat_color:0.8>\nSteps: 28, Sampler: Euler a, Schedule type: Karras, CFG scale: 6.5, Seed: 4242, Size: 832x1216, Model hash: 1449e5b0b9, Model: animagineXL31")
        val ready = ImageJobs.remake(info, models, null)
        setField(ForgeGalleryManager, "_imageJobs", ForgeGalleryManager.ImageJobsRequest(ImageJobs.Kind.VARIANCE, 3, listOf(ready, ready, ImageJobs.Source.Skipped(ImageJobs.NO_DATA))))
        screen { com.example.forgegen.ui.components.ImageJobsDialog(vm) }
        save("o6_variance_dialog")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o7_server_dialog() {
        val vm = vm()
        screen { com.example.forgegen.ui.components.ServerConnectionDialog(viewModel = vm, offline = true, phoneOnline = true, onDismiss = {}, onOpenSettings = {}) }
        save("o7_server_dialog")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun o8_gallery() {
        val vm = vm()
        screen { GalleryScreen(vm, rememberNavController()) }
        save("o8_gallery")
    }

    private fun seedTimeline() {
        seedQueue(paused = false)
        val nm = ForgeQueueManager
        // Speeds learned: every job has a time.
        setField(nm, "speedRates", mapOf("*" to 0.25, "animagineXL31" to 0.25))
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun p1_queue_timeline() {
        val vm = vm()
        seedTimeline()
        screen { QueueScreen(vm, rememberNavController()) }
        save("p1_queue_timeline")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun p2_main_paused_strip() {
        val vm = vm()
        seedQueue(paused = true)
        main(vm, dark = true)
        compose.mainClock.advanceTimeBy(2_000)
        save("p2_main_paused_strip")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun p3_presets_bar() {
        val vm = vm()
        vm.savePreset("Portrait SDXL")
        screen { PresetsScreen(vm, rememberNavController()) }
        save("p3_presets_bar")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun p4_gallery_bar() {
        val vm = vm()
        screen { GalleryScreen(vm, rememberNavController()) }
        save("p4_gallery_bar")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun p5_queue_light() {
        val vm = vm()
        seedTimeline()
        screen(dark = false) { QueueScreen(vm, rememberNavController()) }
        save("p5_queue_light")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun q1_update_card() {
        val changelog = java.io.File("../CHANGELOG.md").readText()
        val section = changelog.substringAfter("## 3.0.0-1").substringBefore("\n## ")
        val items = parseReleaseNotes(section)
        screen {
            Column(Modifier.padding(16.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), MaterialTheme.shapes.small).padding(16.dp)) {
                Text("Update Available: 3.0.0-1", style = MaterialTheme.typography.titleSmall)
                Text("What's new:", style = MaterialTheme.typography.labelMedium)
                MarkdownText(markdown = releaseNotesMarkdown(items.take(3)), textStyle = MaterialTheme.typography.bodySmall)
            }
        }
        save("q1_update_card")
    }

    private val normalMemory = ServerMemory(ramUsed = 12.3, ramTotal = 31.9, vramUsed = 5.1, vramTotal = 8.0)

    @Test @Config(sdk = [35], qualifiers = "w400dp-h420dp-xhdpi")
    fun r1_top_bar() {
        screen {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                MainTopBar(ServerConnection.CONNECTED, 35, 0, normalMemory, {}, {}, {}, {})
                MainTopBar(ServerConnection.CONNECTED, 41, 0, normalMemory.copy(vramUsed = 7.6, ramUsed = 14.0), {}, {}, {}, {})
                MainTopBar(ServerConnection.SEARCHING, 0, System.currentTimeMillis() + 30_000, null, {}, {}, {}, {})
                MainTopBar(ServerConnection.OFFLINE, 0, 0, null, {}, {}, {}, {})
            }
        }
        save("r1_top_bar")
    }

    @Test @Config(sdk = [35], qualifiers = "w360dp-h420dp-xhdpi")
    fun r1b_top_bar_narrow_light() {
        screen(dark = false) {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                MainTopBar(ServerConnection.CONNECTED, 135, 0, normalMemory, {}, {}, {}, {})
                MainTopBar(ServerConnection.CONNECTED, 35, 0, ServerMemory(45.2, 128.0, 21.7, 24.0), {}, {}, {}, {})
                MainTopBar(ServerConnection.SEARCHING, 0, System.currentTimeMillis() + 30_000, null, {}, {}, {}, {})
            }
        }
        save("r1b_top_bar_narrow_light")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun r2_memory_sheet() {
        screen {
            ServerMemorySheet(normalMemory, "animagineXL31", unloading = false, busy = false, onUnload = {}, onDismiss = {})
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("r2_memory_sheet")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun r3_memory_sheet_busy_light() {
        screen(dark = false) {
            ServerMemorySheet(normalMemory.copy(vramUsed = 7.6), "animagineXL31", unloading = false, busy = true, onUnload = {}, onDismiss = {})
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("r3_memory_sheet_busy_light")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun r4_main_dark() {
        val vm = vm()
        fun repoFlow(name: String, value: Any?) {
            val f = ForgeRepository::class.java.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(null) as MutableStateFlow<Any?>).value = value
        }
        repoFlow("_serverMemory", normalMemory)
        repoFlow("_connection", ServerConnection.CONNECTED)
        main(vm, dark = true)
        compose.mainClock.advanceTimeBy(2_000)
        save("r4_main_dark")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h700dp-xhdpi")
    fun r5_update_card() {
        val changelog = java.io.File("../CHANGELOG.md").readText()
        val section = changelog.substringAfter("## 3.0.0-4").substringBefore("\n## ")
        val notes = parseReleaseNotes(section)
        screen {
            Column(Modifier.padding(16.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), MaterialTheme.shapes.small).padding(16.dp)) {
                Text("Update Available: 3.0.0-4", style = MaterialTheme.typography.titleSmall)
                Text("What's new:", style = MaterialTheme.typography.labelMedium)
                MarkdownText(markdown = releaseNotesMarkdown(notes, maxItems = 3), textStyle = MaterialTheme.typography.bodySmall)
                Text("Show All (${releaseNoteCount(notes)})", style = MaterialTheme.typography.labelMedium)
            }
        }
        save("r5_update_card")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun s1_hires_picker() {
        val ups = listOf("None", "Lanczos", "4x-UltraSharp", "R-ESRGAN 4x+ Anime6B")
        screen {
            OptionPickerSheet(
                "Upscaler", HiresUpscalers.LATENT_MODES + ups, "Latent", {}, {},
                groups = listOf("Latent" to HiresUpscalers.LATENT_MODES, "Upscalers" to ups),
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s1_hires_picker")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun s2_vae_picker() {
        screen {
            OptionPickerSheet("VAE", listOf("Built in", "ae", "sdxl_vae"), "Built in", {}, {}, onRefresh = {})
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s2_vae_picker")
    }

    private val loraRes = listOf(
        ApiResource(title = "add_more_light_xl", path = "/l/add_more_light_xl.safetensors", name = "add_more_light_xl"),
        ApiResource(title = "detail", path = "/l/detail.safetensors", name = "detail"),
        ApiResource(title = "epiNoiseoffset", path = "/l/epiNoiseoffset.safetensors", name = "epiNoiseoffset"),
        ApiResource(title = "flat_color", path = "/l/flat_color.safetensors", name = "flat_color"),
        ApiResource(title = "flux_realism", path = "/l/flux_realism.safetensors", name = "flux_realism"),
        ApiResource(title = "pixel_art_xl", path = "/l/pixel_art_xl.safetensors", name = "pixel_art_xl"),
    )
    private val loraIndex = LoraInfoIndex(listOf(
        LoraInfo("detail", "/l/detail.safetensors", LoraBase.SDXL, null, listOf(LoraTag("detailed", 412), LoraTag("intricate", 280), LoraTag("sharp focus", 231), LoraTag("highres", 190), LoraTag("masterpiece", 96)), "1024×1024", 10),
        LoraInfo("flat_color", "/l/flat_color.safetensors", LoraBase.SDXL, null, listOf(LoraTag("flat color", 90), LoraTag("no lineart", 60), LoraTag("simple background", 40))),
        LoraInfo("add_more_light_xl", "/l/add_more_light_xl.safetensors", LoraBase.SDXL, null, listOf(LoraTag("light rays", 30), LoraTag("rim light", 20))),
        LoraInfo("pixel_art_xl", "/l/pixel_art_xl.safetensors", LoraBase.SDXL, null, listOf(LoraTag("pixel art", 50))),
        LoraInfo("epiNoiseoffset", "/l/epiNoiseoffset.safetensors", LoraBase.SD1, null, emptyList()),
        LoraInfo("flux_realism", "/l/flux_realism.safetensors", LoraBase.FLUX, null, listOf(LoraTag("realistic", 10))),
    ))
    private val embeddingList = EmbeddingList(loaded = listOf("EasyNegative", "aesthetic_xl"), skipped = listOf("bad-hands-5"))

    private fun picker(embeddingsTab: Boolean = false) {
        screen {
            LoraPickerSheet(
                loras = loraRes, info = loraIndex, modelType = ModelType.SDXL,
                isActive = { it.name == "detail" || it.name == "flat_color" }, previewCandidates = { emptyList() },
                onPickLora = {}, onRefreshLoras = {}, embeddings = embeddingList,
                positivePrompt = "masterpiece, detailed", negativePrompt = "lowres, EasyNegative",
                onAddEmbedding = { _, _ -> }, onRefreshEmbeddings = {}, onDismiss = {},
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        if (embeddingsTab) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Embeddings · 3", substring = true), useUnmergedTree = true)[0].performClick()
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun t1_lora_picker() {
        picker()
        save("t1_lora_picker")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun t2_embeddings_tab() {
        picker(embeddingsTab = true)
        save("t2_embeddings_tab")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun t3_lora_details() {
        screen {
            LoraDetailsSheet(
                title = "detail", info = loraIndex.of("detail", "/l/detail.safetensors"), previewCandidates = emptyList(),
                modelType = ModelType.SDXL, prompt = "masterpiece, detailed", onAddTags = {}, onDismiss = {},
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("t3_lora_details")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun t4_lora_card_and_styles() {
        val vm = vm()
        // Let the saved state finish loading first, so it does not replace the one set below.
        Thread.sleep(3_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val nm = ForgeViewModel::class.java.getDeclaredField("networkManager").apply { isAccessible = true }.get(vm)
        fun setFlow(name: String, value: Any) {
            val f = nm.javaClass.getDeclaredField(name).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(nm) as MutableStateFlow<Any>).value = value
        }
        setFlow("_availableLoras", loraRes)
        setFlow("_loraInfo", loraIndex)
        setFlow("_promptStyles", listOf(PromptStyle("Cinematic", "cinematic still, {prompt}", "cartoon"), PromptStyle("Film grain", "film grain, 35mm", "")))
        vm.updateModelSettings(vm.selectedModel.value) { it.copy(type = ModelType.SDXL.name) }
        ForgeSettingsManager::class.java.getDeclaredField("_config").apply { isAccessible = true }.get(ForgeSettingsManager).let {
            @Suppress("UNCHECKED_CAST")
            (it as MutableStateFlow<AppConfig>).value = vm.config.value.copy(serverStyles = true)
        }
        vm.updateState { it.copy(positivePrompt = "masterpiece, detailed, <lora:detail:0.6>, <lora:epiNoiseoffset:0.8>", styles = listOf("Cinematic", "Film grain")) }
        screen {
            val state by vm.appState.collectAsStateWithLifecycleCompat()
            val config by vm.config.collectAsStateWithLifecycleCompat()
            val active = parseActiveLoras(state.positivePrompt)
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                PromptCard(vm, state, config, emptyList(), emptyList(), {}, 0)
                LorasCard(vm, loraRes, active)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        // The saved state may have loaded meanwhile and replaced the one set above: set it again.
        setFlow("_availableLoras", loraRes)
        setFlow("_loraInfo", loraIndex)
        setFlow("_promptStyles", listOf(PromptStyle("Cinematic", "cinematic still, {prompt}", "cartoon"), PromptStyle("Film grain", "film grain, 35mm", "")))
        vm.updateState { it.copy(positivePrompt = "masterpiece, detailed, <lora:detail:0.6>, <lora:epiNoiseoffset:0.8>", styles = listOf("Cinematic", "Film grain")) }
        ForgeSettingsManager::class.java.getDeclaredField("_config").apply { isAccessible = true }.get(ForgeSettingsManager).let {
            @Suppress("UNCHECKED_CAST")
            (it as MutableStateFlow<AppConfig>).value = vm.config.value.copy(serverStyles = true)
        }
        vm.updateModelSettings(vm.selectedModel.value) { it.copy(type = ModelType.SDXL.name) }
        // The active LoRAs are read from the prompt off the main thread.
        Thread.sleep(1_000)
        compose.mainClock.advanceTimeBy(2_000)
        save("t4_lora_card_and_styles")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun t5_styles_sheet() {
        screen {
            StylesSheet(
                styles = listOf(
                    PromptStyle("Cinematic", "cinematic still, shallow depth of field, {prompt}", "cartoon, flat colors"),
                    PromptStyle("Film grain", "film grain, 35mm photo, kodak portra", ""),
                    PromptStyle("Anime lineart", "clean lineart, cel shading", "photo, realistic"),
                    PromptStyle("Watercolor", "watercolor painting, soft edges", ""),
                ),
                chosen = listOf("Cinematic", "Film grain"), onChosen = {}, onPaste = {}, onRefresh = {}, onDismiss = {},
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("t5_styles_sheet")
    }

    // ---------------------------------------------------------------- 3.2.0 gallery

    private fun galleryImage(path: String) = GalleryItem(path.substringAfterLast('/'), path, "file", "2026-09-27 10:00:00")

    @Suppress("UNCHECKED_CAST")
    private fun <T> managerFlow(name: String): MutableStateFlow<T> =
        ForgeGalleryManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(ForgeGalleryManager) as MutableStateFlow<T>

    /** IIB answering the gallery's listing and covers, as a Retrofit ForgeApi. */
    private fun fakeGalleryApi(): ForgeApi {
        val gson = com.google.gson.Gson()
        val folders = listOf("2026-09-28", "2026-09-27", "keepers", "wip")
        fun listing(folder: String): String {
            val items =
                if (folder == "/out") {
                    folders.mapIndexed { i, name -> """{"type":"dir","name":"$name","fullpath":"/out/$name","date":"2026-09-2${8 - i} 10:00:00"}""" } +
                        (1..6).map { """{"type":"file","name":"0000$it-1234.png","fullpath":"/out/0000$it-1234.png","date":"2026-09-26 10:0$it:00","bytes":1500000}""" }
                } else {
                    emptyList()
                }
            return """{"files":[${items.joinToString(",")}]}"""
        }
        return java.lang.reflect.Proxy.newProxyInstance(ForgeApi::class.java.classLoader, arrayOf(ForgeApi::class.java)) { _, method, args ->
            val url = args?.firstOrNull() as? String ?: ""
            when (method.name) {
                "getGalleryFilesDynamic" -> {
                    val folder = java.net.URLDecoder.decode(args?.getOrNull(1) as? String ?: "", "UTF-8") // no cookie parameter since 3.5.0
                    retrofit2.Response.success(listing(folder).toResponseBodyCompat())
                }
                "getGalleryFolderCovers" -> {
                    val paths = (args?.get(1) as GalleryPathsRequestDto).paths
                    val covers = paths.associateWith { p -> (1..(if (p.endsWith("wip")) 2 else 4)).map { GalleryItemDto("c$it.png", "$p/c$it.png", "file", "2026-09-27 10:00:00") } }
                    retrofit2.Response.success(covers)
                }
                "toString" -> "FakeGalleryApi"
                "hashCode" -> 1
                "equals" -> false
                else -> retrofit2.Response.error<Any>(404, "{}".toResponseBodyCompat())
            }.also { if (url.isEmpty() && method.name == "getGalleryFilesDynamic") Unit }
        } as ForgeApi
    }

    private fun String.toResponseBodyCompat() =
        okhttp3.ResponseBody.Companion.run { this@toResponseBodyCompat.toResponseBody("application/json".toMediaTypeOrNullCompat()) }

    private fun String.toMediaTypeOrNullCompat() = okhttp3.MediaType.Companion.run { this@toMediaTypeOrNullCompat.toMediaTypeOrNull() }

    private fun galleryVm(): ForgeViewModel {
        val vm = vm()
        Thread.sleep(2_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        ForgeSettingsManager::class.java.getDeclaredField("_config").apply { isAccessible = true }.get(ForgeSettingsManager).let {
            @Suppress("UNCHECKED_CAST")
            (it as MutableStateFlow<AppConfig>).value = vm.config.value.copy(galleryPath = "/out", galleryView = "GRID_3")
        }
        val nm = ForgeViewModel::class.java.getDeclaredField("networkManager").apply { isAccessible = true }.get(vm) as ForgeNetworkManager
        ForgeNetworkManager::class.java.getDeclaredField("forgeApi").apply { isAccessible = true }.set(nm, fakeGalleryApi())
        managerFlow<ForgeGalleryManager.ExtensionStatus>("_extension").value = ForgeGalleryManager.ExtensionStatus(ForgeGalleryManager.Extension.READY)
        // _folderImageCounts is worked out from the index since 3.4.0: nothing to set here.
        managerFlow<String?>("_lastFolder").value = "/out/keepers"
        return vm
    }

    // 3.6.2-1: the searched tags as chips over the list, and the search panel with its tag fields.
    private fun tagSearchGallery(): ForgeViewModel {
        val vm = galleryVm()
        vm.selectGalleryTab(GalleryTab.GALLERY)
        vm.openGallery(GalleryMode.NORMAL)
        vm.applyGalleryFilters(
            ForgeGalleryManager.GalleryFilters(positiveTags = listOf("1girl", "long hair"), negativeTags = listOf("blurry"), exactTags = true),
        )
        screen { GalleryScreen(vm, rememberNavController()) }
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeBy(2_000)
        return vm
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun z10_gallery_tag_chips() {
        tagSearchGallery()
        save("z10_gallery_tag_chips")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun z11_gallery_filter_panel() {
        tagSearchGallery()
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Filter")).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        save("z11_gallery_filter_panel")
        compose.onNode(androidx.compose.ui.test.hasText("Exact Tags")).performScrollTo()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        save("z12_gallery_filter_exact")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun u1_gallery_folder_covers() {
        val vm = galleryVm()
        vm.selectGalleryTab(GalleryTab.GALLERY)
        vm.openGallery(GalleryMode.NORMAL)
        screen { GalleryScreen(vm, rememberNavController()) }
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeBy(2_000)
        save("u1_gallery_folder_covers")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun u2_selection_menu() {
        screen {
            androidx.compose.foundation.layout.Column {
                FloatingTopBar(
                    title = "3 selected",
                    onNavigate = {},
                    navigationIcon = androidx.compose.material.icons.Icons.Default.Close,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    androidx.compose.material3.IconButton(onClick = {}) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Star, null) }
                    androidx.compose.material3.IconButton(onClick = {}) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Share, null) }
                    SelectionMoreMenu(canWrite = true, onSave = {}, onZip = {}, onUpscale = {}, onMove = {}, onCopy = {}, onDelete = {})
                }
            }
        }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("More Actions")).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        save("u2_selection_menu")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun u3_folder_picker() {
        val vm = galleryVm()
        val items = listOf("/out/00001-1234.png", "/out/00002-1234.png", "/out/00003-1234.png").map { galleryImage(it) }
        screen { FolderPickerSheet(vm, items, ForgeGalleryManager.Transfer.MOVE, onPick = { _, _ -> }, onDismiss = {}) }
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeBy(2_000)
        save("u3_folder_picker")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun u4_undo_favorites_random() {
        val pending = ForgeGalleryManager.PendingDelete(listOf(galleryImage("/out/a.png"), galleryImage("/out/b.png"), galleryImage("/out/c.png")))
        screen {
            androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                MissingFavoritesNote(missing = 2, onRemove = {}, modifier = Modifier.padding(horizontal = 12.dp))
                AllImagesOrderRow(order = AllImagesOrder(), onRandom = {}, onStatistics = {})
                AllImagesOrderRow(order = AllImagesOrder(true, 1), onRandom = {}, onStatistics = {})
                UndoDeleteBar(pending = pending, onUndo = {}, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("u4_undo_favorites_random")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1000dp-xhdpi")
    fun u5_statistics() {
        val today = java.time.LocalDate.of(2026, 9, 28)
        val images =
            (0 until 900).map { i ->
                val day = today.minusDays((i * 7L) % 119 + (i % 3))
                IndexedImage("/out/$i.png", "$i.png", "$day 10:00:00", listOf("animagineXL31", "ponyDiffusionV6XL", "flux1-dev")[i % 5 % 3], if (i % 4 == 0) "detail_tweaker_xl" else if (i % 9 == 0) "flat_color" else "", 1_600_000L)
            }
        val stats = GalleryStatistics.compute(images, today, mapOf("1girl" to 812, "solo" to 730, "smile" to 401, "long hair" to 388))
        screen {
            androidx.compose.material3.Scaffold(topBar = { FloatingTopBar(title = "Statistics", subtitle = "From the gallery index on this phone", onNavigate = {}) }) { padding ->
                Box(Modifier.padding(padding)) { StatsContent(stats) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("u5_statistics")
    }

    // ---------------------------------------------------------------- 3.6.0 statistics

    private fun sampleGalleryStats(): GalleryStats {
        val today = java.time.LocalDate.of(2026, 10, 1)
        val models = listOf("animagineXL31", "ponyDiffusionV6XL", "flux1-dev")
        val images =
            (0 until 900).map { i ->
                val day = today.minusDays((i * 7L) % 300 + (i % 3))
                IndexedImage("/out/$i.png", "$i.png", "$day 10:00:00", models[i % 5 % 3], if (i % 4 == 0) "detail_tweaker_xl" else if (i % 9 == 0) "flat_color" else "", 1_600_000L)
            }
        val favorites = images.filterIndexed { i, img -> (img.model == "flux1-dev" && i % 5 == 0) || i % 11 == 0 }.map { it.fullpath }.toSet()
        val insights = GalleryInsights(favorites)
        images.forEachIndexed { i, img ->
            val flux = img.model == "flux1-dev"
            insights.add(
                GalleryStatsRow(
                    img.fullpath, img.date, img.model, if (flux) "Euler" else if (i % 2 == 0) "Euler a" else "DPM++ 2M",
                    img.loras, img.size, if (i % 7 == 0) 1024 else 832, if (i % 7 == 0) 1024 else 1216,
                    if (i % 6 == 0) 20 else if (i % 9 == 0) 35 else 28, if (flux) 1f else if (i % 4 == 0) 6f else 5f,
                    if (flux) 3.5f else null, if (flux) "Simple" else if (i % 2 == 0) "Automatic" else "Karras",
                    if (i % 8 < 3) 1.5f else null, if (i % 8 < 3) "4x-UltraSharp" else null, if (i % 8 < 3) 15 else null,
                    if (i % 8 < 3) 0.35f else null, if (flux) "ae, clip_l, t5xxl_fp8" else "sdxl_vae",
                    if (i % 2 == 0) "easynegative" else null, 1,
                ),
            )
            insights.addPrompts(
                img.fullpath,
                if (img.fullpath in favorites) "1girl, solo, smile, looking at viewer" else if (i % 3 == 0) "1girl, simple background" else "1girl, solo, long hair",
                "worst quality, lowres" + if (i % 2 == 0) ", bad hands" else "",
            )
        }
        return GalleryStatistics.compute(images, today, insights.tagCounts()).copy(details = insights.details(), liked = insights.liked())
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h2900dp-xhdpi")
    fun s1_statistics_gallery() {
        val stats = sampleGalleryStats()
        screen {
            androidx.compose.material3.Scaffold(topBar = { FloatingTopBar(title = "Statistics", subtitle = "From the gallery index on this phone", onNavigate = {}) }) { padding ->
                Box(Modifier.padding(padding)) { StatsContent(stats, backlog = 372) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s1_statistics_gallery")
    }

    private fun sampleRuns(): List<JobRunEntity> {
        val a = "animagineXL31.safetensors [abc]"
        val p = "ponyDiffusionV6XL.safetensors [def]"
        val f = "flux1-dev.safetensors [ghi]"
        val t0 = 1_790_870_000_000L
        val list = ArrayList<JobRunEntity>()
        fun add(i: Int, model: String, kind: JobStart, first: Long, prev: String? = null, load: Long? = null, vram: Long? = null, failure: JobFailure? = null, hash: Boolean = false, hires: Boolean = false) {
            val sampling = if (model == f) 30_000L else 10_000L
            list +=
                JobRunEntity(
                    "r$i", "http://pc:7860", null, t0 + i * 120_000L, model, "", prev, kind.name, hash, 832, 1216, 4, 28,
                    "Euler a", "Karras", if (hires) 1.5f else null, null, load, vram, first, if (failure == null) sampling else null,
                    if (hires) 9_000L else null, if (failure == null) 1_100L else null, first + (if (failure == null) sampling + 1_100 + (if (hires) 9_000 else 0) else 0),
                    if (model == f) 1.6f else 7.4f, if (hires) 2.9f else null, 7.6f, 9.6f, 12f,
                    if (load != null) "0:7.8,${load}:0.9,${load + 1000}:4.3,${first + 100}:7.6,${first + 5000}:9.1" else null,
                    if (failure == null) "DONE" else "FAILED", failure?.name, failure?.let { "CUDA out of memory. Tried to allocate 2.50 GiB" },
                )
        }
        add(0, a, JobStart.COLD, 24_600, load = 19_800, vram = 4_100)
        repeat(40) { add(1 + it, a, JobStart.SAME, 700 + (it % 3) * 100L, hires = it % 3 == 0) }
        add(41, p, JobStart.SWAP, 11_200, prev = a, load = 8_100, vram = 3_100)
        repeat(12) { add(42 + it, p, JobStart.SAME, 800) }
        add(54, a, JobStart.SWAP, 10_900, prev = p, load = 7_900, vram = 3_000)
        add(55, f, JobStart.SWAP, 78_000, prev = a, load = 66_000, vram = 12_000, hash = true)
        repeat(6) { add(56 + it, f, JobStart.SAME, 1_300) }
        add(62, a, JobStart.SWAP, 11_100, prev = f, load = 8_000, vram = 3_100)
        add(63, f, JobStart.SWAP, 26_000, prev = a, load = 20_000, vram = 6_000)
        add(64, f, JobStart.SAME, 1_200, failure = JobFailure.OUT_OF_VRAM)
        add(65, a, JobStart.SWAP, 12_400, prev = f, load = 8_600, vram = 3_200, hires = true)
        return list
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h2500dp-xhdpi")
    fun s2_statistics_generation() {
        val stats = GenerationStatistics.compute(sampleRuns())
        screen {
            androidx.compose.material3.Scaffold(topBar = { FloatingTopBar(title = "Statistics", subtitle = "Measured by this app on every job", onNavigate = {}) }) { padding ->
                Box(Modifier.padding(padding)) { GenerationContent(stats) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s2_statistics_generation")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
    fun s3_job_details() {
        val run = sampleRuns().last()
        screen {
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.padding(top = 16.dp)) { JobDetails(run) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s3_job_details")
    }

    // ---------------------------------------------------------------- 3.6.0 queue with model changes

    private fun modelJob(n: Int, prompt: String, model: String, w: Int, h: Int, status: GenerationStatus = GenerationStatus.QUEUED) =
        job(n, prompt, status).let { it.copy(payload = it.payload.copy(width = w, height = h, override_settings = it.payload.override_settings.copy(sdModelCheckpoint = model))) }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun s4_queue_group() {
        val vm = settledVm()
        setField(ForgeQueueManager, "_generationQueue", listOf(
            modelJob(1, "masterpiece, 1girl, long hair, cherry blossoms", "animagineXL31", 832, 1216, GenerationStatus.GENERATING),
            modelJob(2, "a cat on a windowsill, rain outside", "flux1-dev", 1024, 1024),
            modelJob(3, "portrait of an old sailor, dramatic light", "animagineXL31", 832, 1216),
            modelJob(4, "city at night, neon, reflections", "flux1-dev", 1216, 832),
            modelJob(5, "knight in a forest, morning fog", "ponyDiffusionV6XL", 832, 1216),
        ))
        setField(ForgeQueueManager, "speedRates", mapOf(QueueEstimate.ANY_MODEL to 0.55))
        fun t(kind: JobStart, model: String, ms: Long, from: String? = null) = JobStartTime(model, from, kind.name, false, ms)
        setField(ForgeQueueManager, "changeCosts", ModelChangeCosts.of(listOf(
            t(JobStart.SAME, "animagineXL31", 700), t(JobStart.SAME, "flux1-dev", 900),
            t(JobStart.SWAP, "flux1-dev", 26_800, "animagineXL31"), t(JobStart.SWAP, "animagineXL31", 11_700, "flux1-dev"),
            t(JobStart.SWAP, "ponyDiffusionV6XL", 12_800, "flux1-dev"),
        )))
        val server = GalleryKey.serverOf(ForgeRepository.config.value.apiUrl)
        setField(JobRecorder, "_loadedState", server to LoadedModel(LoadedModel.KNOWN, "animagineXL31", ""))
        Thread.sleep(500)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        screen { QueueScreen(vm, rememberNavController()) }
        compose.mainClock.advanceTimeBy(1_000)
        save("s4_queue_group")
    }

    // ---------------------------------------------------------------- 3.6.0 widgets

    @Test @Config(sdk = [35], qualifiers = "w400dp-h760dp-xhdpi")
    fun s8_widgets() {
        val now = System.currentTimeMillis()
        val running = WidgetState(
            connected = true, waiting = 4, done = 1, total = 5, percent = 42, endsAt = now + 38 * 60_000L,
            imagesToday = 48, gpuTodayMs = 14 * 60_000L, vramUsedGb = 7.6, vramTotalGb = 12.0,
        )
        val idle = WidgetState(connected = true, lastDoneAt = now - 30 * 60_000L, imagesToday = 48, gpuTodayMs = 14 * 60_000L, vramUsedGb = 7.6, vramTotalGb = 12.0)
        val paused = running.copy(pausedByUser = true, paused = true, percent = null)
        @androidx.compose.runtime.Composable
        fun widget(views: (android.content.Context) -> android.widget.RemoteViews, w: Int, h: Int) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> android.widget.FrameLayout(ctx).apply { addView(views(ctx).apply(ctx, this)) } },
                modifier = Modifier.padding(bottom = 16.dp).width(w.dp).height(h.dp),
            )
        }
        screen {
            androidx.compose.foundation.layout.Column(Modifier.background(androidx.compose.ui.graphics.Color(0xFF101418)).fillMaxSize().padding(16.dp)) {
                widget({ WidgetViews.forgeGenViews(it, running) }, 360, 200)
                widget({ WidgetViews.forgeGenViews(it, paused) }, 360, 200)
                androidx.compose.foundation.layout.Row {
                    widget({ WidgetViews.queueViews(it, running) }, 172, 84)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                    widget({ WidgetViews.queueViews(it, idle) }, 172, 96)
                }
                widget({ WidgetViews.forgeGenViews(it, idle.copy(connected = false)) }, 360, 200)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s8_widgets")
    }

    // ---------------------------------------------------------------- 3.6.0 server check

    @Test @Config(sdk = [35], qualifiers = "w400dp-h900dp-xhdpi")
    fun s7_memory_unload_after() {
        screen {
            com.example.forgegen.ui.components.ServerMemorySheet(
                ServerMemory(12.3, 31.9, 7.6, 12.0), "animagineXL31", unloading = false, busy = false, onUnload = {}, onDismiss = {},
                canRestart = true, restarting = false, onRestart = {}, unloadAfter = 10, onUnloadAfter = {}, coldStart = "about 24 s",
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("s7_memory_unload_after")
    }

    private val sampleReport =
        """{"Platform": "Windows-10-10.0.22631-SP0", "Python": "3.11.9", "Version": "neo-2.1",
           "Commandline": ["launch.py", "--api", "--listen", "--api-server-stop", "--cuda-malloc", "--xformers"],
           "Torch env info": {"torch_version": "2.3.1+cu121", "os": "Microsoft Windows 11 Pro", "nvidia_gpu_models": "GPU 0: NVIDIA GeForce RTX 4070"},
           "Exceptions": [
             {"exception": "CUDA out of memory. Tried to allocate 2.50 GiB", "traceback": [["C:\\forge\\modules\\sd_samplers_kdiffusion.py, line 228, sample", "x"]]},
             {"exception": "Sizes of tensors must match except in dimension 1", "traceback": [["C:\\forge\\modules\\processing.py, line 1012, p", "x"]]}],
           "CPU": {"model": "AMD64 Family 25 Model 33", "count logical": 16, "count physical": 8},
           "RAM": {"total": "32GB", "used": "23GB"},
           "Inactive extensions": [{"name": "sd-webui-regional-prompter"}, {"name": "multidiffusion-upscaler"}],
           "Startup": {"total": 41.2},
           "Packages": ["gradio==4.40.0", "safetensors==0.4.3", "torch==2.3.1+cu121", "xformers==0.0.27"]}"""

    private fun seedServerCheck(checked: Boolean = true) {
        val memory =
            com.google.gson.Gson().fromJson(
                """{"cuda":{"system":{"used":8160437862,"total":12884901888},"reserved":{"current":1,"peak":12025908428},"events":{"retries":3,"oom":0}}}""",
                MemoryResponseDto::class.java,
            )
        val server = GalleryKey.serverOf(ForgeRepository.config.value.apiUrl)
        ForgeRepository::class.java.getDeclaredField("checkedServer").apply { isAccessible = true }.set(ForgeRepository, server)
        val check = if (checked) ServerInfoParser.check(sampleReport, memory, System.currentTimeMillis() - 2 * 3_600_000L) else null
        setField(ForgeRepository, "_serverCheck", check)
    }

    // ---------------------------------------------------------------- 3.3.0 server

    private fun settledVm(): ForgeViewModel {
        val vm = vm()
        Thread.sleep(2_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        return vm
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun v1_queue_server_first() {
        val vm = settledVm()
        seedQueue(paused = false)
        setField(ForgeQueueManager, "_serverJobsAhead", 2)
        screen { QueueScreen(vm, rememberNavController()) }
        save("v1_queue_server_first")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun v2_queue_skip() {
        val vm = settledVm()
        seedQueue(paused = false)
        setField(ForgeQueueManager, "_progress", 0.58f)
        screen { QueueScreen(vm, rememberNavController()) }
        save("v2_queue_skip")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3000dp-xhdpi")
    fun v3_server_page() {
        val vm = settledVm()
        // No server here: the ping loop would say "Connecting..." and forget what the page shows.
        (ForgeRepository::class.java.getDeclaredField("pingJob").apply { isAccessible = true }.get(ForgeRepository) as? kotlinx.coroutines.Job)?.cancel()
        Thread.sleep(500)
        setField(ForgeRepository, "_connection", ServerConnection.CONNECTED)
        setField(ForgeRepository, "_pingMs", 35L)
        setField(ForgeRepository, "_serverMemory", ServerMemory(12.3, 31.9, 7.6, 12.0))
        setField(
            ForgeRepository,
            "_serverInfo",
            ServerInfo(
                extensions =
                    ServerInfoParser.extensions(
                        listOf(
                            ServerExtensionDto("sd-webui-infinite-image-browsing", version = "1.9.0"),
                            ServerExtensionDto("a1111-sd-webui-tagcomplete", version = "3.2"),
                            ServerExtensionDto("adetailer", version = "v24.11"),
                            ServerExtensionDto("sd-webui-controlnet", version = "1.1.455"),
                            ServerExtensionDto("sd-dynamic-prompts", version = "v2.17", enabled = false),
                        ),
                    ),
                canRestart = true,
            ),
        )
        seedServerCheck()
        screen { SetupScreen(vm, onDismiss = {}) }
        compose.onAllNodes(androidx.compose.ui.test.hasText("Connected", substring = true) and androidx.compose.ui.test.hasClickAction())[0].performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        Thread.sleep(1_000)
        // Set again: the app's start (config, API client) may have cleared it meanwhile.
        setField(ForgeRepository, "_connection", ServerConnection.CONNECTED)
        setField(ForgeRepository, "_pingMs", 35L)
        setField(ForgeRepository, "_serverMemory", ServerMemory(12.3, 31.9, 7.6, 12.0))
        setField(
            ForgeRepository,
            "_serverInfo",
            ServerInfo(
                extensions =
                    ServerInfoParser.extensions(
                        listOf(
                            ServerExtensionDto("sd-webui-infinite-image-browsing", version = "1.9.0"),
                            ServerExtensionDto("a1111-sd-webui-tagcomplete", version = "3.2"),
                            ServerExtensionDto("adetailer", version = "v24.11"),
                            ServerExtensionDto("sd-webui-controlnet", version = "1.1.455"),
                            ServerExtensionDto("sd-dynamic-prompts", version = "v2.17", enabled = false),
                        ),
                    ),
                canRestart = true,
            ),
        )
        seedServerCheck()
                compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        save("v3_server_page")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun v4_restarting_and_memory() {
        val normal = ServerMemory(12.3, 31.9, 7.6, 8.0)
        screen {
            androidx.compose.foundation.layout.Column {
                MainTopBar(ServerConnection.SEARCHING, 0, System.currentTimeMillis() + 150_000, null, {}, {}, {}, {}, restartingSince = System.currentTimeMillis() - 24_000)
                ServerMemorySheet(normal, "animagineXL31", unloading = false, busy = false, onUnload = {}, onDismiss = {}, canRestart = true, restarting = false, onRestart = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("v4_restarting_and_memory")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun v5_restart_dialog() {
        val vm = settledVm()
        screen { RestartForgeDialog(vm, generating = true, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(1_000)
        save("v5_restart_dialog")
    }

    // 3.4.0: Settings > Features, the image cache in Backup & Data, the top bar without the memory meters.
    private fun settingsPage(
        name: String,
        file: String,
        before: () -> Unit = {},
        withVm: (ForgeViewModel) -> Unit = {},
        config: AppConfig.() -> AppConfig = { this },
    ) {
        val vm = settledVm()
        before()
        vmRef = vm
        withVm(vm)
        ForgeSettingsManager.saveConfig(vm.config.value.config())
        // The settings may still be read from the database after the start: save again once that is over.
        Thread.sleep(1_500)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        ForgeSettingsManager.saveConfig(vm.config.value.config())
        println("[shot] $file galleryKeys=${ForgeSettingsManager.config.value.galleryKeys} saved=${GalleryKey.savedFor(ForgeSettingsManager.config.value)}")
        screen { SetupScreen(vm, onDismiss = {}) }
        compose.onAllNodes(androidx.compose.ui.test.hasText(name) and androidx.compose.ui.test.hasClickAction())[0].performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        save(file)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1700dp-xhdpi")
    fun f1_features_page() = settingsPage("Features", "f1_features_page") { copy(livePreview = false, folderCovers = false) }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun f2_backup_storage() = settingsPage("Backup & Data", "f2_backup_storage")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun f3_settings_home() {
        val vm = settledVm()
        screen { SetupScreen(vm, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(1_000)
        save("f3_settings_home")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1100dp-xhdpi")
    fun l1_updates_page() = settingsPage("Updates", "l1_updates_page")

    // --- Update card: the download sliding out below the changelog, its own section (visualization 2026-10-01) ---

    private val notes =
        parseReleaseNotes(
            """
            **Bugfix** · loading another checkpoint no longer looks like a lost connection

            ### Changed
            - While Forge loads the job's checkpoint, the app says "Loading model…" instead of "Connection lost".

            ### Fixed
            - A slow answer during a model load no longer turns the top bar to "Connecting…".
            - The queue shows its real state again as soon as the model is loaded.
            """.trimIndent(),
        )

    private val manifest353 = UpdateManifest(300500300, "3.5.3", "https://x/ForgeGen.apk", null, 6_543_887L, "2026-10-01", notes)

    private fun offerUpdate(vm: ForgeViewModel) {
        @Suppress("UNCHECKED_CAST")
        (ForgeUpdateManager::class.java.getDeclaredField("_updateManifest").apply { isAccessible = true }.get(vm.updateManager) as MutableStateFlow<UpdateManifest?>).value = manifest353
    }

    private fun setReady(ready: SelfUpdate.ReadyUpdate?) {
        @Suppress("UNCHECKED_CAST")
        (SelfUpdate::class.java.getDeclaredField("_readyUpdate").apply { isAccessible = true }.get(SelfUpdate) as MutableStateFlow<SelfUpdate.ReadyUpdate?>).value = ready
    }

    /** The Updates page with 3.5.3 on offer; [frames]: (file, what to do before it, ms to let pass). */
    private fun updatesFrames(frames: List<Triple<String, () -> Unit, Long>>) {
        GitHubApi.baseUrl = "http://127.0.0.1:9/" // the app's own update check must not replace 3.5.3
        SelfUpdate.setDownloadProgress(null)
        setReady(null)
        settingsPage("Updates", "u_tmp", withVm = { offerUpdate(it) })
        compose.mainClock.autoAdvance = false
        frames.forEach { (file, action, ms) ->
            compose.runOnUiThread { action() }
            compose.mainClock.advanceTimeBy(ms)
            save(file)
        }
        SelfUpdate.setDownloadProgress(null)
        setReady(null)
    }

    private var vmRef: ForgeViewModel? = null

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
    fun u1_after() {
        GitHubApi.baseUrl = "http://127.0.0.1:9/"
        SelfUpdate.setDownloadProgress(null)
        setReady(null)
        settingsPage("Updates", "u1_after_offer", withVm = { offerUpdate(it) })
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
    fun u2_after_animation() {
        val total = 6_543_887L
        fun progress(f: Double) = { SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress("3.5.3", (total * f).toLong(), total)) }
        val frames = mutableListOf<Triple<String, () -> Unit, Long>>()
        var n = 0
        fun frame(action: () -> Unit, ms: Long) { frames += Triple("u2_f%02d".format(n++), action, ms) }
        frame({}, 100) // the offer
        frame(progress(0.0), 16) // "Download" tapped
        repeat(9) { frame({}, 40) } // the progress slides out
        frame(progress(0.25), 100); frame({}, 100); frame({}, 120)
        frame(progress(0.58), 100); frame({}, 100); frame({}, 120)
        frame(progress(1.0), 100); frame({}, 100); frame({}, 120)
        frame({ SelfUpdate.setDownloadProgress(null); setReady(SelfUpdate.ReadyUpdate(300500300, "3.5.3", total)) }, 16) // checked
        repeat(9) { frame({}, 40) } // the progress folds back, "Install" comes out
        frame({}, 300)
        updatesFrames(frames)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
    fun u0_before() {
        GitHubApi.baseUrl = "http://127.0.0.1:9/"
        SelfUpdate.setDownloadProgress(null)
        setReady(null)
        settingsPage("Updates", "u0_before_offer", withVm = { offerUpdate(it) })
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
    fun u0_before_download() =
        updatesFrames(
            listOf(
                Triple("u0_before_f0_offer", {}, 100L),
                Triple("u0_before_f1_tap", { SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress("3.5.3", 0, 0)) }, 16L),
                Triple("u0_before_f2_42", { SelfUpdate.setDownloadProgress(SelfUpdate.DownloadProgress("3.5.3", 2_748_432, 6_543_887)) }, 300L),
            ),
        )

    private fun licenseDialog(
        file: String,
        full: Boolean,
    ) {
        settingsPage("Updates", "l0_tmp")
        compose.onAllNodes(androidx.compose.ui.test.hasText("License") and androidx.compose.ui.test.hasClickAction())[0].performClick()
        compose.waitForIdle()
        if (full) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Full License") and androidx.compose.ui.test.hasClickAction())[0].performClick()
            compose.waitForIdle()
            Thread.sleep(1_000)
            compose.waitForIdle()
        }
        Thread.sleep(500)
        compose.waitForIdle()
        save(file)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun l2_license_notice() = licenseDialog("l2_license_notice", full = false)

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun l3_license_full() = licenseDialog("l3_license_full", full = true)

    private fun lockedGallery(
        file: String,
        status: ForgeGalleryManager.ExtensionStatus,
    ) {
        val vm = galleryVm()
        managerFlow<ForgeGalleryManager.ExtensionStatus>("_extension").value = status
        vm.selectGalleryTab(GalleryTab.GALLERY)
        vm.openGallery(GalleryMode.NORMAL)
        screen { GalleryScreen(vm, rememberNavController()) }
        Thread.sleep(800)
        compose.mainClock.advanceTimeBy(1_000)
        save(file)
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun k1_gallery_locked() = lockedGallery("k1_gallery_locked", ForgeGalleryManager.ExtensionStatus(ForgeGalleryManager.Extension.LOCKED))

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun k2_gallery_key_refused() =
        lockedGallery(
            "k2_gallery_key_refused",
            ForgeGalleryManager.ExtensionStatus(ForgeGalleryManager.Extension.LOCKED, "The server did not accept this key."),
        )

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun k3_gallery_key_not_set() =
        lockedGallery("k3_gallery_key_not_set", ForgeGalleryManager.ExtensionStatus(ForgeGalleryManager.Extension.KEY_NOT_SET))

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1500dp-xhdpi")
    fun k4_server_page_gallery_key() =
        settingsPage("192.168.1.90:7860", "k4_server_page_gallery_key") { copy(galleryKeys = mapOf(GalleryKey.serverOf(apiUrl) to GalleryKey.fingerprint("secret"))) }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h1500dp-xhdpi")
    fun l4_notifications_live_update() =
        settingsPage("Notifications", "l4_notifications_live_update", before = {
            // After the view model's start, which reads the debug switches (DebugMode.init).
            @Suppress("UNCHECKED_CAST")
            (DebugMode::class.java.getDeclaredField("_forceLiveUpdates").apply { isAccessible = true }.get(DebugMode) as MutableStateFlow<Boolean>).value = true
            org.robolectric.RuntimeEnvironment
                .getApplication()
                .getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
                .edit()
                .putInt("live_update_promoted", 1)
                .commit()
        }) { copy(nowBarProgress = true) }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h880dp-xhdpi")
    fun f4_top_bar_without_meters() {
        val normal = ServerMemory(12.3, 31.9, 7.6, 8.0)
        screen {
            androidx.compose.foundation.layout.Column {
                MainTopBar(ServerConnection.CONNECTED, 35, 0, normal, {}, {}, {}, {})
                MainTopBar(ServerConnection.CONNECTED, 35, 0, normal, {}, {}, {}, {}, showMeters = false)
                MainTopBar(ServerConnection.CONNECTED, 35, 0, null, {}, {}, {}, {}, showMeters = false)
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        save("f4_top_bar_without_meters")
    }

    // 3.6.1: every settings page on a tall screen, before and after SetupScreen was split into files per page.
    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z0_split_home() {
        val vm = settledVm()
        screen { SetupScreen(vm, onDismiss = {}) }
        compose.mainClock.advanceTimeBy(1_000)
        save("z0_split_home")
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z1_split_server() = settingsPage("192.168.1.90:7860", "z1_split_server")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z2_split_features() = settingsPage("Features", "z2_split_features")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z3_split_appearance() = settingsPage("Appearance", "z3_split_appearance")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z4_split_notifications() = settingsPage("Notifications", "z4_split_notifications")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z5_split_queue() = settingsPage("Queue & Background", "z5_split_queue")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z6_split_privacy() = settingsPage("Privacy & Security", "z6_split_privacy")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z7_split_updates() = settingsPage("Updates", "z7_split_updates")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z8_split_data() = settingsPage("Backup & Data", "z8_split_data")

    @Test @Config(sdk = [35], qualifiers = "w400dp-h3200dp-xhdpi")
    fun z9_split_updates_offer() = settingsPage("Updates", "z9_split_updates_offer", withVm = { offerUpdate(it) })
}

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() = collectAsState()
