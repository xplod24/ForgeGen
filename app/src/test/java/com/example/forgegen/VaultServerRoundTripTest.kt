package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Exercise the real Android envelope implementation against the shipped opaque Python server. */
class VaultServerRoundTripTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun encryptedUploadRetryReplacementPhoneRecoveryAndTrash() {
        val data = temp.newFolder("data")
        val token = VaultCrypto.encode(VaultCrypto.randomKey())
        val tokenHash =
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(token.toByteArray())
                .joinToString("") { "%02x".format(it) }
        File(data, "server.json").writeText(Gson().toJson(mapOf("token_sha256" to tokenHash)))
        val sourceDir = listOf(File("server/remote-vault"), File("../server/remote-vault")).first { File(it, "server.py").exists() }
        val python = System.getenv("VAULT_PYTHON") ?: "python3"
        val script =
            "import sys; from server import Store,handler; from http.server import ThreadingHTTPServer; " +
                "s=ThreadingHTTPServer(('127.0.0.1',0),handler(Store(sys.argv[1]))); " +
                "print(s.server_port,flush=True); s.serve_forever()"
        val process =
            ProcessBuilder(python, "-u", "-c", script, data.absolutePath)
                .directory(sourceDir)
                .redirectError(File(temp.root, "server-errors.log"))
                .start()
        try {
            val port =
                process.inputStream
                    .bufferedReader()
                    .readLine()
                    .toInt()

            fun request(
                method: String,
                path: String,
                bytes: ByteArray? = null,
                authorized: Boolean = true,
            ): Pair<Int, ByteArray> {
                val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = method
                if (authorized) conn.setRequestProperty("Authorization", "Bearer $token")
                if (bytes != null) {
                    conn.setRequestProperty(
                        "X-Content-SHA256",
                        java.security.MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(bytes)
                            .joinToString("") {
                                "%02x".format(it)
                            },
                    )
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                return try {
                    conn.responseCode to
                        (
                            if (conn.responseCode <
                                400
                            ) {
                                conn.inputStream
                            } else {
                                conn.errorStream
                            }
                        ).use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
            assertEquals(401, request("GET", "/v1/objects", authorized = false).first)
            val root = VaultCrypto.randomKey()
            val code = VaultCrypto.randomKey()
            val envelope = VaultCrypto.encode(VaultCrypto.seal(code, root, "forgegen-vault-recovery-v1"))
            val recovery = Gson().toJson(mapOf("envelope" to envelope)).toByteArray()
            assertEquals(201, request("PUT", "/v1/recovery", recovery).first)
            assertEquals(409, request("PUT", "/v1/recovery", recovery).first)
            val input =
                temp
                    .newFile(
                        "original.png",
                    ).apply { writeBytes(ByteArray(2 * 1024 * 1024 + 17).also(java.security.SecureRandom()::nextBytes)) }
            val encrypted = temp.newFile("ciphertext")
            val id = VaultCrypto.identifier(root, input)
            VaultCrypto.encrypt(root, id, input, encrypted, "unchanged.png", "Żółć: original unmodified metadata", byteArrayOf(9, 8, 7))
            val bytes = encrypted.readBytes()
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("original unmodified metadata"))
            assertEquals(201, request("PUT", "/v1/objects/$id", bytes).first)
            assertEquals(200, request("PUT", "/v1/objects/$id", bytes).first)
            val recovered =
                VaultCrypto.open(
                    code,
                    VaultCrypto.decode(
                        Gson().fromJson(String(request("GET", "/v1/recovery").second), Map::class.java)["envelope"] as String,
                    ),
                    "forgegen-vault-recovery-v1",
                )
            assertArrayEquals(root, recovered)
            val header = VaultCrypto.header(recovered, id, request("GET", "/v1/objects/$id/header").second.inputStream())
            assertEquals("Żółć: original unmodified metadata", header.metadata)
            val output = ByteArrayOutputStream()
            VaultCrypto.decrypt(recovered, id, request("GET", "/v1/objects/$id").second.inputStream(), output)
            assertArrayEquals(input.readBytes(), output.toByteArray())
            assertEquals(200, request("DELETE", "/v1/objects/$id").first)
            assertTrue(String(request("GET", "/v1/objects").second).contains("expires"))
            assertEquals(409, request("PUT", "/v1/objects/$id", bytes).first)
            assertEquals(200, request("POST", "/v1/objects/$id/restore", ByteArray(0)).first)
        } finally {
            process.destroy()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun optOutDoesNotNeedConfigurationKeysOrFiles() {
        assertFalse(RemoteVault.enabled.value)
        assertFalse(RemoteVault.enqueue(File("missing-image.png")))
        RemoteVault.generated(File("missing-image.png"), "Unchanged prompt")
        assertTrue(kotlinx.coroutines.runBlocking { RemoteVault.transfer() })
    }
}
