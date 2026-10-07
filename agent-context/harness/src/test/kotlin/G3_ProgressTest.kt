package com.example.forgegen

import org.junit.Test

class G3_ProgressTest {
    @Test fun `postep i ETA z serwera sa widoczne w trakcie generowania`() {
        val vm = TestApp.start()
        TestApp.forge.generationMs = 4000
        onMain { vm.queueGeneration() }
        awaitUntil("generowanie trwa") { TestApp.forge.generating }
        var seen = 0f
        try {
            awaitUntil("postęp ~50% widoczny w UI (vm.progress)", 3500) { seen = vm.progress.value; vm.progress.value in 0.4f..0.6f && vm.currentEta.value > 0.0 }
        } finally {
            println("[G3] vm.progress=$seen eta=${vm.currentEta.value} (serwer raportuje 0.5 / 3.5 s)")
        }
    }
}
