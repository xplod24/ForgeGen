package com.example.forgegen

import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/** 1.5.0: "Start at" for the queue, and the estimated end of the queue. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class G30_QueueScheduleTest {
    companion object {
        val vm get() = TestApp.vm
        fun calls() = TestApp.forge.calls("/sdapi/v1/txt2img").size
        fun setting(key: String) = runBlocking { TestApp.db.appSettingDao().getSetting(key)?.value }
        fun queue(prompt: String) {
            onMain { vm.updateState { it.copy(positivePrompt = prompt, batchCount = 1) } }
            onMain { vm.queueGeneration() }
        }
        fun idle() = awaitUntil("queue done", 30_000) { vm.generationQueue.value.isEmpty() && !vm.isGenerating.value }

        @BeforeClass @JvmStatic fun init() {
            TestApp.start()
            android.content.ContextWrapper.base = TestApp.app // the service gets its system services from the app
        }
    }

    @Test fun `01 scheduled jobs wait without the wake lock and start at the time`() {
        TestApp.forge.generationMs = 1500
        val before = calls()
        val at = System.currentTimeMillis() + 4_000
        onMain { ForgeQueueManager.scheduleStart(at) }
        assertEquals("inexact@$at", TestApp.app.alarms.calls.last())
        awaitUntil("schedule saved") { setting("queue_scheduled_start") == at.toString() }
        queue("castle at night")
        queue("forest at dawn")
        awaitUntil("queued") { vm.generationQueue.value.size == 2 }

        val service = GenerationService()
        service.onCreate()
        service.onStartCommand(Intent(GenerationService.ACTION_START_GENERATION), 0, 1)
        val lock = TestApp.app.power.lastLock!!
        Thread.sleep(1_500)
        assertEquals("nothing sent before the time", before, calls())
        assertTrue(vm.isWaitingForSchedule.value)
        assertTrue("the service keeps the app alive", vm.isQueueActive.value)
        assertFalse("but lets the phone sleep", lock.isHeld)

        awaitUntil("started", 10_000) { calls() > before }
        val startedAt = System.currentTimeMillis()
        println("[G30-01] started ${startedAt - at} ms after the scheduled time")
        assertTrue(startedAt >= at - 1_000)
        assertNull(vm.scheduledStart.value)
        awaitUntil("wake lock while working") { lock.isHeld }
        idle()
        assertEquals(before + 2, calls())
        awaitUntil("schedule cleared in the database") { setting("queue_scheduled_start") == "" }
        service.onDestroy()
    }

    @Test fun `02 Start Now ends the wait at once`() {
        TestApp.forge.generationMs = 300
        val before = calls()
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 60_000) }
        queue("lighthouse")
        Thread.sleep(1_000)
        assertEquals(before, calls())
        onMain { vm.startScheduledQueueNow() }
        awaitUntil("sent", 5_000) { calls() == before + 1 }
        assertEquals("cancel", TestApp.app.alarms.calls.last())
        idle()
    }

    @Test fun `03 an alarm before the time does not start the queue, and wakes the phone for a moment`() {
        val at = System.currentTimeMillis() + 60_000
        onMain { ForgeQueueManager.scheduleStart(at) }
        QueueScheduleReceiver().onReceive(TestApp.app, Intent())
        assertEquals(at, vm.scheduledStart.value)
        assertTrue(TestApp.app.power.lastLock!!.acquires > 0)
        android.app.AlarmManager.exactAllowed = true
        onMain { ForgeQueueManager.scheduleStart(at + 1) }
        assertEquals("exact where Android allows it", "exact@${at + 1}", TestApp.app.alarms.calls.last())
        android.app.AlarmManager.exactAllowed = false
        onMain { vm.startScheduledQueueNow() }
    }

    @Test fun `04 finished jobs teach the estimate of the queue`() {
        val speed = setting("queue_speed")
        println("[G30-04] speed=$speed")
        assertNotNull("learned from the jobs above", speed)
        assertTrue(speed!!.contains("\"*\""))
        onMain { ForgeQueueManager.scheduleStart(System.currentTimeMillis() + 3_600_000) }
        queue("mountain lake")
        queue("mountain lake")
        awaitUntil("two queued") { vm.generationQueue.value.size == 2 }
        val rates: Map<String, Double> = com.google.gson.Gson().fromJson(speed, object : com.google.gson.reflect.TypeToken<Map<String, Double>>() {}.type)
        val expected = QueueEstimate.remaining(vm.generationQueue.value, rates, 0.0)!!
        awaitUntil("estimate") { vm.queueSecondsLeft.value != null }
        println("[G30-04] two jobs: ${vm.queueSecondsLeft.value} s (expected $expected)")
        assertEquals(expected.toLong(), vm.queueSecondsLeft.value)
        assertTrue(expected > 0)
        onMain { vm.clearQueue() }
        awaitUntil("empty") { vm.generationQueue.value.isEmpty() }
        awaitUntil("estimate follows") { vm.queueSecondsLeft.value == 0L }
        onMain { vm.startScheduledQueueNow() }
    }
}
