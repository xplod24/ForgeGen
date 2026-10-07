package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/** A Forge with its web UI's queue: other jobs first, its report, extensions, flags and restarting (3.3.0). */
object MockServer {
    /** Other tasks the server does before ours (0: ours runs at once). */
    @Volatile var othersFirst = 0

    /** How the web UI routes answer: 200, or 404 (--nowebui) / 401 (web UI login). */
    @Volatile var internalCode = 200
    @Volatile var restartCode = 0 // 0: Forge quits (no answer)
    @Volatile var canRestart = true
    @Volatile var down = false
    @Volatile var ourTask: String? = null
    @Volatile var ourTaskStarted = false

    fun reset() {
        othersFirst = 0
        internalCode = 200
        restartCode = 0
        canRestart = true
        down = false
        ourTask = null
        ourTaskStarted = false
    }

    private const val REPORT =
        """{"Platform": "Windows-10-10.0.22631-SP0", "Python": "3.11.9", "Version": "neo-2.1", "Torch env info": """ +
            """{"torch_version": "2.3.1+cu121", "os": "Microsoft Windows 11 Pro", "nvidia_gpu_models": "GPU 0: NVIDIA GeForce RTX 4070"}, "Config": {}}"""

    fun route(
        ex: HttpExchange,
        path: String,
        body: String,
    ): Boolean {
        if (down) {
            ex.close()
            return true
        }
        when (path) {
            "/sdapi/v1/txt2img" -> {
                // The job arrives: it waits while the others run, as Forge's queue_lock makes it.
                ourTask = org.json.JSONObject(body).optString("force_task_id").ifEmpty { null }
                val end = System.currentTimeMillis() + 30_000
                while (othersFirst > 0 && System.currentTimeMillis() < end) Thread.sleep(50)
                ourTaskStarted = true
                return false // the mock's own txt2img answers
            }
            "/internal/progress" -> {
                if (internalCode != 200) return send(ex, internalCode, """{"detail":"Not Found"}""")
                val id = org.json.JSONObject(body).getString("id_task")
                val queued = id == ourTask && othersFirst > 0
                val active = id == ourTask && !queued && ourTaskStarted
                return send(
                    ex,
                    200,
                    """{"active":$active,"queued":$queued,"completed":false,"textinfo":"${if (queued) "In queue: $othersFirst/$othersFirst" else "Waiting..."}"}""",
                )
            }
            "/internal/pending-tasks" -> {
                if (internalCode != 200) return send(ex, internalCode, "{}")
                val tasks = (1 until othersFirst).map { "\"task(webui-$it)\"" } + listOfNotNull(ourTask?.takeIf { othersFirst > 0 }?.let { "\"$it\"" })
                return send(ex, 200, """{"size":${tasks.size},"tasks":[${tasks.joinToString(",")}]}""")
            }
            "/internal/sysinfo" -> return if (internalCode != 200) send(ex, internalCode, "{}") else send(ex, 200, REPORT)
            "/sdapi/v1/extensions" ->
                return send(
                    ex,
                    200,
                    """[{"name":"sd-webui-infinite-image-browsing","remote":"","branch":"main","commit_hash":"","version":"abc","commit_date":0,"enabled":true},""" +
                        """{"name":"adetailer","remote":"","branch":"main","commit_hash":"","version":"v24","commit_date":0,"enabled":true},""" +
                        """{"name":"a1111-sd-webui-tagcomplete","remote":"","branch":"main","commit_hash":"","version":"x","commit_date":0,"enabled":false}]""",
                )
            "/sdapi/v1/cmd-flags" -> return send(ex, 200, """{"api":true,"nowebui":false,"api_server_stop":$canRestart}""")
            "/sdapi/v1/skip" -> return send(ex, 200, "null")
            "/sdapi/v1/server-restart" -> {
                if (restartCode != 0) return send(ex, restartCode, "")
                down = true // os._exit: no answer, and the server is gone
                ex.close()
                return true
            }
        }
        return false
    }

    private fun send(
        ex: HttpExchange,
        code: Int,
        text: String,
    ): Boolean {
        val bytes = text.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        return true
    }
}

