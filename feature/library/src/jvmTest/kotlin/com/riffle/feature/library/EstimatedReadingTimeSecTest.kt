package com.riffle.feature.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EstimatedReadingTimeSecTest {

    @Test
    fun `personalized estimate multiplies Readium positions by historical speed`() {
        assertEquals(7_560L, estimatedReadingTimeSec(totalPositions = 120, secPerPosition = 63.0))
    }

    @Test
    fun `invalid estimate inputs do not surface a misleading duration`() {
        assertNull(estimatedReadingTimeSec(totalPositions = 0, secPerPosition = 63.0))
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = Double.NaN))
    }

    @Test
    fun `zero or negative speed produces no estimate`() {
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = 0.0))
        assertNull(estimatedReadingTimeSec(totalPositions = 120, secPerPosition = -1.0))
    }
}
