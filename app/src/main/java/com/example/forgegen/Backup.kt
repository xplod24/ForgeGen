package com.example.forgegen

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/* ============================================================================
 * BACKUP
 * The settings (with the presets, the server profiles and the default generation settings) and the wildcards in one
 * JSON file, to move them to another phone or keep them safe. Reading goes through loadConfig, so a backup from an
 * older version, or with a wrong value, gets the defaults for what it lacks. The gallery keys' fingerprints stay out
 * of it (3.5.0): they open the server's gallery like a password, so an import keeps the phone's own.
 * ============================================================================ */
object Backup {
    private const val APP = "ForgeGen"
    private const val FORMAT = 1
    private val gson = GsonBuilder().setPrettyPrinting().create()

    class Contents(
        val config: AppConfig,
        val wildcards: List<WildcardEntity>,
    )

    fun write(
        config: AppConfig,
        wildcards: List<WildcardEntity>,
        appVersion: String,
    ): String =
        gson.toJson(
            linkedMapOf(
                "app" to APP,
                "format" to FORMAT,
                "version" to appVersion,
                "config" to config.copy(galleryKeys = emptyMap()),
                "wildcards" to wildcards,
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
        )
    }
}
