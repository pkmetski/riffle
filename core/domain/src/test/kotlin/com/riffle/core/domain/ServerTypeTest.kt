package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTypeTest {

    @Test
    fun `new writes serialise Storyteller as STORYTELLER_SERVICE`() {
        assertEquals("STORYTELLER_SERVICE", ServerType.STORYTELLER_SERVICE.name)
    }

    // Legacy alias — pre-migration rows written before the ADR 0041 rename used "STORYTELLER".
    // If MIGRATION_48_49 fails to run for any reason (e.g. downgrade + re-upgrade path), this
    // fallback keeps such rows readable and identifies them as the same Storyteller Service.
    @Test
    fun `fromStorageString maps legacy STORYTELLER value to STORYTELLER_SERVICE`() {
        assertEquals(ServerType.STORYTELLER_SERVICE, ServerType.fromStorageString("STORYTELLER"))
    }

    @Test
    fun `fromStorageString round-trips STORYTELLER_SERVICE`() {
        assertEquals(ServerType.STORYTELLER_SERVICE, ServerType.fromStorageString("STORYTELLER_SERVICE"))
    }

    @Test
    fun `fromStorageString returns AUDIOBOOKSHELF for unknown values`() {
        assertEquals(ServerType.AUDIOBOOKSHELF, ServerType.fromStorageString("BOGUS"))
    }

    @Test
    fun `fromStorageString round-trips AUDIOBOOKSHELF`() {
        assertEquals(ServerType.AUDIOBOOKSHELF, ServerType.fromStorageString("AUDIOBOOKSHELF"))
    }
}
