package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryTest {
    private val lost = "lost"

    private fun session(
        images: List<String> = listOf("a", "b", "c", "d"),
        index: Int = 3,
        batchStart: Int = 2,
        batchEnd: Int = 3,
        versionCode: Int = 100,
    ) = SavedSession(versionCode, images, index, batchStart, batchEnd, paused = true, pauseReason = "Paused by you", completed = 2)

    private fun afterUpdate(
        saved: SavedSession?,
        missing: Set<String> = emptySet(),
    ) = SessionMemory.afterUpdate(saved, runningVersion = 101) { it !in missing }

    @Test
    fun `only the first start of another build brings the session back`() {
        assertEquals(session(), afterUpdate(session()))
        assertNull("an ordinary restart", SessionMemory.afterUpdate(session(), runningVersion = 100) { true })
        assertNull("nothing saved", afterUpdate(null))
        assertNull("saved without a build", afterUpdate(session(versionCode = 0)))
    }

    @Test
    fun `images gone from the cache are left out and the positions follow`() {
        val s = afterUpdate(session(), missing = setOf("a"))!!
        assertEquals(listOf("b", "c", "d"), s.images)
        assertEquals("d is still shown", 2, s.index)
        assertEquals(1 to 2, s.batchStart to s.batchEnd)
    }

    @Test
    fun `a shown image that is gone gives way to the one before it in its batch`() {
        val s = afterUpdate(session(index = 3), missing = setOf("d"))!!
        assertEquals(listOf("a", "b", "c"), s.images)
        assertEquals(2 to 2, s.batchStart to s.batchEnd)
        assertEquals(2, s.index)
    }

    @Test
    fun `a batch that is gone leaves the newest image left, and no images leave an empty session`() {
        val s = afterUpdate(session(), missing = setOf("c", "d"))!!
        assertEquals(listOf("a", "b"), s.images)
        assertEquals(Triple(1, 1, 1), Triple(s.batchStart, s.batchEnd, s.index))
        val none = afterUpdate(session(), missing = setOf("a", "b", "c", "d"))!!
        assertTrue(none.images.isEmpty())
        assertEquals(Triple(0, -1, -1), Triple(none.batchStart, none.batchEnd, none.index))
        assertTrue("the pause still comes back", none.paused)
    }

    @Test
    fun `a pause comes back only with jobs left, and never one for a lost connection`() {
        assertTrue(SessionMemory.keepsPause(session(), runnableJobs = 2, connectionLostReason = lost))
        assertFalse("nothing left to hold back", SessionMemory.keepsPause(session(), runnableJobs = 0, connectionLostReason = lost))
        assertFalse(SessionMemory.keepsPause(session().copy(pauseReason = lost), runnableJobs = 2, connectionLostReason = lost))
        assertFalse(SessionMemory.keepsPause(session().copy(paused = false), runnableJobs = 2, connectionLostReason = lost))
    }
}
