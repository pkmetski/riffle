package com.riffle.core.catalog.oreilly

import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue
import kotlin.test.Test

class RequestPacerJitterTest {

    @Test
    fun `jitter interval is sampled within bounds`() {
        repeat(200) {
            val interval = OReillyCatalog.RequestPacer.randomInterval(minMs = 120L, maxMs = 400L)
            assertTrue(interval >= 120L, "interval $interval should be >= 120")
            assertTrue(interval <= 400L, "interval $interval should be <= 400")
        }
    }

    @Test
    fun `zero max interval returns zero`() {
        repeat(100) {
            val interval = OReillyCatalog.RequestPacer.randomInterval(minMs = 0L, maxMs = 0L)
            assertTrue(interval == 0L, "interval should be 0 for lazy path")
        }
    }

    @Test
    fun `fixed interval when min equals max`() {
        repeat(50) {
            val interval = OReillyCatalog.RequestPacer.randomInterval(minMs = 120L, maxMs = 120L)
            assertTrue(interval == 120L, "interval should be exactly 120 when min==max")
        }
    }

    @Test
    fun `pacer with jitter executes block and returns result`() = runBlocking {
        var fakeTime = 1000L
        val pacer = OReillyCatalog.RequestPacer(
            maxConcurrency = 1,
            minIntervalMs = 0L,
            maxIntervalMs = 0L,
            nowMs = { fakeTime },
        )
        val result = pacer.execute { 42 }
        assertTrue(result == 42)
    }
}
