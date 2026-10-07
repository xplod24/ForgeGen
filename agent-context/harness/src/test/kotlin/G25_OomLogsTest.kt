package com.example.forgegen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.util.concurrent.CopyOnWriteArrayList

/** Out-of-memory reports in Downloads, only with the user's consent. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G25_OomLogsTest {
    companion object {
        val vm get() = TestApp.vm
        val toasts = CopyOnWriteArrayList<String>()
        const val OOM_BODY = """{"error":"OutOfMemoryError","detail":"CUDA out of memory. Tried to allocate 2.00 GiB"}"""

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            CoroutineScope(Dispatchers.IO).launch { vm.toastMessage.collect { toasts += it } }
        }

        fun reports() = TestApp.app.resolver.rows.values.filter { it.relativePath.startsWith("Download") }

        fun consent(on: Boolean) = onMain { vm.saveConfig(vm.config.value.copy(saveOomLogs = on)) }

        fun serverOom() {
            TestApp.forge.txt2imgStatus = 500
            TestApp.forge.txt2imgBody = OOM_BODY
            onMain { vm.updateState { it.copy(positivePrompt = "secret prompt", batchCount = 1, width = 768, height = 1024) } }
            onMain { vm.queueGeneration() }
            awaitUntil("OOM seen", 15_000) { vm.oomAlert.value }
            awaitUntil("job done", 15_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }
        }
    }

    @Before fun reset() {
        TestApp.app.resolver.failWrites = false
        onMain { vm.resumeQueue() }
    }

    @Test fun `01 no consent - no report`() {
        consent(false)
        serverOom()
        Thread.sleep(1000)
        assertEquals(emptyList<Any>(), reports())
    }

    @Test fun `02 server OOM - a report in Downloads`() {
        consent(true)
        serverOom()
        awaitUntil("report saved", 10_000) { reports().any { !it.pending } }
        val row = reports().single()
        val text = row.file.readText()
        println("[G25-02] ${row.name} ${row.relativePath}\n$text")
        assertTrue(row.name, Regex("""ForgeGen-OOM-\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d\.txt""").matches(row.name))
        assertEquals("Download/", row.relativePath)
        assertTrue(text.contains("Reason: The server ran out of memory."))
        assertTrue(text.contains("Job: 768x1024, batch size 1, batch count 1"))
        assertTrue(text.contains("CUDA out of memory. Tried to allocate 2.00 GiB"))
        assertTrue(text.contains("App: 1.1.0-DEBUG"))
        assertTrue(text.contains("Phone memory: 1536 MB free of 8192 MB"))
        assertTrue(text.contains("Log of the app (without the content of HTTP requests and answers"))
        assertFalse("prompts stay out", text.contains("secret prompt"))
        awaitUntil("toast") { toasts.any { it == "Out-of-memory log saved to Downloads: ${row.name}" } }
    }

    @Test fun `03 a report that cannot be written leaves nothing behind`() {
        consent(true)
        val before = reports().size
        TestApp.app.resolver.failWrites = true
        serverOom()
        Thread.sleep(1500)
        assertEquals(before, reports().size)
    }

    @Test fun `04 an out-of-memory crash writes a report, then crashes as before`() {
        consent(true)
        val seen = CopyOnWriteArrayList<Throwable>()
        val recorder = Thread.UncaughtExceptionHandler { _, e -> seen += e }
        Thread.setDefaultUncaughtExceptionHandler(recorder)
        OomLogs.install(TestApp.app)
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val before = reports().size

        val oom = RuntimeException("wrapped", OutOfMemoryError("Failed to allocate a 200000000 byte allocation"))
        handler.uncaughtException(Thread.currentThread(), oom)
        assertEquals("written before the crash goes on", before + 1, reports().count { !it.pending })
        val text = reports().maxBy { it.file.lastModified() }.file.readText()
        assertTrue(text, text.contains("Reason: The app ran out of memory and closed"))
        assertTrue(text.contains("Failed to allocate a 200000000 byte allocation"))
        assertSame(oom, seen.single())

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("not memory"))
        assertEquals("other crashes: no report", before + 1, reports().size)
        assertEquals(2, seen.size)

        OomLogs.install(TestApp.app) // installed once only
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test fun `05 no consent - a crash writes nothing`() {
        consent(false)
        val before = reports().size
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), OutOfMemoryError("x"))
        assertEquals(before, reports().size)
    }
}
