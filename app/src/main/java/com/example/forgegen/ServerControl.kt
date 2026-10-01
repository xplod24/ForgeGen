package com.example.forgegen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/* ============================================================================
 * THE SERVER'S QUEUE AND THE SERVER ITSELF (3.3.0)
 * Each job goes to the server with an id of the app's own (force_task_id), so the app can ask whether the server is
 * doing it or doing other jobs first (from its web UI or another app): their progress used to show as the app's.
 * The server page reads what Forge tells about itself: its extensions and how it was started (/sdapi/v1/cmd-flags,
 * which also tells whether it can be restarted from the phone). Since 3.6.0 its report (/internal/sysinfo, slow: Forge
 * lists its Python packages for it) is read only on "Check Now", together with the VRAM's counters from
 * /sdapi/v1/memory, and the last check is kept for each server (ServerCheck).
 * ============================================================================ */

object ServerTasks {
    private const val PREFIX = "task(forgegen-"
    private const val ID_LENGTH = 12
    private val IN_QUEUE = Regex("""In queue:\s*(\d+)\s*/\s*(\d+)""")

    /** The id the server knows [jobId]'s job by. */
    fun idFor(jobId: String): String = PREFIX + jobId.filter { it.isLetterOrDigit() }.take(ID_LENGTH) + ")"

    /**
     * How many jobs the server does before [taskId]: those waiting before it in [pending] (the server's order) and
     * the one it is doing now. Null when [taskId] is not among them.
     */
    fun jobsAhead(
        taskId: String,
        pending: List<String>,
    ): Int? = pending.indexOf(taskId).takeIf { it >= 0 }?.let { it + 1 }

    /** The same from /internal/progress's text, "In queue: 2/3" (its place among the waiting ones); null without it. */
    fun jobsAhead(textinfo: String?): Int? =
        IN_QUEUE
            .find(textinfo.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /** "1 other job goes first", "2 other jobs go first". */
    fun aheadText(ahead: Int): String = if (ahead == 1) "1 other job goes first" else "$ahead other jobs go first"
}

/** An extension of the server; [purpose] says what the app uses it for (null: the app does not use it). */
data class ServerExtension(
    val name: String,
    val enabled: Boolean,
    val version: String,
    val purpose: String?,
)

/** What the server page reads by itself (the light part); each part stays null until the server told it. */
data class ServerInfo(
    val extensions: List<ServerExtension>? = null,
    // Whether Forge was started with --api-server-stop; null while not known.
    val canRestart: Boolean? = null,
)

/** One of the last errors Forge kept: its message and where it happened ("processing.py, line 1012"). */
data class ServerError(
    val message: String,
    val place: String?,
)

/**
 * What "Check Now" found (3.6.0), kept for each server with its time; a part Forge did not tell is null. Gson keeps it
 * in app_settings ("server_check:<server>").
 */
data class ServerCheck(
    val checkedAt: Long = 0L,
    // Forge's version (its git tag or commit) and how long it took to start, in seconds.
    val version: String? = null,
    val startupSeconds: Double? = null,
    val gpu: String? = null,
    val vramTotalGb: Double? = null,
    // "AMD64 Family 25 Model 33 · 8 cores, 16 threads".
    val cpu: String? = null,
    // The whole computer's memory (the meters show Forge's own share).
    val ramUsedGb: Double? = null,
    val ramTotalGb: Double? = null,
    // "Windows 11 · Python 3.11.9 · torch 2.3.1+cu121".
    val system: String? = null,
    // Since Forge started: how often the card ran out of memory, had to free memory to go on, and the most it held.
    val outOfVram: Int? = null,
    val vramShort: Int? = null,
    val vramPeakGb: Double? = null,
    // The last errors Forge kept (up to 5), newest first.
    val errors: List<ServerError> = emptyList(),
    val launchFlags: String? = null,
    // "torch 2.3.1+cu121 · xformers 0.0.27 · ...", and every package with its version.
    val keyPackages: String? = null,
    val packages: List<String> = emptyList(),
    val turnedOffExtensions: List<String> = emptyList(),
    // The whole report as Forge writes it, for "Share Server Report".
    val report: String? = null,
)

object ServerInfoParser {
    /** What the app uses: the gallery (Infinite Image Browsing), tag suggestions and "Restore Last". */
    private val PURPOSES =
        listOf(
            "infinite-image-browsing" to "The gallery",
            "tagcomplete" to "Tag suggestions",
            "prompt-all-in-one" to "Restore Last (prompt history)",
        )

    fun purposeOf(name: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        return PURPOSES.firstOrNull { (part, _) -> part in lower }?.second
    }

    /** The server's extensions: the ones the app uses first, then the others by name. */
    fun extensions(list: List<ServerExtensionDto>): List<ServerExtension> =
        list
            .mapNotNull { dto ->
                val name = dto.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ServerExtension(
                    name = name,
                    enabled = dto.enabled,
                    version = dto.version?.takeIf { it.isNotBlank() } ?: dto.branch.orEmpty(),
                    purpose = purposeOf(name),
                )
            }.sortedWith(compareBy<ServerExtension>({ it.purpose == null }, { it.name.lowercase(Locale.ROOT) }))

    /** Whether Forge can be restarted from the phone, from /sdapi/v1/cmd-flags; null when it does not say. */
    fun canRestart(flags: Map<String, Any?>): Boolean? = flags["api_server_stop"] as? Boolean