@OptIn(DelicateCoroutinesApi::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G44_ServerQueueAndControlTest {
    companion object {
        val toasts = CopyOnWriteArrayList<String>()
        val vm get() = TestApp.vm

        @BeforeClass @JvmStatic fun init() {
            ForgeRepository.searchPingMs = 300
            TestApp.start(custom = { ex, path, body -> MockServer.route(ex, path, body) })
            GlobalScope.launch(Dispatchers.IO) { ForgeSettingsManager.snackbarMessage.collect { toasts += it } }
        }

        fun calls(path: String) = TestApp.forge.calls(path)
    }

    @Before fun reset() {
        MockServer.reset()
        toasts.clear()
        awaitUntil("connected", 20_000) { vm.isConnected.value }
    }

    @Test fun `01 every job goes with the app's own task id`() {
        TestApp.forge.generationMs = 300
        onMain { vm.queueGeneration() }
        awaitUntil("job sent and done", 20_000) { TestApp.forge.txt2imgPayloads().isNotEmpty() && !vm.isGenerating.value }
        val id = TestApp.forge.txt2imgPayloads().last().getString("force_task_id")
        assertTrue(id, id.matches(Regex("""task\(forgegen-[A-Za-z0-9]{1,12}\)""")))
        assertNull("not kept in the saved queue", vm.generationQueue.value.firstOrNull()?.payload?.force_task_id)
    }

    @Test fun `02 while the web UI's jobs go first, their progress is not the job's`() {
        MockServer.othersFirst = 2
        TestApp.forge.generationMs = 300
        onMain { vm.queueGeneration() }
        awaitUntil("waiting behind 2", 15_000) { vm.serverJobsAhead.value == 2 }
        assertEquals("Waiting for the server: 2 other jobs go first", ForgeQueueManager.statusText.value)
        assertEquals(0f, vm.progress.value)
        MockServer.othersFirst = 1
        awaitUntil("behind 1", 15_000) { vm.serverJobsAhead.value == 1 }
        MockServer.othersFirst = 0
        awaitUntil("ours runs", 15_000) { vm.serverJobsAhead.value == 0 }
        awaitUntil("done", 20_000) { !vm.isGenerating.value }
        assertEquals(0, vm.serverJobsAhead.value)
    }

    @Test fun `03 a server without its web UI is not asked again`() {
        MockServer.internalCode = 404
        TestApp.forge.generationMs = 3_000
        val before = calls("/internal/progress").size
        onMain { vm.queueGeneration() }
        awaitUntil("done", 20_000) { vm.isGenerating.value }
        awaitUntil("done", 20_000) { !vm.isGenerating.value }
        assertEquals("asked once, then the old progress", before + 1, calls("/internal/progress").size)
        assertEquals(0, vm.serverJobsAhead.value)
    }

    @Test fun `04 Skip Image asks the server to skip`() {
        onMain { vm.skipImage() }
        awaitUntil("skip sent") { calls("/sdapi/v1/skip").isNotEmpty() }
        awaitUntil("message") { toasts.contains("Skipping this image") }
    }

    @Test fun `05 the server page - flags and extensions by themselves, the report only on Check Now`() {
        onMain { vm.loadServerInfo(again = true) }
        awaitUntil("flags and extensions", 10_000) { vm.serverInfo.value?.extensions != null }
        val info = vm.serverInfo.value!!
        assertEquals(true, info.canRestart)
        // The ones the app uses first (by name), then the others.
        assertEquals(listOf("a1111-sd-webui-tagcomplete", "sd-webui-infinite-image-browsing", "adetailer"), info.extensions!!.map { it.name })
        assertEquals(listOf("Tag suggestions", "The gallery", null), info.extensions!!.map { it.purpose })
        assertEquals(false, info.extensions!!.first().enabled)
        assertTrue("3.6.0: no report without Check Now", TestApp.forge.calls("/internal/sysinfo").isEmpty())
        onMain { vm.checkServer() }
        awaitUntil("checked", 10_000) { vm.lastServerCheck.value != null && vm.checkingSince.value == 0L }
        val check = vm.lastServerCheck.value!!
        assertEquals("neo-2.1", check.version)
        assertEquals("NVIDIA GeForce RTX 4070", check.gpu)
        assertEquals("Microsoft Windows 11 Pro · Python 3.11.9 · torch 2.3.1+cu121", check.system)
        assertNull(vm.checkProblem.value)
        // Without the web UI: flags and extensions, and why there is no report; the last check stays.
        MockServer.internalCode = 404
        MockServer.canRestart = false
        onMain { vm.loadServerInfo(again = true) }
        awaitUntil("flags again") { vm.serverInfo.value?.canRestart == false }
        onMain { vm.checkServer() }
        awaitUntil("no report") { vm.checkProblem.value != null && vm.checkingSince.value == 0L }
        assertTrue(vm.checkProblem.value!!.contains("--nowebui"))
        assertEquals("neo-2.1", vm.lastServerCheck.value?.version)
    }

    @Test fun `06 Restart Forge that cannot restart says why`() {
        MockServer.restartCode = 404
        assertEquals(ForgeRepository.RESTART_NEEDS_FLAG, runBlocking { vm.restartServer() })
        MockServer.restartCode = 501
        assertEquals(ForgeRepository.RESTART_NOT_POSSIBLE, runBlocking { vm.restartServer() })
        assertEquals(0L, vm.restartingSince.value)
    }

    @Test fun `07 Restart Forge - the app waits for it and says when it is back`() {
        assertNull(runBlocking { vm.restartServer() })
        assertTrue(vm.restartingSince.value > 0)
        awaitUntil("gone", 10_000) { !vm.isConnected.value }
        // Waiting longer than for a lost connection.
        assertTrue(vm.searchEndsAt.value - vm.restartingSince.value >= ForgeRepository.restartWaitMs - 1_000)
        Thread.sleep(1_000)
        MockServer.down = false
        awaitUntil("back", 20_000) { vm.isConnected.value && vm.restartingSince.value == 0L }
        awaitUntil("message") { toasts.contains("Forge is back") }
    }
}
