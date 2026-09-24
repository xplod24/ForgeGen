package com.example.forgegen

import android.app.Application
import android.content.Intent
import android.util.Log
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ForgeQueueManagerAdvancedTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockApp: Application

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock external dependencies
        mockApp = mockk(relaxed = true)
        every { mockApp.applicationContext } returns mockApp

        // Mock ForgeRepository
        mockkObject(ForgeRepository)
        every { ForgeRepository.appState } returns
            MutableStateFlow(
                AppState(positivePrompt = "A beautiful __weather__ day", negativePrompt = "ugly"),
            )
        every { ForgeRepository.selectedModel } returns MutableStateFlow("model_v1.safetensors")
        every { ForgeRepository.isServerBusy } returns MutableStateFlow(false)
        every { ForgeRepository.config } returns MutableStateFlow(AppConfig())
        every { ForgeRepository.repositoryScope } returns kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher())

        // Mock ForgePromptManager
        mockkObject(ForgePromptManager)
        every { ForgePromptManager.wildcards } returns
            MutableStateFlow(
                listOf(WildcardEntity(name = "weather", content = "sunny")),
            )

        // Mock ForgeSettingsManager
        mockkObject(ForgeSettingsManager)
        every { ForgeSettingsManager.saveToPromptHistory(any(), any()) } just Runs

        // Mock Android SDK
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().action = any() } just Runs

        // Initialize Queue Manager
        // Note: We bypass loadQueueState database reading for this unit test
        // by avoiding calling init(), we just test the queueGeneration logic directly.
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `test queueGeneration constructs payload and applies wildcards`() =
        runBlocking {
            // Arrange
            // Clear queue first
            val queueField = ForgeQueueManager::class.java.getDeclaredField("_generationQueue")
            queueField.isAccessible = true
            (queueField.get(ForgeQueueManager) as MutableStateFlow<List<QueuedGeneration>>).value = emptyList()

            // Act
            ForgeQueueManager.queueGeneration()
            delay(200) // let IO coroutine finish

            // Assert
            val currentQueue = ForgeQueueManager.generationQueue.value
            assertEquals("Queue should have 1 item", 1, currentQueue.size)

            val queuedItem = currentQueue.first()

            // Verify wildcard application
            assertEquals(
                "Wildcard __weather__ should be replaced with sunny",
                "A beautiful sunny day",
                queuedItem.positivePrompt,
            )
            assertEquals("A beautiful sunny day", queuedItem.payload.prompt)

            // Verify settings mapping
            assertEquals("ugly", queuedItem.payload.negative_prompt)
            assertEquals("model_v1.safetensors", queuedItem.payload.override_settings?.sdModelCheckpoint)
            assertEquals(GenerationStatus.QUEUED, queuedItem.status)

            // Verify history saving was called
            verify { ForgeSettingsManager.saveToPromptHistory("A beautiful __weather__ day", "ugly") }
        }

    @Test
    fun `test suspendCurrentGeneration alters status to SUSPENDED`() =
        runBlocking {
            // Arrange
            val queueField = ForgeQueueManager::class.java.getDeclaredField("_generationQueue")
            queueField.isAccessible = true
            val mockItem =
                QueuedGeneration(
                    id = UUID.randomUUID().toString(),
                    positivePrompt = "Test",
                    payload =
                        Txt2ImgPayloadDto(
                            prompt = "Test",
                            negative_prompt = "",
                            steps = 20,
                            cfg_scale = 7f,
                            width = 512,
                            height = 512,
                            n_iter = 1,
                            batch_size = 1,
                            seed = -1L,
                            sampler_name = "Euler a",
                            scheduler = "Automatic",
                            override_settings = OverrideSettingsDto(1, null),
                            enable_hr = false,
                            hr_scale = 2f,
                            hr_upscaler = "Latent",
                            denoising_strength = 0.7f,
                        ),
                    status = GenerationStatus.GENERATING,
                )
            (queueField.get(ForgeQueueManager) as MutableStateFlow<List<QueuedGeneration>>).value = listOf(mockItem)

            val isGeneratingField = ForgeQueueManager::class.java.getDeclaredField("_isGenerating")
            isGeneratingField.isAccessible = true
            (isGeneratingField.get(ForgeQueueManager) as MutableStateFlow<Boolean>).value = true

            // Act
            ForgeQueueManager.suspendCurrentGeneration()
            delay(200) // let IO coroutine finish

            // Assert
            val currentQueue = ForgeQueueManager.generationQueue.value
            assertEquals(GenerationStatus.SUSPENDED, currentQueue.first().status)
            assertTrue("Queue should be paused", ForgeQueueManager.isQueuePaused.value)
            assertEquals("Queue Suspended (Connection Lost)", ForgeQueueManager.statusText.value)
        }
}
