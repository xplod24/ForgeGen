package com.example.forgegen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/* ============================================================================
 * USTAWIENIA: STRONA "FEATURES" (3.6.1, podział SetupScreen na pliki)
 *
 * Co tu jest: przełączniki funkcji aplikacji (3.4.0). Wyłączona funkcja znika z ekranu, a aplikacja przestaje
 * pytać serwer o jej dane (oszczędność sieci i baterii). Grupy: Prompt, LoRAs and Models, Main Screen, Gallery,
 * Server.
 *
 * Jak to działa: lokalna funkcja feature(...) tworzy jeden wiersz z przełącznikiem; jej ostatni parametr (update)
 * mówi, jak zmienić ustawienia aplikacji (AppConfig) po przełączeniu. viewModel.saveConfig(...) zapisuje zmianę.
 *
 * Do poczytania:
 * - data class i copy(): niezmienne obiekty z ustawieniami; copy(x = ...) tworzy kopię z jedną zmienioną wartością,
 * - funkcja lokalna (fun wewnątrz funkcji) i lambda jako ostatni parametr (składnia "feature(...) { ... }").
 * ============================================================================ */

// Pozycje strony Features, w kolejności wyświetlania.
@Composable
internal fun SettingsUiState.featuresSettings(): List<SettingItem> {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val tagListStatus by viewModel.tagListStatus.collectAsStateWithLifecycle()

    return settingsOf {
        // --- FEATURES (3.4.0) ---
        // Each one can be switched off; off, it leaves the screen and the app no longer asks the server for its data.
        // Jeden przełącznik funkcji w grupie [group]; [update] zwraca nowe ustawienia po przełączeniu.
        fun feature(
            group: String,
            words: String,
            title: String,
            subtitle: String,
            checked: Boolean,
            update: (Boolean) -> AppConfig,
        ) = add(SettingsPage.FEATURES, group, "feature switch $words") {
            SwitchPreference(
                title = title,
                subtitle = subtitle,
                checked = checked,
                onCheckedChange = { viewModel.saveConfig(update(it)) },
            )
        }
        // Podpowiedzi tagów nad klawiaturą podczas pisania promptu.
        feature(
            "Prompt",
            "tag suggestions autocomplete danbooru keyboard wildcards lora tagcomplete",
            "Tag Suggestions",
            "While you type a prompt, tags, __wildcards and <lora: names above the keyboard",
            config.tagSuggestions,
        ) { config.copy(tagSuggestions = it) }
        // Lista tagów (z rozszerzenia tagcomplete na serwerze); stuknięcie pobiera ją ponownie.
        if (config.tagSuggestions) {
            add(SettingsPage.FEATURES, "Prompt", "tag list danbooru csv tagcomplete download reload") {
                TextPreference(title = "Tag List", subtitle = tagListText(tagListStatus)) { viewModel.reloadTagList() }
            }
        }
        // Style zapisane na serwerze (styles.csv); domyślnie wyłączone (decyzja właściciela, 3.1.0).
        // Off unless turned on here (3.1.0, the owner's decision).
        feature(
            "Prompt",
            "server styles styles.csv prompt style preset web ui",
            "Server Styles",
            "The server's saved styles (styles.csv) in a row under the prompt; the chosen ones go with every job",
            config.serverStyles,
        ) { config.copy(serverStyles = it) }
        // Embeddingi serwera na liście LoRA i w podpowiedziach.
        feature(
            "Prompt",
            "embeddings textual inversion negative suggestions",
            "Embeddings",
            "The server's embeddings in the LoRA list and in the suggestions above the keyboard",
            config.embeddings,
        ) { config.copy(embeddings = it) }
        // Szczegóły LoRA (model bazowy, słowa wyzwalające); wyłączone = nie pobiera megabajtów metadanych.
        feature(
            "LoRAs and Models",
            "lora details base model trigger words training tags fits filter metadata",
            "LoRA Details",
            "Each LoRA's model and trigger words, its details and the \"Fits\" filter. Off: their data (megabytes " +
                "with many LoRAs) is not downloaded",
            config.loraDetails,
        ) { config.copy(loraDetails = it) }
        // Obrazki przy modelach i LoRA (z folderów modeli na serwerze).
        feature(
            "LoRAs and Models",
            "model lora pictures previews thumbnails images",
            "Model and LoRA Pictures",
            "The pictures next to the models and LoRAs, from the server's model folders",
            config.resourcePictures,
        ) { config.copy(resourcePictures = it) }
        // Podgląd obrazu w trakcie generowania.
        feature(
            "Main Screen",
            "live preview generating image progress",
            "Live Preview",
            "The image as it forms while it is generated. Off: the server sends none",
            config.livePreview,
        ) { config.copy(livePreview = it) }
        // Mierniki VRAM i RAM serwera na górnym pasku.
        feature(
            "Main Screen",
            "memory meters vram ram top bar server memory",
            "Memory Meters",
            "The server's VRAM and RAM in the top bar. Off: an icon opens Server Memory, which reads them when it opens",
            config.memoryMeters,
        ) { config.copy(memoryMeters = it) }
        // Okładki folderów w galerii (najnowsze obrazy folderu).
        feature(
            "Gallery",
            "folder covers gallery thumbnails",
            "Folder Covers",
            "A folder's newest images as its picture",
            config.folderCovers,
        ) { config.copy(folderCovers = it) }
        // Sprawdzanie, czy ulubione obrazy nie zniknęły z serwera.
        feature(
            "Gallery",
            "check favorites missing deleted gone server",
            "Check Favorites",
            "Tells when favorites are gone from the server",
            config.favoritesCheck,
        ) { config.copy(favoritesCheck = it) }
        // Zadania z obrazów w galerii: Upscale, More Like This, Variance.
        feature(
            "Gallery",
            "image jobs upscale more like this variance seed",
            "Image Jobs",
            "Upscale, More Like This and Variance for gallery images",
            config.imageJobs,
        ) { config.copy(imageJobs = it) }
        // Informacja, że serwer najpierw robi zadania z web UI albo innej aplikacji.
        feature(
            "Server",
            "other jobs server queue web ui waiting internal progress",
            "Other Jobs on the Server",
            "Says when jobs from the web UI or another app go first",
            config.serverQueue,
        ) { config.copy(serverQueue = it) }
        // Historia generowania (czasy ładowania modelu, próbkowania, VRAM) do statystyk i kolejki.
        feature(
            "Server",
            "generation history statistics model loading swap cold start speed vram job times",
            "Generation History",
            "Times each job's model loading, sampling and VRAM for the statistics and the queue. Off: nothing is " +
                "recorded and the VRAM is not read while a model loads",
            config.generationHistory,
        ) { config.copy(generationHistory = it) }
    }
}

// Opis wiersza "Tag List": ile tagów, z jakiego pliku i kiedy zapisane, albo dlaczego ich nie ma.

/** The "Tag List" line of the settings: how many tags, from which file and when, or why there are none. */
private fun tagListText(status: ForgeTagManager.Status): String {
    val date =
        status.savedAt.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
        }
    val saved = String.format(java.util.Locale.US, "%,d tags from %s", status.count, status.file) + (date?.let { ", saved $it" } ?: "")
    return when (status.source) {
        ForgeTagManager.Source.OFF -> "Off"
        ForgeTagManager.Source.NONE -> "Downloaded from the server's tagcomplete extension once connected"
        ForgeTagManager.Source.LOADING -> "Loading…"
        ForgeTagManager.Source.READY -> saved + (status.message?.let { ". $it" } ?: "") + ". Tap to download it again"
        ForgeTagManager.Source.MISSING ->
            (status.message ?: "The server has no tagcomplete extension") +
                if (status.count > 0) ". Using the saved $saved" else ". Wildcards and LoRAs are still suggested"
        ForgeTagManager.Source.FAILED -> (status.message ?: "The tag list could not be loaded") + ". Tap to try again"
    }
}
