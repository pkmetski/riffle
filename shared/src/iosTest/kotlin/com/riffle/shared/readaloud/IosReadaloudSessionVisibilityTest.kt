package com.riffle.shared.readaloud

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.feature.player.AudiobookHandoffState
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import com.riffle.shared.audiobook.BundleAudioExtractor
import com.riffle.shared.library.IosReadaloudHandoff
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that [IosReadaloudSession.readaloudVisible] matches Android's [readaloudControlState]
 * logic: visible only for Storyteller sources or ABS items with a Storyteller link; hidden for
 * unmatched ABS items.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosReadaloudSessionVisibilityTest {

    private val scheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(scheduler)

    private class FakeSourceRepository(private val source: Source?) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOfNotNull(source))
        override suspend fun getActive(): Source? = source
        override suspend fun getById(sourceId: String): Source? = source
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeLinkRepository(private val link: ReadaloudLink?) : ReadaloudLinkRepository {
        override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(listOfNotNull(link))
        override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = link
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = listOfNotNull(link)
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
        override suspend fun countForSource(sourceId: String): Int = if (link != null) 1 else 0
    }

    private fun noOpController(scope: CoroutineScope): IosReadaloudController =
        IosReadaloudController(
            bridge = object : IosReadaloudBridge {
                override fun prepareAudioSrcs(fileUrls: List<String>, startIndex: Int, startOffsetSec: Double) = Unit
                override fun play() = Unit
                override fun pause() = Unit
                override fun setSpeed(speed: Float) = Unit
                override fun seekToSrc(srcIndex: Int, offsetSec: Double) = Unit
                override fun currentSrcIndex(): Int = 0
                override fun currentOffsetSec(): Double = 0.0
                override fun isPlaying(): Boolean = false
                override fun setPositionCallback(callback: IosReadaloudPositionCallback?) = Unit
                override fun setPlayingCallback(callback: IosReadaloudPlayingCallback?) = Unit
                override fun dispose() = Unit
            },
            bundleAudioExtractor = object : BundleAudioExtractor {
                override suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String> = emptyList()
            },
            readaloudHandoff = IosReadaloudHandoff(),
            mainScope = scope,
        )

    private class FakeAudioRepository : ReadaloudAudioRepository {
        override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = false
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(
            sourceId: String,
            bookId: String,
            onProgress: (Long, Long) -> Unit,
        ): AudioDownloadResult = AudioDownloadResult.NoBundle
        override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
    }

    private class FakeBundleReader : ReadaloudBundleReader {
        override fun bundlePath(sourceId: String, itemId: String): String? = null
        override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = null
    }

    private class FakePreferencesStore : ReadaloudPreferencesStore {
        override val preferences: Flow<ReadaloudPreferences> = flowOf(ReadaloudPreferences())
        override suspend fun update(prefs: ReadaloudPreferences) = Unit
    }

    private class FakeResumeStore : ReadaloudResumeStore {
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) = Unit
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun clear(sourceId: String, itemId: String) = Unit
    }

    private fun fakeDispatcherProvider(dispatcher: CoroutineDispatcher) = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }

    private fun makeSource(serverType: ServerType): Source = Source(
        id = "src1",
        url = SourceUrl.parse("http://test.local")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "test",
        serverType = serverType,
    )

    private fun makeLink(): ReadaloudLink = ReadaloudLink(
        storytellerSourceId = "st-src",
        storytellerBookId = "st-book",
        absSourceId = "src1",
        absLibraryItemId = "item1",
        userConfirmed = true,
        identityResult = AudiobookIdentityResult.UNKNOWN,
    )

    private fun makeSession(sourceRepository: SourceRepository, linkRepository: ReadaloudLinkRepository): IosReadaloudSession {
        val dispatchers = fakeDispatcherProvider(testDispatcher)
        val scope = CoroutineScope(testDispatcher)
        return IosReadaloudSession(
            sourceId = "src1",
            itemId = "item1",
            controller = noOpController(scope),
            audioRepository = FakeAudioRepository(),
            bundleReader = FakeBundleReader(),
            readaloudPreferencesStore = FakePreferencesStore(),
            resumeStore = FakeResumeStore(),
            sourceRepository = sourceRepository,
            linkRepository = linkRepository,
            audiobookHandoffState = AudiobookHandoffState(),
            dispatchers = dispatchers,
            parentScope = kotlinx.coroutines.CoroutineScope(testDispatcher),
        )
    }

    @Test fun storyteller_source_shows_readaloud_button() = runTest(testDispatcher) {
        val session = makeSession(
            sourceRepository = FakeSourceRepository(makeSource(ServerType.STORYTELLER_SERVICE)),
            linkRepository = FakeLinkRepository(null),
        )
        scheduler.runCurrent()
        assertTrue(session.readaloudVisible.value)
        assertTrue(session.readaloudAvailable.value)
        session.onDestroy()
    }

    @Test fun abs_source_with_link_shows_readaloud_button() = runTest(testDispatcher) {
        val session = makeSession(
            sourceRepository = FakeSourceRepository(makeSource(ServerType.AUDIOBOOKSHELF)),
            linkRepository = FakeLinkRepository(makeLink()),
        )
        scheduler.runCurrent()
        assertTrue(session.readaloudVisible.value)
        assertTrue(session.readaloudAvailable.value)
        session.onDestroy()
    }

    @Test fun abs_source_without_link_hides_readaloud_button() = runTest(testDispatcher) {
        val session = makeSession(
            sourceRepository = FakeSourceRepository(makeSource(ServerType.AUDIOBOOKSHELF)),
            linkRepository = FakeLinkRepository(null),
        )
        scheduler.runCurrent()
        assertFalse(session.readaloudVisible.value)
        assertFalse(session.readaloudAvailable.value)
        session.onDestroy()
    }

    @Test fun unknown_source_hides_readaloud_button() = runTest(testDispatcher) {
        val session = makeSession(
            sourceRepository = FakeSourceRepository(null),
            linkRepository = FakeLinkRepository(null),
        )
        scheduler.runCurrent()
        assertFalse(session.readaloudVisible.value)
        assertFalse(session.readaloudAvailable.value)
        session.onDestroy()
    }
}
