package com.riffle.app.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ReleaseDateTest {

    @Test
    fun `release date label formats the GitHub timestamp as a localized date`() {
        assertEquals(
            "Jul 28, 2026",
            releaseDateLabel(
                publishedAt = "2026-07-28T21:14:00Z",
                locale = Locale.US,
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `release date label uses the device time zone`() {
        assertEquals(
            "Jul 29, 2026",
            releaseDateLabel(
                publishedAt = "2026-07-28T23:30:00Z",
                locale = Locale.US,
                zoneId = ZoneOffset.ofHours(2),
            ),
        )
    }

    @Test
    fun `release date label is omitted when the timestamp is absent or malformed`() {
        assertNull(releaseDateLabel(""))
        assertNull(releaseDateLabel("not-a-timestamp"))
    }
}
