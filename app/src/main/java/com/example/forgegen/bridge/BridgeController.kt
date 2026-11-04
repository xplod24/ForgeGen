package com.example.forgegen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class BridgeConnection {
    OFF,
    CHECKING,
    ABSENT,
    UNAVAILABLE,
    PAIRING_REQUIRED,
    CONNECTED,
    AUTH_REQUIRED,
    REVOKED,
    INCOMPATIBLE,
    SERVER_CHANGED,
}

data class BridgeState(
    val base: String = "",
    val enabled: Boolean = false,
    val connection: BridgeConnection = BridgeConnection.OFF,
    val info: BridgeInfo? = null,
    val device: BridgeDevice? = null,
    val capabilities: BridgeCapabilities? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val pending: BridgeIntent? = null,
    val lastOperationId: String? = null,
    val revision: Int = 0,
) {
    val paired get() = connection == BridgeConnection.CONNECTED && device != null
    val fullModels get() = capabilities?.privacyPolicy == "full"
    val pairingOnly get() = (info?.serverMode ?: capabilities?.serverMode) == "pairing_only"
    val administrator get() = paired && device?.adminAccess == true

    fun scoped(scope: String) = paired && scope in device?.scopes.orEmpty()

    fun can(feature: String): Boolean =
        paired &&
            feature in BridgeProtocol.knownFeatures &&
            BridgeProtocol.allowed(capabilities?.features?.get(feature)) &&
            (BridgeProtocol.scopeFor(feature)?.let(::scoped) ?: true) &&
            (!extensionMutation(feature) || extensionFlags.all { capabilities?.requiredFlags?.get(it) == true }) &&
            (feature !in MODEL_ACTIONS || fullModels)

    fun reason(feature: String): String {
        val extension = extensionMutation(feature)
        val flags = capabilities?.requiredFlags.orEmpty()
        return BridgeProtocol.explanation(
            when {
                pairingOnly -> "PAIRING_ONLY"
                !fullModels && feature in MODEL_ACTIONS -> "PRIVACY_POLICY"
                extension && flags["enable_insecure_extension_access"] != true -> "FLAG_REQUIRED"
                extension && extensionFlags.any { flags[it] != true } -> "FORGE_RESTRICTED"
                else -> capabilities?.features?.get(feature)?.reasonCode
            },
        )
    }

    private fun extensionMutation(feature: String) = feature.startsWith("extensions.") && feature != "extensions.list"

    companion object {
        private val extensionFlags = listOf("enable_insecure_extension_access", "extension_access_allowed", "extensions_globally_allowed")
        val MODEL_ACTIONS =
            setOf(
                "models.catalog",
                "models.scan",
                "models.move",
                "models.delete",
                "downloads.create",
                "downloads.retry",
                "civitai.download",
            )
    }
}

