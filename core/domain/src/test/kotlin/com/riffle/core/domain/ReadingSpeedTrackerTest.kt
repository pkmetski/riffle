package com.riffle.core.domain

import org.junit.Assert.*
import org.junit.Test

class ReadingSpeedTrackerTest {

    @Test
    fun `valid session blends observed rate into prior via EWMA`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 25f / 500f,
            timeDeltaSec = 1250.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNotNull(result)
        assertEquals(60.4, result!!, 0.01)
    }

    @Test
    fun `session shorter than 30 seconds is discarded`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 10f / 500f,
            timeDeltaSec = 29.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session moving fewer than 0·5 positions is discarded`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 0.4f / 500f,
            timeDeltaSec = 60.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session implying fewer than 20 WPM is discarded (left book open)`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 1f / 500f,
            timeDeltaSec = 760.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session implying more than 1000 WPM is discarded (scanning)`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 1f / 500f,
            timeDeltaSec = 14.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session at exactly the WPM upper bound is kept`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 2f / 500f,
            timeDeltaSec = 30.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNotNull(result)
    }

    @Test
    fun `default secs per position constant equals 63`() {
        assertEquals(63.0, ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION, 0.001)
    }
}
