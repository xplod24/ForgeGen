package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.5.2-1: the backup (format 2) carries the gallery favorites and the queue, for the move to the app without ".debug". */
class BackupTest {
    private val payload =
        Txt2ImgPayloadDto(
            "a cat",
            "blurry",
            20,
            7f,
            512,
            768,
            1,
            1,
            1234L,
            "Euler a",
            "Automatic",
            OverrideSettingsDto(1, null),
            false,
            2f,
            "Latent",
            0.7f,
        )

    @Test
    fun `favorites and the queue come back from a backup`() {
        val favorites =
            listOf(
                FavoriteImageEntity(fullpath = "/out/txt2img/a.png", name = "a.png", date = "2026-09-30", savedAt = 42L),
                FavoriteImageEntity(fullpath = "/out/txt2img/b.png", name = "b.png", date = null, savedAt = 43L),
            )
        val queue =
            listOf(
                QueuedGeneration(id = "1", positivePrompt = "a cat", payload = payload),
                QueuedGeneration(
                    id = "2",
                    positivePrompt = "a cat",
                    payload = payload,
                    status = GenerationStatus.FAILED,
                    error = "OOM",
                    label = "Upscale ×2",
                ),
            )
        val json = Backup.write(AppConfig(), emptyList(), "3.5.2-1", favorites, queue)
        assertTrue(json.contains("\"format\": 2"))
        val read = Backup.read(json)!!
        assertEquals(favorites, read.favorites)
        assertEquals(queue, read.queue)
    }

    @Test
    fun `a backup from before 3_5_2-1 has no favorites nor queue`() {
        val old = """{"app":"ForgeGen","format":1,"version":"3.5.2","config":{},"wildcards":[]}"""
        val read = Backup.read(old)!!
        assertTrue(read.favorites.isEmpty())
        assertTrue(read.queue.isEmpty())
    }

    @Test
    fun `broken entries are left out, the rest is kept`() {
        val json =
            """
            {"app":"ForgeGen","format":2,"config":{},"wildcards":[],
             "favorites":[{"name":"no path.png"},{"fullpath":"/out/c.png"},"text",{"fullpath":"  "}],
             "queue":[{"id":"x","positivePrompt":"p"},{"positivePrompt":"no id"},
                      {"id":"y","positivePrompt":"p","status":"QUEUED","payload":${com.google.gson.Gson().toJson(payload)}}]}
            """.trimIndent()
        val read = Backup.read(json)!!
        assertEquals(listOf("/out/c.png"), read.favorites.map { it.fullpath })
        assertEquals("c.png", read.favorites.single().name)
        assertNull(read.favorites.single().date)
        assertEquals(listOf("y"), read.queue.map { it.id })
    }

    @Test
    fun `a favorites or queue part that is not a list is ignored`() {
        val json = """{"app":"ForgeGen","config":{},"wildcards":[],"favorites":{"a":1},"queue":"none"}"""
        val read = Backup.read(json)!!
        assertTrue(read.favorites.isEmpty())
        assertTrue(read.queue.isEmpty())
    }
}
