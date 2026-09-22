package com.riffle.feature.designsystem

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.RecordingLogger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `RIFFLE_COVERS` line format. The instrumentation moved out of `:app` when
 * [BookCoverTile] became shared; these assertions are what stops the move from silently changing
 * the text the offline-cover investigation greps for, and they run on iOS too — which previously
 * logged nothing at all about covers.
 */
class CoverLoadLoggingTest {

    @Test
    fun hitLineNamesTheCoilDataSource() {
        val logger = RecordingLogger()
        LoggingCoverLoadReporter(logger).report("item", "abs-1", "https://host/c.jpg", hit = true, detail = "DISK")

        assertEquals(1, logger.records.size)
        assertEquals(LogChannel.Covers, logger.records.single().channel)
        assertEquals(
            "hit kind=item key=abs-1 source=DISK url=https://host/c.jpg",
            logger.records.single().message,
        )
    }

    @Test
    fun missLineNamesTheThrowable() {
        val logger = RecordingLogger()
        LoggingCoverLoadReporter(logger).report("series", null, null, hit = false, detail = "IOException")

        assertEquals(
            "miss kind=series key=null err=IOException url=null",
            logger.records.single().message,
        )
    }
}
