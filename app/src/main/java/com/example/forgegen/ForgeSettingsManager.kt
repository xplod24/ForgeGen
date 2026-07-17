@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.example.forgegen

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/* ============================================================================
 * SETTINGS MANAGER (SINGLETON)
 * Owns all settings/configuration state: AppConfig, AppState, prompt history,
 * gallery metadata toggle, snackbar event bus, OkHttpClient, Gson instance,
 * DataStore preference keys, preset management, and server profiles.
 * ============================================================================ */

@SuppressLint("StaticFieldLeak")
object ForgeSettingsManager {
    private const val TAG = "ForgeSettingsManager"

    lateinit var application: Application
        private set

    val gson = Gson()

    val settingsScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // --- DataStore preference keys ---
    val CONFIG_KEY = stringPreferencesKey("config")
    val STATE_KEY = stringPreferencesKey("last_state")
    val HISTORY_KEY = stringPreferencesKey("prompt_history")
    val SHOW_META_KEY = booleanPreferencesKey("show_gallery_meta")

    // --- EVENT BUS DLA SNACKBARÓW ---
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

    // --- Gallery metadata toggle ---
    private val _showGalleryMetadata = MutableStateFlow(false)
    val showGalleryMetadata: StateFlow<Boolean> = _showGalleryMetadata.asStateFlow()

    /**
     * Callback invoked when the API URL changes, so ForgeRepository can rebuild the ForgeApi.
     * Set by ForgeRepository during its init().
     */
    var onApiUrlChanged: ((String) -> Unit)? = null

    /**
     * Callback invoked when the persistent service setting changes.
     * Set by ForgeRepository during its init().
     */
    var onPersistentServiceChanged: ((Boolean) -> Unit)? = null

    /**
     * Initialize ForgeSettingsManager. Must be called before any other method.
     * Returns a Triple of (config, appState, promptHistory) loaded from DataStore.
     */
    suspend fun init(app: Application): Triple<AppConfig, AppState, List<PromptHistoryItem>> {
        application = app

        val prefs = application.dataStore.data.first()
        val loadedConfig = loadConfig(prefs)
        val loadedState = loadState(prefs, loadedConfig)
        val loadedHistory = loadPromptHistory(prefs)
        val loadedShowMeta = prefs[SHOW_META_KEY] ?: false

        _config.value = loadedConfig
        _appState.value = loadedState
        _promptHistory.value = loadedHistory
        _showGalleryMetadata.value = loadedShowMeta

        client = createClient(loadedConfig.connectionTimeout)

        return Triple(loadedConfig, loadedState, loadedHistory)
    }

    // --- Client creation ---
    fun createClient(timeoutSeconds: Int): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()

                val isGalleryCall = originalRequest.url.encodedPath.contains("infinite_image_browsing")

                if (isGalleryCall) {
                    requestBuilder.header("Cookie", "IIB_S=bf63789069ec13d6b7b95a5176468e99f8940fe6aa65931edc17e1abf5c5e172")
                }