    // The packages that say most about how Forge runs, in this order.
    private val KEY_PACKAGES =
        listOf("torch", "xformers", "sageattention", "flash-attn", "gradio", "safetensors", "transformers", "diffusers")
    private const val MAX_ERRORS = 5
    private const val MAX_ERROR_TEXT = 300
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    /**
     * What "Check Now" found at [at]: Forge's report [text] (JSON, /internal/sysinfo) and its VRAM counters [memory]
     * (/sdapi/v1/memory); nulls for what they do not say.
     */
    fun check(
        text: String,
        memory: MemoryResponseDto?,
        at: Long,
    ): ServerCheck {
        val cuda = memory?.cuda
        val fromMemory =
            ServerCheck(
                checkedAt = at,
                vramTotalGb =
                    cuda
                        ?.system
                        ?.total
                        ?.takeIf { it > 0 }
                        ?.let { it / GIB },
                outOfVram = cuda?.events?.oom,
                vramShort = cuda?.events?.retries,
                vramPeakGb =
                    cuda
                        ?.reserved
                        ?.peak
                        ?.takeIf { it > 0 }
                        ?.let { it / GIB },
                report = text,
            )
        val root =
            try {
                JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                null
            } ?: return fromMemory
        val torch = root.get("Torch env info")?.takeIf { it.isJsonObject }?.asJsonObject
        val os = torch?.text("os") ?: root.text("Platform")?.substringBefore('-')
        val system =
            listOfNotNull(
                os,
                root.text("Python")?.let { "Python $it" },
                torch?.text("torch_version")?.let { "torch $it" },
            ).joinToString(" · ").ifEmpty { null }
        val ram = root.get("RAM")?.takeIf { it.isJsonObject }?.asJsonObject
        val packages =
            root
                .get("Packages")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { it.textOrNull() }
                .orEmpty()
        return fromMemory.copy(
            version = root.text("Version"),
            startupSeconds =
                root
                    .get("Startup")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.number("total"),
            gpu = torch?.let { gpuOf(it.get("nvidia_gpu_models")) },
            cpu = cpuOf(root.get("CPU")),
            ramUsedGb = ram?.text("used")?.let { gbOf(it) },
            ramTotalGb = ram?.text("total")?.let { gbOf(it) },
            system = system,
            errors = errorsOf(root.get("Exceptions")),
            launchFlags =
                root
                    .get("Commandline")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { it.textOrNull() }
                    ?.drop(1) // the script itself
                    ?.joinToString(" ")
                    ?.ifEmpty { null },
            keyPackages = keyPackages(packages),
            packages = packages,
            turnedOffExtensions =
                root
                    .get("Inactive extensions")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.text("name") }
                    .orEmpty(),
        )
    }

    /** "torch 2.3.1+cu121 · xformers 0.0.27" from pip's "name==version" lines; null when none of them is there. */
    fun keyPackages(packages: List<String>): String? {
        val versions =
            packages
                .mapNotNull { line ->
                    val name = line.substringBefore("==", "").trim()
                    if (name.isEmpty()) null else name.lowercase(Locale.ROOT) to line.substringAfter("==").trim()
                }.toMap()
        return KEY_PACKAGES.mapNotNull { name -> versions[name]?.let { "$name $it" } }.joinToString(" · ").ifEmpty { null }
    }

    /** "AMD64 Family 25 Model 33 · 8 cores, 16 threads"; the model alone or the cores alone when that is all. */
    private fun cpuOf(element: JsonElement?): String? {
        val cpu = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val cores = cpu.number("count physical")?.toInt()
        val threads = cpu.number("count logical")?.toInt()
        val count =
            when {
                cores != null && threads != null && threads != cores -> "$cores cores, $threads threads"
                cores != null -> "$cores cores"
                threads != null -> "$threads threads"
                else -> null
            }
        return listOfNotNull(cpu.text("model"), count).joinToString(" · ").ifEmpty { null }
    }

    /** Forge writes the computer's memory as "32GB" (pip's pretty_bytes, whole units of 1024). */
    fun gbOf(text: String): Double? {
        val match = Regex("""^\s*([0-9.]+)\s*([KMGTP]?)B\s*$""").find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val power = "KMGTP".indexOf(match.groupValues[2]) + 1 // "" is bytes
        return value * Math.pow(1024.0, power.toDouble()) / GIB
    }

    /** Forge's last errors (newest first): the message's first line and the file and line where it was raised. */
    private fun errorsOf(element: JsonElement?): List<ServerError> =
        element
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { item ->
                val error = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val message =
                    error
                        .text("exception")
                        ?.lineSequence()
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                        ?.take(MAX_ERROR_TEXT) ?: return@mapNotNull null
                // The last frame: "C:\forge\modules\processing.py, line 1012, process_images" -> "processing.py, line 1012".
                val frame =
                    error
                        .get("traceback")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.lastOrNull()
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.firstOrNull()
                        ?.textOrNull()
                val place =
                    frame?.let {
                        val parts = it.split(", ")
                        val file = parts.first().substringAfterLast('/').substringAfterLast('\\')
                        listOfNotNull(file, parts.getOrNull(1)?.takeIf { p -> p.startsWith("line ") }).joinToString(", ")
                    }
                ServerError(message, place)
            }?.take(MAX_ERRORS)
            .orEmpty()

    /** "GPU 0: NVIDIA GeForce RTX 4070 (UUID: ...)" (text or lines) -> "NVIDIA GeForce RTX 4070". */
    private fun gpuOf(element: JsonElement?): String? {
        val first =
            when {
                element == null || element.isJsonNull -> null
                element.isJsonArray -> element.asJsonArray.firstOrNull { it.isJsonPrimitive }?.asString
                element.isJsonPrimitive -> element.asString.lineSequence().firstOrNull()
                else -> null
            }?.trim()
        return first
            ?.substringAfter(':', first)
            ?.substringBefore(" (UUID")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "None" }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.textOrNull()

    private fun JsonElement.textOrNull(): String? =
        takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "None" }

    private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
}
