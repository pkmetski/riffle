package com.riffle.app.feature.audiobook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.AudiobookBookmark
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.BookmarkTitleBuilder
import com.riffle.core.domain.Clock
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
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
import javax.inject.Inject

/**
 * Maps a book-absolute listen position to the unified 0..1 `readingProgress` fraction the library
 * and detail screens render (ADR 0029). Returns 0 when the duration isn't known yet (so a not-yet-
 * prepared player never writes a bogus 100%).
 */
internal fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    if (durationSec > 0.0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f

/**
 * The book-absolute resume position. When NO position was tracked at all ([hadTrackedPosition] false —
 * no local audiobook row and no server record, e.g. offline with only a downloaded bundle) fall back
 * to the item's library [readingProgressFraction] mapped to seconds. This resumes near where the rest
 * of the app shows progress instead of restarting at 0, and (crucially) seeds the resume floor so the
 * close/follow persist guard never writes a ~0 over that progress — the offline-erase bug.
 *
 * Gating on [hadTrackedPosition] — not on `reconciledSec > 0` — is deliberate: a genuinely-tracked
 * position of exactly 0 (a server record or local row that says "at the start", with a real
 * timestamp) must be honoured, not replaced by the progress fallback. Online behaviour is unchanged
 * because a server record always counts as a tracked position.
 */
internal fun audiobookResumeSec(
    reconciledSec: Double,
    hadTrackedPosition: Boolean,
    readingProgressFraction: Float,
    durationSec: Double,
): Double =
    if (hadTrackedPosition || readingProgressFraction <= 0f || durationSec <= 0.0) reconciledSec
    else (readingProgressFraction * durationSec).coerceIn(0.0, durationSec)

/** How close to the end a resume position must sit to count the book as finished (and restart at 0). */
internal const val AUDIOBOOK_FINISHED_EPS_SEC: Double = 1.0

/**
 * The position to actually start a normally-opened audiobook at. A resume that lands at (or within
 * [AUDIOBOOK_FINISHED_EPS_SEC] of) the end means the book was finished: seeding the player there puts
 * it at the end of the last track, where ExoPlayer is `STATE_ENDED` and `play()` is a no-op — the
 * player sits silent with the seek bar pinned at the end. Reopening a finished book is a replay
 * intent, so restart from the beginning instead. A position anywhere short of the end is honoured
 * as-is. No-op when the duration is unknown (we can't tell what "the end" is).
 */
internal fun audiobookStartSec(resumeSec: Double, durationSec: Double): Double =
    if (durationSec > 0.0 && resumeSec >= durationSec - AUDIOBOOK_FINISHED_EPS_SEC) 0.0 else resumeSec

/**
 * The one-line facts shown beneath the cover on the landscape player, e.g.
 * "Audiobook · 10h 53m · Science Fiction & Fantasy". Duration is omitted when unknown; at most two
 * genres are listed to keep it tidy. Null when there's nothing to show.
 */
internal fun buildAudiobookFacts(durationSec: Double, genres: List<String>): String? {
    val parts = buildList {
        add("Audiobook")
        if (durationSec > 0.0) {
            val total = durationSec.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            add(
                when {
                    h > 0 && m > 0 -> "${h}h ${m}m"
                    h > 0 -> "${h}h"
                    else -> "${m}m"
                },
            )
        }
        genres.take(2).forEach { add(it) }
    }
    // "Audiobook" alone is just the medium label with no real facts — not worth a line.
    return if (parts.size > 1) parts.joinToString(" · ") else null
}

/** One-shot UI events emitted by the [AudiobookPlayerViewModel] — collected by the screen. */
sealed interface AudiobookPlayerEvent {
    /** Playback finished naturally (last track ended); the screen should close the player. */
    data object Finished : AudiobookPlayerEvent
}

/** UI state for the full-screen [Audiobook Player] (ADR 0029). */
data class AudiobookPlayerUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val author: String = "",
    val publishedYear: String? = null,
    val coverUrl: String? = null,
    val authToken: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapterStartsSec: List<Double> = emptyList(),
    // The full chapter list + the index of the chapter the playhead is in, for the Chapters sheet.
    val chapters: List<AudiobookChapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
    // Book details for the landscape two-column player: a facts line and the blurb (ADR 0029).
    val facts: String? = null,
    val description: String? = null,
    // The linked readaloud EBOOK item id, when this title has one (split-library ebook, or this same
    // item if it's a combined ebook+audio). Non-null enables swipe-down → switch to the readaloud
    // reader; null means there's no readaloud to switch to, so no swipe-down.
    val readaloudEbookItemId: String? = null,
    // User bookmarks for this audiobook, observed live from the store (ordered by position, earliest first).
    val bookmarks: List<AudiobookBookmark> = emptyList(),
    // True only when this item has unsynced (dirty) bookmarks AND the device is offline, so the
    // Bookmarks sheet can show a quiet "Offline — bookmarks will sync" note. Sync is otherwise silent.
    val bookmarksOffline: Boolean = false,
    val sleepTimer: SleepTimerMode = SleepTimerMode.None,
    val skipIntervalSeconds: Int = 30,
    val rewindIntervalSeconds: Int = 15,
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val audiobookRepository: AudiobookRepository,
    private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,
    private val bundleAudiobookSource: com.riffle.core.domain.BundleAudiobookSource,
    private val libraryObserver: LibraryObserver,
    private val updateReadingProgressUseCase: UpdateReadingProgress,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val controller: AudiobookController,
    private val readaloudController: com.riffle.app.feature.reader.readaloud.ReadaloudController,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val readaloudAudioRepository: com.riffle.core.domain.ReadaloudAudioRepository,
    private val nowPlayingStore: com.riffle.app.playback.NowPlayingStore,
    private val audiobookPositionStore: com.riffle.core.domain.AudiobookPositionStore,
    // While the player is open it drives the book's reconciliation; the durable sweep skips it so it
    // can't absorb a cross-device server-win the player hasn't seeked to (ADR 0030).
    private val openReconcileTargets: com.riffle.core.data.OpenReconcileTargets,
    private val progressFlushScope: com.riffle.app.feature.reader.ProgressFlushScope,
    private val bookmarkStore: AudiobookBookmarkStore,
    private val connectivityObserver: com.riffle.core.domain.ConnectivityObserver,
    private val audiobookHandoffState: AudiobookHandoffState,
    private val followLoopOrchestrator: FollowLoopOrchestrator,
    private val resumeResolver: AudiobookResumeResolver,
    private val reconciliationCoordinator: AudiobookReconciliationCoordinator,
    private val clock: Clock,
    private val logger: Logger,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""

    // readaloud→audiobook swipe handoff: the listen position to continue from.
    // -2 = PREWARM_SENTINEL: pre-warm mode (overlay always-mounted, actual position arrives via
    //      AudiobookHandoffState when the user swipes up — controller.prepare() is deferred).
    // -1 = normal resume from saved position.
    // ≥0 = explicit start second (handoff from the standalone reader path).
    private val startAtSec: Double = (savedStateHandle.get<Float>("startAtSec") ?: -1f).toDouble()

    // Stored during pre-warm init so activateFromHandoff() can prepare the controller without
    // re-fetching. Set even in normal-open mode so re-activations (second+ swipe-up) can
    // re-prepare the controller after releaseForHandoff() clears the spans.
    private var resolvedSession: com.riffle.core.domain.AudiobookSession? = null
    // Completed (with the session or null on failure) when init finishes, so activateFromHandoff()
    // can await rather than drop when a swipe arrives before the ~1–2 s bundle-session fetch is done.
    private val sessionDeferred = CompletableDeferred<com.riffle.core.domain.AudiobookSession?>()
    private var resolvedCoverUri: String? = null
    private var resolvedInitialSpeed: Float = 1f

    private val meta = MutableStateFlow(
        AudiobookPlayerUiState(loading = true),
    )
    private var timeline: AudiobookTimeline = AudiobookTimeline(0.0)
    private var serverId: String = ""

    // The per-book audio-settings key (ADR 0028). The audiobook's own (serverId, itemId) IS the
    // canonical key when an audiobook exists, and a linked Readaloud resolves to that same id, so the
    // speed is shared between them automatically; an audiobook with no bundle gets its own row.
    private var audioSettingsIdentity: AudioIdentity = AudioIdentity("", itemId)
    // Debounced-save bookkeeping for the granular speed sheet (mirrors EpubReaderViewModel): the
    // settled value is written once rather than per intermediate 0.05× step. `pendingSpeed` holds the
    // not-yet-persisted value so onCleared can flush it.
    private var pendingSpeed: Float? = null
    private var speedSaveJob: kotlinx.coroutines.Job? = null

    // The timestamp the local listen position was last genuinely set at; adopted from server stamps
    // after a write/jump so our own writes never read back as "newer" and re-seek (feedback loop).
    private var localUpdatedAt: Long = 0L

    /** Adapter that lets [followLoopOrchestrator] read/write this VM's per-book follow state. */
    private val followContext = object : FollowContext {
        override fun currentAudioSec(): Double = controller.currentAbsoluteSec()
        override fun isPlaying(): Boolean = controller.state.value.isPlaying
        override fun seekTo(positionSec: Double) { controller.seekTo(positionSec) }
        override val readerSync: com.riffle.app.feature.reader.ReaderSyncCoordinator?
            get() = reconciliationCoordinator.readerSync
        override suspend fun tryAttachReaderSync(currentAudioSec: Double): Boolean =
            attachReaderSync(currentAudioSec, 0L)
        override fun hasServer(): Boolean = serverId.isNotEmpty()
        override fun progressFraction(positionSec: Double): Float =
            audiobookProgressFraction(positionSec, timeline.durationSec)
        override suspend fun onHotPathAdvance(positionSec: Double) {
            positionSaveCoordinator.onChanged(positionSec)
        }
        override suspend fun writeSinglePeerFallback(positionSec: Double) {
            audiobookRepository.saveProgress(serverId, itemId, positionSec, timeline.durationSec)
        }
        override suspend fun writeCloseFlush(positionSec: Double, fraction: Float) {
            positionSaveCoordinator.onClose(positionSec, fraction)
        }
        override var reconciledResumeSec: Double
            get() = this@AudiobookPlayerViewModel.reconciledResumeSec
            set(value) { this@AudiobookPlayerViewModel.reconciledResumeSec = value }
        override var localUpdatedAt: Long
            get() = this@AudiobookPlayerViewModel.localUpdatedAt
            set(value) { this@AudiobookPlayerViewModel.localUpdatedAt = value }
    }
    // The position the player is *meant* to be at — the resume, last inbound jump, or last user
    // navigation. The audio position may only DRIVE the ebook outbound once it has genuinely reached/
    // advanced past this point; a transient book-start/pre-seek position below it must never win the
    // cycle (the erase was a fresh-stamped 0 overwriting the read CFI). Below it, the cycle runs
    // inbound-only so a newer remote (e.g. an ABS-web read) still pulls in. ADR 0029.
    private var reconciledResumeSec: Double = 0.0

    // Shared local-persistence policy with the ebook reader. Hot path (follow-loop tick): persist the
    // listen position to the durable audiobook store so it survives process death and can win the
    // last-update-wins resume. Cold path (pause/close): also write the `readingProgress` float to
    // `library_items` so the detail/library screens reflect listening, without invalidating the library
    // Room flow per tick. Backend (ABS) sync runs outside this coordinator (ADR 0029).
    private val positionSaveCoordinator = com.riffle.app.feature.reader.PositionSaveCoordinator<Double>(
        updateProgress = { progress -> updateReadingProgressUseCase(itemId, progress) },
        savePosition = { pos ->
            if (serverId.isNotEmpty()) {
                audiobookPositionStore.save(serverId, itemId, pos)
                // Matched book: listening is also reading — persist the translated reading position
                // locally so the durable sweep pushes the ebook record too, without reopening (ADR 0030).
                reconciliationCoordinator.mirrorListeningToReading(serverId, itemId, pos)
                // …and the readaloud resume, so reopening the reader and pressing Play lands on the
                // listened sentence rather than a stale one (ADR 0031). Runs on every save (hot path
                // and close) so a process-death mid-listen still leaves a fresh resume.
                reconciliationCoordinator.writeListeningToReadaloud(serverId, itemId, pos)
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
            val server = serverRepository.getActive()
            serverId = server?.id ?: ""
            // Claim this audiobook so the durable sweep leaves it to this player's own cycle (ADR 0030).
            if (serverId.isNotEmpty()) openReconcileTargets.markOpen(serverId, itemId)
            // Observe this book's bookmarks live into the UI state (ordered by position, earliest first).
            if (serverId.isNotEmpty()) {
                viewModelScope.launch {
                    bookmarkStore.observe(serverId, itemId).collect { list ->
                        meta.value = meta.value.copy(bookmarks = list)
                    }
                }
                // Derive the quiet offline note: unsynced (dirty) bookmarks for this item AND offline.
                // Sync is otherwise silent, so the note only shows when a push is genuinely blocked.
                viewModelScope.launch {
                    combine(
                        bookmarkStore.observeHasUnsynced(serverId, itemId),
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
            // Prefer a dedicated audiobook download, then a downloaded readaloud bundle's audio, then
            // stream from ABS (connectivity-independent: a local copy always beats streaming).
            val session = if (serverId.isEmpty()) null
                else audiobookDownloadRepository.localSession(serverId, itemId)
                    ?.also { logger.d(LogChannel.Handoff) { "AB.VM init: local download session +${clock.nowMs() - t0}ms" } }
                    ?: bundleAudiobookSource.localSession(serverId, itemId)
                    ?.also { logger.d(LogChannel.Handoff) { "AB.VM init: bundle session +${clock.nowMs() - t0}ms" } }
                    ?: audiobookRepository.openSession(serverId, itemId)
                    ?.also { logger.d(LogChannel.Handoff) { "AB.VM init: ABS network session +${clock.nowMs() - t0}ms" } }
            if (item == null || session == null) {
                logger.d(LogChannel.Handoff) { "AB.VM init: FAILED (item=${item != null} session=${session != null}) +${clock.nowMs() - t0}ms" }
                meta.value = meta.value.copy(loading = false, failed = true)
                return@launch
            }
            timeline = session.timeline
            // Resolve the resume position: last-update-wins reconcile (ADR 0029), progress-fraction
            // fallback for offline-with-bundle-only, finished-book replay guard, and the readaloud→
            // audiobook handoff override — all consolidated in AudiobookResumeResolver.
            val resume = resumeResolver.resolve(
                serverId = serverId,
                itemId = itemId,
                session = session,
                readingProgressFraction = item.readingProgress,
                startAtSec = startAtSec,
            )
            val resumeSec = resume.resumeSec
            val resumeStamp = resume.resumeStamp
            // Per-book speed (ADR 0028), shared with the linked Readaloud. Resolve the audio-settings
            // key the *same* way the reader does — via the resolver on this audiobook's link — so both
            // land on the identical key regardless of the `hasAudio` flag or sort order. With no link,
            // settings key on this ABS item (an audiobook with no bundle gets its own row). Loaded
            // before prepare so it's applied to the freshly-connected controller below; absent ⇒ 1×.
            val link = readaloudLinkRepository.findByAbsItem(serverId, itemId)
            audioSettingsIdentity = if (link != null) {
                audioIdentityResolver.resolveForStorytellerBook(link.storytellerServerId, link.storytellerBookId)
            } else {
                AudioIdentity(serverId, itemId)
            }
            val initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: listeningPreferencesStore.defaultPlaybackSpeed.first()
            // Readaloud is only actually offerable when the synced bundle is present — the same gate the
            // reader applies (readaloudControlState): a Storyteller book always qualifies, a matched ABS
            // book only once its bundle is downloaded, an unmatched ABS book never. The bundle is keyed by
            // the linked Storyteller book (or this item, on a Storyteller server), NOT the ABS item id.
            val isStoryteller = server?.serverType == com.riffle.core.domain.ServerType.STORYTELLER
            val audioServerId = link?.storytellerServerId ?: serverId
            val audioBookId = link?.storytellerBookId ?: itemId
            val readaloudAvailable = com.riffle.app.feature.reader.readaloudControlState(
                isStoryteller = isStoryteller,
                isMatchedAbs = link != null,
                bundlePresent = readaloudAudioRepository.isAudioAvailable(audioServerId, audioBookId),
            ).enabled
            // The readaloud EBOOK to switch to on swipe-down: among this Storyteller book's ABS targets,
            // the readable one (the ebook in a split library); or this same item if it's a combined
            // ebook+audio. Null when there's no readaloud ebook OR no synced bundle yet, which keeps the
            // swipe-down hint hidden and the gesture inert until read-along can actually happen.
            val readaloudEbookItemId: String? = if (!readaloudAvailable) null else link?.let { l ->
                readaloudLinkRepository.findByStorytellerBook(l.storytellerServerId, l.storytellerBookId)
                    .firstOrNull { t ->
                        t.absLibraryItemId != itemId &&
                            libraryObserver.getItem(t.absServerId, t.absLibraryItemId)?.isReadable == true
                    }
                    ?.absLibraryItemId
                    ?: itemId.takeIf { item.isReadable }
            }
            // copy() (not a fresh state) so any bookmarks the live collector already observed during
            // the suspend points above carry forward — a fresh state would wipe them, leaving an
            // existing book's bookmarks stuck empty until Room's Flow next re-emits (on add/rename/delete).
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
                facts = buildAudiobookFacts(session.timeline.durationSec, item.genres),
                description = item.description,
            )
            // Rewind-on-resume also applies to reopening an in-progress book — the open path plays
            // directly (below), bypassing togglePlayPause's resume rewind, so without this the setting
            // would only ever fire on an in-player pause→play and look broken on the far more common
            // "left and came back" resume. It shifts only where playback *starts*, NOT the resume floor
            // (reconciledResumeSec stays at the true resumeSec below): seeding prepare() lower must not let
            // the follow-loop / close-persist guards write that lower point back, or repeated open→close
            // cycles would creep the saved position backward by rewindOnResume each time. Re-hearing the
            // rewound seconds simply doesn't advance saved progress until playback passes the real resume.
            // Read straight from the store (not the eager StateFlow, which may not have emitted this early in
            // init). Guarded on resumeSec > 0 (a fresh/replayed book at the start has nothing to rewind) and
            // on the normal-open branch only — the handoff must continue seamlessly from its hand-off point.
            var playbackStartSec = resumeSec
            if (startAtSec < 0.0) {
                val rewindOnResume = listeningPreferencesStore.rewindOnResumeSeconds.first().toDouble()
                if (rewindOnResume > 0.0 && resumeSec > 0.0) {
                    playbackStartSec = (resumeSec - rewindOnResume).coerceAtLeast(0.0)
                }
            }

            // Store resolved data so handoff activations (first swipe-up or re-activations after
            // a swipe-down) can re-prepare the controller without re-fetching (ADR 0032).
            resolvedSession = session
            sessionDeferred.complete(session)
            resolvedCoverUri = item.coverUrl
            resolvedInitialSpeed = initialSpeed
            logger.d(LogChannel.Handoff) { "AB.VM init: resolvedSession ready +${clock.nowMs() - t0}ms (startAtSec=$startAtSec)" }

            if (startAtSec == PREWARM_SENTINEL) {
                // Pre-connect the binder now so the first swipe-up pays ~0 ms instead of the full
                // MediaController.Builder.buildAsync round-trip (ADR 0032).
                logger.d(LogChannel.Handoff) { "AB.VM init: warming binder +${clock.nowMs() - t0}ms" }
                controller.warmBinder()
                logger.d(LogChannel.Handoff) { "AB.VM init: binder warm +${clock.nowMs() - t0}ms" }
            } else {
                // Normal open or readaloud→audiobook handoff via nav arg: prepare and play now.
                // Resume at the server-recorded position (last-update-wins resume; ADR 0029).
                controller.prepare(
                    trackUrls = session.trackUrls,
                    spans = session.tracks,
                    durationSec = session.timeline.durationSec,
                    startAtSec = playbackStartSec,
                    localZipFile = session.localZipFile,
                    coverUri = item.coverUrl,
                )
                // Record the active session so a media-notification tap reopens this audiobook player.
                nowPlayingStore.set(com.riffle.app.playback.NowPlaying.Audiobook(itemId))
                // Apply the persisted per-book speed to the freshly-prepared (singleton) controller,
                // which would otherwise retain the previous book's speed. Set directly on the controller
                // (not the VM's setSpeed) so restoring the saved value doesn't re-save it.
                controller.setSpeed(initialSpeed)
                reconciledResumeSec = resumeSec
                localUpdatedAt = resumeStamp
                // Opening the player is itself a "play" intent (the user tapped Listen), so start
                // playback immediately rather than landing on a paused player. A genuinely-newer remote
                // resume is still honoured: attachReaderSync's inbound-only reconcile seeks the
                // already-playing position to the reconciled point (ADR 0029).
                controller.play()

                // The cross-EPUB index build (needed for the full ebook-sync coordinator) is self-healed
                // by ReaderSyncFactory.createIfApplicable below: if the bundle is present but the index
                // isn't built yet, it enqueues the build there — so this open is itself the player-open
                // retry path (ADR 0031). No explicit enqueue needed here.

                // Attach the matched 2-peer cycle if its prerequisites are already cached (the follow
                // loop re-attaches later if the index is still building). Seed it with the reconciled
                // resume so a genuinely-newer local listen can lead the first cycle. Use resumeStamp
                // (== resumeUpdatedAt normally, but the fresh now-stamp on a readaloud→audiobook
                // handoff) so the handed-off position leads the first cycle.
                attachReaderSync(resumeSec, resumeStamp)
                // Always run the follow loop so the listen position reaches ABS while playing — in the
                // matched case it drives both ABS peers (audio-led), otherwise it pushes the single ABS
                // audiobook record (ADR 0029). Without this a plain audiobook only synced on pause/close.
                followLoopOrchestrator.start(viewModelScope, followContext)
            }
            // PREWARM_SENTINEL: controller.prepare() is intentionally deferred. The binder is
            // pre-connected above; the handoff watcher below receives the actual start position
            // when the user swipes up.
            } finally {
                // Guarantee the deferred is always completed so activateFromHandoff() never
                // hangs on await() when init fails or returns early (e.g. missing item/session).
                if (!sessionDeferred.isCompleted) sessionDeferred.complete(null)
            }
        }

        // Watch for the swipe-up handoff signal from EpubReaderViewModel. Runs in all modes so
        // re-activations (second+ swipe-up after a swipe-down) also work.
        viewModelScope.launch {
            audiobookHandoffState.pendingHandoff
                .filterNotNull()
                .filter { it.itemId == itemId }
                .collect { signal ->
                    audiobookHandoffState.consumeHandoff()
                    activateFromHandoff(signal.atSec)
                }
        }

        // Watch for the overlay-dismissed signal (back button without a swipe-down handoff).
        // Pause the controller so audiobook audio stops while the reader is visible.
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

        // End-of-book: when the player reports STATE_ENDED (last track played through), close the
        // android-level player immediately and tell the screen to pop back to the detail view.
        //
        // We can't rely solely on onCleared() to tear the player down: by the time the screen pops
        // and the VM is cleared, the player has been sitting in STATE_ENDED with media items still
        // in the queue, and the [AudioPlayerService] foreground notification stays put for the user
        // to see. Tear down here so the foreground notification drops right away; onCleared() runs
        // the same teardown shortly after, idempotently (controller is already null on the second
        // pass).
        viewModelScope.launch {
            controller.playbackEnded.collect {
                flushPendingSpeed()
                pushProgressAndStopPlayer()
                clearAudiobookNowPlaying()
                _events.tryEmit(AudiobookPlayerEvent.Finished)
            }
        }

        // End-of-chapter sleep timer: fire when chapter index advances while EoC mode is active.
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

    /**
     * Delegate to [reconciliationCoordinator], applying the returned jump (if any) to the
     * controller + resume floor and adopting the canonical timestamp — wiring the coordinator's
     * side-effect-free result into the VM's audio + floor state (ADR 0029).
     */
    private suspend fun attachReaderSync(atSec: Double, atUpdatedAt: Long): Boolean {
        val result = reconciliationCoordinator.attach(serverId, itemId, atSec, atUpdatedAt)
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
            if (rewindSec > 0) {
                val newPos = (controller.currentAbsoluteSec() - rewindSec).coerceAtLeast(0.0)
                reconciledResumeSec = newPos
                controller.seekTo(newPos)
            }
            controller.play()
        }
    }

    // A user seek is genuine navigation: move the resume baseline to the target so the new position
    // (forward or backward) is allowed to drive the ebook, rather than being mistaken for a transient.
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

    /**
     * The book-absolute listen position right now. The UI snapshots this when the New-bookmark dialog
     * opens so the title, position label and the eventually-saved row all agree, even if playback keeps
     * running while the user edits the title.
     */
    fun currentPositionSec(): Double = controller.currentAbsoluteSec()

    /** The pre-filled (editable) default title for a new bookmark at [positionSec]. */
    fun defaultBookmarkTitle(positionSec: Double): String =
        BookmarkTitleBuilder.defaultTitle(timeline, positionSec)

    /**
     * Create a bookmark at [positionSec] (the position pinned when the dialog opened — NOT the live
     * playhead, which may have drifted while the user typed). The new row arrives in
     * [AudiobookPlayerUiState.bookmarks] via the store observation.
     */
    fun addBookmark(title: String, positionSec: Double) {
        if (serverId.isEmpty()) return
        viewModelScope.launch { bookmarkStore.add(serverId, itemId, positionSec, title, clock.nowMs()) }
    }

    fun renameBookmark(id: String, title: String) {
        viewModelScope.launch { bookmarkStore.rename(id, title, clock.nowMs()) }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkStore.delete(id, clock.nowMs()) }
    }

    /** Jump the player to a saved bookmark's book-absolute position (genuine user navigation). */
    fun seekToBookmark(positionSec: Double) {
        seekTo(positionSec)
    }

    /**
     * Set playback speed to a granular value (the shared speed sheet snaps to 0.05× steps). Applied to
     * the live player immediately; persisted per book (ADR 0028) debounced so a scrub through many
     * intermediate values writes only the settled speed, not every step.
     */
    fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
        pendingSpeed = speed
        speedSaveJob?.cancel()
        speedSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SPEED_SAVE_DEBOUNCE_MS)
            if (serverId.isEmpty()) return@launch
            audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed)
            pendingSpeed = null
        }
    }

    fun setSleepTimer(mode: SleepTimerMode) = controller.setSleepTimer(mode)
    fun cancelSleepTimer() = controller.cancelSleepTimer()

    /** Persist a speed change that the debounce window hadn't flushed yet (e.g. on close). */
    private fun flushPendingSpeed() {
        val speed = pendingSpeed ?: return
        speedSaveJob?.cancel()
        pendingSpeed = null
        if (serverId.isEmpty()) return
        // progressFlushScope, not viewModelScope: onCleared cancels viewModelScope before calling
        // this, so a launch there never executes (same reasoning as FollowLoopOrchestrator.stopWithFinalFlush).
        progressFlushScope.flush { audioPlaybackPreferencesStore.save(audioSettingsIdentity, speed) }
    }

    // Set when the user swipes down to switch into readaloud. The audiobook and readaloud share ONE
    // AudioPlayerService; readaloud takes over that player, so onCleared must NOT stop/clear it (that
    // would pause the readaloud playback that just started). We save progress + release the handle
    // here instead.
    private var handingOffToReadaloud = false

    /**
     * Called when the user starts dragging down (before the threshold). Pre-resolves the SMIL seek
     * target so [playFromSecond] in [ReadaloudController] can skip the computation at commit time
     * (ADR 0032). No-op when no readaloud track is loaded (audiobook-only session).
     */
    fun hintReadaloudHandoff() {
        readaloudController.preWarmSeek(controller.currentAbsoluteSec())
    }

    /** Discard the pre-warm if the drag was abandoned without crossing the threshold. */
    fun cancelHandoffHint() {
        readaloudController.cancelPreWarm()
    }

    /**
     * Prepare to switch to the readaloud reader: persist the just-reached listen position (so the
     * reader/readaloud resume is current) and release the audiobook's handle to the shared player
     * WITHOUT stopping it, so readaloud can take it over and keep playing. Call right before navigating.
     */
    fun prepareReadaloudHandoff() {
        if (handingOffToReadaloud) return
        handingOffToReadaloud = true
        // Cancel the follow loop now (VM stays alive in the always-mounted overlay, so onCleared
        // won't do this until the user exits the reader entirely).
        followLoopOrchestrator.stopWithFinalFlush()
        controller.releaseForHandoff()
    }

    /**
     * Prepare and start the audiobook controller from [atSec] on the concatenated timeline.
     * Called by the handoff watcher when the user swipes up (for both first activation from
     * pre-warm state and subsequent re-activations after a swipe-down).
     */
    private suspend fun activateFromHandoff(atSec: Double) {
        val t0 = clock.nowMs()
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff start atSec=$atSec resolvedSession=${resolvedSession != null}" }
        val session = resolvedSession ?: run {
            // Init still running — await the bundle-session fetch rather than drop the handoff.
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
            localZipFile = session.localZipFile,
            coverUri = resolvedCoverUri,
        )
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: prepare() done +${clock.nowMs() - t0}ms" }
        controller.setSpeed(resolvedInitialSpeed)
        reconciledResumeSec = finalSec
        localUpdatedAt = clock.nowMs()
        if (serverId.isNotEmpty()) {
            audiobookPositionStore.save(serverId, itemId, finalSec)
            audiobookPositionStore.updateLocalTimestamp(serverId, itemId, localUpdatedAt)
        }
        nowPlayingStore.set(com.riffle.app.playback.NowPlaying.Audiobook(itemId))
        controller.play()
        logger.d(LogChannel.Handoff) { "AB.activateFromHandoff: play() called +${clock.nowMs() - t0}ms" }
        attachReaderSync(finalSec, localUpdatedAt)
        followLoopOrchestrator.start(viewModelScope, followContext)
    }

    /**
     * Flush the just-ended position to ABS / the local position store, then tear down the
     * MediaSession queue and release the [AudiobookController] (which dismisses the foreground
     * notification). Idempotent: the controller's own stop() guards against a re-stop after release.
     */
    private fun pushProgressAndStopPlayer() {
        followLoopOrchestrator.stopWithFinalFlush()
        controller.stop()
    }

    /** Clear THIS book from the now-playing store so the mini-bar doesn't linger after the session ends. */
    private fun clearAudiobookNowPlaying() {
        nowPlayingStore.clearIf { it is com.riffle.app.playback.NowPlaying.Audiobook && it.itemId == itemId }
    }

    override fun onCleared() {
        followLoopOrchestrator.cancel()
        // Release this book to the durable sweep again (ADR 0030).
        if (serverId.isNotEmpty()) {
            openReconcileTargets.markClosed(serverId, itemId)
            // Mirror the coordinator's markOpen: on the index-free fallback path readerSync stays null
            // but audiobookFollow marked the ebook item open, so close via the same fallback chain —
            // otherwise the ebook item leaks in the open set and the sweep skips it until process death.
            reconciliationCoordinator.ebookItemIdForMarkClosed
                ?.let { openReconcileTargets.markClosed(serverId, it) }
        }
        flushPendingSpeed()
        // On a readaloud handoff the progress was already pushed and the handle released without
        // stopping the shared player (readaloud now owns it) — stopping here would pause readaloud.
        if (!handingOffToReadaloud) pushProgressAndStopPlayer()
        // Leaving the player stops playback (no mini-bar), so this session is no longer playing.
        clearAudiobookNowPlaying()
        super.onCleared()
    }

    private companion object {
        // Debounce window for persisting a speed change, so a granular scrub settles to a single write
        // rather than one per intermediate 0.05× value (matches EpubReaderViewModel).
        const val SPEED_SAVE_DEBOUNCE_MS = 400L
        // Sentinel for startAtSec: the overlay is always mounted for pre-warming; the actual start
        // position arrives via AudiobookHandoffState when the user swipes up.
        const val PREWARM_SENTINEL = -2.0
    }
}
