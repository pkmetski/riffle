package com.riffle.app.dictionary

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class PackDownloadWorkerOutcomeMappingTest {

    @Test
    fun `download success maps to Result success`() {
        assertEquals(
            ListenableWorker.Result.success(),
            downloadResultFor(downloadOk = true),
        )
    }

    @Test
    fun `download failure maps to Result retry`() {
        assertEquals(
            ListenableWorker.Result.retry(),
            downloadResultFor(downloadOk = false),
        )
    }
}
