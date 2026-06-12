package com.riffle.core.data

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        storytellerServerId = "st", storytellerBookId = "book-1",
        absServerId = "abs", absLibraryItemId = "item-1", userConfirmed = true,
    )

    private class FakeLinks(private val all: List<ReadaloudLink>) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = MutableStateFlow(all)
        override fun observeLinkedAbsItemIds() = MutableStateFlow(all.map { it.absLibraryItemId }.toSet())
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) =
            all.firstOrNull { it.absServerId == absServerId && it.absLibraryItemId == absLibraryItemId }
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            all.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) = Unit
        override suspend fun countForServer(serverId: String) = all.count { it.absServerId == serverId }
    }

    private class FakeAudio(
        private val bundle: File?,
        private val track: ReadaloudTrack?,
    ) : ReadaloudAudioRepository {
        override fun isAudioAvailable(serverId: String, itemId: String) = bundle != null
        override fun bundleFile(serverId: String, itemId: String) = bundle
        override suspend fun readTrack(serverId: String, itemId: String) = track
        override suspend fun probeSizeBytes(serverId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(serverId: String, bookId: String, onProgress: (Long, Long) -> Unit) =
            com.riffle.core.domain.AudioDownloadResult.Success
        override suspend fun removeAudio(serverId: String, itemId: String) = 0L
    }

    private val track = ReadaloudTrack(
        listOf(MediaOverlayClip("c1.xhtml#s0", "audio/0.mp3", 0.0, 30.0)),
    )
    private val bundle = File("/tmp/book-1.epub")

    @Test
    fun `localSession resolves link then bundle then builds a session`() = runTest {
        val source = StorytellerBundleAudiobookSource(
            readaloudLinkRepository = FakeLinks(listOf(link)),
            readaloudAudioRepository = FakeAudio(bundle, track),
            applicationScope = backgroundScope,
        )

        val session = source.localSession("abs", "item-1")!!

        assertEquals(listOf("audio/0.mp3"), session.trackUrls)
        assertEquals(bundle, session.localZipFile)
        assertEquals(30.0, session.timeline.durationSec, 1e-9)
    }

    @Test
    fun `localSession is null with no link, no bundle, or no overlay`() = runTest {
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(emptyList()), FakeAudio(bundle, track), backgroundScope)
                .localSession("abs", "item-1"),
        )
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(null, track), backgroundScope)
                .localSession("abs", "item-1"),
        )
        assertNull(
            StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(bundle, null), backgroundScope)
                .localSession("abs", "item-1"),
        )
    }

    @Test
    fun `isAvailableOffline reflects the link snapshot and bundle presence`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            // Unconfined → the init collector runs eagerly to the StateFlow's first (current) value, so
            // the snapshot is populated synchronously before these assertions, no scheduler advance needed.
            val present = StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(bundle, track), scope)
            assertTrue(present.isAvailableOffline("abs", "item-1"))
            assertFalse(present.isAvailableOffline("abs", "other"))

            val noBundle = StorytellerBundleAudiobookSource(FakeLinks(listOf(link)), FakeAudio(null, track), scope)
            assertFalse(noBundle.isAvailableOffline("abs", "item-1"))
        } finally {
            scope.cancel()
        }
    }
}
