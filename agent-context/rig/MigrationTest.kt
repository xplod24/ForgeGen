package com.example.forgegen

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A database of version 13 (where this app started, 3.5.2-2) opens with the migration to 14 (3.6.0) and keeps its rows;
 * an older one, never this app's, is built anew (3.6.0-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test
    fun `a database older than 13 is built anew, as no step leads from it`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val name = "migration_test.db"
        app.deleteDatabase(name)
        // A database as the app makes it now, then turned back into version 12 (an app this one never was).
        Room.databaseBuilder(app, ForgeDatabase::class.java, name).build().apply {
            runBlocking {
                galleryImageDao().insertImages(
                    listOf(GalleryImageEntity("/out/a.png", "a.png", "2026-09-27 10:00:00", "a cat", "ugly", "m", "Euler a", "7", "detail", 5L, 1234L)),
                )
                appSettingDao().putSetting(AppSettingEntity("config", "{}"))
            }
            close()
        }
        val path = app.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE `job_runs`")
            db.version = 12
        }
        // As ForgeRepository opens it (3.6.0-2: only 13 -> 14 is left).
        val opened =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, name)
                .addMigrations(ForgeRepository.MIGRATION_13_14)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        runBlocking {
            assertTrue("built anew, empty", opened.galleryImageDao().getIndexedImages().isEmpty())
            assertNull(opened.appSettingDao().getSetting("config"))
            assertEquals(0, opened.jobRunDao().count())
            // And it works as a new one.
            opened.appSettingDao().putSetting(AppSettingEntity("config", "{}"))
            assertEquals("{}", opened.appSettingDao().getSetting("config")?.value)
        }
        opened.close()
    }

    @Test
    fun `version 13 moves to 14 with the details to read once more and an empty job history`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val name = "migration_test_14.db"
        app.deleteDatabase(name)
        Room.databaseBuilder(app, ForgeDatabase::class.java, name).build().apply {
            runBlocking {
                galleryImageDao().insertImages(
                    listOf(
                        GalleryImageEntity("/out/a.png", "a.png", "2026-09-27 10:00:00", "a cat", "ugly", "m", "Euler a", "7", "", 5L, 1234L),
                        GalleryImageEntity("/out/b.png", "b.png", "2026-09-27 11:00:00", "", "", "", "", "", "", 0L, 99L),
                    ),
                )
                favoriteImageDao().insertFavorite(FavoriteImageEntity("/out/a.png", "a.png", "2026-09-27 10:00:00"))
            }
            close()
        }
        val path = app.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TABLE `gallery_old` (`fullpath` TEXT NOT NULL, `name` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                    "`positivePrompt` TEXT NOT NULL, `negativePrompt` TEXT NOT NULL, `model` TEXT NOT NULL, `sampler` TEXT NOT NULL, " +
                    "`seed` TEXT NOT NULL, `loras` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `size` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`fullpath`))",
            )
            db.execSQL(
                "INSERT INTO `gallery_old` SELECT fullpath, name, date, positivePrompt, negativePrompt, model, sampler, seed, loras, " +
                    "savedAt, size FROM gallery_images",
            )
            db.execSQL("DROP TABLE `gallery_images`")
            db.execSQL("ALTER TABLE `gallery_old` RENAME TO `gallery_images`")
            db.execSQL("DROP TABLE `job_runs`")
            db.version = 13
        }
        val migrated =
            Room
                .databaseBuilder(app, ForgeDatabase::class.java, name)
                .addMigrations(ForgeRepository.MIGRATION_13_14)
                .build()
        runBlocking {
            val dao = migrated.galleryImageDao()
            assertEquals(2, dao.getIndexedImages().size)
            assertEquals("a cat", dao.getPositivePrompt("/out/a.png"))
            assertTrue(migrated.favoriteImageDao().isFavorite("/out/a.png"))
            // Only the image whose data was read waits for its details; the unread one waits for the sync.
            assertEquals(1, dao.countDetailsBacklog(IndexDetails.VERSION))
            val backlog = dao.getDetailsBacklog(IndexDetails.VERSION, 100).single()
            assertEquals("/out/a.png", backlog.fullpath)
            assertEquals(1234L, backlog.size)
            val details = IndexDetails.of(Infotext.parse("a cat\nSteps: 28, Sampler: Euler a, CFG scale: 5, Seed: 7, Size: 832x1216"))
            dao.updateDetails(listOf(GalleryImageDetails.of("/out/a.png", details)))
            assertEquals(0, dao.countDetailsBacklog(IndexDetails.VERSION))
            val row = dao.getStatsRows(10, 0).single { it.fullpath == "/out/a.png" }
            assertEquals(832, row.width)
            assertEquals(28, row.steps)
            assertEquals("a cat", dao.getPositivePrompt("/out/a.png"))
            // The statistics rows open their images (real SQLite: TRIM, IFNULL, the float CFG).
            assertEquals(listOf("/out/a.png"), dao.findPathsBySampler("Euler a", ""))
            assertEquals(emptyList<String>(), dao.findPathsBySampler("Euler a", "Karras"))
            assertEquals(listOf("/out/a.png"), dao.findPathsBySize(832, 1216))
            assertEquals(listOf("/out/a.png"), dao.findPathsByCfg(5f))
            assertEquals(listOf("/out/a.png"), dao.findPathsBySteps(28))
            assertEquals(listOf("/out/a.png"), dao.findPathsByModules(""))
            // The job history starts empty and takes jobs.
            val jobs = migrated.jobRunDao()
            assertEquals(0, jobs.count())
            val run =
                JobRunEntity(
                    "j1", "http://pc:7860", "q1", 1000L, "m", "", null, "SAME", false, 832, 1216, 2, 28, "Euler a", "Karras",
                    null, null, null, null, 700L, 9000L, null, 800L, 10500L, 7.4f, null, 7.6f, 9.1f, 12f, "0:7.6,1000:7.9",
                    "DONE", null, null,
                )
            jobs.insert(run)
            assertEquals(1, jobs.count())
            assertNull("the list leaves the readings out", jobs.getAllWithoutCurves().single().vramCurve)
            assertEquals("0:7.6,1000:7.9", jobs.get("j1")?.vramCurve)
            assertEquals(listOf(-1L), jobs.insertMissing(listOf(run.copy(totalMs = 1))))
            assertEquals(10500L, jobs.get("j1")?.totalMs)
        }
        migrated.close()
    }
}
