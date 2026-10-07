package com.example.forgegen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class G14_RealGitHubJsonTest {
    @Test fun `prawdziwa odpowiedz GitHuba mapuje sie na aktualizacje`() {
        val json = javaClass.getResource("/real_release.json")!!.readText()
        val dto = Gson().fromJson(json, GitHubReleaseDto::class.java)
        println("[G14] tag=${dto.tagName} assets=${dto.assets?.map { it.name to it.digest }}")
        assertNull("stary tag release-main nie jest aktualizacją", dto.toUpdateManifest())
        val m = dto.copy(tagName = "v1.0.1").toUpdateManifest()!!
        assertEquals(100_000_100, m.versionCode) // v1.0.1, formula since 1.1.4-1
        assertTrue(m.url.endsWith(".apk"))
        assertEquals(64, m.sha256!!.length)
        println("[G14] ${m.copy(changelog = null)}")
    }
}
