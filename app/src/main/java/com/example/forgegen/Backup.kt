package com.example.forgegen

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/* ============================================================================
 * BACKUP
 * The settings (with the presets, the server profiles and the default generation settings) and the wildcards in one
 * JSON file, to move them to another phone or keep them safe. Reading goes through loadConfig, so a backup from an
 * older version, or with a wrong value, gets the defaults for what it lacks. The gallery keys' fingerprints stay out
 * of it (3.5.0): they open the server's gallery like a password, so an import keeps the phone's own.
 * Since 3.5.2-1 (format 2) it also carries the gallery favorites and the queue, so everything moves to the app without
 * ".debug" in its package (3.5.2-2), which Android treats as another app. Older versions ignore those two parts.
 * ============================================================================ */
object Backup {
    private const val APP = "ForgeGen"
    private const val FORMAT = 2
    private val gson = GsonBuilder().setPrettyPrinting().create()

    class Contents(
        val config: AppConfig,
        val wildcards: List<WildcardEntity>,
        val favorites: List<FavoriteImageEntity> = emptyList(),
        val queue: List<QueuedGeneration> = emptyList(),
    )

    fun write(
        config: AppConfig,
        wildcards: List<WildcardEntity>,
        appVersion: String,
        favorites: List<FavoriteImageEntity> = emptyList(),
        queue: List<QueuedGeneration> = emptyList(),
    ): String =
        gson.toJson(
            linkedMapOf(
                "app" to APP,
                "format" to FORMAT,
                "version" to appVersion,
                "config" to config.copy(galleryKeys = emptyMap()),
                "wildcards" to wildcards,
                "favorites" to favorites,
                "queue" to queue,
            ),
        )

    /** The backup in [json], or null when it is not a ForgeGen backup. */
    fun read(json: String): Contents? {
        val root =
            try {
                JsonParser.parseString(json).asJsonObject
            } catch (e: Exception) {
                return null
            }
        if (root.get("app")?.takeIf { it.isJsonPrimitive }?.asString != APP) return null
        val config = root.get("config")?.takeIf { it.isJsonObject } ?: return null
        val wildcards =
            try {
                gson.fromJson<List<WildcardEntity>>(root.get("wildcards"), object : TypeToken<List<WildcardEntity>>() {}.type)
            } catch (e: Exception) {
                null
            }.orEmpty()
        return Contents(
            ForgeSettingsManager.loadConfig(config.toString()),
            wildcards.filter { !it.name.isNullOrBlank() && it.content != null },
            favoritesOf(root.get("favorites")),
            queueOf(root.get("queue")),
        )
    }

    /** The favorites of a backup, read field by field: an entry without its path is left out. */
    private fun favoritesOf(element: JsonElement?): List<FavoriteImageEntity> {
        val list = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return list.mapNotNull { entry ->
            val item = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null

            fun text(key: String) = item.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            val path = text("fullpath")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FavoriteImageEntity(
                fullpath = path,
                name = text("name") ?: path.substringAfterLast('/'),
                date = text("date"),
                savedAt = text("savedAt")?.toLongOrNull() ?: System.currentTimeMillis(),
            )
        }
    }

    /** The queued jobs of a backup; a job Gson could not fill (no id, payload or status) is left out. */
    private fun queueOf(element: JsonElement?): List<QueuedGeneration> {
        val jobs =
            try {
                gson.fromJson<List<QueuedGeneration>>(element, object : TypeToken<List<QueuedGeneration>>() {}.type)
            } catch (e: Exception) {
                null
            }.orEmpty()
        // Gson leaves missing fields null even where Kotlin says they cannot be: reading them throws then.
        return jobs.filter { job ->
            runCatching { job.id.isNotBlank() && job.payload.prompt.length >= 0 && job.status.name.isNotEmpty() }.getOrDefault(false)
        }
    }
}
