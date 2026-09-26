package com.example.forgegen

import org.junit.Assert.assertEquals
import org.junit.Test

class OomLogsTest {
    @Test
    fun `the report leaves out HTTP traffic and API error bodies`() {
        val log =
            listOf(
                "09-26 10:00:00.001  100  101 I ForgeQueueManager: Generation started",
                "09-26 10:00:00.002  100  102 I okhttp.OkHttpClient: --> POST http://pc:7860/sdapi/v1/txt2img",
                "09-26 10:00:00.003  100  102 I okhttp.OkHttpClient: {\"prompt\":\"secret prompt\"}",
                "09-26 10:00:01.000  100  103 E ForgeSettingsManager: API ERROR [422]: http://pc:7860/sdapi/v1/txt2img",
                "09-26 10:00:01.000  100  103 E ForgeSettingsManager: Body: {\"detail\":\"secret prompt\"}",
                "09-26 10:00:01.000  100  103 E ForgeSettingsManager: }",
                "09-26 10:00:02.000  100  101 E ForgeQueueManager: Unexpected error",
                "--------- beginning of crash",
            )
        assertEquals(
            listOf(
                "09-26 10:00:00.001  100  101 I ForgeQueueManager: Generation started",
                "09-26 10:00:02.000  100  101 E ForgeQueueManager: Unexpected error",
                "--------- beginning of crash",
            ),
            OomLogs.filterLog(log.asSequence()).toList(),
        )
    }
}
