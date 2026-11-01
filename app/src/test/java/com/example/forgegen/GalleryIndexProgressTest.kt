package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryIndexProgressTest {
    @Test fun discoveringFoldersKeepsTheCountWithoutInventingATotal() {
        val state = GalleryIndexProgress(stage = GalleryIndexStage.CHECKING_FOLDERS, indexed = 8_240)
        assertTrue(state.isActive)
        assertNull(state.total)
        assertNull(state.remaining)
        assertNull(state.fraction)
    }

    @Test fun incrementalWorkHasItsOwnDenominator() {
        val state =
            GalleryIndexProgress(
                stage = GalleryIndexStage.READING_IMAGES,
                indexed = 1_100,
                total = 1_120,
                processed = 100,
                workTotal = 120,
            )
        assertEquals(20, state.remaining)
        assertEquals(100f / 120, state.fraction!!, 0.0001f)
    }

    @Test fun finalizationDoesNotPretendTheEntireTaskHasFinished() {
        val state = GalleryIndexProgress(stage = GalleryIndexStage.FINISHING, indexed = 120, total = 120, processed = 120, workTotal = 120)
        assertTrue(state.isActive)
        assertNull(state.fraction)
    }

    @Test fun imageDetailsCanRemainAfterEveryImageHasBeenIndexed() {
        val state =
            GalleryIndexProgress(
                stage = GalleryIndexStage.READING_DETAILS,
                indexed = 1_120,
                total = 1_120,
                processed = 100,
                workTotal = 300,
                detailsRemaining = 200,
            )
        assertEquals(0, state.remaining)
        assertEquals(1f / 3, state.fraction!!, 0.0001f)
        assertEquals(200, state.detailsRemaining)
        assertFalse(state.copy(stage = GalleryIndexStage.WAITING_FOR_GENERATION).isActive)
    }

    @Test fun localDeletionAdjustsKnownCoverage() {
        val state = GalleryIndexProgress(source = "server", stage = GalleryIndexStage.UP_TO_DATE, indexed = 1_000, total = 1_000)
        val changed = state.afterReload("server", 995)
        assertEquals(995, changed.indexed)
        assertEquals(995, changed.total)
        assertEquals(0, changed.remaining)
    }

    @Test fun deletionFromAPartialIndexDoesNotMakeItComplete() {
        val state = GalleryIndexProgress(source = "server", stage = GalleryIndexStage.FAILED, indexed = 100, total = 205, error = "Offline")
        val changed = state.afterReload("server", 99)
        assertEquals(204, changed.total)
        assertEquals(105, changed.remaining)
        assertEquals("Offline", changed.error)
    }

    @Test fun aDifferentServerDoesNotInheritTheLastServersDetails() {
        val state =
            GalleryIndexProgress(
                source = "old server",
                stage = GalleryIndexStage.FAILED,
                indexed = 100,
                total = 205,
                unreadable = 12,
                error = "Offline",
            )
        val changed = state.afterReload("new server", 7)
        assertEquals(GalleryIndexStage.IDLE, changed.stage)
        assertEquals(7, changed.indexed)
        assertNull(changed.total)
        assertNull(changed.error)
        assertNull(changed.unreadable)
    }

    @Test fun anOlderMemorySnapshotCannotOverwriteActiveBatchProgress() {
        val state = GalleryIndexProgress(source = "server", stage = GalleryIndexStage.READING_IMAGES, indexed = 100, total = 205)
        assertSame(state, state.afterReload("server", 0))
    }
}
