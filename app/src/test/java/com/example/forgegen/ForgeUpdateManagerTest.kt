package com.example.forgegen

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ForgeUpdateManagerTest {
    private lateinit var updateManager: ForgeUpdateManager
    private lateinit var mockApi: GitHubApi

    private var testConfig = AppConfig()

    private fun apk(digest: String? = "sha256:abc123") =
        GitHubAssetDto(
            name = "app-debug.apk",
            downloadUrl = "https://github.com/xplod24/ForgeGen/releases/download/build-1101/app-debug.apk",
            size = 1234,
            digest = digest,
        )

    private fun release(
        tag: String,
        assets: List<GitHubAssetDto> = listOf(apk()),
        body: String? = null,
    ) = GitHubReleaseDto(tagName = tag, name = "build-1101", body = body, publishedAt = "2026-09-24T10:00:00Z", assets = assets)

    @Before
    fun setup() {
        val mockApplication = mockk<Application>()
        val mockPackageManager = mockk<PackageManager>()
        mockApi = mockk()

        every { mockApplication.packageManager } returns mockPackageManager
        every { mockApplication.packageName } returns "com.example.forgegen"

        val mockPackageInfo = mockk<PackageInfo>()
        every { mockPackageInfo.longVersionCode } returns 1100L
        every { mockPackageManager.getPackageInfo("com.example.forgegen", 0) } returns mockPackageInfo

        updateManager =
            ForgeUpdateManager(
                application = mockApplication,
                gitHubApi = mockApi,
                getConfig = { testConfig },
                saveConfig = { testConfig = it },
                showToast = {},
                scope = TestScope(StandardTestDispatcher()),
            )
    }

    @Test
    fun `newer GitHub release is offered with its notes and digest`() =
        kotlinx.coroutines.runBlocking {
            val body = "What's new:\n- Added cool new feature\n- Naprawiono błąd\n\nBuilt by CI"
            coEvery { mockApi.getLatestRelease(ForgeUpdateManager.UPDATE_REPOSITORY) } returns
                Response.success(release("build-1101", body = body))

            updateManager.checkForUpdates(manual = true)

            // checkForUpdates runs on Dispatchers.IO
            var waitCount = 0
            while (updateManager.updateManifest.value == null && waitCount < 20) {
                kotlinx.coroutines.delay(100)
                waitCount++
            }

            val manifest = updateManager.updateManifest.value
            assertNotNull("Update manifest should not be null", manifest)
            assertEquals(1101, manifest?.versionCode)
            assertEquals("abc123", manifest?.sha256)
            assertEquals(apk().downloadUrl, manifest?.url)
            assertEquals(listOf("Added cool new feature", "Naprawiono błąd"), manifest?.changelog)
        }

    @Test
    fun `release that is not newer than the installed build is ignored`() =
        kotlinx.coroutines.runBlocking {
            coEvery { mockApi.getLatestRelease(any()) } returns Response.success(release("build-1100"))

            updateManager.checkForUpdates(manual = true)
            kotlinx.coroutines.delay(300)

            assertNull("Same version must not be offered as an update", updateManager.updateManifest.value)
        }

    @Test
    fun `only build tags with an APK asset count as updates`() {
        assertNull(release("release-main").toUpdateManifest())
        assertNull(release("build-1101", assets = emptyList()).toUpdateManifest())
        assertNull(release("build-1101", assets = listOf(apk().copy(name = "notes.txt"))).toUpdateManifest())
        assertEquals(1101, release("build-1101").toUpdateManifest()?.versionCode)
    }

    @Test
    fun `missing digest leaves sha256 empty instead of failing`() {
        assertNull(release("build-1101", assets = listOf(apk(digest = null))).toUpdateManifest()?.sha256)
    }
}
