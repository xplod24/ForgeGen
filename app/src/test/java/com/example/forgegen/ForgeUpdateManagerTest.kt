package com.example.forgegen

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ForgeUpdateManagerTest {

    private lateinit var updateManager: ForgeUpdateManager
    private lateinit var mockApplication: Application
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockApi: ForgeApi
    private lateinit var testScope: TestScope
    
    private var testConfig = AppConfig()

    @Before
    fun setup() {
        mockApplication = mockk()
        mockPackageManager = mockk()
        mockApi = mockk()
        testScope = TestScope(StandardTestDispatcher())

        every { mockApplication.packageManager } returns mockPackageManager
        every { mockApplication.packageName } returns "com.example.forgegen"
        
        val mockPackageInfo = mockk<PackageInfo>()
        every { mockPackageInfo.longVersionCode } returns 100L
        every { mockPackageManager.getPackageInfo("com.example.forgegen", 0) } returns mockPackageInfo

        updateManager = ForgeUpdateManager(
            application = mockApplication,
            getForgeApi = { mockApi },
            getConfig = { testConfig },
            saveConfig = { testConfig = it },
            showToast = {},
            scope = testScope
        )
    }

    @Test
    fun `checkForUpdates fetches manifest with changelog and correctly updates state`() = kotlinx.coroutines.runBlocking {
        // Arrange
        // The manifest changelog is a flat list of lines (generateUpdateJson* reads them from CHANGELOG.md).
        val expectedChangelog = listOf("Added cool new feature", "Fixed a bug", "Dodano nową funkcję", "Naprawiono błąd")
        
        val fakeManifestDto = UpdateManifestDto(
            versionCode = 101, // Higher than local 100
            versionName = "1.0.1",
            url = "http://example.com/update.apk",
            channel = "Release",
            sha256 = "dummy_sha256",
            releaseDate = "2026-07-17",
            isCritical = false,
            changelog = expectedChangelog,
        )
        
        coEvery { mockApi.getAppMetadata() } returns Response.success(fakeManifestDto)

        // Act
        updateManager.checkForUpdates(manual = true)
        
        // Wait for IO dispatcher
        var waitCount = 0
        while(updateManager.updateManifest.value == null && waitCount < 10) {
            kotlinx.coroutines.delay(100)
            waitCount++
        }

        // Assert
        val manifest = updateManager.updateManifest.value
        assertNotNull("Update manifest should not be null", manifest)
        assertEquals(101, manifest?.versionCode)
        
        assertEquals(expectedChangelog, manifest?.changelog)
    }
    
    @Test
    fun `checkForUpdates ignores older version`() = kotlinx.coroutines.runBlocking {
        // Arrange
        val fakeManifestDto = UpdateManifestDto(
            versionCode = 99, // Lower than local 100
            versionName = "0.9.9",
            url = "http://example.com/update.apk",
            channel = "Release",
            sha256 = "dummy_sha256",
            changelog = null
        )
        coEvery { mockApi.getAppMetadata() } returns Response.success(fakeManifestDto)

        // Act
        updateManager.checkForUpdates(manual = true)
        
        // Wait for IO dispatcher (it shouldn't change the value, but we wait just in case to avoid race conditions)
        kotlinx.coroutines.delay(200)

        // Assert
        val manifest = updateManager.updateManifest.value
        assertTrue("Update manifest should be null because update is older", manifest == null)
    }
}
