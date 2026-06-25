package com.riffle.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationSyncExceptionMappingTest {

    @Test
    fun `AuthFailed maps to Failed Auth`() {
        val out = AnnotationSyncException.AuthFailed(code = 401).toFailedCycleOutcome(at = 10L)
        assertTrue(out is CycleOutcome.Failed.Auth)
        assertEquals(10L, out.atMs)
        assertEquals(401, (out as CycleOutcome.Failed.Auth).code)
    }

    @Test
    fun `HttpFailure maps to Failed Server`() {
        val out = AnnotationSyncException.HttpFailure(code = 503, operation = "write").toFailedCycleOutcome(at = 10L)
        assertTrue(out is CycleOutcome.Failed.Server)
        assertEquals(10L, out.atMs)
        assertEquals(503, (out as CycleOutcome.Failed.Server).code)
    }

    @Test
    fun `NetworkError maps to Failed Network`() {
        val out = AnnotationSyncException.NetworkError("offline").toFailedCycleOutcome(at = 10L)
        assertTrue(out is CycleOutcome.Failed.Network)
        assertEquals(10L, out.atMs)
        assertEquals("offline", (out as CycleOutcome.Failed.Network).message)
    }

    @Test
    fun `TlsError maps to Failed Tls`() {
        val out = AnnotationSyncException.TlsError("untrusted cert").toFailedCycleOutcome(at = 10L)
        assertTrue(out is CycleOutcome.Failed.Tls)
        assertEquals(10L, out.atMs)
        assertEquals("untrusted cert", (out as CycleOutcome.Failed.Tls).message)
    }

    @Test
    fun `bare Exception maps to Failed Unknown`() {
        val out = RuntimeException("boom").toFailedCycleOutcome(at = 10L)
        assertTrue(out is CycleOutcome.Failed.Unknown)
        assertEquals("boom", (out as CycleOutcome.Failed.Unknown).message)
    }
}
