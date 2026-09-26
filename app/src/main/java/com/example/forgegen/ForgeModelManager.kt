package com.example.forgegen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Model lists and samplers live in ForgeNetworkManager; this object only holds the active checkpoint.
 */
object ForgeModelManager {
    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun updateState(selectedModel: String) {
        _selectedModel.value = selectedModel
    }
}
