package com.example.forgegen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader

class VaultCryptoTest {
    private fun rejected(action: () -> Unit) {
        try {
            action()
            fail("Unauthenticated data accepted")
        } catch (expected: Exception) {
            // Authentication must fail.
        }
    }

    @Test fun recoveryRequiresIndependentCode() {
        val root = VaultCrypto.randomKey()
        val code = VaultCrypto.randomKey()
        val envelope = VaultCrypto.seal(code, root, "forgegen-vault-recovery-v1")
        assertArrayEquals(root, VaultCrypto.open(code, envelope, "forgegen-vault-recovery-v1"))
        rejected { VaultCrypto.open(root, envelope, "forgegen-vault-recovery-v1") }
        rejected { VaultCrypto.open(code, envelope, "wrong-context") }
        assertEquals(43, VaultCrypto.encode(code).length)
    }

    @Test fun largeImageRoundTripAndTampering() {
        val source = File.createTempFile("vault-image", ".png")
        val encrypted = File.createTempFile("vault-image", ".enc")
        try {
            val bytes = ByteArray(2 * 1024 * 1024 + 91).also(java.security.SecureRandom()::nextBytes)
            source.writeBytes(bytes)
            val key = VaultCrypto.randomKey()
            val id = VaultCrypto.identifier(key, source)
            assertEquals(id, VaultCrypto.identifier(key, source))
            assertNotEquals(id, VaultCrypto.identifier(VaultCrypto.randomKey(), source))
            VaultCrypto.encrypt(key, id, source, encrypted, "original.png", "Unchanged prompt: Żółć", byteArrayOf(1, 2, 3))
            val output = ByteArrayOutputStream()
            val header = encrypted.inputStream().use { VaultCrypto.decrypt(key, id, it, output) }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals("Unchanged prompt: Żółć", header.metadata)
            assertArrayEquals(byteArrayOf(1, 2, 3), VaultCrypto.decode(header.thumbnail))
            rejected { encrypted.inputStream().use { VaultCrypto.header(key, "b".repeat(64), it) } }
            val payload = encrypted.readBytes()
            encrypted.writeBytes(payload.copyOf(payload.size - 1))
            rejected { encrypted.inputStream().use { VaultCrypto.decrypt(key, id, it, ByteArrayOutputStream()) } }
            payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
            encrypted.writeBytes(payload)
            rejected { encrypted.inputStream().use { VaultCrypto.decrypt(key, id, it, ByteArrayOutputStream()) } }
        } finally {
            source.delete()
            encrypted.delete()
        }
    }

    @Test fun metadataPreservesUnicodeAndImageStreaming() {
        val info = "{\"infotexts\":[\"Żółć without filtering\"]}"
        val body =
            "{\"images\":[\"AQID\"],\"info\":" +
                com.google.gson
                    .Gson()
                    .toJson(info) + "}"
        var captured = ""
        assertEquals(
            1,
            Txt2ImgImages.readWithInfo(StringReader(body), { _, input -> assertArrayEquals(byteArrayOf(1, 2, 3), input.readBytes()) }, {
                captured =
                    it
            }),
        )
        assertEquals(info, captured)
    }
}