/** Owns optional Bridge state only. Never reconnects, pauses or rewrites Forge's clients or queue. */
class BridgeController(
    private val scope: CoroutineScope,
    serverUrls: Flow<String>,
    private val store: BridgeStore,
    private val apiFactory: (String) -> BridgeApi = { BridgeApi(it) },
) {
    private val mutable = MutableStateFlow(BridgeState())
    val state = mutable.asStateFlow()
    private val generation = AtomicInteger()
    private val guard = Any()

    @Volatile private var work: Job? = null
    private var pairing: Pair<BridgeInvitation, String>? = null
    private var pairingName = ""
    private var pairingRequiresAdmin = false

    init {
        scope.launch {
            serverUrls.map { runCatching { BridgeProtocol.base(it) }.getOrDefault("") }.distinctUntilChanged().collectLatest { base ->
                work?.cancel()
                pairing = null
                val run = generation.incrementAndGet()
                val local = withContext(Dispatchers.IO) { runCatching { store.read(base) } }
                mutable.value =
                    BridgeState(
                        base = base,
                        enabled = local.getOrNull()?.enabled == true,
                        pending = local.getOrNull()?.pending,
                        message =
                            if (local.isFailure) {
                                "Private Bridge settings could not be opened. Forget pairing and pair again."
                            } else {
                                null
                            },
                    )
                if (base.isNotEmpty() && local.getOrNull()?.enabled == true) {
                    work = scope.launch { guarded(run) { discover(run, base) } }
                }
            }
        }
    }

    private fun publish(
        run: Int,
        change: (BridgeState) -> BridgeState,
    ) {
        mutable.update { if (generation.get() == run) change(it) else it }
    }

    private fun updateLocal(
        base: String,
        change: (BridgeSession) -> BridgeSession,
    ): BridgeSession =
        synchronized(guard) {
            change(store.read(base)).also { store.write(base, it) }
        }

    private suspend fun guarded(
        run: Int,
        block: suspend () -> Unit,
    ) {
        publish(run) { it.copy(busy = true, message = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleError(run, e)
        } finally {
            publish(run) { it.copy(busy = false) }
        }
    }

    private fun handleError(
        run: Int,
        e: Exception,
    ) {
        val failure = e as? BridgeFailure
        if (failure?.code == "TOKEN_REVOKED" && run == generation.get()) {
            val base = state.value.base
            // A locked or unavailable Keystore must not let an optional Bridge failure escape.
            runCatching { updateLocal(base) { it.copy(token = "", device = null) } }
        }
        publish(run) { current ->
            val connection =
                when {
                    failure?.code == "TOKEN_REVOKED" -> BridgeConnection.REVOKED
                    failure?.status == 401 -> BridgeConnection.AUTH_REQUIRED
                    failure?.code == "ABSENT" -> BridgeConnection.ABSENT
                    failure?.code == "INCOMPATIBLE_PROTOCOL" -> BridgeConnection.INCOMPATIBLE
                    failure?.code in setOf("NETWORK", "REDIRECT_BLOCKED", "INVALID_RESPONSE") -> BridgeConnection.UNAVAILABLE
                    current.connection == BridgeConnection.CHECKING -> BridgeConnection.UNAVAILABLE
                    else -> current.connection
                }
            current.copy(
                connection = connection,
                capabilities = if (connection == BridgeConnection.CONNECTED) current.capabilities else null,
                device = if (connection == BridgeConnection.REVOKED) null else current.device,
                message =
                    when (e) {
                        is BridgeFailure -> e.message
                        is IllegalArgumentException -> e.message?.take(200) ?: "Invalid Bridge data."
                        else -> "Bridge could not complete this request. Your Forge profile is kept."
                    },
            )
        }
    }

    private suspend fun discover(
        run: Int,
        base: String,
    ) = withContext(Dispatchers.IO) {
        publish(run) { it.copy(connection = BridgeConnection.CHECKING) }
        val api = apiFactory(base)
        val info =
            try {
                api.info()
            } catch (e: IllegalArgumentException) {
                throw BridgeFailure(426, "INCOMPATIBLE_PROTOCOL")
            }
        if (generation.get() != run) return@withContext
        val local = store.read(base)
        publish(run) { it.copy(info = info, pending = local.pending, lastOperationId = local.operationIds.lastOrNull()) }
        if (local.serverId.isNotEmpty() && local.serverId != info.serverId) {
            publish(run) {
                it.copy(
                    connection = BridgeConnection.SERVER_CHANGED,
                    message = "The server identity changed. Verify the server, then forget pairing and pair again.",
                )
            }
            return@withContext
        }
        if (local.token.isBlank()) {
            publish(run) { it.copy(connection = BridgeConnection.PAIRING_REQUIRED) }
            return@withContext
        }
        val me = BridgeApi.decode<BridgeDevice>(api.request("GET", "devices/me", local.token))
        require(BridgeProtocol.uuid(me.id) && me.revokedAt == null && me.id == local.device?.id) { "Unexpected Bridge device identity." }
        val capabilities = BridgeApi.decode<BridgeCapabilities>(api.request("GET", "capabilities", local.token))
        require(capabilities.apiVersion?.major == 1L && capabilities.apiVersion.minor == 0L && capabilities.features != null) {
            "Unsupported Bridge capabilities."
        }
        publish(
            run,
        ) { it.copy(connection = BridgeConnection.CONNECTED, device = me, capabilities = capabilities, revision = it.revision + 1) }
    }

    fun refresh() {
        val snapshot = state.value
        if (!snapshot.enabled || snapshot.base.isEmpty() || snapshot.busy) return
        if (!mutable.compareAndSet(snapshot, snapshot.copy(busy = true, message = null))) return
        val run = generation.get()
        work = scope.launch { guarded(run) { discover(run, snapshot.base) } }
    }

    fun setEnabled(enabled: Boolean) {
        val base = state.value.base
        if (base.isEmpty()) return
        work?.cancel()
        pairing = null
        val run = generation.incrementAndGet()
        work =
            scope.launch {
                guarded(run) {
                    val local = withContext(Dispatchers.IO) { updateLocal(base) { it.copy(enabled = enabled) } }
                    publish(run) { BridgeState(base = base, enabled = enabled, pending = local.pending, busy = true) }
                    if (enabled) discover(run, base)
                }
            }
    }

    fun forget() {
        val base = state.value.base
        work?.cancel()
        pairing = null
        val run = generation.incrementAndGet()
        work =
            scope.launch {
                guarded(run) {
                    withContext(Dispatchers.IO) { store.forget(base) }
                    publish(run) { BridgeState(base = base) }
                }
            }
    }

    fun pair(
        rawInvitation: String,
        deviceName: String,
        requireAdminAccess: Boolean = false,
    ) {
        val snapshot = state.value
        if (!snapshot.enabled || snapshot.busy) return
        if (!mutable.compareAndSet(snapshot, snapshot.copy(busy = true, message = null))) return
        val run = generation.get()
        work =
            scope.launch {
                guarded(run) {
                    withContext(Dispatchers.IO) {
                        val invite = BridgeProtocol.invitation(rawInvitation, snapshot.base)
                        require(deviceName.trim().length in 1..100) { "Use a device name between 1 and 100 characters." }
                        val old = pairing
                        require(
                            old?.first?.invitationId != invite.invitationId ||
                                old.first.code != invite.code ||
                                (pairingName == deviceName.trim() && pairingRequiresAdmin == requireAdminAccess),
                        ) {
                            "Use the same device name and access mode when retrying this invitation, or create a new invitation."
                        }
                        val key =
                            if (old?.first?.invitationId == invite.invitationId && old.first.code == invite.code) {
                                old.second
                            } else {
                                UUID.randomUUID().toString()
                            }
                        pairing = invite to key
                        pairingName = deviceName.trim()
                        pairingRequiresAdmin = requireAdminAccess
                        val api = apiFactory(snapshot.base)
                        val info = api.info()
                        if (requireAdminAccess && info.adminPairingSupported != true) throw BridgeFailure(0, "ADMIN_PAIRING_UNSUPPORTED")
                        require(info.serverId == invite.serverId) { "The invitation does not match this server identity." }
                        val local = store.read(snapshot.base)
                        require(
                            local.serverId.isEmpty() || local.serverId == invite.serverId,
                        ) { "Forget the old pairing before pairing a changed server." }
                        val body =
                            JsonObject().apply {
                                addProperty("invitation_id", invite.invitationId)
                                addProperty("code", invite.code)
                                addProperty("device_name", deviceName.trim())
                                if (requireAdminAccess) addProperty("require_admin_access", true)
                            }
                        val pair = BridgeApi.decode<BridgePairResponse>(api.request("POST", "pairing/exchange", body = body, key = key))
                        require(pair.tokenType == "Bearer" && !pair.accessToken.isNullOrBlank() && BridgeProtocol.uuid(pair.device?.id)) {
                            "Invalid pairing response."
                        }
                        val me = BridgeApi.decode<BridgeDevice>(api.request("GET", "devices/me", pair.accessToken!!))
                        require(me.id == pair.device?.id && me.revokedAt == null) { "The device could not be verified." }
                        if (requireAdminAccess && me.adminAccess != true) throw BridgeFailure(403, "ADMIN_ACCESS_REQUIRED")
                        updateLocal(snapshot.base) { it.copy(serverId = invite.serverId, token = pair.accessToken, device = me) }
                        pairing = null
                        discover(run, snapshot.base)
                    }
                }
            }
    }

    suspend fun read(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val snapshot = state.value
        check(snapshot.paired) { "Pair this phone to use Bridge tools." }
        val run = generation.get()
        return try {
            withContext(Dispatchers.IO) {
                val local = store.read(snapshot.base)
                check(local.serverId == snapshot.info?.serverId && local.token.isNotEmpty())
                apiFactory(snapshot.base).request("GET", path, local.token, query = query)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleError(run, e)
            throw e
        }
    }

    fun perform(
        title: String,
        feature: String,
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
    ) {
        val snapshot = state.value
        if (snapshot.busy || !snapshot.paired || snapshot.pending != null) return
        if (!permitted(snapshot, feature, path)) {
            mutable.update { it.copy(message = snapshot.reason(feature)) }
            return
        }
        if (!mutable.compareAndSet(snapshot, snapshot.copy(busy = true, message = null))) return
        val run = generation.get()
        work =
            scope.launch {
                guarded(run) {
                    withContext(Dispatchers.IO) {
                        val local = store.read(snapshot.base)
                        BridgeProtocol.validateMutation(method, path, body)
                        val intention =
                            BridgeIntent(
                                UUID.randomUUID().toString(),
                                method,
                                path,
                                body?.toString(),
                                query,
                                title,
                                feature,
                                local.device?.id.orEmpty(),
                                local.serverId,
                            )
                        updateLocal(snapshot.base) {
                            check(it.pending == null)
                            it.copy(pending = intention)
                        }
                        publish(run) { it.copy(pending = intention) }
                        execute(run, snapshot, intention)
                    }
                }
            }
    }

    private fun permitted(
        snapshot: BridgeState,
        feature: String,
        path: String,
    ): Boolean =
        when (feature) {
            "operations.cancel" ->
                snapshot.can(feature) &&
                    (!path.startsWith("downloads/") || snapshot.fullModels)
            "downloads.cancel" -> snapshot.can(feature) && snapshot.fullModels
            else -> snapshot.can(feature)
        }

    private suspend fun execute(
        run: Int,
        snapshot: BridgeState,
        intention: BridgeIntent,
        recovering: Boolean = false,
    ) {
        val local = store.read(snapshot.base)
        require(local.device?.id == intention.deviceId && local.serverId == intention.serverId) {
            "This request belongs to another paired identity. Review server operations before discarding it."
        }
        val result =
            try {
                apiFactory(snapshot.base).request(
                    intention.method,
                    intention.path,
                    local.token,
                    intention.body?.let { JsonParser.parseString(it).asJsonObject },
                    intention.query,
                    intention.key,
                )
            } catch (e: BridgeFailure) {
                if (!recovering && !e.uncertain && e.status != 429) {
                    updateLocal(snapshot.base) { it.copy(pending = null) }
                    publish(run) { it.copy(pending = null) }
                }
                throw e
            }
        val revocation = intention.path.startsWith("devices/") && intention.method == "DELETE"
        val id = result.text("operation_id").takeIf(BridgeProtocol::uuid)
        if ((!revocation && (id == null || result.text("status_url").isEmpty())) ||
            (revocation && (result.text("id") != intention.path.substringAfter('/') || result.text("revoked_at").isEmpty()))
        ) {
            throw BridgeFailure(202, "INVALID_RESPONSE", true)
        }
        updateLocal(snapshot.base) {
            it.copy(
                pending = null,
                operationIds = if (id == null) it.operationIds else (it.operationIds + id).distinct().takeLast(100),
            )
        }
        publish(run) {
            it.copy(
                pending = null,
                lastOperationId = id ?: it.lastOperationId,
                revision = it.revision + 1,
                message = if (id == null) "Request completed." else "Request accepted. Track it in Operations.",
            )
        }
        if (intention.path == "devices/${local.device?.id}" && intention.method == "DELETE") {
            updateLocal(snapshot.base) { it.copy(token = "", device = null) }
            publish(run) { it.copy(connection = BridgeConnection.REVOKED, device = null, capabilities = null) }
        }
    }

    fun retryPending() {
        val snapshot = state.value
        val intent = snapshot.pending ?: return
        if (snapshot.busy || !snapshot.paired || !permitted(snapshot, intent.feature, intent.path)) return
        if (!mutable.compareAndSet(snapshot, snapshot.copy(busy = true, message = null))) return
        val run = generation.get()
        work = scope.launch { guarded(run) { withContext(Dispatchers.IO) { execute(run, snapshot, intent, recovering = true) } } }
    }

    fun dismissPending() {
        val snapshot = state.value
        if (snapshot.busy) return
        val run = generation.get()
        work =
            scope.launch {
                guarded(run) {
                    withContext(Dispatchers.IO) { updateLocal(snapshot.base) { it.copy(pending = null) } }
                    publish(run) { it.copy(pending = null) }
                }
            }
    }
}
