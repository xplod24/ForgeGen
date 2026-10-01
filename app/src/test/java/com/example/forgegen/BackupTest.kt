package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.5.2-1: the backup (format 2) carries the gallery favorites and the queue, for the move to the app without ".debug";
 * 3.6.0 (format 3) the generation history too.
 */
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
        assertTrue(json.contains("\"format\": 3"))
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

    private fun run(
        id: String,
        curve: String? = "0:7.6,1000:9.0",
    ) = JobRunEntity(
        id,
        "http://pc:7860",
        "q-$id",
        1_000L,
        "animagineXL31.safetensors [abc]",
        "",
        null,
        "SAME",
        false,
        832,
        1216,
        2,
        28,
        "Euler a",
        "Karras",
        null,
        null,
        null,
        null,
        700L,
        9_000L,
        null,
        800L,
        10_500L,
        7.4f,
        null,
        7.6f,
        9.0f,
        12f,
        curve,
        "DONE",
        null,
        null,
    )

    @Test
    fun `3_6_0 - the generation history comes back from a backup, VRAM readings and all`() {
        val jobs =
            listOf(run("a"), run("b", curve = null).copy(outcome = "FAILED", failure = "OUT_OF_VRAM", failureText = "CUDA out of memory"))
        val json = Backup.write(AppConfig(), emptyList(), "3.6.0", jobs = jobs)
        assertEquals(jobs, Backup.read(json)!!.jobs)
    }

    @Test
    fun `3_6_0 - a format 2 backup reads without a history, and a broken job is left out`() {
        val two = """{"app":"ForgeGen","format":2,"version":"3.5.3","config":{},"wildcards":[],"favorites":[],"queue":[]}"""
        assertTrue(Backup.read(two)!!.jobs.isEmpty())
        val broken =
            """{"app":"ForgeGen","format":3,"config":{},"wildcards":[],"jobs":[{"id":"x"},""" +
                com.google.gson
                    .Gson()
                    .toJson(run("ok")) + """,7]}"""
        assertEquals(listOf("ok"), Backup.read(broken)!!.jobs.map { it.id })
        val notList = """{"app":"ForgeGen","format":3,"config":{},"wildcards":[],"jobs":{"id":"x"}}"""
        assertTrue(Backup.read(notList)!!.jobs.isEmpty())
    }
}
