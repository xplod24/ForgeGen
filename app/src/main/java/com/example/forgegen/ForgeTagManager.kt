package com.example.forgegen

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The tag list of the suggestion strip (2.4.2): the tag file of the server's tagcomplete extension. The extension's
 * own script finds its folder in the web UI's `tmp/tagAutocompletePath.txt` and reads the files through `file=`;
 * the app does the same, once, and keeps the list on the phone (so it also works offline). It is downloaded again
 * after [MAX_AGE_MS], when the server uses other files, or on "Tag List" in the settings.
 */
object ForgeTagManager {
    private const val TAG = "ForgeTagManager"
    const val PATH_FILE = "tmp/tagAutocompletePath.txt"
    private const val DEFAULT_TAG_FILE = "danbooru.csv"
    private const val NO_FILE = "None"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    enum class Source {
        // Suggestions are switched off.
        OFF,

        // No list yet: the server was not reached since the app was installed.
        NONE,
        LOADING,
        READY,

        // The server has no tagcomplete extension (or no tag file chosen in it).
        MISSING,
        FAILED,
    }

    /** How the list stands: [count] tags from [file] (saved at [savedAt]); [message] says why there is none. */
    data class Status(
        val source: Source,
        val count: Int = 0,
        val file: String? = null,
        val savedAt: Long = 0,
        val message: String? = null,
    )

    /** Where the saved list came from, and how its server writes tags. */
    private data class Meta(
        val server: String,
        val file: String,
        val extraFile: String?,
        val extraFirst: Boolean,
        val savedAt: Long,
        val replaceUnderscores: Boolean,
        val keepUnderscores: List<String>,
        val escapeBrackets: Boolean,
    ) {
        fun rules() = TagInsertRules(replaceUnderscores, keepUnderscores.toSet(), escapeBrackets)
    }

    /** What the server said the last time it was reached, so "Tag List" can ask it again. */
    private class ServerCall(
        val api: ForgeApi,
        val options: OptionsResponseDto?,
        val server: String,
    )

    private val _tags = MutableStateFlow<TagList?>(null)
    val tags: StateFlow<TagList?> = _tags.asStateFlow()

    private val _status = MutableStateFlow(Status(Source.NONE))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _rules = MutableStateFlow(TagInsertRules())
    val rules: StateFlow<TagInsertRules> = _rules.asStateFlow()

    @Volatile private var dir: File? = null

    @Volatile private var lastCall: ServerCall? = null
    private val mutex = Mutex()

    private val listFile get() = dir?.let { File(it, "tags.csv") }
    private val extraFile get() = dir?.let { File(it, "extra.csv") }
    private val metaFile get() = dir?.let { File(it, "tags.json") }

    private fun enabled() = ForgeRepository.config.value.tagSuggestions

