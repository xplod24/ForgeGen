package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class G11_SessionMetadataTest {
    @Test fun `metadane obrazu wygenerowanego w tej sesji sa dostepne`() {
        val vm = TestApp.start()
        onMain { vm.queueGeneration() }
        awaitUntil("obraz w sesji") { vm.sessionImages.value.isNotEmpty() && !vm.isGenerating.value }
        onMain { vm.loadMetadataForLocalFile(vm.sessionImages.value.first()) }
        awaitUntil("metadane") { vm.currentImageMetadata.value.let { it != null && it != "Loading metadata..." } }
        assertEquals(INFOTEXT, vm.currentImageMetadata.value)
    }
}
