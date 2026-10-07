package com.example.forgegen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class G9_LoraTest {
    companion object {
        lateinit var vm: ForgeViewModel
        @BeforeClass @JvmStatic fun init() { vm = TestApp.start() }
    }

    private fun setPrompt(p: String) = onMain { vm.updateState { it.copy(positivePrompt = p) } }
    private fun prompt() = vm.appState.value.positivePrompt

    @Test fun `dodanie LoRA gdy prompt konczy sie przecinkiem i spacja`() {
        setPrompt("a cat, ")
        onMain { vm.appendLora("detail") }
        assertEquals("a cat, <lora:detail:1.0>", prompt())
    }

    @Test fun `usuniecie LoRA ze srodka promptu`() {
        setPrompt("a cat, <lora:detail:1.0>, sitting")
        onMain { vm.removeLora("detail") }
        assertEquals("a cat, sitting", prompt())
    }

    @Test fun `LoRA z ujemna waga jest rozpoznawana i edytowalna`() {
        val job = CoroutineScope(TestMain.dispatcher).launch { vm.activeLoras.collect { } }
        setPrompt("a cat, <lora:detail:-0.5>")
        awaitUntil("activeLoras", 2000) { vm.activeLoras.value.isNotEmpty() }
        assertEquals(listOf(ActiveLora("detail", -0.5f)), vm.activeLoras.value)
        onMain { vm.updateLoraStrength("detail", 0.75f) }
        assertEquals("a cat, <lora:detail:0.75>", prompt())
        job.cancel()
    }
}
