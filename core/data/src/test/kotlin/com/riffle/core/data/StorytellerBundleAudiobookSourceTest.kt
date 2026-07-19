package com.riffle.core.data

import com.riffle.core.data.testing.TestApplicationScope
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StorytellerBundleAudiobookSourceTest {

    private val link = ReadaloudLink(
        storytellerSourceId = "st", storytellerBookId = "book-1",
        absSourceId = "abs", absLibraryItemId = "item-1", userConfirmed = true,
    )

    private class FakeLinks(private val all: List<ReadaloudLink>) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = MutableStateFlow(all)
        override fun observeLinkedAbsItemIds() = MutableStateFlow(all.map { it.absLibraryItemId }.toSet())
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String) =
            all.firstOrNull { it.absSourceId == absSourceId && it.absLibraryItemId == absLibraryItemId }
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) =
            all.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
        override suspend fun countForSource(sourceId: String) = all.count { it.absSourceId == sourceId }
    }

    private class FakeAudio(
        private val bundle: File?,
        private val track: ReadaloudTrack?,
    ) : ReadaloudAudioRepository {
        override fun isAudioAvailable(sourceId: String, itemId: String) = bundle != null
        override fun bundleFile(sourceId: String, itemId: String) = bundle
        override suspend fun readTrack(sourceId: String, itemId: String) = track
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit) =
            com.riffle.core.domain.AudioDownloadResult.Success
        override suspend fun removeAudio(sourceId: String, itemId: String) = 0L
    }

    private val track = ReadaloudTrack(
        listOf(MediaOverlayClip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0)),
    )
    private val bundle = File("/tmp/book-1.epub")

    private fun snapshotFor(links: FakeLinks, appScope: TestApplicationScope) =
        OfflineAvailabilitySnapshot(
            applicationScope = appScope,
            source = links.observeAll().map(::readaloudLinksByAbsItemKey),
        )

    @Test
    fun `localSession resolves link then bundle then builds a session`() = runTest {
        val appScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val links = FakeLinks(listOf(link))
        try {
            val source = StorytellerBundleAudiobookSource(
                readaloudLinkRepository = links,
                readaloudAudioRepository = FakeAudio(bundle, track),
                linksByAbsItem = snapshotFor(links, appScope),
            )

            val session = source.localSession("abs", "item-1")!!

            assertEquals(listOf("audio/0.mp3"), session.trackUrls)
            assertEquals(bundle, session.localZipFile)
            assertEquals(30.0, session.timeline.durationSec, 1e-9)
        } finally {
            appScope.coroutineScope.cancel()
        }
    }

    @Test
    fun `localSession is null with no link, no bundle, or no overlay`() = runTest {
        val appScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        try {
            val noLink = FakeLinks(emptyList())
            assertNull(
                StorytellerBundleAudiobookSource(noLink, FakeAudio(bundle, track), snapshotFor(noLink, appScope))
                    .localSession("abs", "item-1"),
            )
            val withLink = FakeLinks(listOf(link))
            assertNull(
                StorytellerBundleAudiobookSource(withLink, FakeAudio(null, track), snapshotFor(withLink, appScope))
                    .localSession("abs", "item-1"),
            )
            assertNull(
                StorytellerBundleAudiobookSource(withLink, FakeAudio(bundle, null), snapshotFor(withLink, appScope))
                    .localSession("abs", "item-1"),
            )
        } finally {
            appScope.coroutineScope.cancel()
        }
    }

    @Test
    fun `isAvailableOffline reflects the link snapshot and bundle presence`() = runTest {
        val appScope = TestApplicationScope(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        try {
            // Unconfined → the init collector runs eagerly to the StateFlow's first (current) value, so
            // the snapshot is populated synchronously before these assertions, no scheduler advance needed.
            val withLink = FakeLinks(listOf(link))
            val present = StorytellerBundleAudiobookSource(
                withLink, FakeAudio(bundle, track), snapshotFor(withLink, appScope),
            )
            assertTrue(present.isAvailableOffline("abs", "item-1"))
            assertFalse(present.isAvailableOffline("abs", "other"))

            val noBundle = StorytellerBundleAudiobookSource(
                withLink, FakeAudio(null, track), snapshotFor(withLink, appScope),
            )
            assertFalse(noBundle.isAvailableOffline("abs", "item-1"))
        } finally {
            appScope.coroutineScope.cancel()
        }
    }
}
