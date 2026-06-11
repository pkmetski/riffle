package com.riffle.app.feature.audiobook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AudiobookRepository
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Maps a book-absolute listen position to the unified 0..1 `readingProgress` fraction the library
 * and detail screens render (ADR 0029). Returns 0 when the duration isn't known yet (so a not-yet-
 * prepared player never writes a bogus 100%).
 */
internal fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    if (durationSec > 0.0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f

/** UI state for the full-screen [Audiobook Player] (ADR 0029). */
data class AudiobookPlayerUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val authToken: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapterStartsSec: List<Double> = emptyList(),
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val audiobookRepository: AudiobookRepository,
    private val audiobookDownloadRepository: com.riffle.core.domain.AudiobookDownloadRepository,
    private val libraryRepository: LibraryRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val controller: AudiobookController,
    private val readerSyncFactory: com.riffle.app.feature.reader.ReaderSyncFactory,
    private val readaloudLinkRepository: com.riffle.core.domain.ReadaloudLinkRepository,
    private val crossEpubIndexBuilder: com.riffle.core.data.CrossEpubIndexBuilderService,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId") ?: ""

    private val meta = MutableStateFlow(
        AudiobookPlayerUiState(loading = true),
    )
    private var timeline: AudiobookTimeline = AudiobookTimeline(0.0)
    private var serverId: String = ""

    // Non-null for a matched book whose readaloud prerequisites (ABS EPUB + bundle + cross-EPUB
    // index) are cached: playback then drives the canonical reconciliation cycle **audio-led**, so a
    // listen propagates to the ebook CFI + audiobook record and a cross-device change pulls in
    // (ADR 0029). Null (audiobook-only, or prerequisites not cached) → single-peer saveProgress.
    private var readerSync: com.riffle.app.feature.reader.ReaderSyncCoordinator? = null
    // The timestamp the local listen position was last genuinely set at; adopted from server stamps
    // after a write/jump so our own writes never read back as "newer" and re-seek (feedback loop).
    private var localUpdatedAt: Long = 0L
    private var followJob: kotlinx.coroutines.Job? = null
    // The position the player is *meant* to be at — the resume, last inbound jump, or last user
    // navigation. The audio position may only DRIVE the ebook outbound once it has genuinely reached/
    // advanced past this point; a transient book-start/pre-seek position below it must never win the
    // cycle (the erase was a fresh-stamped 0 overwriting the read CFI). Below it, the cycle runs
    // inbound-only so a newer remote (e.g. an ABS-web read) still pulls in. ADR 0029.
    private var reconciledResumeSec: Double = 0.0

    // Same cold-path local-persistence policy as the ebook reader: the `readingProgress` float is
    // written to `library_items` only on pause/close (never on the hot follow-loop tick), so the
    // detail/library screens reflect listening without invalidating the library Room flow per tick.
    // No `savePosition` — the audiobook resumes from ABS, so there is no local position to store;
    // its backend sync (audio-led cycle / single-peer push) runs outside the coordinator (ADR 0029).
    private val positionSaveCoordinator = com.riffle.app.feature.reader.PositionSaveCoordinator<Double>(
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
    )

    val uiState: StateFlow<AudiobookPlayerUiState> =
        combine(meta, controller.state) { m, playback ->
            val pos = playback.positionSec
            val chapter = timeline.chapterAt(pos)
            m.copy(
                isPlaying = playback.isPlaying,
                speed = playback.speed,
                positionSec = pos,
                durationSec = if (playback.durationSec > 0) playback.durationSec else m.durationSec,
                currentChapterTitle = chapter?.title,
                chapterStartsSec = timeline.chapters.map { it.startSec },
                canPreviousChapter = timeline.canPreviousChapter,
                canNextChapter = timeline.canNextChapter,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AudiobookPlayerUiState(loading = true))

    init {
        viewModelScope.launch {
            val server = serverRepository.getActive()
            serverId = server?.id ?: ""
            val token = server?.let { tokenStorage.getToken(it.id) } ?: ""
            val item = libraryRepository.getItem(itemId)
            // Prefer the downloaded local copy (offline, file:// tracks); else stream from ABS (ADR 0029).
            val session = if (serverId.isEmpty()) null
                else audiobookDownloadRepository.localSession(serverId, itemId)
                    ?: audiobookRepository.openSession(serverId, itemId)
            if (item == null || session == null) {
                meta.value = meta.value.copy(loading = false, failed = true)
                return@launch
            }
            timeline = session.timeline
            meta.value = AudiobookPlayerUiState(
                loading = false,
                title = item.title,
                author = item.author,
                coverUrl = item.coverUrl,
                authToken = token,
                durationSec = session.timeline.durationSec,
            )
            // Resume at the server-recorded position (last-update-wins resume; ADR 0029) so the
            // audio-led canonical never starts behind.
            controller.prepare(
                trackUrls = session.trackUrls,
                spans = session.tracks,
                durationSec = session.timeline.durationSec,
                startAtSec = session.serverCurrentTimeSec,
            )
            reconciledResumeSec = session.serverCurrentTimeSec

            // Ebook sync needs the cross-EPUB index (ABS EPUB + bundle SMIL). The match is confirmed
            // *before* the readaloud bundle is downloaded, so that build defers; re-enqueue it here so a
            // matched audiobook starts driving the ebook once the bundle is present and the index lands
            // (ADR 0029). Idempotent + background.
            readaloudLinkRepository.findByAbsItem(serverId, itemId)?.let { crossEpubIndexBuilder.enqueueBuild(it) }

            // Attach the matched 2-peer cycle if its prerequisites are already cached (the follow loop
            // re-attaches later if the index is still building).
            attachReaderSync()
            // Always run the follow loop so the listen position reaches ABS while playing — in the
            // matched case it drives both ABS peers (audio-led), otherwise it pushes the single ABS
            // audiobook record (ADR 0029). Without this a plain audiobook only synced on pause/close.
            startFollowLoop()
        }
    }

    /**
     * Attach the matched two-peer cycle once its prerequisites (bundle + ABS EPUB + cross-EPUB index)
     * are cached, running the open-reconcile once. No-op when already attached or not yet buildable —
     * called on open and re-tried each follow-loop tick so the ebook starts syncing as soon as the
     * background index build finishes (ADR 0029).
     */
    private suspend fun attachReaderSync(): Boolean {
        if (readerSync != null || serverId.isEmpty()) return readerSync != null
        val rs = readerSyncFactory.createIfApplicable(itemId) ?: return false
        readerSync = rs
        // Inbound-only reconcile (local time 0 → local can never win): the freshest remote wins this
        // first cycle and the player seeks to the reconciled position before any playback leads — the
        // resume-at-reconciled guard. A book-start local position can never drive the ebook here.
        val r = rs.runAudioLedCycle(controller.currentAbsoluteSec(), localUpdatedAt = 0L)
        r.jumpToAudioSec?.let { controller.seekTo(it); reconciledResumeSec = it }
        localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
        return true
    }

    /**
     * While the player is open and playing, push the listen position to ABS every [FOLLOW_INTERVAL_MS].
     * Matched book: run the audio-led reconciliation (stamp `now` so the live listen leads and advances
     * both the ebook CFI and the audiobook record; when paused, let a genuinely-newer remote win and
     * seek). Plain audiobook: push the single ABS audiobook record.
     */
    private fun startFollowLoop() {
        if (followJob?.isActive == true) return
        followJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(FOLLOW_INTERVAL_MS)
                // Self-heal: a matched book's index may finish building after open — attach then and
                // skip this tick so the reconcile's resume seek settles before anything drives.
                if (readerSync == null && attachReaderSync()) continue

                val rs = readerSync
                val pos = controller.currentAbsoluteSec()
                val playing = controller.state.value.isPlaying
                if (rs != null) {
                    // Only a GENUINE, settled, forward listen may drive the ebook (stamp `now` → local
                    // leads). A transient position below the reconciled resume — buffering, a track
                    // transition, a not-yet-settled seek, the book-start 0 — must NEVER lead; that was
                    // the erase (a fresh-stamped 0 overwriting the read CFI). Below the resume we run
                    // inbound-only (local time 0 → local can't win), so a genuinely-newer remote still
                    // pulls in and seeks, but nothing local can erase progress.
                    if (playing && pos >= reconciledResumeSec - SETTLE_EPS_SEC) {
                        localUpdatedAt = System.currentTimeMillis()
                        reconciledResumeSec = maxOf(reconciledResumeSec, pos)
                        val r = rs.runAudioLedCycle(pos, localUpdatedAt)
                        localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
                    } else {
                        val r = rs.runAudioLedCycle(pos, localUpdatedAt = 0L)
                        r.jumpToAudioSec?.let { controller.seekTo(it); reconciledResumeSec = it }
                        localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
                    }
                } else if (playing && pos >= reconciledResumeSec - SETTLE_EPS_SEC) {
                    // single-peer: push the ABS audiobook currentTime, but never a transient position
                    // below the resume (which would regress the record).
                    reconciledResumeSec = maxOf(reconciledResumeSec, pos)
                    saveProgress()
                }
            }
        }
    }

    fun togglePlayPause() {
        if (controller.state.value.isPlaying) {
            controller.pause()
            pushProgressOnStop()
        } else {
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
        reconciledResumeSec = (controller.currentAbsoluteSec() - AudiobookController.REWIND_SEC).coerceAtLeast(0.0)
        controller.rewind()
    }

    fun forward() {
        reconciledResumeSec = controller.currentAbsoluteSec() + AudiobookController.FORWARD_SEC
        controller.forward()
    }

    fun previousChapter() {
        timeline.previousChapterTargetSec(controller.currentAbsoluteSec())?.let { reconciledResumeSec = it; controller.seekTo(it) }
    }

    fun nextChapter() {
        timeline.nextChapterTargetSec(controller.currentAbsoluteSec())?.let { reconciledResumeSec = it; controller.seekTo(it) }
    }

    /** Set playback speed to a granular value (the shared speed sheet snaps to 0.05× steps). */
    fun setSpeed(speed: Float) = controller.setSpeed(speed)

    /**
     * Push the just-stopped listen position. Matched (bundle present) → one audio-led cycle stamped
     * `now`, so the listen wins last-update-wins and propagates to the ebook + audiobook (ADR 0029).
     * Audiobook-only / no bundle → the simple single-peer ABS audiobook write.
     */
    private fun pushProgressOnStop() {
        if (serverId.isEmpty()) return
        val pos = controller.currentAbsoluteSec()
        val fraction = audiobookProgressFraction(pos, timeline.durationSec)
        viewModelScope.launch {
            // Backend (ABS) sync — outside the coordinator, like the reader's progress-sync cycle.
            val rs = readerSync
            if (rs != null) {
                localUpdatedAt = System.currentTimeMillis()
                val r = rs.runAudioLedCycle(pos, localUpdatedAt)
                localUpdatedAt = maxOf(localUpdatedAt, r.canonicalLastUpdate)
            } else {
                audiobookRepository.saveProgress(serverId, itemId, pos, timeline.durationSec)
            }
            // Shared cold-path local persistence (same policy as the ebook reader).
            positionSaveCoordinator.onClose(pos, fraction)
        }
    }

    private fun saveProgress() {
        if (serverId.isEmpty()) return
        val pos = controller.currentAbsoluteSec()
        val dur = timeline.durationSec
        viewModelScope.launch { audiobookRepository.saveProgress(serverId, itemId, pos, dur) }
    }

    override fun onCleared() {
        followJob?.cancel()
        pushProgressOnStop()
        controller.stop()
        super.onCleared()
    }

    private companion object {
        const val FOLLOW_INTERVAL_MS = 10_000L
        // How far below the reconciled resume a position may sit and still be treated as "settled"
        // (covers a seek/buffer landing within a tick); anything further behind is a transient that
        // must not lead. Small, so a genuine book-start 0 can never drive the ebook.
        const val SETTLE_EPS_SEC = 3.0
    }
}
