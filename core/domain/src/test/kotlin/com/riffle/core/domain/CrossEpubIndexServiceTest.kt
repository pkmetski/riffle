package com.riffle.core.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossEpubIndexServiceTest {

    private val link = ReadaloudLink(
        storytellerSourceId = "st1",
        storytellerBookId = "book-1",
        absSourceId = "abs1",
        absLibraryItemId = "item-1",
        userConfirmed = false,
    )

    private val inputs = CrossEpubBuildInputs(
        absChecksum = EpubChecksum.of("abs-v1".toByteArray()),
        storytellerChecksum = EpubChecksum.of("st-v1".toByteArray()),
        absChaptersHtml = listOf("<html><body><p>Hello there.</p></body></html>"),
        storytellerChaptersHtml = listOf("<html><body><p>Hello there.</p></body></html>"),
    )

    private class FakeStore : CrossEpubIndexStore {
        val rows = mutableMapOf<Pair<String, String>, Pair<String, Long>>()
        override suspend fun exists(absChecksum: String, storytellerChecksum: String) =
            (absChecksum to storytellerChecksum) in rows
        override suspend fun put(absChecksum: String, storytellerChecksum: String, blob: String, builtAt: Long) {
            rows[absChecksum to storytellerChecksum] = blob to builtAt
        }
    }

    @Test
    fun `building on Confirm persists the index keyed by both EPUB checksums`() = runTest {
        val store = FakeStore()
        val service = CrossEpubIndexService(
            loadInputs = { inputs },
            store = store,
            clock = { 5000L },
        )

        val outcome = service.buildOnConfirm(link)

        val absChk = inputs.absChecksum
        val stChk = inputs.storytellerChecksum
        assertEquals(CrossEpubIndexBuildOutcome.Built(absChk, stChk), outcome)
        val (blob, builtAt) = store.rows.getValue(absChk to stChk)
        assertEquals(5000L, builtAt)
        // The persisted blob deserialises back to the expected per-chapter char map.
        assertEquals(
            CrossEpubIndex(listOf(ChapterCharMap(absChars = 12, storytellerChars = 12))),
            CrossEpubIndexSerializer.decode(blob),
        )
    }

    @Test
    fun `a missing prerequisite EPUB defers the build and persists nothing`() = runTest {
        val store = FakeStore()
        val service = CrossEpubIndexService(
            loadInputs = { null }, // e.g. offline at Confirm time
            store = store,
            clock = { 5000L },
        )

        val outcome = service.buildOnConfirm(link)

        assertEquals(CrossEpubIndexBuildOutcome.Deferred, outcome)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `an already-cached index for the same checksums is not rebuilt`() = runTest {
        val store = FakeStore()
        val absChk = inputs.absChecksum
        val stChk = inputs.storytellerChecksum
        store.put(absChk, stChk, "preexisting", 1L)
        var loadCalls = 0
        val service = CrossEpubIndexService(
            loadInputs = { loadCalls++; inputs },
            store = store,
            clock = { 9999L },
        )

        val outcome = service.buildOnConfirm(link)

        assertEquals(CrossEpubIndexBuildOutcome.AlreadyBuilt, outcome)
        // The pre-existing row is untouched (no rebuild).
        assertEquals("preexisting" to 1L, store.rows.getValue(absChk to stChk))
    }
}
