package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G6_WildcardTest {
    @Test fun `wildcardy z bazy dzialaja zaraz po starcie aplikacji`() {
        val vm = TestApp.start(seed = { wildcardRows["weather"] = WildcardEntity("weather", "sunny") })
        onMain { vm.updateState { it.copy(positivePrompt = "a __weather__ day") } }
        onMain { vm.queueGeneration() }
        awaitUntil("txt2img") { TestApp.forge.calls("/sdapi/v1/txt2img").isNotEmpty() }
        assertEquals("a sunny day", TestApp.forge.txt2imgPayloads().single().getString("prompt"))
    }
}
