package com.example.forgegen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/*
 * Model lists, samplers and the Civitai sync state live in ForgeNetworkManager;
 * this object only holds the active checkpoint and the LoRA trigger-word lookup.
 */
object ForgeModelManager {
    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    // LoRAs (their names in prompts) that Civitai marks as a real person; set with the model lists.
    private val _realPersonLoras = MutableStateFlow<Set<String>>(emptySet())
    val realPersonLoras: StateFlow<Set<String>> = _realPersonLoras.asStateFlow()

    fun setRealPersonLoras(names: Set<String>) {
        _realPersonLoras.value = names
    }

    suspend fun getTagsForLora(hash: String): List<String> =
        withContext(Dispatchers.IO) {
            val model = ForgeRepository.db.civitaiModelDao().getModelByHash(hash)
            model
                ?.trainedWords
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList()
        }

    fun updateState(selectedModel: String) {
        _selectedModel.value = selectedModel
    }
}
