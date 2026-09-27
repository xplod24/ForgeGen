package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        val expectedImage = java.util.Base64.getEncoder().encodeToString(imageBytes)

        // Act
        ForgeQueueManager.updateExternalProgress(expectedProgress, expectedEta, expectedImage)
        val preview = ForgeQueueManager.livePreviewImage.value

        // Assert
        assertEquals("Progress should match the updated value", expectedProgress, ForgeQueueManager.progress.value)
        assertEquals("ETA should match the updated value", expectedEta, ForgeQueueManager.currentEta.value, 0.0)
        assertEquals("Live preview image should be decoded", imageBytes.toList(), preview?.bytes?.toList())

        // The same image sent again is not decoded again.
        ForgeQueueManager.updateExternalProgress(0.9f, 4.0, expectedImage)
        assertSame(preview, ForgeQueueManager.livePreviewImage.value)
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

    @Test
    fun `the server's own error text is read from Forge's and FastAPI's error answers`() {
        val forge = """{"error": "TypeError", "detail": "", "body": "", "errors": "argument of type 'NoneType' is not iterable"}"""
        assertEquals("TypeError: argument of type 'NoneType' is not iterable", ForgeQueueManager.serverError(forge))
        val trimmed = """{"error": "HTTPException", "errors": " Sampler not found "}"""
        assertEquals("HTTPException: Sampler not found", ForgeQueueManager.serverError(trimmed))
        assertEquals("Not Found", ForgeQueueManager.serverError("""{"detail": "Not Found"}"""))
        assertEquals(300, ForgeQueueManager.serverError("""{"error": "E", "errors": "${"x".repeat(1000)}"}""")?.length)
        // A FastAPI validation error lists its problems instead of a text: nothing is added then.
        assertNull(ForgeQueueManager.serverError("""{"detail": [{"loc": ["body"], "msg": "field required"}]}"""))
        assertNull(ForgeQueueManager.serverError("Internal Server Error"))
        assertNull(ForgeQueueManager.serverError(""))
        assertNull(ForgeQueueManager.serverError("[1, 2]"))
    }
}
