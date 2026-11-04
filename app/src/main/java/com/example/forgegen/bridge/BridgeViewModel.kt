package com.example.forgegen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map

/** Activity-scoped optional companion; the existing Forge ViewModel and managers stay independent. */
class BridgeViewModel(
    application: Application,
) : AndroidViewModel(application) {
    val controller = BridgeController(viewModelScope, ForgeRepository.config.map { it.apiUrl }, AndroidBridgeStore(application))
}
