package com.example.forgegen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ForgePromptManager {
    private const val TAG = "ForgePromptManager"
    private val repositoryScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _wildcards = MutableStateFlow<List<WildcardEntity>>(emptyList())
    val wildcards: StateFlow<List<WildcardEntity>> = _wildcards.asStateFlow()

    fun init() {
        loadWildcards()
    }

    private fun loadWildcards() {
        repositoryScope.launch(Dispatchers.IO) {
            _wildcards.value = ForgeRepository.db.wildcardDao().getAllWildcards()
        }
    }

    fun saveWildcard(
        name: String,
        content: String,
    ) {
        repositoryScope.launch(Dispatchers.IO) {
            ForgeRepository.db.wildcardDao().insertWildcard(WildcardEntity(name, content))
            _wildcards.value = ForgeRepository.db.wildcardDao().getAllWildcards()
        }
    }

    fun deleteWildcard(name: String) {
        repositoryScope.launch(Dispatchers.IO) {
            ForgeRepository.db.wildcardDao().deleteWildcard(WildcardEntity(name, ""))
            _wildcards.value = ForgeRepository.db.wildcardDao().getAllWildcards()
        }
    }

    fun deleteAllWildcards() {
        repositoryScope.launch(Dispatchers.IO) {
            ForgeRepository.db.wildcardDao().clearAll()
            loadWildcards()
        }
    }

    suspend fun getWildcardCount(): Int =
        withContext(Dispatchers.IO) {
            ForgeRepository.db.wildcardDao().count()
        }

    suspend fun clearWildcards() =
        withContext(Dispatchers.IO) {
            ForgeRepository.db.wildcardDao().clearAll()
            _wildcards.value = emptyList()
        }
}
