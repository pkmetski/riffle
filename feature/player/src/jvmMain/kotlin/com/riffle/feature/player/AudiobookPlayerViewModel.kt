package com.riffle.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.models.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.BookmarkTitleBuilder
import com.riffle.core.common.Clock
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.feature.reader.PositionSaveCoordinator
import com.riffle.feature.reader.ProgressFlushScope
import com.riffle.feature.reader.ReaderSyncCoordinatorInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.AudiobookTracks

class AudiobookPlayerViewModel constructor(
    navItemId: String,
    navPlaylistId: String?,
    navPlaylistLibraryId: String?,
    navStartAtSec: Float,
    private val audiobookRepository: AudiobookRepository,
    private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,
    private val audiobookCacheRepository: AudiobookCacheRepository,
    private val bundleAudiobookSource: com.riffle.core.domain.BundleAudiobookSource,
    private val libraryObserver: LibraryObserver,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val controller: AudioPlayerInterface,
    private val readaloudHandoff: ReadaloudHandoff,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
    private val nowPlayingStore: NowPlayingStore,
    private val audiobookPositionStore: com.riffle.core.domain.AudiobookPositionStore,
    private val openReconcileTargets: com.riffle.core.sync.OpenReconcileTargets,
    private val progressFlushScope: ProgressFlushScope,
    private val bookmarkStore: AudiobookBookmarkStore,
    private val connectivityObserver: com.riffle.core.domain.ConnectivityObserver,
    private val audiobookHandoffState: AudiobookHandoffState,
    private val followLoopOrchestrator: FollowLoopOrchestrator,
    private val resumeResolver: AudiobookResumeResolver,
    private val reconciliationCoordinator: AudiobookReconciliationCoordinator,
    private val clock: Clock,
    private val logger: Logger,
    private val playlistsRepository: com.riffle.core.domain.PlaylistsRepository,
    private val contentCacheAccessStore: ContentCacheAccessStore,
    private val progressSweep: com.riffle.core.sync.ProgressSweep,
) : ViewModel() {

    private val itemId: String = navItemId

    private val playlistId: String? = navPlaylistId?.takeIf { it.isNotEmpty() }
    private val playlistLibraryId: String? = navPlaylistLibraryId?.takeIf { it.isNotEmpty() }

    private val startAtSec: Double = navStartAtSec.toDouble()

    private var resolvedSession: com.riffle.core.domain.AudiobookSession? = null
    private val sessionDeferred = CompletableDeferred<com.riffle.core.domain.AudiobookSession?>()
    private var resolvedCoverUri: String? = null
    private var resolvedBookTitle: String? = null
    private var resolvedInitialSpeed: Float = 1f

    private val meta = MutableStateFlow(
        AudiobookPlayerUiState(loading = true),
    )
    private var timeline: AudiobookTimeline = AudiobookTimeline(0.0)
    private var sourceId: String = ""

    private var audioSettingsIdentity: AudioIdentity = AudioIdentity("", itemId)
    private var pendingSpeed: Float? = null
    private var speedSaveJob: kotlinx.coroutines.Job? = null

    private var localUpdatedAt: Long = 0L

    private val followContext = object : FollowContext {
        override fun currentAudioSec(): Double = controller.currentAbsoluteSec()
        override fun isPlaying(): Boolean = controller.state.value.isPlaying
        override fun seekTo(positionSec: Double) { controller.seekTo(positionSec) }
        override val readerSync: ReaderSyncCoordinatorInterface?
            get() = reconciliationCoordinator.readerSync
        override suspend fun tryAttachReaderSync(currentAudioSec: Double): Boolean =
            attachReaderSync(currentAudioSec, 0L)
        override fun hasServer(): Boolean = sourceId.isNotEmpty()
        override fun progressFraction(positionSec: Double): Float =
            audiobookProgressFraction(positionSec, timeline.durationSec)
        override suspend fun onHotPathAdvance(positionSec: Double) {
            positionSaveCoordinator.onChanged(positionSec)
        }
        override suspend fun writeSinglePeerFallback(positionSec: Double) {
            audiobookRepository.saveProgress(sourceId, itemId, positionSec, timeline.durationSec)
        }
        override suspend fun writeCloseFlush(positionSec: Double, fraction: Float) {
            positionSaveCoordinator.onChanged(positionSec)
            positionSaveCoordinator.onClose(fraction)
        }
        override var reconciledResumeSec: Double
            get() = this@AudiobookPlayerViewModel.reconciledResumeSec
            set(value) { this@AudiobookPlayerViewModel.reconciledResumeSec = value }
        override var localUpdatedAt: Long
            get() = this@AudiobookPlayerViewModel.localUpdatedAt
            set(value) { this@AudiobookPlayerViewModel.localUpdatedAt = value }
    }
    private var reconciledResumeSec: Double = 0.0

    private val positionSaveCoordinator = PositionSaveCoordinator<Double>(
        updateProgress = { progress -> updateReadingProgressUseCase(itemId, progress) },
        savePosition = { pos ->
            if (sourceId.isNotEmpty()) {
                audiobookPositionStore.save(sourceId, itemId, pos)
                reconciliationCoordinator.mirrorListeningToReading(sourceId, itemId, pos)
                reconciliationCoordinator.writeListeningToReadaloud(sourceId, itemId, pos)
            }
        },
    )

    private val skipIntervalSec: StateFlow<Double> = listeningPreferencesStore.skipIntervalSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS.toDouble())

    private val rewindIntervalSec: StateFlow<Double> = listeningPreferencesStore.rewindIntervalSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS.toDouble())

    private val rewindOnResumeSec: StateFlow<Double> = listeningPreferencesStore.rewindOnResumeSeconds
        .map { it.toDouble() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS.toDouble())

    private val _events = MutableSharedFlow<AudiobookPlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AudiobookPlayerEvent> = _events.asSharedFlow()

    val uiState: StateFlow<AudiobookPlayerUiState> =
        combine(meta, controller.state, controller.sleepTimer) { m, playback, timer ->
            val pos = playback.positionSec
            val chapter = timeline.chapterAt(pos)
            m.copy(
                isPlaying = playback.isPlaying,
                speed = playback.speed,
                positionSec = pos,
                durationSec = if (playback.durationSec > 0) playback.durationSec else m.durationSec,
                bufferedPositionSec = playback.bufferedSec,
                currentChapterTitle = chapter?.title,
                chapterStartsSec = timeline.chapters.map { it.startSec },
                chapters = timeline.chapters,
                currentChapterIndex = chapter?.index ?: -1,
                canPreviousChapter = timeline.canPreviousChapter,
                canNextChapter = timeline.canNextChapter,
                bookmarks = m.bookmarks,
                bookmarksOffline = m.bookmarksOffline,
                sleepTimer = timer,
            )
        }.combine(skipIntervalSec) { state, skip ->
            state.copy(skipIntervalSeconds = skip.toInt())
        }.combine(rewindIntervalSec) { state, rewind ->
            state.copy(rewindIntervalSeconds = rewind.toInt())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AudiobookPlayerUiState(loading = true))

    init {
        viewModelScope.launch {
            try {
            val t0 = clock.nowMs()
            logger.d(LogChannel.Handoff) { "AB.VM init start itemId=$itemId startAtSec=$startAtSec" }
            val server = sourceRepository.getActive()
            sourceId = server?.id ?: ""
            if (sourceId.isNotEmpty()) openReconcileTargets.markOpen(sourceId, itemId)
            if (sourceId.isNotEmpty()) {
                viewModelScope.launch {
                    bookmarkStore.observe(sourceId, itemId).collect { list ->
                        meta.value = meta.value.copy(bookmarks = list)
                    }
                }
                viewModelScope.launch {
                    combine(
                        bookmarkStore.observeHasUnsynced(sourceId, itemId),
                        connectivityObserver.isOnline,
                    ) { hasUnsynced, isOnline -> hasUnsynced && !isOnline }
                        .collect { offline ->
                            meta.value = meta.value.copy(bookmarksOffline = offline)
                        }
                }
            }
            val token = server?.let { tokenStorage.getToken(it.id) } ?: ""
            logger.d(LogChannel.Handoff) { "AB.VM init: got server +${clock.nowMs() - t0}ms" }
            val item = libraryObserver.getItem(itemId)
            logger.d(LogChannel.Handoff) { "AB.VM init: got item +${clock.nowMs() - t0}ms" }
            var launchCacheJob = false
            var usedAutoCache = false
            val session = if (sourceId.isEmpty()) null
                else audiobookDownloadRepository.localSession(sourceId, itemId)
                    ?.also { logger.d(LogChannel.Handoff) { "AB.VM init: local download session +${clock.nowMs() - t0}ms" } }
                    ?: bundleAudiobookSource.localSession(sourceId, itemId)
                    ?.also { logger.d(LogChannel.Handoff) { "AB.VM init: bundle session +${clock.nowMs() - t0}ms" } }
                    ?: audiobookCacheRepository.localSession(sourceId, itemId)
                    ?.also {
                        usedAutoCache = true
                        logger.d(LogChannel.Handoff) { "AB.VM init: auto-cache session +${clock.nowMs() - t0}ms" }
                    }
                    ?: audiobookRepository.openSession(sourceId, itemId)
                    ?.also { launchCacheJob = true; logger.d(LogChannel.Handoff) { "AB.VM init: ABS network session +${clock.nowMs() - t0}ms" } }
            if (item == null || session == null) {
                logger.d(LogChannel.Handoff) { "AB.VM init: FAILED (item=${item != null} session=${session != null}) +${clock.nowMs() - t0}ms" }
                meta.value = meta.value.copy(loading = false, failed = true)
                return@launch
            }
            if (usedAutoCache) {
                viewModelScope.launch { contentCacheAccessStore.markAccessed(audiobookCacheKey(sourceId, itemId)) }
            }
            timeline = session.timeline
            val resume = resumeResolver.resolve(
                sourceId = sourceId,
                itemId = itemId,
                session = session,
                readingProgressFraction = item.readingProgress,
                startAtSec = startAtSec,
            )
            val resumeSec = resume.resumeSec
            val resumeStamp = resume.resumeStamp
            val link = readaloudLinkRepository.findByAbsItem(sourceId, itemId)
            audioSettingsIdentity = if (link != null) {
                audioIdentityResolver.resolveForStorytellerBook(link.storytellerSourceId, link.storytellerBookId)
            } else {
                AudioIdentity(sourceId, itemId)
            }
            val initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: listeningPreferencesStore.defaultPlaybackSpeed.first()
            val isStoryteller = server?.serverType == com.riffle.core.models.ServerType.STORYTELLER_SERVICE
            val audioServerId = link?.storytellerSourceId ?: sourceId
            val audioBookId = link?.storytellerBookId ?: itemId
            val readaloudAvailable = readaloudControlState(
                isStoryteller = isStoryteller,
                isMatchedAbs = link != null,
                bundlePresent = readaloudAudioRepository.isAudioAvailable(audioServerId, audioBookId),
            ).enabled
            val readaloudEbookItemId: String? = if (!readaloudAvailable) null else link?.let { l ->
                readaloudLinkRepository.findByStorytellerBook(l.storytellerSourceId, l.storytellerBookId)
                    .firstOrNull { t ->
                        t.absLibraryItemId != itemId &&
                            libraryObserver.getItem(t.absSourceId, t.absLibraryItemId)?.isReadable == true
                    }
                    ?.absLibraryItemId
                    ?: itemId.takeIf { item.isReadable }
            }
            meta.value = meta.value.copy(
                loading = false,
                failed = false,
                title = item.title,
                author = item.author,
                publishedYear = item.publishedYear,
                coverUrl = item.coverUrl,
                authToken = token,
                durationSec = session.timeline.durationSec,
                readaloudEbookItemId = readaloudEbookItemId,
                genres = item.genres,
                facts = buildAudiobookFacts(session.timeline.durationSec, item.genres),
                description = item.description,
            )
            var playbackStartSec = resumeSec
            if (startAtSec < 0.0) {
                val rewindOnResume = listeningPreferencesStore.rewindOnResumeSeconds.first().toDouble()
                if (rewindOnResume > 0.0 && resumeSec > 0.0) {
                    playbackStartSec = (resumeSec - rewindOnResume).coerceAtLeast(0.0)
                }
            }

            if (launchCacheJob && session.timeline.durationSec > 0.0) {
                val capturedSession = session
                val capturedSourceId = sourceId
                val capturedItemId = itemId
                viewModelScope.launch {
                    audiobookCacheRepository.awaitCachedAudiobook(capturedSourceId, capturedItemId, capturedSession)
                    val cachedSession = audiobookCacheRepository.localSession(capturedSourceId, capturedItemId) ?: return@launch
                    contentCacheAccessStore.markAccessed(audiobookCacheKey(capturedSourceId, capturedItemId))
                    val currentTrackIndex = AudiobookTracks.trackIndexAt(
                        controller.currentAbsoluteSec(),
                        capturedSession.tracks,
                    )
                    val nextIndex = currentTrackIndex + 1
                    controller.swapTracksFromIndex(nextIndex, cachedSession.trackUrls.drop(nextIndex))
                    logger.d(LogChannel.Handoff) { "AB.VM cache swap: tracks from $nextIndex swapped to local files" }
                }
            }

            resolvedSession = session
            sessionDeferred.complete(session)
            resolvedCoverUri = item.coverUrl
            resolvedBookTitle = item.title
            resolvedInitialSpeed = initialSpeed
            logger.d(LogChannel.Handoff) { "AB.VM init: resolvedSession ready +${clock.nowMs() - t0}ms (startAtSec=$startAtSec)" }

            if (startAtSec == PREWARM_SENTINEL) {
                logger.d(LogChannel.Handoff) { "AB.VM init: warming binder +${clock.nowMs() - t0}ms" }
                controller.warmBinder()
                logger.d(LogChannel.Handoff) { "AB.VM init: binder warm +${clock.nowMs() - t0}ms" }
            } else {
                controller.prepare(
                    trackUrls = session.trackUrls,
                    spans = session.tracks,
                    durationSec = session.timeline.durationSec,
                    startAtSec = playbackStartSec,
                    localZipFilePath = session.localZipFile?.absolutePath,
                    coverUri = item.coverUrl,
                    bookTitle = item.title,
                    chapters = session.timeline.chapters,
                )
                nowPlayingStore.set(NowPlaying.Audiobook(itemId))
                controller.setSpeed(initialSpeed)
                reconciledResumeSec = resumeSec
                localUpdatedAt = resumeStamp
                if (!resume.wasFinishedOnOpen) controller.play()
                attachReaderSync(resumeSec, resumeStamp)
                followLoopOrchestrator.start(viewModelScope, followContext)
            }
            } finally {
                if (!sessionDeferred.isCompleted) sessionDeferred.complete(null)
            }
        }

        viewModelScope.launch {
            audiobookHandoffState.pendingHandoff
                .filterNotNull()
                .filter { it.itemId == itemId }
                .collect { signal ->
                    audiobookHandoffState.consumeHandoff()
                    activateFromHandoff(signal.atSec)
                }
        }

        viewModelScope.launch {
            audiobookHandoffState.pendingDismiss
                .filterNotNull()
                .filter { it == itemId }
                .collect {
                    audiobookHandoffState.consumeDismiss()
                    followLoopOrchestrator.cancel()
                    controller.pause()
                }
        }

        viewModelScope.launch {
            controller.playbackEnded.collect {
                if (handingOffToPlaylistAdvance) return@collect
                flushPendingSpeed()
                val nextInPlaylist = resolveNextPlaylistItem()
                if (nextInPlaylist != null) {
                    handingOffToPlaylistAdvance = true
                    controller.clearEndOfBookCache()
                    logger.d(LogChannel.Handoff) { "AB.VM playlist auto-advance → $nextInPlaylist" }
                    _events.tryEmit(AudiobookPlayerEvent.PlaylistAdvance(nextInPlaylist))
                } else {
                    pushProgressAndStopPlayer()
                    clearAudiobookNowPlaying()
                    _events.tryEmit(AudiobookPlayerEvent.Finished)
                }
            }
        }

        var eocPrevChapterIndex = -1
        viewModelScope.launch {
            uiState.collect { state ->
                val idx = state.currentChapterIndex
                if (eocPrevChapterIndex >= 0
                    && idx > eocPrevChapterIndex
                    && state.sleepTimer is SleepTimerMode.EndOfChapter
                ) {
                    controller.triggerSleepNow()
                }
                eocPrevChapterIndex = idx
            }
        }
    }

    private fun audiobookCacheKey(sourceId: String, itemId: String): ContentCacheKey =
        ContentCacheKey(sourceId, itemId, ContentCacheArtifactKind.Audiobook)

    private suspend fun attachReaderSync(atSec: Double, atUpdatedAt: Long): Boolean {
        val result = reconciliationCoordinator.attach(sourceId, itemId, atSec, atUpdatedAt)
        result.jumpToAudioSec?.let { controller.seekTo(it); reconciledResumeSec = it }
        localUpdatedAt = maxOf(localUpdatedAt, result.canonicalLastUpdate)
        return result.readerSyncAttached
    }

    fun togglePlayPause() {
        if (controller.state.value.isPlaying) {
            controller.pause()
            followLoopOrchestrator.flushNow()
        } else {
            val rewindSec = rewindOnResumeSec.value
            val posBeforeRewind = controller.currentAbsoluteSec()
            if (rewindSec > 0) {
                val newPos = (posBeforeRewind - rewindSec).coerceAtLeast(0.0)
                reconciledResumeSec = newPos
                controller.seekTo(newPos)
            }
            controller.play()
        }
    }

    fun seekTo(positionSec: Double) {
        reconciledResumeSec = positionSec
        controller.seekTo(positionSec)
    }

    fun rewind() {
        val rewindSec = rewindIntervalSec.value
        reconciledResumeSec = (controller.currentAbsoluteSec() - rewindSec).coerceAtLeast(0.0)
        controller.skipBy(-rewindSec)
    }

    fun forward() {
        val skipSec = skipIntervalSec.value
        reconciledResumeSec = controller.currentAbsoluteSec() + skipSec
        controller.skipBy(skipSec)
    }

    fun previousChapter() {
        timeline.previousChapterTargetSec(controller.currentAbsoluteSec())?.let { reconciledResumeSec = it; controller.seekTo(it) }
    }

    fun nextChapter() {
        timeline.nextChapterTargetSec(controller.currentAbsoluteSec())?.let { reconciledResumeSec = it; controller.seekTo(it) }
    }

    fun currentPositionSec(): Double = controller.currentAbsoluteSec()

    fun defaultBookmarkTitle(positionSec: Double): String =
        BookmarkTitleBuilder.defaultTitle(timeline, positionSec)

    fun addBookmark(title: String, positionSec: Double) {
        if (sourceId.isEmpty()) return
        viewModelScope.launch { bookmarkStore.add(sourceId, itemId, positionSec, title, clock.nowMs()) }
    }

    fun renameBookmark(id: String, title: String) {
        viewModelScope.launch { bookmarkStore.rename(id, title, clock.nowMs()) }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkStore.delete(id, clock.nowMs()) }
    }

    fun seekToBookmark(positionSec: Double) {
        seekTo(positionSec)
    }

    fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
        pendingSpeed = speed
        speedSaveJob?.cancel()
        speedSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SPEED_SAVE_DEBOUNCE_MS)
            if (sourceId.isEmpty()) return@launch
            audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed)
            pendingSpeed = null
        }
    }

    fun setSleepTimer(mode: SleepTimerMode) = controller.setSleepTimer(mode)
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    private fun flushPendingSpeed() {
        val speed = pendingSpeed ?: return
        speedSaveJob?.cancel()
        pendingSpeed = null
        if (sourceId.isEmpty()) return
        progressFlushScope.flush { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }

    private var handingOffToReadaloud = false
    private var handingOffToPlaylistAdvance = false

    fun hintReadaloudHandoff() {
        readaloudHandoff.preWarmSeek(controller.currentAbsoluteSec())
    }

    fun cancelHandoffHint() {
        readaloudHandoff.cancelPreWarm()
    }

    fun prepareReadaloudHandoff() {
        if (handingOffToReadaloud) return
        handingOffToReadaloud = true
        followLoopOrchestrator.stopWithFinalFlush()
        controller.releaseForHandoff()
    }

    private suspend fun activateFromHandoff(atSec: Double) {
        val t0 = clock.nowMs()
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff start atSec=$atSec resolvedSession=${resolvedSession != null}" }
        val session = resolvedSession ?: run {
            logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: init in progress, awaiting session (up to 10s)" }
            withTimeoutOrNull(10_000L) { sessionDeferred.await() }
        } ?: run {
            logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: DROPPED — session unavailable after waiting" }
            return
        }
        handingOffToReadaloud = false
        val finalSec = atSec.coerceIn(0.0, session.timeline.durationSec)
        controller.prepare(
            trackUrls = session.trackUrls,
            spans = session.tracks,
            durationSec = session.timeline.durationSec,
            startAtSec = finalSec,
            localZipFilePath = session.localZipFile?.absolutePath,
            coverUri = resolvedCoverUri,
            bookTitle = resolvedBookTitle,
            chapters = session.timeline.chapters,
        )
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: prepare() done +${clock.nowMs() - t0}ms" }
        controller.setSpeed(resolvedInitialSpeed)
        reconciledResumeSec = finalSec
        localUpdatedAt = clock.nowMs()
        if (sourceId.isNotEmpty()) {
            audiobookPositionStore.save(sourceId, itemId, finalSec)
            audiobookPositionStore.updateLocalTimestamp(sourceId, itemId, localUpdatedAt)
        }
        nowPlayingStore.set(NowPlaying.Audiobook(itemId))
        controller.play()
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: play() called +${clock.nowMs() - t0}ms" }
        attachReaderSync(finalSec, localUpdatedAt)
        followLoopOrchestrator.start(viewModelScope, followContext)
    }

    private fun pushProgressAndStopPlayer() {
        followLoopOrchestrator.stopWithFinalFlush()
        controller.stop()
    }

    private fun clearAudiobookNowPlaying() {
        nowPlayingStore.clearIf { it is NowPlaying.Audiobook && it.itemId == itemId }
    }

    override fun onCleared() {
        followLoopOrchestrator.cancel()
        if (sourceId.isNotEmpty()) {
            openReconcileTargets.markClosed(sourceId, itemId)
            reconciliationCoordinator.ebookItemIdForMarkClosed
                ?.let { openReconcileTargets.markClosed(sourceId, it) }
        }
        flushPendingSpeed()
        if (!handingOffToReadaloud && !handingOffToPlaylistAdvance) pushProgressAndStopPlayer()
        if (!handingOffToPlaylistAdvance) clearAudiobookNowPlaying()
        progressFlushScope.flush { runCatching { progressSweep.run() } }
        super.onCleared()
    }

    private suspend fun resolveNextPlaylistItem(): String? {
        val pid = playlistId ?: return null
        val lid = playlistLibraryId ?: return null
        val playlist = runCatching { playlistsRepository.getPlaylist(lid, pid) }.getOrNull() ?: return null
        val currentIndex = playlist.itemIds.indexOf(itemId).takeIf { it >= 0 } ?: return null
        return playlist.itemIds.getOrNull(currentIndex + 1)
    }

    private companion object {
        const val SPEED_SAVE_DEBOUNCE_MS = 400L
        const val PREWARM_SENTINEL = -2.0
    }
}
