package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** 3.5.3: a server too busy to answer (Forge loading a checkpoint) is told from a lost one. */
class SlowServerTest {
    @Test
    fun `only an answer that did not come in time is a slow server`() {
        assertTrue("OkHttp's read timeout", SlowServer.isSlowAnswer(SocketTimeoutException("timeout")))
        assertTrue(SlowServer.isSlowAnswer(SocketTimeoutException("Read timed out")))
        val androidConnectTimeout = "failed to connect to /192.168.1.90 (port 7860) from /192.168.1.5 after 3000ms"
        assertFalse("Android's connect timeout", SlowServer.isSlowAnswer(SocketTimeoutException(androidConnectTimeout)))
        assertFalse(SlowServer.isSlowAnswer(SocketTimeoutException("connect timed out")))
        assertFalse(SlowServer.isSlowAnswer(ConnectException("Connection refused")))
        assertFalse(SlowServer.isSlowAnswer(IOException("unexpected end of stream")))
    }

    @Test
    fun `a port that takes connections is still listening, a closed one is not`() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/"
        assertTrue(SlowServer.stillListening(url))
        server.close()
        assertFalse(SlowServer.stillListening(url))
        assertFalse(SlowServer.stillListening("not an address"))
    }

    @Test
    fun `the queue says what it waits for`() {
        assertEquals("The server is busy", SlowServer.statusText(generating = false, jobModel = null, lastJobModel = null))
        assertEquals(
            "a job with another checkpoint",
            "Loading model other…",
            SlowServer.statusText(true, "other.safetensors [def456]", "model.safetensors [abc123]"),
        )
        assertEquals("the first job", "Loading model model…", SlowServer.statusText(true, "model.safetensors [abc123]", null))
        assertEquals(
            "The server is busy, waiting for its answer…",
            SlowServer.statusText(true, "model.safetensors [abc123]", "model.safetensors [abc123]"),
        )
        assertEquals("The server is busy, waiting for its answer…", SlowServer.statusText(true, null, null))
    }
}
