package com.example.forgegen

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ForgeModelManager {
    private const val TAG = "ForgeModelManager"
    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _samplers = MutableStateFlow<List<String>>(emptyList())
    val samplers: StateFlow<List<String>> = _samplers.asStateFlow()

    private val _schedulers = MutableStateFlow<List<String>>(emptyList())
    val schedulers: StateFlow<List<String>> = _schedulers.asStateFlow()

    private val _models = MutableStateFlow<List<ApiResource>>(emptyList())
    val models: StateFlow<List<ApiResource>> = _models.asStateFlow()

    private val _upscalers = MutableStateFlow<List<String>>(emptyList())
    val upscalers: StateFlow<List<String>> = _upscalers.asStateFlow()

    private val _availableLoras = MutableStateFlow<List<ApiResource>>(emptyList())
    val availableLoras: StateFlow<List<ApiResource>> = _availableLoras.asStateFlow()

    private val _isCivitaiSyncing = MutableStateFlow(IndicatorState.IDLE)
    val isCivitaiSyncing: StateFlow<IndicatorState> = _isCivitaiSyncing.asStateFlow()

    private val _civitaiSyncCurrentModel = MutableStateFlow("")
    val civitaiSyncCurrentModel: StateFlow<String> = _civitaiSyncCurrentModel.asStateFlow()

    private val _civitaiSyncProgress = MutableStateFlow(Pair(0, 0))
    val civitaiSyncProgress: StateFlow<Pair<Int, Int>> = _civitaiSyncProgress.asStateFlow()

    private val _civitaiSyncLastResult = MutableStateFlow<String?>(null)
    val civitaiSyncLastResult: StateFlow<String?> = _civitaiSyncLastResult.asStateFlow()

    suspend fun getTagsForLora(hash: String): List<String> = withContext(Dispatchers.IO) {
        val model = ForgeRepository.db.civitaiModelDao().getModelByHash(hash)
        model?.trainedWords?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    suspend fun getCivitaiModelCount(): Int = withContext(Dispatchers.IO) {
        ForgeRepository.db.civitaiModelDao().count()
    }

    suspend fun clearCivitaiCache() = withContext(Dispatchers.IO) {
        ForgeRepository.db.civitaiModelDao().clearAll()
    }
    
    // Callbacks to communicate back to ForgeRepository/ViewModel
    var onShowSnackbar: ((String) -> Unit)? = null
    var onConfigUpdateRequired: ((AppConfig) -> Unit)? = null
    
    // Used internally to manipulate state from ForgeRepository during refactoring
    // (A proper refactor would move all of fetchApiData and syncCivitaiModelsManual here,
    // but they rely heavily on forgeApi and civitaiApi which are in ForgeRepository for now)
    fun updateState(
        selectedModel: String? = null,
        samplers: List<String>? = null,
        schedulers: List<String>? = null,
        models: List<ApiResource>? = null,
        upscalers: List<String>? = null,
        availableLoras: List<ApiResource>? = null
    ) {
        selectedModel?.let { _selectedModel.value = it }
        samplers?.let { _samplers.value = it }
        schedulers?.let { _schedulers.value = it }
        models?.let { _models.value = it }
        upscalers?.let { _upscalers.value = it }
        availableLoras?.let { _availableLoras.value = it }
    }
    
    fun updateCivitaiSyncState(
        state: IndicatorState? = null,
        currentModel: String? = null,
        progress: Pair<Int, Int>? = null,
        lastResult: String? = null
    ) {
        state?.let { _isCivitaiSyncing.value = it }
        currentModel?.let { _civitaiSyncCurrentModel.value = it }
        progress?.let { _civitaiSyncProgress.value = it }
        lastResult?.let { _civitaiSyncLastResult.value = it }
    }
}
