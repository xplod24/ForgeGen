package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/* ============================================================================
 * SETTINGS MANAGER (SINGLETON)
 * Owns all settings/configuration state: AppConfig, AppState, prompt history,
 * gallery metadata toggle, snackbar event bus, OkHttpClient, Gson instance,
 * Preference keys, preset management, and server profiles.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeSettingsManager {
    private const val TAG = "ForgeSettingsManager"

    lateinit var application: Application
        private set

    val gson = Gson()

    val settingsScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Settings are persisted fire-and-forget; a single-threaded dispatcher keeps the writes in call order,
    // so a burst of updates (e.g. dragging a slider) can never leave an older value in the database.
    private val dbWriteDispatcher = Dispatchers.IO.limitedParallelism(1)

    // --- Preference keys ---
    const val CONFIG_KEY = "config"
    const val STATE_KEY = "last_state"
    const val HISTORY_KEY = "prompt_history"
    const val PINNED_IMAGES_KEY = "pinned_images"

    // 0 would mean "no timeout" in OkHttp and a negative value throws, so user input is clamped.
    private const val MIN_TIMEOUT_SECONDS = 1
    private const val MAX_TIMEOUT_SECONDS = 600

    // --- INITIALIZATION STATE ---
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _initStatus = MutableStateFlow("Initializing...")
    val initStatus: StateFlow<String> = _initStatus.asStateFlow()

    fun updateInitStatus(status: String) {
        _initStatus.value = status
    }

    fun setInitialized() {
        _isInitialized.value = true
    }

    // --- EVENT BUS FOR SNACKBARS ---
    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    fun showSnackbar(message: String) {
        _snackbarMessage.tryEmit(message)
    }

    fun showToast(message: String) {
        showSnackbar(message)
    }

    // --- Config state ---
    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    // --- OkHttpClient ---
    var client: OkHttpClient = OkHttpClient()
        private set

    // --- App state ---
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    // --- Prompt history ---
    private val _promptHistory = MutableStateFlow<List<PromptHistoryItem>>(emptyList())
    val promptHistory: StateFlow<List<PromptHistoryItem>> = _promptHistory.asStateFlow()

    // --- Pinned Images (up to 1.0.2) ---
    // Pins were a second list of bookmarks next to the favorites. ForgeGalleryManager moves them into the
    // favorites once and then clears them here.
    private val _pinnedImages = MutableStateFlow<Set<String>>(emptySet())
    val pinnedImages: StateFlow<Set<String>> = _pinnedImages.asStateFlow()

    fun clearPinnedImages() {
        _pinnedImages.value = emptySet()
        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().removeSetting(PINNED_IMAGES_KEY)
        }
    }

    /**
     * Callback invoked when the API URL changes, so ForgeRepository can rebuild the ForgeApi.
     * Set by ForgeRepository during its init().
     */
    var onApiUrlChanged: ((String) -> Unit)? = null

    /**
     * Initialize ForgeSettingsManager. Must be called before any other method.
     * Returns a Triple of (config, appState, promptHistory) loaded from Room Database.
     */
    private lateinit var db: ForgeDatabase

    suspend fun init(app: Application, database: ForgeDatabase): Triple<AppConfig, AppState, List<PromptHistoryItem>> {
        application = app
        db = database

        val dao = db.appSettingDao()
        val loadedConfig = loadConfig(dao.getSetting("config")?.value)
        val loadedState = loadState(dao.getSetting("last_state")?.value, loadedConfig)
        val loadedHistory = loadPromptHistory(dao.getSetting("prompt_history")?.value)
        
        val pinnedJson = dao.getSetting("pinned_images")?.value
        val loadedPinnedImages = if (!pinnedJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<Set<String>>() {}.type
                gson.fromJson<Set<String>>(pinnedJson, type)
            } catch (e: Exception) { emptySet() }
        } else {
            emptySet()
        }

        _config.value = loadedConfig
        cacheThemeMode(loadedConfig.themeMode)
        _appState.value = loadedState
        _promptHistory.value = loadedHistory
        _pinnedImages.value = loadedPinnedImages

        client = createClient(loadedConfig.timeout)

        return Triple(loadedConfig, loadedState, loadedHistory)
    }

    // --- Client creation ---
    // txt2img answers only when the whole batch is finished, so the user's "Connection Timeout"
    // (default 10 s) must not apply to it. Connection loss is still detected by connectTimeout and the ping loop.
    private const val GENERATION_READ_TIMEOUT_MINUTES = 120L

    fun createClient(timeoutSeconds: Int): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.endsWith("sdapi/v1/txt2img")) {
                    chain
                        .withReadTimeout(GENERATION_READ_TIMEOUT_MINUTES.toInt(), TimeUnit.MINUTES)
                        .proceed(request)
                } else {
                    chain.proceed(request)
                }
            }.addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                val isGalleryCall = originalRequest.url.encodedPath.contains("infinite_image_browsing")

                if (isGalleryCall) {
                    requestBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                }

                try {
                    val response = chain.proceed(requestBuilder.build())
                    if (!response.isSuccessful) {
                        try {
                            val bodyStr = response.peekBody(Long.MAX_VALUE).string()
                            Log.e(TAG, "API ERROR [${response.code}]: ${response.request.url}\nBody: $bodyStr")
                        } catch (e: Exception) {
                            Log.e(TAG, "API ERROR [${response.code}]: ${response.request.url} (Could not read body)")
                        }
                    }
                    response
                } catch (e: Exception) {
                    Log.e(TAG, "API CALL FAILED: ${e.message}", e)
                    throw e
                }
            }.build()

    // --- Theme ---
    // The theme is also kept in SharedPreferences, which can be read at once: the settings come from the database
    // only after the first frames, which used to show the light theme to users of the dark one.
    private const val UI_PREFS = "ui"
    private const val THEME_MODE_PREF = "theme_mode"

    /** Before the settings are loaded: the theme chosen last time, so the first frames already use it. */
    fun applyCachedThemeMode(context: Context) {
        if (::db.isInitialized) return
        val cached =
            context
                .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .getString(THEME_MODE_PREF, null) ?: return
        _config.update { it.copy(themeMode = cached) }
    }

    private fun cacheThemeMode(mode: String) {
        if (!::application.isInitialized) return
        application
            .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(THEME_MODE_PREF, mode)
            .apply()
    }

    /** "themeMode" replaced the switch "isDarkMode" in 1.1.5; an older config keeps the look it had. */
    private fun themeModeOf(
        json: String?,
        parsed: AppConfig?,
    ): String {
        val stored =
            try {
                json?.let { JsonParser.parseString(it).asJsonObject }
            } catch (_: Exception) {
                null
            }
        val mode =
            when {
                stored == null -> THEME_SYSTEM
                stored.has("themeMode") -> parsed?.themeMode
                stored.has("isDarkMode") -> if (stored.get("isDarkMode").asBoolean) THEME_DARK else THEME_LIGHT
                else -> THEME_SYSTEM
            }
        return mode?.takeIf { it in listOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK) } ?: THEME_SYSTEM
    }

    // --- Load/Save Config ---
    fun loadConfig(json: String?): AppConfig {
        val parsed =
            if (json != null) {
                try {
                    gson.fromJson(json, AppConfig::class.java)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        return AppConfig(
            apiUrl = parsed?.apiUrl ?: "http://192.168.1.90:7860",
            serverBasePath = parsed?.serverBasePath ?: "",
            galleryPath = parsed?.galleryPath ?: "",
            themeMode = themeModeOf(json, parsed),
            timeout = (parsed?.timeout ?: 10).coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
            notifOnBatchFinish = parsed?.notifOnBatchFinish ?: false,
            notifOnQueueFinish = parsed?.notifOnQueueFinish ?: true,
            notifCivitaiSync = parsed?.notifCivitaiSync ?: true,
            autoDismissCivitaiNotif = parsed?.autoDismissCivitaiNotif ?: false,
            notificationMode = parsed?.notificationMode ?: "Simple",
            keepScreenOn = parsed?.keepScreenOn ?: false,
            swipeToBrowseGallery = parsed?.swipeToBrowseGallery ?: true,
            bottomSheetExpandedByDefault = parsed?.bottomSheetExpandedByDefault ?: false,
            serverProfiles = parsed?.serverProfiles ?: listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),

            useNativeSecurity = parsed?.useNativeSecurity ?: false,
            useBiometricLock = parsed?.useBiometricLock ?: false,
            overnightMode = parsed?.overnightMode ?: false,
            showGridAfterGeneration = parsed?.showGridAfterGeneration ?: true,
            showActiveTagsUI = parsed?.showActiveTagsUI ?: true,
            enableLogging = parsed?.enableLogging ?: false,
            lastUpdateCheckDate = parsed?.lastUpdateCheckDate ?: "",
            defaultState = parsed?.defaultState ?: AppState(),
            presets = parsed?.presets ?: emptyList(),
            autoSyncModels = parsed?.autoSyncModels ?: false,
            mainPromptsExpanded = parsed?.mainPromptsExpanded ?: true,
            mainSettingsExpanded = parsed?.mainSettingsExpanded ?: false,
            mainLorasExpanded = parsed?.mainLorasExpanded ?: false,
            autoSaveMode = parsed?.autoSaveMode ?: AUTO_SAVE_OFF,
            autoSaveSince = parsed?.autoSaveSince ?: "",
            saveOomLogs = parsed?.saveOomLogs ?: false,
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }

        val oldUrl = _config.value.apiUrl
        val oldTimeout = _config.value.timeout
        val updatedConfig =
            newConfig.copy(
                apiUrl = cleanUrl,
                timeout = newConfig.timeout.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
            )

        _config.value = updatedConfig
        cacheThemeMode(updatedConfig.themeMode)

        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().putSetting(AppSettingEntity(CONFIG_KEY, gson.toJson(updatedConfig)))
        }

        client =
            client
                .newBuilder()
                .connectTimeout(updatedConfig.timeout.toLong(), TimeUnit.SECONDS)
                .readTimeout(updatedConfig.timeout.toLong(), TimeUnit.SECONDS)
                .build()

        if (cleanUrl != oldUrl || updatedConfig.timeout != oldTimeout) {
            onApiUrlChanged?.invoke(cleanUrl)
        } else if (ForgeRepository.isConnected.value == false) {
            // Reconnect if offline and user clicked save
            onApiUrlChanged?.invoke(cleanUrl)
        }
    }

    // --- Load/Save State ---
    fun loadState(json: String?, currentConfig: AppConfig? = null): AppState {
        val parsed =
            if (json != null) {
                try {
                    gson.fromJson(json, AppState::class.java)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        if (parsed != null) return parsed
        return (currentConfig ?: _config.value).defaultState.copy()
    }

    fun updateState(update: (AppState) -> AppState) {
        val newState = update(_appState.value)
        _appState.value = newState
        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().putSetting(AppSettingEntity(STATE_KEY, gson.toJson(newState)))
        }
    }

    // --- Defaults ---
    fun saveCurrentAsDefault() {
        val currentConfig = _config.value
        val newState = currentConfig.copy(defaultState = _appState.value.copy())
        saveConfig(newState)
        showSnackbar("Set Current as Default")
    }

    /** Restores every option to its default, keeping only the server connection, presets and profiles. */
    fun resetSettings() {
        val current = _config.value
        saveConfig(
            AppConfig(
                apiUrl = current.apiUrl,
                serverBasePath = current.serverBasePath,
                galleryPath = current.galleryPath,
                serverProfiles = current.serverProfiles,
                presets = current.presets,
            ),
        )
        resetToDefaults()
    }

    fun resetToDefaults() {
        _appState.value = _config.value.defaultState.copy()
        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().putSetting(AppSettingEntity(STATE_KEY, gson.toJson(_appState.value)))
        }
        showSnackbar("Reset to Defaults")
    }

    // --- Prompt history ---
    fun loadPromptHistory(json: String?): List<PromptHistoryItem> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<PromptHistoryItem>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveToPromptHistory(
        positive: String,
        negative: String,
    ) {
        if (positive.isBlank() && negative.isBlank()) return

        val currentList = _promptHistory.value.toMutableList()
        if (currentList.isNotEmpty() && currentList.first().positivePrompt == positive && currentList.first().negativePrompt == negative) {
            return
        }

        val newItem = PromptHistoryItem(positive, negative, System.currentTimeMillis())
        currentList.add(0, newItem)

        val trimmedList = currentList.take(20)
        _promptHistory.value = trimmedList

        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().putSetting(AppSettingEntity(HISTORY_KEY, gson.toJson(trimmedList)))
        }
    }

    fun clearPromptHistory() {
        _promptHistory.value = emptyList()
        settingsScope.launch(dbWriteDispatcher) {
            db.appSettingDao().removeSetting(HISTORY_KEY)
        }
    }

    // --- Presets ---
    fun savePreset(
        name: String,
        includePrompts: Boolean = true,
    ) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        currentPresets.add(GenerationPreset(name, _appState.value.copy(), includePrompts))
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    fun loadPreset(name: String) {
        val preset = _config.value.presets.find { it.name == name }
        if (preset != null) {
            val current = _appState.value
            _appState.value =
                if (preset.includePrompts) {
                    preset.state.copy()
                } else {
                    preset.state.copy(positivePrompt = current.positivePrompt, negativePrompt = current.negativePrompt)
                }
            settingsScope.launch(dbWriteDispatcher) {
                db.appSettingDao().putSetting(AppSettingEntity(STATE_KEY, gson.toJson(_appState.value)))
            }
            showSnackbar("Loaded: $name")
        }
    }

    fun deletePreset(name: String) {
        val currentPresets = _config.value.presets.toMutableList()
        currentPresets.removeAll { it.name == name }
        saveConfig(_config.value.copy(presets = currentPresets))
    }

    fun updatePreset(
        oldName: String,
        preset: GenerationPreset,
    ) {
        val current = _config.value
        // A blank or duplicate name would make presets impossible to tell apart (and to load by name),
        // so such a rename keeps the old name while the other edits are still saved.
        val newName = preset.name.trim()
        val nameTaken = current.presets.any { it.name == newName && it.name != oldName }
        val safePreset = if (newName.isEmpty() || nameTaken) preset.copy(name = oldName) else preset.copy(name = newName)
        val newPresets = current.presets.map { if (it.name == oldName) safePreset else it }
        saveConfig(current.copy(presets = newPresets))
    }

    // --- Server profiles ---
    fun addServerProfile(
        name: String,
        url: String,
    ) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        currentProfiles.add(ServerProfile(name, url))
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    fun removeServerProfile(name: String) {
        val currentProfiles = _config.value.serverProfiles.toMutableList()
        currentProfiles.removeAll { it.name == name }
        saveConfig(_config.value.copy(serverProfiles = currentProfiles))
    }

    /** Infotext of the last image generated by this app (cached as last_generated_image.png by ForgeQueueManager). */
    suspend fun loadLastGeneratedInfo(): String? =
        withContext(Dispatchers.IO) {
            val file = File(application.cacheDir, "last_generated_image.png")
            if (!file.exists()) return@withContext null
            file.inputStream().use { PngMetadata.readParameters(it) }.ifBlank { null }
        }
}
