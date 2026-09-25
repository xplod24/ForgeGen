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
            downloadUrl = "https://github.com/xplod24/ForgeGen/releases/download/v1.0.1/app-debug.apk",
            size = 1234,
            digest = digest,
        )

    private fun release(
        tag: String,
        assets: List<GitHubAssetDto> = listOf(apk()),
        body: String? = null,
    ) = GitHubReleaseDto(tagName = tag, name = tag, body = body, publishedAt = "2026-09-24T10:00:00Z", assets = assets)

    @Before
    fun setup() {
        val mockApplication = mockk<Application>()
        val mockPackageManager = mockk<PackageManager>()
        mockApi = mockk()

        every { mockApplication.packageManager } returns mockPackageManager
        every { mockApplication.packageName } returns "com.example.forgegen"

        val mockPackageInfo = mockk<PackageInfo>()
        every { mockPackageInfo.longVersionCode } returns 100_000_000L // 1.0.0
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
                Response.success(release("v1.0.1", body = body))

            updateManager.checkForUpdates(manual = true)

            // checkForUpdates runs on Dispatchers.IO
            var waitCount = 0
            while (updateManager.updateManifest.value == null && waitCount < 20) {
                kotlinx.coroutines.delay(100)
                waitCount++
            }

            val manifest = updateManager.updateManifest.value
            assertNotNull("Update manifest should not be null", manifest)
            assertEquals(100_000_100, manifest?.versionCode)
            assertEquals("1.0.1", manifest?.versionName)
            assertEquals("abc123", manifest?.sha256)
            assertEquals(apk().downloadUrl, manifest?.url)
            assertEquals(listOf("Added cool new feature", "Naprawiono błąd"), manifest?.changelog)
        }

    @Test
    fun `release that is not newer than the installed build is ignored`() =
        kotlinx.coroutines.runBlocking {
            coEvery { mockApi.getLatestRelease(any()) } returns Response.success(release("v1.0.0"))

            updateManager.checkForUpdates(manual = true)
            kotlinx.coroutines.delay(300)

            assertNull("Same version must not be offered as an update", updateManager.updateManifest.value)
        }

    @Test
    fun `only version tags with an APK asset count as updates`() {
        assertNull(release("release-main").toUpdateManifest())
        assertNull("old build-N tags are no longer releases", release("build-1034").toUpdateManifest())
        assertNull(release("v1.0.1", assets = emptyList()).toUpdateManifest())
        assertNull(release("v1.0.1", assets = listOf(apk().copy(name = "notes.txt"))).toUpdateManifest())
        assertEquals(100_000_100, release("v1.0.1").toUpdateManifest()?.versionCode)
        assertEquals(100_100_401, release("v1.1.4-1").toUpdateManifest()?.versionCode)
    }

    @Test
    fun `version tags map to the versionCode formula of the build script`() {
        assertEquals(100_000_000, versionCodeFromTag("v1.0.0"))
        assertEquals(100_401_200, versionCodeFromTag("v1.4.12"))
        assertEquals(200_000_000, versionCodeFromTag(" v2.0.0 "))
        assertEquals(100_100_401, versionCodeFromTag("v1.1.4-1"))
        assertEquals(versionCodeFromTag("v1.1.4"), versionCodeFromTag("v1.1.4-0"))
        // Releases stay above the old commit-count builds (build-1034 had versionCode 1034) and above the codes of
        // the old formula (1.1.4 had major * 1_000_000 + minor * 1_000 + patch = 1001004), and keep their order.
        assertEquals(true, versionCodeFromTag("v1.0.0")!! > 1034)
        assertEquals(true, versionCodeFromTag("v1.0.0")!! > 1_001_004)
        assertEquals(true, versionCodeFromTag("v1.9.999")!! < versionCodeFromTag("v1.10.0")!!)
        assertEquals(true, versionCodeFromTag("v1.1.4")!! < versionCodeFromTag("v1.1.4-1")!!)
        assertEquals(true, versionCodeFromTag("v1.1.4-99")!! < versionCodeFromTag("v1.1.5")!!)
        assertEquals(true, versionCodeFromTag("v20.999.999-99")!! <= Int.MAX_VALUE)
        assertNull(versionCodeFromTag("v1.1000.0"))
        assertNull(versionCodeFromTag("v21.0.0"))
        assertNull(versionCodeFromTag("v1.1.4-100"))
        assertNull(versionCodeFromTag("v1.1.4-"))
        assertNull(versionCodeFromTag("v1.1.4-beta"))
        assertNull(versionCodeFromTag("1.0.0"))
        assertNull(versionCodeFromTag("v1.0"))
        assertNull(versionCodeFromTag("V-1_0_0"))
    }

    @Test
    fun `missing digest leaves sha256 empty instead of failing`() {
        assertNull(release("v1.0.1", assets = listOf(apk(digest = null))).toUpdateManifest()?.sha256)
    }
}
