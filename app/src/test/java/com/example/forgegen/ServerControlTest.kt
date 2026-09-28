package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.3.0: the app's own task ids and the jobs the server does before them. */
class ServerTasksTest {
    @Test
    fun `a job's task id is short, safe and its own`() {
        val id = ServerTasks.idFor("3f2a9c1e-77b0-4d1a-9e3c-000000000001")
        assertEquals("task(forgegen-3f2a9c1e77b0)", id)
        assertTrue(ServerTasks.idFor("other-job") != id)
    }

    @Test
    fun `the jobs ahead are those waiting before it and the running one`() {
        val ours = "task(forgegen-abc)"
        assertEquals(1, ServerTasks.jobsAhead(ours, listOf(ours)))
        assertEquals(3, ServerTasks.jobsAhead(ours, listOf("task(a)", "task(b)", ours, "task(c)")))
        assertNull(ServerTasks.jobsAhead(ours, listOf("task(a)")))
        // The same from /internal/progress's text.
        assertEquals(2, ServerTasks.jobsAhead("In queue: 2/3"))
        assertNull(ServerTasks.jobsAhead("Waiting..."))
        assertNull(ServerTasks.jobsAhead(null))
        assertEquals("1 other job goes first", ServerTasks.aheadText(1))
        assertEquals("2 other jobs go first", ServerTasks.aheadText(2))
    }

    @Test
    fun `the id is sent with a job only when set`() {
        val payload =
            Txt2ImgPayloadDto(
                "p",
                "n",
                20,
                7f,
                512,
                512,
                1,
                1,
                -1,
                "Euler a",
                "Automatic",
                OverrideSettingsDto(1, null),
                false,
                2f,
                "Latent",
                0.7f,
            )
        assertFalse(Gson().toJson(payload).contains("force_task_id"))
        assertTrue(Gson().toJson(payload.copy(force_task_id = "task(forgegen-x)")).contains("\"force_task_id\":\"task(forgegen-x)\""))
    }
}

/** 3.3.0: what the server page reads from Forge. */
class ServerInfoParserTest {
    @Test
    fun `version, system and GPU from Forge's report`() {
        val report =
            """{"Platform": "Windows-10-10.0.22631-SP0", "Python": "3.11.9", "Version": "neo-2.1",
               "Torch env info": {"torch_version": "2.3.1+cu121", "os": "Microsoft Windows 11 Pro",
               "nvidia_gpu_models": "GPU 0: NVIDIA GeForce RTX 4070 (UUID: GPU-1234)"}}"""
        val info = ServerInfoParser.fromReport(report)
        assertEquals("neo-2.1", info.version)
        assertEquals("Microsoft Windows 11 Pro · Python 3.11.9 · torch 2.3.1+cu121", info.system)
        assertEquals("NVIDIA GeForce RTX 4070", info.gpu)
        assertEquals(report, info.report)
    }

    @Test
    fun `a report without torch details, with GPU lines, or not JSON`() {
        val lines =
            """{"Platform": "Linux-6.8.0-x86_64", "Python": "3.10.14",
               "Torch env info": {"nvidia_gpu_models": ["GPU 0: NVIDIA A100", "GPU 1: NVIDIA A100"]}}"""
        val info = ServerInfoParser.fromReport(lines)
        assertEquals("Linux · Python 3.10.14", info.system)
        assertEquals("NVIDIA A100", info.gpu)
        assertNull(info.version)
        val broken = ServerInfoParser.fromReport("not json")
        assertNull(broken.version)
        assertEquals("not json", broken.report)
        assertNull(ServerInfoParser.fromReport("""{"Torch env info": {"nvidia_gpu_models": "None"}}""").gpu)
    }

    @Test
    fun `the extensions the app uses come first, with what for`() {
        val list =
            ServerInfoParser.extensions(
                listOf(
                    ServerExtensionDto("adetailer", version = "v24"),
                    ServerExtensionDto("sd-webui-infinite-image-browsing", version = "abc"),
                    ServerExtensionDto("", version = "x"),
                    ServerExtensionDto("sd-webui-prompt-all-in-one", branch = "main", enabled = false),
                ),
            )
        assertEquals(listOf("sd-webui-infinite-image-browsing", "sd-webui-prompt-all-in-one", "adetailer"), list.map { it.name })
        assertEquals(listOf("The gallery", "Restore Last (prompt history)", null), list.map { it.purpose })
        assertEquals("main", list[1].version) // the branch when there is no version
        assertFalse(list[1].enabled)
    }

    @Test
    fun `whether Forge can restart from the phone`() {
        assertEquals(true, ServerInfoParser.canRestart(mapOf("api_server_stop" to true)))
        assertEquals(false, ServerInfoParser.canRestart(mapOf("api_server_stop" to false)))
        assertNull(ServerInfoParser.canRestart(mapOf("api" to true)))
    }
}
