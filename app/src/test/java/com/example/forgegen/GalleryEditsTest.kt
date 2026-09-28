package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 3.2.0: paths of the server's gallery, written as the server writes them. */
class GalleryPathsTest {
    @Test
    fun `a file in a folder uses the folder's separator`() {
        assertEquals("C:\\sd\\out\\keepers", GalleryPaths.child("C:\\sd\\out\\", "keepers"))
        assertEquals("/srv/out/keepers", GalleryPaths.child("/srv/out", "keepers"))
        assertEquals("00012-7.png", GalleryPaths.nameOf("C:\\sd\\out\\2026-09-28\\00012-7.png"))
        assertEquals("C:\\sd\\out\\2026-09-28", GalleryPaths.parentOf("C:\\sd\\out\\2026-09-28\\00012-7.png"))
        assertTrue(GalleryPaths.same("C:\\SD\\Out\\", "c:/sd/out"))
    }

    @Test
    fun `a new folder's name is checked as Windows would`() {
        assertNull(GalleryPaths.folderNameProblem("keepers 2"))
        assertEquals("Type a name", GalleryPaths.folderNameProblem("  "))
        assertEquals("Not a folder name", GalleryPaths.folderNameProblem(".."))
        assertTrue(GalleryPaths.folderNameProblem("a/b") != null)
        assertTrue(GalleryPaths.folderNameProblem("what?") != null)
        assertTrue(GalleryPaths.folderNameProblem("dot.") != null)
        assertTrue(GalleryPaths.folderNameProblem("x".repeat(101)) != null)
    }
}

/** 3.2.0: what a move or copy sends, and what it could not do. */
class GalleryTransferTest {
    private fun image(path: String) = GalleryItem(GalleryPaths.nameOf(path), path, "file")

    @Test
    fun `images already there and names taken stay out`() {
        val items =
            listOf(
                image("/out/a/1.png"),
                image("/out/keep/2.png"), // already in the folder
                image("/out/a/3.png"), // "3.png" is there already
                image("/out/b/1.png"), // the same name as the first one sent
                image("/out/b/4.png"),
            )
        val plan = GalleryTransfer.plan(items, "/out/keep", listOf("3.PNG", "x.txt"))
        assertEquals(listOf("/out/a/1.png", "/out/b/4.png"), plan.send.map { it.fullpath })
        assertEquals(listOf("/out/keep/2.png"), plan.alreadyThere.map { it.fullpath })
        assertEquals(listOf("/out/a/3.png", "/out/b/1.png"), plan.nameTaken.map { it.fullpath })
    }

    @Test
    fun `the paths the server's errors name failed`() {
        val sent = listOf("C:\\out\\a\\1.png", "C:\\out\\a\\2.png")
        val errors = listOf("Error moving file C:\\out\\a\\2.png to C:\\out\\keep: [WinError 32] in use")
        assertEquals(setOf("C:\\out\\a\\2.png"), GalleryTransfer.failed(sent, errors))
        assertTrue(GalleryTransfer.failed(sent, emptyList()).isEmpty())
    }
}

/** 3.2.0: the numbers under folder covers and the statistics come from the index on the phone. */
class GalleryStatisticsTest {
    private fun indexed(
        path: String,
        date: String,
        model: String = "",
        loras: String = "",
        size: Long = 0,
    ) = IndexedImage(path, GalleryPaths.nameOf(path), date, model, loras, size)

    @Test
    fun `a folder counts the images of its subfolders too`() {
        val images =
            listOf(
                indexed("C:\\out\\2026-09-28\\1.png", ""),
                indexed("C:\\out\\keep\\best\\2.png", ""),
                indexed("C:\\out\\keep\\3.png", ""),
                indexed("C:\\out\\4.png", ""),
            )
        val counts = GalleryFolders.imageCounts(images, "C:\\out")
        assertEquals(1, counts["c:/out/2026-09-28"])
        assertEquals(2, counts["c:/out/keep"])
        assertEquals(1, counts["c:/out/keep/best"])
        assertNull(counts["c:/out"]) // the top folder is not a folder cell
    }

    @Test
    fun `the numbers, the days and what was used most`() {
        val today = LocalDate.of(2026, 9, 28)
        val images =
            listOf(
                indexed("/o/1.png", "2026-09-28 10:00:00", "animagine", "detail,flat", 1_000),
                indexed("/o/2.png", "2026-09-28 11:00:00", "animagine", "detail", 2_000),
                indexed("/o/3.png", "2026-09-02 09:00:00", "pony", "", 3_000),
                indexed("/o/4.png", "2025-01-01 09:00:00", "", "detail,detail", 0),
                indexed("/o/5.png", "broken", "flux"),
            )
        val stats = GalleryStatistics.compute(images, today, mapOf("1girl" to 5, "solo" to 2))
        assertEquals(5, stats.images)
        assertEquals(3, stats.thisMonth)
        assertEquals(6_000, stats.bytes)
        assertFalse(stats.sizesKnown)
        assertEquals(DayOfWeek.MONDAY, stats.firstDay.dayOfWeek)
        assertEquals(today, stats.firstDay.plusDays(stats.perDay.size - 1L))
        assertTrue(stats.perDay.size in (16 * 7 + 1)..(GalleryStatistics.WEEKS * 7))
        assertEquals(2, stats.perDay.last())
        assertEquals(2, stats.busiestDay)
        assertEquals(listOf("animagine" to 2, "flux" to 1, "pony" to 1), stats.topModels)
        assertEquals(listOf("detail" to 3, "flat" to 1), stats.topLoras) // once per image
        assertEquals(listOf("1girl" to 5, "solo" to 2), stats.topTags)
        assertEquals(0, stats.level(0))
        assertEquals(2, stats.level(1))
        assertEquals(GalleryStats.LEVELS, stats.level(2))
    }

    @Test
    fun `tags are counted without weights, LoRAs and BREAK, once per image`() {
        val counts = HashMap<String, Int>()
        GalleryStatistics.countTags("1girl, (long_hair:1.2), <lora:detail:0.8>, BREAK, 1girl, solo", counts)
        GalleryStatistics.countTags("Solo, masterpiece", counts)
        assertEquals(mapOf("1girl" to 1, "long hair" to 1, "solo" to 2, "masterpiece" to 1), counts)
    }

    @Test
    fun `sizes read as a file manager shows them`() {
        assertEquals("512 B", GalleryStatistics.formatBytes(512))
        assertEquals("1.5 KB", GalleryStatistics.formatBytes(1_536))
        assertEquals("812 MB", GalleryStatistics.formatBytes(812L * 1024 * 1024))
        assertEquals("41 GB", GalleryStatistics.formatBytes(41L * 1024 * 1024 * 1024))
    }

    @Test
    fun `random keeps every image, and another shuffle gives another order`() {
        val items = (1..50).toList()
        val first = AllImagesOrder(true, 1).apply(items)
        assertEquals(items.toSet(), first.toSet())
        assertEquals(first, AllImagesOrder(true, 1).apply(items)) // the same seed, the same order while it shows
        assertNotEquals(first, AllImagesOrder(true, 2).apply(items))
        assertEquals(items, AllImagesOrder().apply(items))
    }
}
