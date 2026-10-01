package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Check Now" (3.6.0): Forge Neo's report with its VRAM counters, and Unload After the Queue's rules. */
class ServerCheckTest {
    // As modules/sysinfo.py writes it (shortened: the config, git status and environment are left out).
    private val report =
        """
        {
            "Platform": "Windows-10-10.0.22631-SP0",
            "Python": "3.11.9",
            "Version": "neo-2.1",
            "Commandline": ["launch.py", "--api", "--listen", "--api-server-stop", "--cuda-malloc", "--xformers"],
            "Torch env info": {
                "torch_version": "2.3.1+cu121",
                "os": "Microsoft Windows 11 Pro",
                "nvidia_gpu_models": "GPU 0: NVIDIA GeForce RTX 4070 (UUID: GPU-1234)"
            },
            "Exceptions": [
                {
                    "exception": "CUDA out of memory. Tried to allocate 2.50 GiB\nGPU 0 has a total capacity of 12.00 GiB",
                    "traceback": [
                        ["C:\\forge\\modules\\call_queue.py, line 74, f", "res = list(func(*args, **kwargs))"],
                        ["C:\\forge\\modules\\sd_samplers_kdiffusion.py, line 228, sample", "samples = self.launch_sampling(steps, denoiser)"]
                    ]
                },
                {
                    "exception": "Sizes of tensors must match except in dimension 1",
                    "traceback": [["/home/u/forge/modules/processing.py, line 1012, process_images_inner", "x = decode(...)"]]
                }
            ],
            "CPU": {"model": "AMD64 Family 25 Model 33, AuthenticAMD", "count logical": 16, "count physical": 8},
            "RAM": {"total": "32GB", "used": "23GB", "free": "9GB"},
            "Extensions": [{"name": "sd-webui-infinite-image-browsing", "path": "x", "commit": "a", "branch": "main", "remote": "r"}],
            "Inactive extensions": [
                {"name": "sd-webui-regional-prompter", "path": "x", "commit": "a", "branch": "main", "remote": "r"},
                {"name": "multidiffusion-upscaler", "path": "x", "commit": "a", "branch": "main", "remote": "r"}
            ],
            "Startup": {"total": 41.23, "records": {"launcher": 0.4, "import torch": 6.1}},
            "Packages": ["accelerate==0.31.0", "gradio==4.40.0", "safetensors==0.4.3", "torch==2.3.1+cu121", "xformers==0.0.27", "-e git+https://x"]
        }
        """.trimIndent()

    private val memory =
        Gson().fromJson(
            """{"ram":{"free":1,"used":8589934592,"total":34359738368},"cuda":{"device":"cuda:0",
               "system":{"free":4,"used":8160437862,"total":12884901888},
               "reserved":{"current":7516192768,"peak":12025908428},"events":{"retries":3,"oom":0}}}""",
            MemoryResponseDto::class.java,
        )

    @Test
    fun `the report and the counters give the server page`() {
        val check = ServerInfoParser.check(report, memory, at = 1_000L)
        assertEquals(1_000L, check.checkedAt)
        assertEquals("neo-2.1", check.version)
        assertEquals(41.23, check.startupSeconds!!, 1e-9)
        assertEquals("NVIDIA GeForce RTX 4070", check.gpu)
        assertEquals(12.0, check.vramTotalGb!!, 1e-9)
        assertEquals("AMD64 Family 25 Model 33, AuthenticAMD · 8 cores, 16 threads", check.cpu)
        assertEquals(23.0, check.ramUsedGb!!, 1e-9)
        assertEquals(32.0, check.ramTotalGb!!, 1e-9)
        assertEquals("Microsoft Windows 11 Pro · Python 3.11.9 · torch 2.3.1+cu121", check.system)
        assertEquals(0, check.outOfVram)
        assertEquals(3, check.vramShort)
        assertEquals(11.2, check.vramPeakGb!!, 0.01)
        assertEquals("--api --listen --api-server-stop --cuda-malloc --xformers", check.launchFlags)
        assertEquals("torch 2.3.1+cu121 · xformers 0.0.27 · gradio 4.40.0 · safetensors 0.4.3", check.keyPackages)
        assertEquals(6, check.packages.size)
        assertEquals(listOf("sd-webui-regional-prompter", "multidiffusion-upscaler"), check.turnedOffExtensions)
        assertEquals(report, check.report)
    }

    @Test
    fun `an error shows its first line and the file and line it came from`() {
        val errors = ServerInfoParser.check(report, null, 0L).errors
        assertEquals(2, errors.size)
        assertEquals("CUDA out of memory. Tried to allocate 2.50 GiB", errors[0].message)
        assertEquals("sd_samplers_kdiffusion.py, line 228", errors[0].place)
        assertEquals("processing.py, line 1012", errors[1].place)
    }

    @Test
    fun `what the report does not say stays unknown`() {
        val check = ServerInfoParser.check("not json", null, 5L)
        assertNull(check.version)
        assertNull(check.outOfVram)
        assertEquals("not json", check.report)
        val bare = ServerInfoParser.check("""{"Version": "v1", "CPU": {"model": "", "count logical": 4, "count physical": 4}}""", null, 0L)
        assertEquals("4 cores", bare.cpu)
        assertNull(bare.ramTotalGb)
        assertEquals(emptyList<ServerError>(), bare.errors)
        // Saved as JSON and read back the same (app_settings "server_check:<server>").
        val gson = Gson()
        val full = ServerInfoParser.check(report, memory, 9L)
        assertEquals(full, gson.fromJson(gson.toJson(full), ServerCheck::class.java))
    }

    @Test
    fun `memory sizes as Forge writes them`() {
        assertEquals(32.0, ServerInfoParser.gbOf("32GB")!!, 1e-9)
        assertEquals(0.5, ServerInfoParser.gbOf("512MB")!!, 1e-9)
        assertEquals(2048.0, ServerInfoParser.gbOf("2TB")!!, 1e-9)
        assertNull(ServerInfoParser.gbOf("a lot"))
        assertNull(ServerInfoParser.keyPackages(listOf("numpy==1.26.0")))
    }

    @Test
    fun `the model leaves only when nothing needs it`() {
        val loaded = LoadedModel(LoadedModel.KNOWN, "m", "")
        assertTrue(AutoUnload.mayUnload(ourJobsWaiting = false, serverBusy = false, loaded = loaded))
        assertTrue("not known what is loaded: unload anyway", AutoUnload.mayUnload(false, false, LoadedModel.unknown))
        assertFalse("a job waits in the app's queue", AutoUnload.mayUnload(true, false, loaded))
        assertFalse("the server does another job", AutoUnload.mayUnload(false, true, loaded))
        assertFalse("the server could not be asked", AutoUnload.mayUnload(false, null, loaded))
        assertFalse("already unloaded", AutoUnload.mayUnload(false, false, LoadedModel.empty))
        assertEquals(AutoUnload.OFF, AutoUnload.of(null))
        assertEquals(AutoUnload.OFF, AutoUnload.of(7))
        assertEquals(10, AutoUnload.of(10))
        assertEquals(listOf("Off", "At once", "10 min", "30 min"), AutoUnload.CHOICES.map { AutoUnload.label(it) })
    }
}
