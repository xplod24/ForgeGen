package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G10_QueueGuardTest {
    @Test fun `aktywne zadanie zostaje na pierwszym miejscu kolejki`() {
        val vm = TestApp.start()
        TestApp.forge.generationMs = 3000
        onMain { vm.updateState { it.copy(positivePrompt = "A") } }
        onMain { vm.queueGeneration() }
        awaitUntil("A generuje się") { TestApp.forge.generating }
        onMain { vm.updateState { it.copy(positivePrompt = "B") } }
        onMain { vm.queueGeneration() }
        awaitUntil("B w kolejce") { vm.generationQueue.value.size == 2 }
        val idA = vm.generationQueue.value.first().id
        val idB = vm.generationQueue.value.last().id
        onMain { vm.moveQueueItemUp(idB) }
        assertEquals("QueueScreen pokazałby B jako generowane", "A", vm.generationQueue.value.first().positivePrompt)
        onMain { vm.moveQueueItemDown(idA) }
        assertEquals("A", vm.generationQueue.value.first().positivePrompt)
    }
}
