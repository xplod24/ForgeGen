package com.example.forgegen

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalNetworkPermissionTest {
    private val context = mockk<Context>()
    private val activity = mockk<Activity>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var requested = false
    private var granted = false

    @Before fun prepare() {
        mockkStatic(ContextCompat::class, ActivityCompat::class)
        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean(any(), false) } answers { requested }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), true) } answers {
            requested = true
            editor
        }
        every { editor.apply() } just Runs
        every { ContextCompat.checkSelfPermission(context, LocalNetworkPermission.PERMISSION) } answers {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    @After fun cleanUp() = unmockkAll()

    @Test fun olderAndroidDoesNotQueryOrRequestTheNewPermission() {
        for (sdk in 31..36) {
            assertTrue(LocalNetworkPermission.isGranted(context, sdk))
            assertFalse(LocalNetworkPermission.shouldRequestAtStartup(context, sdk))
        }
        verify(exactly = 0) { context.getSharedPreferences(any(), any()) }
        verify(exactly = 0) { ContextCompat.checkSelfPermission(any(), any()) }
    }

    @Test fun firstAndroid17LaunchAsksForMissingLocalAccess() {
        assertFalse(LocalNetworkPermission.isGranted(context, 37))
        assertTrue(LocalNetworkPermission.shouldRequestAtStartup(context, 37))
        assertTrue(LocalNetworkPermission.canRequest(context, activity))
    }

    @Test fun aDenialIsRememberedAcrossSubsequentStartupChecks() {
        LocalNetworkPermission.markRequested(context)
        assertTrue(LocalNetworkPermission.wasRequested(context))
        assertFalse(LocalNetworkPermission.shouldRequestAtStartup(context, 37))
        assertFalse(LocalNetworkPermission.isGranted(context, 37))
        verify { editor.apply() }
    }

    @Test fun anExistingNearbyDevicesGrantDoesNotPromptAgain() {
        granted = true
        assertFalse(LocalNetworkPermission.shouldRequestAtStartup(context, 37))
        assertTrue(LocalNetworkPermission.isGranted(context, 37))
    }

    @Test fun settingsCanRetryAfterAnOrdinaryDenial() {
        LocalNetworkPermission.markRequested(context)
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalNetworkPermission.PERMISSION) } returns true
        assertTrue(LocalNetworkPermission.canRequest(context, activity))
    }

    @Test fun permanentDenialRequiresSystemSettingsInsteadOfAnotherPrompt() {
        LocalNetworkPermission.markRequested(context)
        every { ActivityCompat.shouldShowRequestPermissionRationale(activity, LocalNetworkPermission.PERMISSION) } returns false
        assertFalse(LocalNetworkPermission.canRequest(context, activity))
    }

    @Test fun revocationIsReadFromAndroidRatherThanTheSavedRequestFlag() {
        LocalNetworkPermission.markRequested(context)
        granted = true
        assertTrue(LocalNetworkPermission.isGranted(context, 37))
        granted = false
        assertFalse(LocalNetworkPermission.isGranted(context, 37))
        assertFalse(LocalNetworkPermission.shouldRequestAtStartup(context, 37))
    }

    @Test fun noActivityCannotLaunchAPermissionDialog() {
        assertFalse(LocalNetworkPermission.canRequest(context, null))
    }
}
