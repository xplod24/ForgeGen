package com.example.forgegen

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import com.google.gson.Gson
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class VaultHeader(val name: String = "", val metadata: String = "", val thumbnail: String = "", val keyset: String = "")

/** Versioned opaque envelopes. Recovery codes are independent of the vault key and cannot be reconstructed from it. */
object VaultCrypto {
    private val random = SecureRandom()
    private val gson = Gson()
    private const val MAX_HEADER = 1024 * 1024 - 4
    init { StreamingAeadConfig.register() }
    fun randomKey() = ByteArray(32).also(random::nextBytes)
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim())

    fun seal(key: ByteArray, bytes: ByteArray, context: String): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray())
        return nonce + cipher.doFinal(bytes)
    }

    fun open(key: ByteArray, bytes: ByteArray, context: String): ByteArray {
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray())
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    fun digest(file: File): String = file.inputStream().use { digest(it) }.joinToString("") { "%02x".format(it) }
    private fun digest(input: InputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return sha.digest()
            sha.update(buffer, 0, count)
        }
    }

    /** HMAC hides the plaintext checksum and provides stable identities across retries/imports. */
    fun identifier(key: ByteArray, file: File): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(file.inputStream().use { digest(it) }).joinToString("") { "%02x".format(it) }
    }

    fun encrypt(key: ByteArray, id: String, image: File, target: File, name: String, metadata: String, thumbnail: ByteArray) {
        val imageKey = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
        val header = VaultHeader(name, metadata, encode(thumbnail), TinkJsonProtoKeysetFormat.serializeKeyset(imageKey, InsecureSecretKeyAccess.get()))
        val encrypted = seal(key, gson.toJson(header).toByteArray(), "forgegen-vault-header-v1:$id")
        require(encrypted.size <= MAX_HEADER)
        target.outputStream().use { output ->
            val data = DataOutputStream(output)
            data.writeInt(encrypted.size)
            data.write(encrypted)
            val primitive = imageKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            primitive.newEncryptingStream(output, "forgegen-vault-image-v1:$id".toByteArray()).use { cipher ->
                image.inputStream().use { it.copyTo(cipher, 65536) }
            }
        }
    }

    fun header(key: ByteArray, id: String, input: InputStream): VaultHeader {
        val data = DataInputStream(input)
        val length = data.readInt()
        require(length in 28..MAX_HEADER)
        val encrypted = ByteArray(length)
        data.readFully(encrypted)
        return gson.fromJson(String(open(key, encrypted, "forgegen-vault-header-v1:$id")), VaultHeader::class.java)
    }

    fun decrypt(key: ByteArray, id: String, input: InputStream, output: OutputStream): VaultHeader {
        val header = header(key, id, input)
        val imageKey = TinkJsonProtoKeysetFormat.parseKeyset(header.keyset, InsecureSecretKeyAccess.get())
        imageKey.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            .newDecryptingStream(input, "forgegen-vault-image-v1:$id".toByteArray()).use { it.copyTo(output, 65536) }
        return header
    }
}
