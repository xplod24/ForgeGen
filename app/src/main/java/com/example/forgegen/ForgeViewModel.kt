package com.yourname.forgegen

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ActiveLora(val name: String, val strength: Float)

class ForgeViewModel(private val application: Application) : AndroidViewModel(application) {

    private val TAG = "ForgeAPI"

    private val prefs = application.getSharedPreferences("ForgeGenPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private var client = createClient(10)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    private val _appState = MutableStateFlow(loadState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    val activeLoras: StateFlow<List<ActiveLora>> = _appState.map { state ->
        val regex = Regex("<lora:([^:]+):([0-9.]+)>")
        regex.findAll(state.positivePrompt).mapNotNull { match ->
            val n = match.groupValues[1]
            val s = match.groupValues[2].toFloatOrNull() ?: 1f
            ActiveLora(n, s)
        }.toList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _lastPromptState = MutableStateFlow(AppState())

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _pingMs = MutableStateFlow(0L)
    val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

    val progress: StateFlow<Float> = ForgeState.progress.asStateFlow()
    val statusText: StateFlow<String> = ForgeState.statusText.asStateFlow()
    val isGenerating: StateFlow<Boolean> = ForgeState.isGenerating.asStateFlow()
    val sessionImages: StateFlow<List<String>> = ForgeState.sessionImages.asStateFlow()
    val currentSessionIndex: StateFlow<Int> = ForgeState.currentSessionIndex.asStateFlow()

    private val _useMultiThreading = MutableStateFlow(prefs.getBoolean("multi_threading", true))
    val useMultiThreading: StateFlow<Boolean> = _useMultiThreading.asStateFlow()
    private var workerDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _allTags = mutableListOf<String>()
    private val _tagSuggestions = MutableStateFlow<List<String>>(emptyList())
    val tagSuggestions: StateFlow<List<String>> = _tagSuggestions.asStateFlow()

    private val _isRestoringPrompt = MutableStateFlow(false)
    val isRestoringPrompt: StateFlow<Boolean> = _isRestoringPrompt.asStateFlow()

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    val samplers = MutableStateFlow<List<String>>(emptyList())
    val schedulers = MutableStateFlow<List<String>>(emptyList())
    val models = MutableStateFlow<List<String>>(emptyList())
    val upscalers = MutableStateFlow<List<String>>(emptyList())
    val availableLoras = MutableStateFlow<List<String>>(emptyList())

    private val _galleryFiles = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryFiles: StateFlow<List<GalleryItem>> = _galleryFiles.asStateFlow()

    private val _currentGalleryPath = MutableStateFlow("")
    val currentGalleryPath: StateFlow<String> = _currentGalleryPath.asStateFlow()

    private val _isGalleryLoading = MutableStateFlow(false)
    val isGalleryLoading: StateFlow<Boolean> = _isGalleryLoading.asStateFlow()

    private val _galleryError = MutableStateFlow<String?>(null)
    val galleryError: StateFlow<String?> = _galleryError.asStateFlow()

    private val _showGalleryMetadata = MutableStateFlow(prefs.getBoolean("show_gallery_meta", false))
    val showGalleryMetadata: StateFlow<Boolean> = _showGalleryMetadata.asStateFlow()

    private val _currentImageMetadata = MutableStateFlow<String?>(null)
    val currentImageMetadata: StateFlow<String?> = _currentImageMetadata.asStateFlow()

    // Lua Plugins State
    private val _plugins = MutableStateFlow<List<String>>(emptyList())
    val plugins: StateFlow<List<String>> = _plugins.asStateFlow()

    private var activeLuaGlobals: org.luaj.vm2.Globals? = null
    private var activeLuaChunk: org.luaj.vm2.LuaValue? = null

    private val _loadedPluginName = MutableStateFlow<String?>(null)
    val loadedPluginName: StateFlow<String?> = _loadedPluginName.asStateFlow()

    // Logcat State
    private val _appLogs = MutableStateFlow<List<String>>(emptyList())
    val appLogs: StateFlow<List<String>> = _appLogs.asStateFlow()

    init {
        updateDispatcher()
        ForgeState.logServer("Initializing ForgeGen App...")
        startBackgroundPing()
        startLogcatReader()
        fetchApiData()
        loadTags()
        startQueueManager()
        refreshPlugins()
    }

    private fun startLogcatReader() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pid = android.os.Process.myPid()
                val process = Runtime.getRuntime().exec("logcat -v time --pid=$pid")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                var line: String?
                val logBuffer = java.util.LinkedList<String>()

                while (isActive) {
                    line = reader.readLine()
                    if (line != null) {
                        logBuffer.add(line)
                        if (logBuffer.size > 200) {
                            logBuffer.removeFirst()
                        }
                        // Update UI seamlessly
                        _appLogs.value = ArrayList(logBuffer)
                    } else {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Logcat Error", e)
                _appLogs.value = listOf("Failed to read logcat: ${e.message}")
            }
        }
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    private fun loadConfig(): AppConfig {
        val json = prefs.getString("config", null)
        val parsed = if (json != null) gson.fromJson(json, AppConfig::class.java) else null
        return AppConfig(
            apiUrl = parsed?.apiUrl ?: "http://192.168.1.90:7860",
            galleryPath = parsed?.galleryPath ?: "C:\\webui_forge_cu124_torch24\\webui\\outputs\\txt2img-images",
            isDarkMode = parsed?.isDarkMode ?: false,
            connectionTimeout = parsed?.connectionTimeout ?: 10,
            updateServerUrl = parsed?.updateServerUrl ?: "http://localhost/update",
            silentNotifications = parsed?.silentNotifications ?: false
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        val updatedConfig = newConfig.copy(apiUrl = cleanUrl)
        _config.value = updatedConfig
        prefs.edit().putString("config", gson.toJson(updatedConfig)).apply()
        client = createClient(updatedConfig.connectionTimeout)
        ForgeState.logServer("Configuration saved. API URL: $cleanUrl")
        fetchApiData()
    }

    private fun loadState(): AppState {
        val json = prefs.getString("last_state", null)
        val parsed = if (json != null) gson.fromJson(json, AppState::class.java) else null
        return AppState(
            positivePrompt = parsed?.positivePrompt ?: "",
            negativePrompt = parsed?.negativePrompt ?: "",
            cfgScale = parsed?.cfgScale ?: 7.0f,
            steps = parsed?.steps ?: 20,
            width = parsed?.width ?: 512,
            height = parsed?.height ?: 512,
            batchSize = parsed?.batchSize ?: 1,
            clipSkip = parsed?.clipSkip ?: 1,
            seed = parsed?.seed ?: -1L,
            sampler = parsed?.sampler ?: "Euler a",
            scheduler = parsed?.scheduler ?: "Automatic",
            hiresFix = parsed?.hiresFix ?: false,
            hiresScale = parsed?.hiresScale ?: 2.0f,
            denoising = parsed?.denoising ?: 0.7f,
            upscaler = parsed?.upscaler ?: "Latent"
        )
    }

    fun updateState(update: (AppState) -> AppState) {
        val newState = update(_appState.value)
        _appState.value = newState
        prefs.edit().putString("last_state", gson.toJson(newState)).apply()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun updateDispatcher() {
        workerDispatcher = if (_useMultiThreading.value) {
            Dispatchers.Default
        } else {
            Dispatchers.IO.limitedParallelism(1)
        }
    }

    fun setMultiThreading(use: Boolean) {
        _useMultiThreading.value = use
        prefs.edit().putBoolean("multi_threading", use).apply()
        updateDispatcher()
    }

    // --- QUEUE MANAGER ---
    private fun startQueueManager() {
        viewModelScope.launch(workerDispatcher) {
            while (true) {
                try {
                    val queue = ForgeState.generationQueue.value
                    val isBusy = ForgeState.isServerBusy.value
                    val isGeneratingLocally = ForgeState.isGenerating.value

                    if (queue.isNotEmpty() && !isBusy && !isGeneratingLocally) {
                        val nextJob = queue.first()
                        ForgeState.isGenerating.value = true
                        executeGeneration(nextJob)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Queue Manager Exception", e)
                }
                delay(1000)
            }
        }
    }

    fun queueGeneration() {
        val state = _appState.value
        val payload = Txt2ImgPayload(
            prompt = state.positivePrompt, negative_prompt = state.negativePrompt,
            steps = state.steps, cfg_scale = state.cfgScale, width = state.width, height = state.height,
            batch_size = state.batchSize, seed = state.seed, sampler_name = state.sampler, scheduler = state.scheduler,
            override_settings = OverrideSettings(state.clipSkip), enable_hr = state.hiresFix, hr_scale = state.hiresScale,
            hr_upscaler = state.upscaler, denoising_strength = state.denoising
        )

        val item = QueuedGeneration(
            id = java.util.UUID.randomUUID().toString(),
            positivePrompt = state.positivePrompt,
            payload = payload
        )

        ForgeState.generationQueue.update { it + item }
        ForgeState.logServer("Queued generation added: ${item.id}")

        if (ForgeState.isServerBusy.value && !ForgeState.isGenerating.value) {
            Toast.makeText(application, "External generation active. Added to queue.", Toast.LENGTH_SHORT).show()
        } else if (ForgeState.isGenerating.value) {
            Toast.makeText(application, "Added to queue.", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeFromQueue(id: String) {
        ForgeState.generationQueue.update { currentQueue ->
            currentQueue.filter { it.id != id }
        }
    }

    private suspend fun executeGeneration(job: QueuedGeneration) {
        withContext(Dispatchers.Main) {
            _lastPromptState.value = _appState.value.copy()
            ForgeState.progress.value = 0f
            // Verbose status text for the notification
            val previewText = job.positivePrompt.take(30).replace("\n", " ")
            ForgeState.statusText.value = "Generating: \"$previewText...\""

            val serviceIntent = Intent(application, GenerationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                application.startForegroundService(serviceIntent)
            } else {
                application.startService(serviceIntent)
            }
        }

        try {
            val url = _config.value.apiUrl.trimEnd('/')
            val jsonPayload = gson.toJson(job.payload)
            val body = jsonPayload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url("$url/sdapi/v1/txt2img").post(body).build()

            ForgeState.logServer("Starting generation request (Payload ID: ${job.id}) to $url...")

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val imagesArray = json.optJSONArray("images")

                    if (imagesArray != null && imagesArray.length() > 0) {
                        val currentList = ForgeState.sessionImages.value.toMutableList()
                        val startIndex = currentList.size

                        for (i in 0 until imagesArray.length()) {
                            val b64 = imagesArray.getString(i)
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val file = File(application.cacheDir, "gen_${System.currentTimeMillis()}_$i.png")
                            file.writeBytes(bytes)
                            currentList.add(file.absolutePath)
                        }

                        val endIndex = currentList.size - 1

                        ForgeState.logServer("Generation Complete. Saved ${imagesArray.length()} local image(s).")

                        withContext(Dispatchers.Main) {
                            ForgeState.sessionImages.value = currentList
                            ForgeState.currentBatchStartIndex.value = startIndex
                            ForgeState.currentBatchEndIndex.value = endIndex
                            ForgeState.currentSessionIndex.value = endIndex
                            ForgeState.statusText.value = "Generation Complete"
                        }
                    } else {
                        ForgeState.logServer("API succeeded but returned no images.")
                    }
                } else {
                    ForgeState.logServer("Generation Failed: API returned code ${response.code}.")
                    withContext(Dispatchers.Main) { ForgeState.statusText.value = "Error: ${response.code}" }
                }
            }
        } catch (e: Exception) {
            ForgeState.logServer("Generation Exception: ${e.localizedMessage}")
            withContext(Dispatchers.Main) { ForgeState.statusText.value = "Failed: ${e.localizedMessage}" }
        } finally {
            withContext(Dispatchers.Main) {
                ForgeState.isGenerating.value = false
                ForgeState.progress.value = 1f
                removeFromQueue(job.id)
            }
        }
    }

    private fun startBackgroundPing() {
        viewModelScope.launch(Dispatchers.IO) {
            var failCount = 0
            while (true) {
                try {
                    val url = _config.value.apiUrl.trimEnd('/')
                    if (url.isNotEmpty()) {
                        val start = System.currentTimeMillis()
                        val request = Request.Builder().url("$url/sdapi/v1/progress?skip_current_image=true").build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                _pingMs.value = System.currentTimeMillis() - start
                                _isConnected.value = true
                                failCount = 0

                                val body = response.body?.string()
                                if (body != null) {
                                    val json = JSONObject(body)
                                    val progressVal = json.optDouble("progress", 0.0).toFloat()
                                    val stateObj = json.optJSONObject("state")
                                    val jobCount = stateObj?.optInt("job_count", 0) ?: 0

                                    val busy = progressVal > 0.001f || jobCount > 0
                                    ForgeState.isServerBusy.value = busy

                                    ForgeState.progress.value = progressVal

                                    if (busy && !ForgeState.isGenerating.value) {
                                        ForgeState.statusText.value = "External Task: ${(progressVal * 100).toInt()}%"
                                    } else if (!busy && !ForgeState.isGenerating.value) {
                                        ForgeState.statusText.value = "Ready"
                                    }
                                }
                            } else throw Exception("Bad Status")
                        }
                    }
                } catch (e: Exception) {
                    _isConnected.value = false
                    ForgeState.isServerBusy.value = false
                    failCount++
                    if (failCount == _config.value.connectionTimeout) {
                        ForgeState.logServer("Connection lost to server.")
                    }
                    if (failCount >= _config.value.connectionTimeout && !ForgeState.isGenerating.value) {
                        ForgeState.statusText.value = "Connection Lost (Timeout)"
                    }
                }
                delay(1000)
            }
        }
    }

    // --- UTILITY METHODS (GALLERY / LORA / TAGS) ---
    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        prefs.edit().putBoolean("show_gallery_meta", newVal).apply()
    }

    fun loadMetadataForImage(item: GalleryItem?) {
        if (item == null) {
            _currentImageMetadata.value = null
            return
        }
        _currentImageMetadata.value = "Loading metadata..."
        viewModelScope.launch(workerDispatcher) {
            try {
                val urlStr = _config.value.apiUrl.trimEnd('/')
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { _currentImageMetadata.value = "Invalid URL." }
                    return@launch
                }
                val imgReq = Request.Builder().url(imageUrl).build()
                var base64Img = ""
                client.newCall(imgReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val bytes = res.body?.bytes()
                        if (bytes != null) base64Img = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
                if (base64Img.isNotEmpty()) {
                    val payload = JSONObject().apply { put("image", "data:image/png;base64,$base64Img") }
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    val infoReq = Request.Builder().url("$urlStr/sdapi/v1/png-info").post(body).build()
                    client.newCall(infoReq).execute().use { infoRes ->
                        if (infoRes.isSuccessful) {
                            val infoJson = JSONObject(infoRes.body?.string() ?: "{}")
                            val infoStr = infoJson.optString("info", "")
                            withContext(Dispatchers.Main) { _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found." }
                        } else {
                            withContext(Dispatchers.Main) { _currentImageMetadata.value = "Server Error: ${infoRes.code}" }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { _currentImageMetadata.value = "Failed to load image." }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _currentImageMetadata.value = "Failed: ${e.message}" }
            }
        }
    }

    fun loadMetadataForLocalFile(filePath: String) {
        _currentImageMetadata.value = "Loading metadata..."
        viewModelScope.launch(workerDispatcher) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) { _currentImageMetadata.value = "File not found locally." }
                    return@launch
                }
                val bytes = file.readBytes()
                val base64Img = Base64.encodeToString(bytes, Base64.NO_WRAP)

                val urlStr = _config.value.apiUrl.trimEnd('/')
                val payload = JSONObject().apply { put("image", "data:image/png;base64,$base64Img") }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val infoReq = Request.Builder().url("$urlStr/sdapi/v1/png-info").post(body).build()

                client.newCall(infoReq).execute().use { infoRes ->
                    if (infoRes.isSuccessful) {
                        val infoJson = JSONObject(infoRes.body?.string() ?: "{}")
                        val infoStr = infoJson.optString("info", "")
                        withContext(Dispatchers.Main) {
                            _currentImageMetadata.value = if (infoStr.isNotBlank()) infoStr else "No generation data found."
                        }
                    } else {
                        withContext(Dispatchers.Main) { _currentImageMetadata.value = "Server Error: ${infoRes.code}" }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { _currentImageMetadata.value = "Failed: ${e.message}" }
            }
        }
    }

    fun changeCheckpoint(modelTitle: String) {
        _selectedModel.value = modelTitle
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = _config.value.apiUrl.trimEnd('/')
                val payload = JSONObject().apply { put("sd_model_checkpoint", modelTitle) }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("$url/sdapi/v1/options").post(body).build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) ForgeState.logServer("Successfully switched Checkpoint to $modelTitle")
                    else ForgeState.logServer("Failed to switch Checkpoint (Error: ${res.code})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
                ForgeState.logServer("Exception during Checkpoint swap: ${e.message}")
            }
        }
    }

    fun appendLora(name: String) {
        val current = _appState.value.positivePrompt
        if (!current.contains("<lora:$name:")) {
            val prefix = if (current.isNotEmpty() && !current.endsWith(",")) ", " else ""
            val newPrompt = current.trimEnd() + prefix + "<lora:$name:1.0>"
            updateState { it.copy(positivePrompt = newPrompt) }
        }
    }

    fun updateLoraStrength(name: String, strength: Float) {
        val current = _appState.value.positivePrompt
        val regex = Regex("<lora:${Regex.escape(name)}:[0-9.]+>")
        val formattedStrength = String.format(Locale.US, "%.2f", strength)
        val newPrompt = current.replace(regex, "<lora:$name:$formattedStrength>")
        updateState { it.copy(positivePrompt = newPrompt) }
    }

    fun removeLora(name: String) {
        val current = _appState.value.positivePrompt
        val escapedName = Regex.escape(name)
        val regex = Regex(",?\\s*<lora:$escapedName:[0-9.]+>\\s*,?")
        var newPrompt = current.replace(regex, ", ").trim()
        if (newPrompt.startsWith(",")) newPrompt = newPrompt.substring(1).trim()
        if (newPrompt.endsWith(",")) newPrompt = newPrompt.substring(0, newPrompt.length - 1).trim()
        updateState { it.copy(positivePrompt = newPrompt) }
    }

    private fun loadTags() {
        viewModelScope.launch(workerDispatcher) {
            val file = File(application.cacheDir, "tags.csv")
            if (!file.exists()) {
                try {
                    val url = "https://raw.githubusercontent.com/DominikDoom/a1111-sd-webui-tagcomplete/main/tags/danbooru.csv"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { res ->
                        if (res.isSuccessful) file.writeText(res.body?.string() ?: "")
                    }
                } catch (e: Exception) {}
            }
            if (file.exists()) {
                _allTags.clear()
                file.useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split(",")
                        if (parts.isNotEmpty() && parts[0].isNotBlank()) _allTags.add(parts[0])
                    }
                }
            }
        }
    }

    fun searchTags(query: String) {
        if (query.length < 2) {
            _tagSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch(workerDispatcher) {
            _tagSuggestions.value = _allTags.filter { it.startsWith(query, ignoreCase = true) }.take(8)
        }
    }

    // --- LUA PLUGIN ENGINE ---

    inner class ForgeLuaApi {
        fun toast(msg: String) {
            viewModelScope.launch(Dispatchers.Main) { Toast.makeText(application, msg, Toast.LENGTH_SHORT).show() }
        }
        fun setPositivePrompt(prompt: String) { updateState { it.copy(positivePrompt = prompt) } }
        fun getPositivePrompt(): String = _appState.value.positivePrompt
        fun setNegativePrompt(prompt: String) { updateState { it.copy(negativePrompt = prompt) } }
        fun getNegativePrompt(): String = _appState.value.negativePrompt
        fun setSteps(s: Int) { updateState { it.copy(steps = s) } }
        fun queue() { viewModelScope.launch(Dispatchers.Main) { queueGeneration() } }
    }

    fun refreshPlugins() {
        val pluginsDir = File(application.filesDir, "plugins")
        if (pluginsDir.exists()) {
            _plugins.value = pluginsDir.listFiles()?.filter { it.extension == "lua" }?.map { it.name } ?: emptyList()
        }
    }

    fun importPlugin(uri: android.net.Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pluginsDir = File(context.filesDir, "plugins")
                if (!pluginsDir.exists()) pluginsDir.mkdirs()

                var fileName = "plugin_${System.currentTimeMillis()}.lua"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            val name = cursor.getString(displayNameIndex)
                            if (name != null && name.endsWith(".lua")) fileName = name
                        }
                    }
                }

                val destFile = File(pluginsDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                refreshPlugins()
                ForgeState.logServer("Successfully imported Plugin: $fileName")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Imported $fileName", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                ForgeState.logServer("Failed to import plugin: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to import plugin", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun loadPluginIntoMemory(pluginName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pluginsDir = File(application.filesDir, "plugins")
                val file = File(pluginsDir, pluginName)
                if (!file.exists()) return@launch

                val script = file.readText()
                val globals = JsePlatform.standardGlobals()
                val api = CoerceJavaToLua.coerce(ForgeLuaApi())
                globals.set("forge", api)

                val chunk = globals.load(script)

                activeLuaGlobals = globals
                activeLuaChunk = chunk
                _loadedPluginName.value = pluginName

                ForgeState.logServer("Plugin '$pluginName' loaded directly into engine memory.")
                withContext(Dispatchers.Main) { Toast.makeText(application, "Loaded $pluginName into memory", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Lua Load Error", e)
                ForgeState.logServer("Lua Syntax/Load Error inside '$pluginName': ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(application, "Lua Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun executeLoadedPlugin() {
        if (activeLuaChunk != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    activeLuaChunk?.call()
                    ForgeState.logServer("Executed Lua Plugin: ${_loadedPluginName.value}")
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Executed ${_loadedPluginName.value}", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    Log.e(TAG, "Lua Execution Error", e)
                    ForgeState.logServer("Lua Exception during Execution: ${e.message}")
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Lua Execution Error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        } else {
            Toast.makeText(application, "No script is currently loaded!", Toast.LENGTH_SHORT).show()
        }
    }

    fun unloadPlugin() {
        val pluginStr = _loadedPluginName.value ?: "unknown"
        activeLuaGlobals = null
        activeLuaChunk = null
        _loadedPluginName.value = null
        ForgeState.logServer("Plugin '$pluginStr' unloaded from memory.")
        Toast.makeText(application, "Plugin unloaded from memory", Toast.LENGTH_SHORT).show()
    }

    private fun parseGalleryItems(json: String): List<GalleryItem> {
        val list = mutableListOf<GalleryItem>()
        if (json.isEmpty()) return list
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) list.add(gson.fromJson(array.getJSONObject(i).toString(), GalleryItem::class.java))
        } catch (e: Exception) {
            try {
                val fileList = gson.fromJson(json, GalleryFileList::class.java)
                if (fileList?.files != null) list.addAll(fileList.files)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to parse gallery items", e2)
            }
        }
        return list
    }

    fun recoverLastPrompt() {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        viewModelScope.launch(workerDispatcher) {
            try {
                val urlStr = _config.value.apiUrl.trimEnd('/')
                val rootPath = _config.value.galleryPath
                var targetFile: GalleryItem? = null

                val rootUrl = urlStr.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegments("infinite_image_browsing/files")
                    ?.addQueryParameter("folder_path", rootPath)
                    ?.build() ?: throw Exception("Invalid URL")

                var rootItems = emptyList<GalleryItem>()
                client.newCall(Request.Builder().url(rootUrl).build()).execute().use { response ->
                    if (response.isSuccessful) rootItems = parseGalleryItems(response.body?.string() ?: "")
                }

                val rootImages = rootItems.filter { !it.isDir }
                if (rootImages.isNotEmpty()) {
                    targetFile = rootImages.maxWithOrNull(
                        compareBy<GalleryItem> { it.name.substringBefore("-").toLongOrNull() ?: -1L }
                            .thenBy { it.createdTime?.toDoubleOrNull() ?: 0.0 }
                    )
                }

                if (targetFile == null) {
                    val dateFolders = rootItems.filter { it.isDir }.sortedByDescending { it.name }
                    for (folder in dateFolders) {
                        val folderUrl = urlStr.toHttpUrlOrNull()?.newBuilder()
                            ?.addPathSegments("infinite_image_browsing/files")
                            ?.addQueryParameter("folder_path", folder.fullpath)
                            ?.build() ?: continue

                        var folderItems = emptyList<GalleryItem>()
                        client.newCall(Request.Builder().url(folderUrl).build()).execute().use { response ->
                            if (response.isSuccessful) folderItems = parseGalleryItems(response.body?.string() ?: "")
                        }

                        val folderImages = folderItems.filter { !it.isDir }
                        if (folderImages.isNotEmpty()) {
                            targetFile = folderImages.maxWithOrNull(
                                compareBy<GalleryItem> { it.name.substringBefore("-").toLongOrNull() ?: -1L }
                                    .thenBy { it.createdTime?.toDoubleOrNull() ?: 0.0 }
                            )
                            break
                        }
                    }
                }

                if (targetFile != null) {
                    val imageUrl = getGalleryImageUrl(targetFile!!)
                    if (imageUrl.isNotEmpty()) {
                        val imgReq = Request.Builder().url(imageUrl).build()
                        var base64Img = ""
                        client.newCall(imgReq).execute().use { res ->
                            if (res.isSuccessful) {
                                val bytes = res.body?.bytes()
                                if (bytes != null) base64Img = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }
                        }
                        if (base64Img.isNotEmpty()) {
                            val payload = JSONObject().apply { put("image", "data:image/png;base64,$base64Img") }
                            val body = payload.toString().toRequestBody("application/json".toMediaType())
                            val infoReq = Request.Builder().url("$urlStr/sdapi/v1/png-info").post(body).build()
                            client.newCall(infoReq).execute().use { infoRes ->
                                if (infoRes.isSuccessful) {
                                    val infoJson = JSONObject(infoRes.body?.string() ?: "{}")
                                    val infoStr = infoJson.optString("info", "")
                                    withContext(Dispatchers.Main) {
                                        parseAndApplyPngInfo(infoStr)
                                        _isRestoringPrompt.value = false
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    updateState { _lastPromptState.value.copy() }
                    Toast.makeText(application, "Used local cache (No images found in gallery)", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateState { _lastPromptState.value.copy() }
                    Toast.makeText(application, "Used local cache (Network error)", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            }
        }
    }

    fun recoverPromptFromImage(item: GalleryItem) {
        if (_isRestoringPrompt.value) return
        _isRestoringPrompt.value = true

        viewModelScope.launch(workerDispatcher) {
            try {
                val urlStr = _config.value.apiUrl.trimEnd('/')
                val imageUrl = getGalleryImageUrl(item)
                if (imageUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { Toast.makeText(application, "Invalid Image URL", Toast.LENGTH_SHORT).show() }
                    _isRestoringPrompt.value = false
                    return@launch
                }

                val imgReq = Request.Builder().url(imageUrl).build()
                var base64Img = ""

                client.newCall(imgReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val bytes = res.body?.bytes()
                        if (bytes != null) base64Img = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }

                if (base64Img.isNotEmpty()) {
                    val payload = JSONObject().apply { put("image", "data:image/png;base64,$base64Img") }
                    val body = payload.toString().toRequestBody("application/json".toMediaType())
                    val infoReq = Request.Builder().url("$urlStr/sdapi/v1/png-info").post(body).build()

                    client.newCall(infoReq).execute().use { infoRes ->
                        if (infoRes.isSuccessful) {
                            val infoJson = JSONObject(infoRes.body?.string() ?: "{}")
                            val infoStr = infoJson.optString("info", "")

                            withContext(Dispatchers.Main) {
                                parseAndApplyPngInfo(infoStr)
                                _isRestoringPrompt.value = false
                            }
                            return@launch
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Failed to extract data", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(application, "Network error", Toast.LENGTH_SHORT).show()
                    _isRestoringPrompt.value = false
                }
            }
        }
    }

    private fun parseAndApplyPngInfo(info: String) {
        if (info.isEmpty()) return
        var pos = ""
        var neg = ""
        var params = ""

        val lines = info.split("\n")
        var currentMode = 0

        for (line in lines) {
            if (line.startsWith("Negative prompt:")) {
                currentMode = 1
                neg += line.substringAfter("Negative prompt:").trim() + "\n"
            } else if (line.startsWith("Steps:")) {
                currentMode = 2
                params = line
            } else {
                if (currentMode == 0) pos += line + "\n"
                else if (currentMode == 1) neg += line + "\n"
            }
        }

        updateState { state ->
            val newState = state.copy(
                positivePrompt = pos.trim(),
                negativePrompt = neg.trim()
            )

            val paramPairs = params.split(", ")
            paramPairs.forEach { pair ->
                val kv = pair.split(": ")
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val v = kv[1].trim()
                    when (k) {
                        "Steps" -> newState.steps = v.toIntOrNull() ?: newState.steps
                        "CFG scale" -> newState.cfgScale = v.toFloatOrNull() ?: newState.cfgScale
                        "Seed" -> newState.seed = v.toLongOrNull() ?: newState.seed
                        "Sampler" -> newState.sampler = v
                        "Size" -> {
                            val dims = v.split("x")
                            if (dims.size == 2) {
                                newState.width = dims[0].toIntOrNull() ?: newState.width
                                newState.height = dims[1].toIntOrNull() ?: newState.height
                            }
                        }
                        "Clip skip" -> newState.clipSkip = v.toIntOrNull() ?: newState.clipSkip
                    }
                }
            }
            newState
        }
        ForgeState.logServer("Prompt and parameters restored natively from metadata context.")
        Toast.makeText(application, "Loaded generation data", Toast.LENGTH_SHORT).show()
    }

    private fun fetchApiData() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = _config.value.apiUrl.trimEnd('/')
            if (url.isEmpty()) return@launch
            try {
                listOf(
                    Pair("samplers", samplers),
                    Pair("schedulers", schedulers),
                    Pair("sd-models", models),
                    Pair("upscalers", upscalers),
                    Pair("loras", availableLoras)
                ).forEach { (endpoint, stateFlow) ->
                    val request = Request.Builder().url("$url/sdapi/v1/$endpoint").build()
                    client.newCall(request).execute().use { res ->
                        if (res.isSuccessful) {
                            val array = JSONArray(res.body?.string() ?: "[]")
                            val list = mutableListOf<String>()
                            val key = if (endpoint == "sd-models") "title" else "name"
                            for (i in 0 until array.length()) list.add(array.getJSONObject(i).getString(key))
                            stateFlow.value = list
                        }
                    }
                }

                val reqOpts = Request.Builder().url("$url/sdapi/v1/options").build()
                client.newCall(reqOpts).execute().use { res ->
                    if (res.isSuccessful) {
                        val json = JSONObject(res.body?.string() ?: "{}")
                        _selectedModel.value = json.optString("sd_model_checkpoint", "")
                    }
                }
                ForgeState.logServer("Server Parameters synchronized (Models, Samplers, Settings)")
            } catch (e: Exception) {
                ForgeState.logServer("Failed to synchronize API definitions: ${e.message}")
            }
        }
    }

    fun fetchGalleryFolder(path: String = _config.value.galleryPath) {
        _isGalleryLoading.value = true
        _galleryError.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baseUrlStr = _config.value.apiUrl.trimEnd('/')
                val url = baseUrlStr.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegments("infinite_image_browsing/files")
                    ?.addQueryParameter("folder_path", path)
                    ?.build() ?: throw Exception("Invalid API URL format")
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        _galleryFiles.value = parseGalleryItems(response.body?.string() ?: "").sortedWith(compareBy({ !it.isDir }, { it.name }))
                        _currentGalleryPath.value = path
                        ForgeState.logServer("Gallery path accessed: $path")
                    } else {
                        _galleryError.value = "Server returned Error ${response.code}"
                        ForgeState.logServer("Gallery request failed: Code ${response.code}")
                    }
                }
            } catch (e: Exception) {
                _galleryError.value = e.message ?: "Failed to reach server."
                ForgeState.logServer("Gallery Error: ${e.message}")
            }
            finally { _isGalleryLoading.value = false }
        }
    }

    fun getGalleryImageUrl(item: GalleryItem): String {
        val urlStr = _config.value.apiUrl.trimEnd('/')
        val url = urlStr.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("infinite_image_browsing/file")
            ?.addQueryParameter("path", item.fullpath)
            ?.addQueryParameter("t", item.date ?: "")
            ?.build()
        return url?.toString() ?: ""
    }

    fun downloadImage(item: GalleryItem) {
        viewModelScope.launch(workerDispatcher) {
            try {
                val url = getGalleryImageUrl(item)
                if (url.isEmpty()) throw Exception("Invalid Gallery URL")

                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: throw Exception("Empty response body")
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ForgeGen")
                            }
                        }

                        val resolver = application.contentResolver
                        val insertUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        }

                        val uri = resolver.insert(insertUri, contentValues)

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            withContext(Dispatchers.Main) { Toast.makeText(application, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
                        } else throw Exception("Failed to create file in MediaStore")
                    } else throw Exception("Server returned ${response.code}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(application, "Download Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun sessionPrev() {
        val idx = ForgeState.currentSessionIndex.value
        if (idx > ForgeState.currentBatchStartIndex.value) ForgeState.currentSessionIndex.value = idx - 1
    }

    fun sessionNext() {
        val idx = ForgeState.currentSessionIndex.value
        if (idx < ForgeState.currentBatchEndIndex.value) ForgeState.currentSessionIndex.value = idx + 1
    }
}