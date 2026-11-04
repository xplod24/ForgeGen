package com.example.forgegen

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BridgeIntent(
    val key: String,
    val method: String,
    val path: String,
    val body: String?,
    val query: Map<String, String>,
    val title: String,
    val feature: String,
    val deviceId: String,
    val serverId: String,
) {
    override fun toString() = "BridgeIntent(redacted)"
}

data class BridgeSession(
    val enabled: Boolean = false,
    val serverId: String = "",
    val token: String = "",
    val device: BridgeDevice? = null,
    val pending: BridgeIntent? = null,
    val operationIds: List<String> = emptyList(),
) {
    override fun toString() = "BridgeSession(redacted)"
}

interface BridgeStore {
    fun read(base: String): BridgeSession

    fun write(
        base: String,
        value: BridgeSession,
    )

    fun forget(base: String)
}

/** Credentials and uncertain intentions never enter AppConfig, backups, logs or saved screen state. */
class AndroidBridgeStore(
    context: Context,
) : BridgeStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "forgegen-bridge")

    private fun file(base: String) =
        File(
            directory,
            MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).joinToString("") { "%02x".format(it) } + ".enc",
        )

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec
                    .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    @Synchronized override fun read(base: String): BridgeSession {
        val file = file(base)
        if (!file.exists()) return BridgeSession()
        require(file.length() in 28..262_144) { "Invalid private Bridge settings." }
        val data = AtomicFile(file).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(file.name.toByteArray())
        return BridgeProtocol.gson.fromJson(String(cipher.doFinal(data, 12, data.size - 12)), BridgeSession::class.java)
    }

    @Synchronized override fun write(
        base: String,
        value: BridgeSession,
    ) {
        check(directory.isDirectory || directory.mkdirs())
        val file = file(base)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(file.name.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(BridgeProtocol.gson.toJson(value).toByteArray())
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    @Synchronized override fun forget(base: String) {
        AtomicFile(file(base)).delete()
    }

    companion object {
        private const val ALIAS = "forgegen-bridge-device-v1"
    }
}
