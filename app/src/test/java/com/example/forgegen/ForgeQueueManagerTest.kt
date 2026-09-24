package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Very simple local unit test for ForgeQueueManager.
 * This runs on the local JVM (no emulator required).
 */
class ForgeQueueManagerTest {
    @Test
    fun `test updating external progress updates StateFlow correctly`() {
        // Arrange
        val expectedProgress = 0.85f
        val expectedEta = 5.2
        val expectedImage = "fake_base64_image_data"

        // Act
        ForgeQueueManager.updateExternalProgress(expectedProgress, expectedEta, expectedImage)

        // Assert
        assertEquals("Progress should match the updated value", expectedProgress, ForgeQueueManager.progress.value)
        assertEquals("ETA should match the updated value", expectedEta, ForgeQueueManager.currentEta.value, 0.0)
        assertEquals("Live preview image should match", expectedImage, ForgeQueueManager.livePreviewImage.value)
    }

    @Test
    fun `test updating status text updates StateFlow correctly`() {
        // Arrange
        val newStatus = "Generating Batch 1 of 4..."

        // Act
        ForgeQueueManager.updateStatusText(newStatus)

        // Assert
        assertEquals("Status text should be successfully updated", newStatus, ForgeQueueManager.statusText.value)
    }
}
