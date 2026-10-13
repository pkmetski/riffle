package com.riffle.shared.readaloud

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudResumePosition
import com.riffle.core.domain.ReadaloudResumeStore
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.Source
import com.riffle.feature.player.AudiobookHandoffState
import com.riffle.shared.audiobook.BundleAudioExtractor
import com.riffle.shared.library.IosReadaloudHandoff
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the handoff paths of [IosReadaloudSession]:
 *  - [IosReadaloudSession.startReadaloudAtSecond] marks the session open.
 *  - [IosReadaloudSession.prepareAudiobookHandoff] signals [AudiobookHandoffState] with the current
 *    position and audiobookItemId, mirroring the Android ReadaloudSessionTest assertions.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IosReadaloudSessionTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val mainImmediate: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
    }

    private val clips = listOf(
        MediaOverlayClip("ch01.html#s1", "audio/ch01.mp3", 0.0, 10.0),
        MediaOverlayClip("ch01.html#s2", "audio/ch01.mp3", 10.0, 20.0),
        MediaOverlayClip("ch02.html#s1", "audio/ch02.mp3", 0.0, 15.0),
    )
    private val track = ReadaloudTrack(clips)

    private class FakeBridge : IosReadaloudBridge {
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
    }

    private class FakeBundleReader(
        private val bundlePathVal: String? = "/tmp/bundle.zip",
        private val trackVal: ReadaloudTrack? = null,
    ) : ReadaloudBundleReader {
        override fun bundlePath(sourceId: String, itemId: String): String? = bundlePathVal
        override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = trackVal
    }

    private class FakeAudioRepository : ReadaloudAudioRepository {
        override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = true
        override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
        override suspend fun downloadAudio(
            sourceId: String,
            bookId: String,
            onProgress: (Long, Long) -> Unit,
        ): AudioDownloadResult = AudioDownloadResult.NoBundle
        override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
    }

    private class FakePreferencesStore : ReadaloudPreferencesStore {
        override val preferences: Flow<ReadaloudPreferences> = flowOf(ReadaloudPreferences())
        override suspend fun update(prefs: ReadaloudPreferences) = Unit
    }

    private class FakeResumeStore : ReadaloudResumeStore {
        override suspend fun load(sourceId: String, itemId: String): ReadaloudResumePosition? = null
        override suspend fun save(sourceId: String, itemId: String, position: ReadaloudResumePosition) = Unit
        override suspend fun clear(sourceId: String, itemId: String) = Unit
    }

    private class FakeSourceRepository : SourceRepository {
        override fun observeAll() = flowOf(emptyList<Source>())
        override suspend fun getActive(): Source? = null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
            CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeLinkRepository : ReadaloudLinkRepository {
        override fun observeAll() = flowOf(emptyList<ReadaloudLink>())
        override fun observeLinkedAbsItemIds() = flowOf(emptySet<String>())
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
        override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) = Unit
        override suspend fun countForSource(sourceId: String): Int = 0
    }

    private fun makeSession(
        bundleReader: ReadaloudBundleReader = FakeBundleReader(),
        audioRepository: ReadaloudAudioRepository = FakeAudioRepository(),
        audiobookHandoffState: AudiobookHandoffState = AudiobookHandoffState(),
        parentScope: CoroutineScope = CoroutineScope(dispatcher),
    ): IosReadaloudSession {
        val controller = IosReadaloudController(
            bridge = FakeBridge(),
            bundleAudioExtractor = object : BundleAudioExtractor {
                override suspend fun extractedTrackUrls(zipFilePath: String, entryPaths: List<String>): List<String> =
                    entryPaths.map { "file:///extracted/$it" }
            },
            readaloudHandoff = IosReadaloudHandoff(),
        )
        return IosReadaloudSession(
            sourceId = "src1",
            itemId = "item1",
            controller = controller,
            audioRepository = audioRepository,
            bundleReader = bundleReader,
            readaloudPreferencesStore = FakePreferencesStore(),
            resumeStore = FakeResumeStore(),
            sourceRepository = FakeSourceRepository(),
            linkRepository = FakeLinkRepository(),
            audiobookHandoffState = audiobookHandoffState,
            dispatchers = dispatchers,
            parentScope = parentScope,
        )
    }

    @Test
    fun startReadaloudAtSecondSetsReadaloudOpen() = runTest(dispatcher) {
        val parentScope = CoroutineScope(dispatcher)
        val session = makeSession(
            bundleReader = FakeBundleReader(trackVal = track),
            parentScope = parentScope,
        )
        session.startReadaloudAtSecond(5.0)
        assertTrue(session.readaloudOpen.value, "readaloudOpen must be true after startReadaloudAtSecond")
        parentScope.cancel()
    }

    @Test
    fun prepareAudiobookHandoffSignalsHandoffStateWithItemId() = runTest(dispatcher) {
        val parentScope = CoroutineScope(dispatcher)
        val handoffState = AudiobookHandoffState()
        val session = makeSession(
            audiobookHandoffState = handoffState,
            parentScope = parentScope,
        )
        session.audiobookItemId = "audiobook-item-42"
        session.prepareAudiobookHandoff()
        val pending = handoffState.pendingHandoff.value
        assertNotNull(pending, "audiobookHandoffState must have a pending handoff")
        assertEquals("audiobook-item-42", pending.itemId, "pending handoff must carry the audiobookItemId")
        parentScope.cancel()
    }

    @Test
    fun prepareAudiobookHandoffWithNoItemIdLeavesHandoffStateEmpty() = runTest(dispatcher) {
        val parentScope = CoroutineScope(dispatcher)
        val handoffState = AudiobookHandoffState()
        val session = makeSession(
            audiobookHandoffState = handoffState,
            parentScope = parentScope,
        )
        session.audiobookItemId = null
        session.prepareAudiobookHandoff()
        assertEquals(null, handoffState.pendingHandoff.value, "no signal when audiobookItemId is null")
        parentScope.cancel()
    }
}
