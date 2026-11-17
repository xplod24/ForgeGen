package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.net.InetSocketAddress
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory

class HomelabCaTest {
    private val fixture = "abc".toByteArray()
    private val fixtureSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun bundledRootIsTheCaAcceptedByTheInstaller() {
        val bytes = File("src/main/res/raw/forgegen_homelab_ca.der").readBytes()
        val ca = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
        assertEquals(ca.subjectX500Principal, ca.issuerX500Principal)
        org.junit.Assert.assertTrue(ca.basicConstraints >= 0)
        ca.verify(ca.publicKey)

        withServer({ it.sendFixture(bytes) }) { origin ->
            assertArrayEquals(bytes, HomelabCa.download(origin, origin))
        }
    }

    @Test
    fun bundledRootIsOnlyTrustedForTheTwoExactHomelabNames() {
        val xml =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/xml/forge_network_security.xml"))
        val anchors = xml.getElementsByTagName("certificates")
        val bundled =
            (0 until anchors.length)
                .map { anchors.item(it) as Element }
                .single { it.getAttribute("src") == "@raw/forgegen_homelab_ca" }
        val hostConfig = bundled.parentNode.parentNode as Element
        assertEquals("domain-config", hostConfig.tagName)

        val domains = hostConfig.getElementsByTagName("domain")
        val names =
            (0 until domains.length).map { index ->
                val domain = domains.item(index) as Element
                assertEquals("false", domain.getAttribute("includeSubdomains"))
                domain.textContent.trim()
            }
        assertEquals(listOf("diffusion.local", "diffusion.home.arpa"), names)
    }

    @Test
    fun knownHttpsHostsUseOnlyTheFixedPublicCertificatePath() {
        assertEquals(
            "http://diffusion.local/.well-known/forgegen/ca.cer".toHttpUrl(),
            HomelabCa.certificateUrl(" https://Diffusion.Local:7860/sdapi?example=1#example "),
        )
        assertEquals(
            "http://diffusion.home.arpa/.well-known/forgegen/ca.cer".toHttpUrl(),
            HomelabCa.certificateUrl("diffusion.home.arpa"),
        )
    }

    @Test
    fun otherAddressesAndEmbeddedCredentialsDoNotOfferTheInstallAction() {
        assertNull(HomelabCa.certificateUrl("https://diffusion.local.example.test"))
        assertNull(HomelabCa.certificateUrl("https://example.test"))
        assertNull(HomelabCa.certificateUrl("https://10.8.0.1"))
        assertNull(HomelabCa.certificateUrl("https://username:password@diffusion.local"))
        assertNull(HomelabCa.certificateUrl("http://diffusion.local"))
    }

    @Test
    fun returnsBytesOnlyAfterVerifyingTheExpectedDigest() {
        withServer({ it.sendFixture(fixture) }) { origin ->
            assertArrayEquals(fixture, HomelabCa.download(origin, origin, fixtureSha256))
            assertThrows(HomelabCaException::class.java) { HomelabCa.download(origin, origin) }
        }
    }

    @Test
    fun redirectIsNotFollowedAndUsesTheSeparateFallback() {
        val redirectTargetRequests = AtomicInteger()
        withServer({ request ->
            when (request.requestURI.path) {
                "/primary" -> {
                    request.responseHeaders.set("Location", "/redirect-target")
                    request.sendResponseHeaders(302, -1)
                }
                "/fallback" -> request.sendFixture(fixture)
                else -> {
                    redirectTargetRequests.incrementAndGet()
                    request.sendFixture(fixture)
                }
            }
        }) { origin ->
            assertArrayEquals(
                fixture,
                HomelabCa.download(requireNotNull(origin.resolve("/primary")), requireNotNull(origin.resolve("/fallback")), fixtureSha256),
            )
            assertEquals(0, redirectTargetRequests.get())
        }
    }

    @Test
    fun unexpectedCertificateFailsEvenWithAFallbackAvailable() {
        val fallbackRequests = AtomicInteger()
        withServer({ request ->
            if (request.requestURI.path == "/fallback") {
                fallbackRequests.incrementAndGet()
                request.sendFixture(fixture)
            } else {
                request.sendFixture("unexpected certificate".toByteArray())
            }
        }) { origin ->
            assertThrows(HomelabCaException::class.java) {
                HomelabCa.download(requireNotNull(origin.resolve("/primary")), requireNotNull(origin.resolve("/fallback")), fixtureSha256)
            }
            assertEquals(0, fallbackRequests.get())
        }
    }

    @Test
    fun rejectsAnOversizedChunkedFile() {
        withServer({ request ->
            request.sendResponseHeaders(200, 0)
            request.responseBody.use { it.write(ByteArray(20_000)) }
        }) { origin ->
            assertEquals(
                "The downloaded certificate file is too large.",
                assertThrows(HomelabCaException::class.java) { HomelabCa.download(origin, origin, fixtureSha256) }.message,
            )
        }
    }

    private fun withServer(
        handler: (HttpExchange) -> Unit,
        test: (HttpUrl) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/".toHttpUrl())
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.sendFixture(bytes: ByteArray) {
        responseHeaders.set("Content-Type", "application/x-x509-ca-cert")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
