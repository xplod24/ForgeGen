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

    // The server's VAEs and text encoders, and how it takes them (3.0.0, model settings); set by ForgeNetworkManager.
    private val _modules = MutableStateFlow<List<ServerModule>>(emptyList())
    val modules: StateFlow<List<ServerModule>> = _modules.asStateFlow()
    private val _moduleSupport = MutableStateFlow(ModuleSupport.NONE)
    val moduleSupport: StateFlow<ModuleSupport> = _moduleSupport.asStateFlow()

    fun updateState(selectedModel: String) {
        _selectedModel.value = selectedModel
    }

    fun updateModules(
        modules: List<ServerModule>,
        support: ModuleSupport,
    ) {
        _modules.value = modules
        _moduleSupport.value = support
    }

    /** [payload] with the settings of its model (or of [fallbackModel]) from the user's model settings. */
    fun withModelSettings(
        payload: Txt2ImgPayloadDto,
        fallbackModel: String?,
    ): Txt2ImgPayloadDto {
        val model = payload.override_settings.sdModelCheckpoint ?: fallbackModel
        val settings = ModelSettingsRules.of(ForgeRepository.config.value.modelSettings, model)
        return ModelSettingsRules.applyTo(payload, settings, _moduleSupport.value)
    }
}
