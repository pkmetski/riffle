package com.riffle.app.feature.reader

import com.riffle.core.data.CycleOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubReaderAnnotationSyncIndicatorMappingTest {

    @Test
    fun `Auth failure maps to AuthOrTlsError regardless of pending`() {
        assertEquals(
            AnnotationSyncIndicator.AuthOrTlsError,
            mapAnnotationSyncIndicator(0, CycleOutcome.Failed.Auth(0L, 401)),
        )
        assertEquals(
            AnnotationSyncIndicator.AuthOrTlsError,
            mapAnnotationSyncIndicator(3, CycleOutcome.Failed.Auth(0L, 401)),
        )
    }

    @Test
    fun `Tls failure maps to AuthOrTlsError regardless of pending`() {
        assertEquals(
            AnnotationSyncIndicator.AuthOrTlsError,
            mapAnnotationSyncIndicator(0, CycleOutcome.Failed.Tls(0L, "x")),
        )
        assertEquals(
            AnnotationSyncIndicator.AuthOrTlsError,
            mapAnnotationSyncIndicator(5, CycleOutcome.Failed.Tls(0L, "x")),
        )
    }

    @Test
    fun `pending with Network failure maps to Offline`() {
        assertEquals(
            AnnotationSyncIndicator.Offline,
            mapAnnotationSyncIndicator(2, CycleOutcome.Failed.Network(0L, "x")),
        )
    }

    @Test
    fun `pending with no failure maps to Pending`() {
        assertEquals(
            AnnotationSyncIndicator.Pending,
            mapAnnotationSyncIndicator(1, CycleOutcome.Success(0L)),
        )
        assertEquals(
            AnnotationSyncIndicator.Pending,
            mapAnnotationSyncIndicator(1, CycleOutcome.NeverRun),
        )
    }

    @Test
    fun `no pending and no failure maps to null`() {
        assertNull(mapAnnotationSyncIndicator(0, CycleOutcome.Success(0L)))
        assertNull(mapAnnotationSyncIndicator(0, CycleOutcome.NeverRun))
    }

    @Test
    fun `no pending and Network failure still maps to null`() {
        // Network without pending = transient blip with nothing to push; don't nag the user.
        assertNull(mapAnnotationSyncIndicator(0, CycleOutcome.Failed.Network(0L, "x")))
    }

    @Test
    fun `Server failure with no pending maps to null`() {
        assertNull(mapAnnotationSyncIndicator(0, CycleOutcome.Failed.Server(0L, 500)))
    }
}
