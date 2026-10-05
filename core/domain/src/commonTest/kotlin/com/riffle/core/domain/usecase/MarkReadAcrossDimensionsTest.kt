package com.riffle.core.domain.usecase

import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.ProgressSyncCycleResult
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceUrl
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.SyncSessionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.Test

/**
 * Use-case-level test: the cross-dimension mark-read choreography must touch every ABS item
 * coupled to the readaloud bundle, not just the opened one. This is the audiobook PATCH bug area
 * described in issue #322 — it used to live spread across LibraryItemDetailViewModel.
 */
class MarkReadAcrossDimensionsTest {

    private class RecordingMutator : LibraryMutator {
        val progressCalls = mutableListOf<Triple<String, String, Float>>()
        override suspend fun markItemOpened(itemId: String) = Unit
        override suspend fun currentReadingProgress(itemId: String): Float? = null
        override suspend fun currentReadingProgress(sourceId: String, itemId: String): Float? = null
        override suspend fun updateReadingProgress(itemId: String, progress: Float) {
            progressCalls += Triple("", itemId, progress)
        }
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
            progressCalls += Triple(sourceId, itemId, progress)
        }
        override suspend fun deleteItem(sourceId: String, itemId: String) = Unit
    }

    private class RecordingSession : ReadingSessionRepository {
        val finished = mutableListOf<Triple<String, String, Boolean>>()
        override suspend fun syncProgress(itemId: String, payload: SessionPayload) = SyncSessionResult.Success
        override suspend fun runSyncCycle(itemId: String, payload: SessionPayload, sourceId: String?) = ProgressSyncCycleResult.InSync
        override suspend fun markFinished(sourceId: String, itemId: String, finished: Boolean) { this.finished += Triple(sourceId, itemId, finished) }
        override suspend fun touchOpenTimestamp(itemId: String) = Unit
    }

    private class LinkRepo(private val links: Map<String, ReadaloudLink>) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(links.values.toList())
        override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(links.keys)
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) = links[absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) =
            links.values.filter { it.storytellerBookId == storytellerBookId }
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
        override suspend fun countForSource(sourceId: String) = links.size
        override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) = Unit
    }

    private class FakeServerRepository(private val active: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOfNotNull(active))
        override suspend fun getActive(): Source? = active
        override suspend fun getById(sourceId: String): Source? =
            if (active?.id == sourceId) active else null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun activeServer(id: String = "abs-1") = Source(
        id = id,
        url = SourceUrl.parse("https://abs.example.com")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
    )

    private fun link(absItem: String, bookId: String, server: String = "abs-1") =
        ReadaloudLink("st-1", bookId, server, absItem, userConfirmed = true, identityResult = AudiobookIdentityResult.UNKNOWN)

    @Test
    fun marksReadAcrossBothCoupledAbsItemsOnTheActiveServer() = runTest {
        val mutator = RecordingMutator()
        val session = RecordingSession()
        val links = LinkRepo(
            mapOf(
                "ebook-item" to link("ebook-item", "book-42"),
                "audio-item" to link("audio-item", "book-42"),
            ),
        )
        val useCase = MarkReadAcrossDimensions(mutator, session, links, FakeServerRepository(activeServer()))

        useCase("abs-1", "ebook-item", finished = true)

        assertEquals(
            setOf(Triple("abs-1", "ebook-item", 1.0f), Triple("abs-1", "audio-item", 1.0f)),
            mutator.progressCalls.toSet(),
        )
        assertEquals(
            setOf(Triple("abs-1", "ebook-item", true), Triple("abs-1", "audio-item", true)),
            session.finished.toSet(),
        )
    }

    @Test
    fun fallsBackToTheOpenedItemWhenThereIsNoLink() = runTest {
        val mutator = RecordingMutator()
        val session = RecordingSession()
        val useCase = MarkReadAcrossDimensions(mutator, session, LinkRepo(emptyMap()), FakeServerRepository(activeServer()))

        useCase("abs-1", "lonely-item", finished = false)

        assertEquals(listOf(Triple("abs-1", "lonely-item", 0.0f)), mutator.progressCalls)
        assertEquals(listOf(Triple("abs-1", "lonely-item", false)), session.finished)
    }

    @Test
    fun usesCallerSourceIdNotActiveSourceForWebSourceItems() = runTest {
        // Regression test: before the fix, MarkReadAcrossDimensions used sourceRepository.getActive()?.id
        // as the sourceId, causing writes to (absSourceId, podcastId) — a non-existent row — when
        // an ABS server was active and the user marked a radio.es podcast as read.
        val mutator = RecordingMutator()
        val session = RecordingSession()
        val useCase = MarkReadAcrossDimensions(
            mutator, session,
            LinkRepo(emptyMap()),
            FakeServerRepository(activeServer(id = "abs-server")),
        )

        useCase("radio-es-source", "podcast-42", finished = true)

        // Writes must target the radio.es source row, not the ABS row.
        assertEquals(listOf(Triple("radio-es-source", "podcast-42", 1.0f)), mutator.progressCalls)
        assertEquals(listOf(Triple("radio-es-source", "podcast-42", true)), session.finished)
    }
}
