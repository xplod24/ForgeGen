package com.example.forgegen

import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3.0.1: hires fix's latent modes come from /sdapi/v1/latent-upscale-modes (the upscaler list leaves them out, so
 * "Latent" could not be picked again), and Refresh in the VAE list asks /sdapi/v1/refresh-vae, then reads Forge's
 * module list again.
 */
@OptIn(DelicateCoroutinesApi::class)
class G41_HiresListsTest {
    companion object {
        val refreshes = AtomicInteger()
        val moduleReads = AtomicInteger()
        val toasts = CopyOnWriteArrayList<String>()

        private fun json(
            ex: HttpExchange,
            body: String,
        ): Boolean {
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return true
        }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start(custom = { ex: HttpExchange, path, _ ->
                when (path) {
                    "/sdapi/v1/latent-upscale-modes" -> json(ex, """[{"name":"Latent"},{"name":"Latent (nearest-exact)"}]""")
                    "/sdapi/v1/upscalers" -> json(ex, """[{"name":"None"},{"name":"Latent"},{"name":"4x-UltraSharp"}]""")
                    "/sdapi/v1/refresh-vae" -> {
                        refreshes.incrementAndGet()
                        json(ex, "null")
                    }
                    "/sdapi/v1/sd-modules" -> {
                        // A file added on the PC shows once the server was asked to refresh.
                        moduleReads.incrementAndGet()
                        val extra = if (refreshes.get() > 0) """,{"model_name":"sdxl_vae_new.safetensors","filename":"/sd/models/VAE/sdxl_vae_new.safetensors"}""" else ""
                        json(ex, """[{"model_name":"ae.safetensors","filename":"/sd/models/VAE/ae.safetensors"}$extra]""")
                    }
                    else -> false
                }
            })
            GlobalScope.launch(Dispatchers.IO) { TestApp.vm.toastMessage.collect { toasts += it } }
        }
    }

    @Test fun `latent modes come first and are not listed twice`() {
        val vm = TestApp.vm
        awaitUntil("upscalers") { vm.upscalers.value.isNotEmpty() }
        assertEquals(listOf("Latent", "Latent (nearest-exact)"), vm.latentModes.value)
        assertEquals(listOf("None", "4x-UltraSharp"), vm.upscalers.value)
    }

    @Test fun `refresh asks the server and reads the module list again`() {
        val vm = TestApp.vm
        awaitUntil("modules") { vm.moduleSupport.value == ModuleSupport.FORGE }
        val before = vm.serverModules.value.map { it.name }
        onMain { vm.refreshModules() }
        awaitUntil("refreshed") { refreshes.get() == 1 && vm.serverModules.value.size == before.size + 1 }
        assertTrue(vm.serverModules.value.any { it.name.startsWith("sdxl_vae_new") })
        awaitUntil("told") { toasts.any { it.startsWith("Lists read again") } }
    }
}