                try {
                    chain.proceed(requestBuilder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "API CALL FAILED: ${e.message}", e)
                    throw e
                }
            }.build()

    // --- Load/Save Config ---
    fun loadConfig(prefs: Preferences): AppConfig {
        val json = prefs[CONFIG_KEY]
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

        val oldLivePreviewState =
            try {
                val jsonObj = org.json.JSONObject(json ?: "{}")
                jsonObj.optBoolean("livePreviews", false)
            } catch (_: Exception) {
                false
            }

        val finalPreviewMode = parsed?.previewMode ?: if (oldLivePreviewState) "Normal" else "Finished"

        return AppConfig(
            apiUrl = parsed?.apiUrl ?: "http://192.168.1.90:7860",
            serverBasePath = parsed?.serverBasePath ?: "",
            galleryPath = parsed?.galleryPath ?: "",
            isDarkMode = parsed?.isDarkMode ?: false,
            connectionTimeout = parsed?.connectionTimeout ?: 10,
            checkpointTimeout = parsed?.checkpointTimeout ?: 45,
            receiveGenerationNotification = parsed?.receiveGenerationNotification ?: true,
            notifQueueStatus = parsed?.notifQueueStatus ?: false,
            notificationMode = parsed?.notificationMode ?: "Simple",
            keepScreenOn = parsed?.keepScreenOn ?: false,
            enablePersistentService = parsed?.enablePersistentService ?: false,
            swipeToBrowseGallery = parsed?.swipeToBrowseGallery ?: true,
            bottomSheetExpandedByDefault = parsed?.bottomSheetExpandedByDefault ?: false,
            serverProfiles = parsed?.serverProfiles ?: listOf(ServerProfile("Default Local", "http://192.168.1.90:7860")),
            previewMode = finalPreviewMode,
            useNativeSecurity = parsed?.useNativeSecurity ?: false,
            useBiometricLock = parsed?.useBiometricLock ?: false,
            overnightMode = parsed?.overnightMode ?: false,
            showGridAfterGeneration = parsed?.showGridAfterGeneration ?: true,
            showActiveTagsUI = parsed?.showActiveTagsUI ?: true,
            enableLogging = parsed?.enableLogging ?: false,
            defaultState = parsed?.defaultState ?: AppState(),
            presets = parsed?.presets ?: emptyList(),
        )
    }

    fun saveConfig(newConfig: AppConfig) {
        var cleanUrl = newConfig.apiUrl.trim()
        if (cleanUrl.isNotEmpty() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }

        val oldPersistent = _config.value.enablePersistentService
        val oldUrl = _config.value.apiUrl
        val updatedConfig = newConfig.copy(apiUrl = cleanUrl)

        _config.value = updatedConfig

        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[CONFIG_KEY] = gson.toJson(updatedConfig) }
        }

        client =
            client
                .newBuilder()
                .connectTimeout(updatedConfig.connectionTimeout.toLong(), TimeUnit.SECONDS)
                .build()

        if (updatedConfig.enablePersistentService != oldPersistent) {
            onPersistentServiceChanged?.invoke(updatedConfig.enablePersistentService)
        }

        if (cleanUrl != oldUrl) {
            onApiUrlChanged?.invoke(cleanUrl)
        }
    }

    // --- Load/Save State ---
    fun loadState(
        prefs: Preferences,
        currentConfig: AppConfig? = null,
    ): AppState {
        val json = prefs[STATE_KEY]
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
        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[STATE_KEY] = gson.toJson(newState) }
        }
    }

    // --- Defaults ---
    fun saveCurrentAsDefault() {
        val currentConfig = _config.value
        val newState = currentConfig.copy(defaultState = _appState.value.copy())
        saveConfig(newState)
        showSnackbar("Set Current as Default")
    }

    fun resetToDefaults() {
        _appState.value = _config.value.defaultState.copy()
        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[STATE_KEY] = gson.toJson(_appState.value) }
        }
        showSnackbar("Reset to Defaults")
    }

    // --- Prompt history ---
    fun loadPromptHistory(prefs: Preferences): List<PromptHistoryItem> {
        val json = prefs[HISTORY_KEY]
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

        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[HISTORY_KEY] = gson.toJson(trimmedList) }
        }
    }

    fun clearPromptHistory() {
        _promptHistory.value = emptyList()
        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it.remove(HISTORY_KEY) }
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
            _appState.value = preset.state.copy()
            settingsScope.launch(Dispatchers.IO) {
                application.dataStore.edit { it[STATE_KEY] = gson.toJson(_appState.value) }
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
        val newPresets = current.presets.map { if (it.name == oldName) preset else it }
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

    // --- Gallery metadata toggle ---
    fun toggleGalleryMetadata() {
        val newVal = !_showGalleryMetadata.value
        _showGalleryMetadata.value = newVal
        settingsScope.launch(Dispatchers.IO) {
            application.dataStore.edit { it[SHOW_META_KEY] = newVal }
        }
    }

    suspend fun getPngInfoFromServer(base64: String): String? {
        try {
            val response = ForgeRepository.forgeApi?.getPngInfo(PngInfoPayloadDto(base64))
            if (response?.isSuccessful == true) {
                return response.body()?.info
            }
        } catch (e: Exception) {
            Log.e("ForgeSettingsManager", "Error getting png info", e)
        }
        return null
    }

    suspend fun loadLastGeneratedInfo(): String? {
        return null // Placeholder since it was hallucinated
    }

    suspend fun saveLastGeneratedInfo(info: String) {
        // Placeholder
    }
}
