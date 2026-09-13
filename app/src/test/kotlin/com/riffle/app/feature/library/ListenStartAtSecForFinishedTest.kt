package com.riffle.app.feature.library

import com.riffle.app.feature.library.listenStartAtSecForFinished
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenStartAtSecForFinishedTest {

    @Test
    fun `finished book returns 0`() {
        assertEquals(0.0, listenStartAtSecForFinished(1.0f))
    }

    @Test
    fun `in-progress book returns null`() {
        assertNull(listenStartAtSecForFinished(0.5f))
    }

    @Test
    fun `zero progress returns null`() {
        assertNull(listenStartAtSecForFinished(0.0f))
    }

    @Test
    fun `just below threshold returns null`() {
        assertNull(listenStartAtSecForFinished(0.99f))
    }

    @Test
    fun `exactly 1f returns 0`() {
        assertEquals(0.0, listenStartAtSecForFinished(1.0f))
    }
}