    /** Loads the saved list in the background, and follows the setting: off frees the list, on loads it again. */
    fun start(context: Context) {
        dir = File(context.filesDir, "tags")
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) {
            var wasOn: Boolean? = null
            ForgeRepository.config.collect { config ->
                if (config.tagSuggestions == wasOn) return@collect
                wasOn = config.tagSuggestions
                if (config.tagSuggestions) {
                    mutex.withLock { loadSaved() }
                    lastCall?.let { refresh(it, force = false) }
                } else {
                    mutex.withLock {
                        _tags.value = null
                        _status.value = Status(Source.OFF)
                    }
                }
            }
        }
    }

    /** The server's options were read (every connect): its list is downloaded when the saved one is not its current one. */
    fun onServerOptions(
        api: ForgeApi,
        options: OptionsResponseDto?,
        server: String,
    ) {
        val call = ServerCall(api, options, server)
        lastCall = call
        if (!enabled()) return
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) { refresh(call, force = false) }
    }

    /** "Tag List" in the settings: downloads the list again now. */
    fun reload() {
        val call = lastCall
        if (call == null || !ForgeRepository.isConnected.value) {
            ForgeRepository.showToast("Connect to the server to load its tag list")
            return
        }
        ForgeRepository.repositoryScope.launch(Dispatchers.IO) { refresh(call, force = true) }
    }

    private suspend fun refresh(
        call: ServerCall,
        force: Boolean,
    ) = mutex.withLock {
        if (!enabled()) return@withLock
        val options = call.options
        val rules =
            TagInsertRules.of(
                replaceUnderscores = options?.tacReplaceUnderscores?.toBooleanStrictOrNull(),
                keepUnderscores = options?.tacKeepUnderscores,
                escapeBrackets = options?.tacEscapeParentheses?.toBooleanStrictOrNull(),
            )
        val file = options?.tacTagFile?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TAG_FILE
        val extra = options?.tacExtraFile?.trim()?.takeIf { it.isNotEmpty() && it != NO_FILE }
        val extraFirst = options?.tacExtraAddMode != "Insert after"
        if (file == NO_FILE) {
            missing("tagcomplete on the server has no tag file chosen")
            return@withLock
        }

        val saved = readMeta()
        val current =
            saved != null &&
                saved.server == call.server &&
                saved.file == file &&
                saved.extraFile == extra &&
                saved.extraFirst == extraFirst &&
                System.currentTimeMillis() - saved.savedAt < MAX_AGE_MS &&
                listFile?.exists() == true
        if (current && !force) {
            // The same list; only how the server writes tags may have changed.
            if (saved.rules() != rules) writeMeta(saved.withRules(rules))
            _rules.value = rules
            if (_tags.value == null) loadSaved()
            return@withLock
        }

        _status.value = _status.value.copy(source = Source.LOADING)
        try {
            val folder = download(call.api, PATH_FILE)?.trim()?.trimEnd('/')
            if (folder.isNullOrEmpty()) {
                missing("The server has no tagcomplete extension")
                return@withLock
            }
            val dir = dir ?: return@withLock
            dir.mkdirs()
            val newList = File(dir, "tags.csv.part")
            val newExtra = File(dir, "extra.csv.part")
            if (!downloadTo(call.api, "$folder/$file", newList)) {
                failed("The server did not send its tag file $file")
                return@withLock
            }
            val hasExtra = extra != null && downloadTo(call.api, "$folder/$extra", newExtra)
            val list =
                withContext(Dispatchers.Default) {
                    newList.reader().use { main ->
                        if (hasExtra) newExtra.reader().use { TagList.parse(main, it, extraFirst) } else TagList.parse(main)
                    }
                }
            if (list.size == 0) {
                failed("The tag file $file is empty")
                return@withLock
            }
            newList.renameTo(listFile!!)
            if (hasExtra) newExtra.renameTo(extraFile!!) else extraFile?.delete()
            newExtra.delete()
            // The extra file as the server names it (so a missing one is not asked for at every connect); whether it
            // came is whether extra.csv exists.
            val meta =
                Meta(call.server, file, extra, extraFirst, System.currentTimeMillis(), true, emptyList(), true)
                    .withRules(rules)
            writeMeta(meta)
            if (!enabled()) return@withLock
            _tags.value = list
            _rules.value = rules
            _status.value = Status(Source.READY, list.size, file, meta.savedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Tag list not loaded: $e")
            failed(if (ForgeRepository.isConnected.value) "The tag list could not be downloaded" else "Not connected to the server")
        }
    }

    /** The server's file at [path] as text, or null when it has none (404 and other refusals). */
    private suspend fun download(
        api: ForgeApi,
        path: String,
    ): String? {
        val response = api.getServerFile(fileUrl(path))
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        return response.body()?.use { it.string() }
    }

    private suspend fun downloadTo(
        api: ForgeApi,
        path: String,
        target: File,
    ): Boolean {
        val response = api.getServerFile(fileUrl(path))
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return false
        }
        val body = response.body() ?: return false
        body.use { source -> target.outputStream().use { source.byteStream().copyTo(it) } }
        return true
    }

    /** The web UI's address of a file it serves: `file=` and the path, each character a URL may not hold encoded. */
    fun fileUrl(path: String): String {
        val allowed = "-._~!$&'()*+,;=:@/"
        val encoded = StringBuilder("file=")
        for (byte in path.replace('\\', '/').toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (byte >= 0 && (c.isLetterOrDigit() || c in allowed)) {
                encoded.append(c)
            } else {
                encoded.append('%').append(String.format(java.util.Locale.ROOT, "%02X", byte.toInt() and 0xFF))
            }
        }
        return encoded.toString()
    }

    /** The server has no list: a saved one (from this or another server) is still used. */
    private fun missing(reason: String) {
        val list = _tags.value
        _status.value =
            if (list != null) {
                _status.value.copy(source = Source.MISSING, message = reason)
            } else {
                Status(Source.MISSING, message = reason)
            }
    }

    private fun failed(reason: String) {
        _status.value = _status.value.copy(source = if (_tags.value != null) Source.READY else Source.FAILED, message = reason)
    }

    /** The list saved on the phone, read into memory (under [mutex]). */
    private suspend fun loadSaved() {
        val meta = readMeta()
        val file = listFile
        if (meta == null || file == null || !file.exists()) {
            _status.value = Status(Source.NONE)
            return
        }
        _status.value = Status(Source.LOADING, file = meta.file, savedAt = meta.savedAt)
        try {
            val extra = extraFile?.takeIf { meta.extraFile != null && it.exists() }
            val list =
                withContext(Dispatchers.Default) {
                    file.reader().use { main ->
                        if (extra != null) extra.reader().use { TagList.parse(main, it, meta.extraFirst) } else TagList.parse(main)
                    }
                }
            _tags.value = list
            _rules.value = meta.rules()
            _status.value = Status(Source.READY, list.size, meta.file, meta.savedAt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Saved tag list not read: $e")
            _status.value = Status(Source.FAILED, message = "The saved tag list could not be read")
        }
    }

    private fun Meta.withRules(rules: TagInsertRules) =
        copy(
            replaceUnderscores = rules.replaceUnderscores,
            keepUnderscores = rules.keepUnderscores.toList(),
            escapeBrackets = rules.escapeBrackets,
        )

    private fun readMeta(): Meta? =
        try {
            metaFile?.takeIf { it.exists() }?.let { ForgeSettingsManager.gson.fromJson(it.readText(), Meta::class.java) }
        } catch (e: Exception) {
            null
        }

    private fun writeMeta(meta: Meta) {
        metaFile?.writeText(ForgeSettingsManager.gson.toJson(meta))
    }
}
