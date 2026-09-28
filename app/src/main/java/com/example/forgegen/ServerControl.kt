package com.example.forgegen

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Locale

/* ============================================================================
 * THE SERVER'S QUEUE AND THE SERVER ITSELF (3.3.0)
 * Each job goes to the server with an id of the app's own (force_task_id), so the app can ask whether the server is
 * doing it or doing other jobs first (from its web UI or another app): their progress used to show as the app's.
 * The server page reads what Forge tells about itself: its report (/internal/sysinfo), its extensions and how it was
 * started (/sdapi/v1/cmd-flags, which also tells whether it can be restarted from the phone).
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

/** What the server page shows; each part stays null until the server told it. */
data class ServerInfo(
    // Forge's version (its git tag or commit).
    val version: String? = null,
    // "Windows 11 · Python 3.11.9 · torch 2.3.1+cu121".
    val system: String? = null,
    val gpu: String? = null,
    // The whole report as Forge writes it, for "Share Server Report".
    val report: String? = null,
    // Why the report could not be read (e.g. a server without its web UI); null while it loads or when it was read.
    val reportProblem: String? = null,
    val extensions: List<ServerExtension>? = null,
    // Whether Forge was started with --api-server-stop; null while not known.
    val canRestart: Boolean? = null,
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

    /** Version, system and GPU from Forge's report (JSON); nulls for what it does not say. */
    fun fromReport(text: String): ServerInfo {
        val root =
            try {
                JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: Exception) {
                null
            } ?: return ServerInfo(report = text)
        val torch = root.get("Torch env info")?.takeIf { it.isJsonObject }?.asJsonObject
        val os = torch?.text("os") ?: root.text("Platform")?.substringBefore('-')
        val system =
            listOfNotNull(
                os,
                root.text("Python")?.let { "Python $it" },
                torch?.text("torch_version")?.let { "torch $it" },
            ).joinToString(" · ").ifEmpty { null }
        return ServerInfo(
            version = root.text("Version"),
            system = system,
            gpu = torch?.let { gpuOf(it.get("nvidia_gpu_models")) },
            report = text,
        )
    }

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

    private fun JsonObject.text(key: String): String? =
        get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "None" }
}
