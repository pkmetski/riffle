@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.AudiobookFollow
import com.riffle.app.feature.reader.ReaderSyncCoordinator
import com.riffle.app.feature.reader.ReaderSyncFactory
import com.riffle.core.data.OpenReconcileTargets
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.AudioIdentity
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudioPlaybackPreferencesStore
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Owns book-open orchestration, matched-book cross-sync state, and per-session bookkeeping
 * (`Publication`, `epubFile`, `epubZip`, `closeSyncDone`) previously scattered across
 * [com.riffle.app.feature.reader.EpubReaderViewModel].
 *
 * The lifecycle resolves everything the VM needs to bind its per-book collaborators
 * (readaloud, position, bookmarks, annotations) and hands back an [OpenOutcome.Ready] payload.
 * Binding itself stays in the VM — the seam is intentionally Compose-facing-composer + resolver,
 * not a super-orchestrator.
 *
 * MUST NOT import android.webkit.*, ContinuousReaderView, or any Compose types.
 */
class ReaderSessionLifecycle @AssistedInject constructor(
    @Assisted private val openPublication: suspend (File) -> Publication?,
    @Assisted private val cfiStringToLocator: suspend (String) -> Locator?,
    private val libraryObserver: LibraryObserver,
    private val epubRepository: EpubRepository,
    private val sourceRepository: SourceRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val openReconcileTargets: OpenReconcileTargets,
    private val readerSyncFactory: ReaderSyncFactory,
    private val annotationStore: AnnotationStore,
    private val logger: Logger,
) {

    @AssistedFactory
    interface Factory {
        fun create(
            openPublication: suspend (File) -> Publication?,
            cfiStringToLocator: suspend (String) -> Locator?,
        ): ReaderSessionLifecycle
    }

    private val _publication = MutableStateFlow<Publication?>(null)
    val publication: StateFlow<Publication?> = _publication

    private val _matchedSync = MutableStateFlow<MatchedSync?>(null)
    val matchedSync: StateFlow<MatchedSync?> = _matchedSync

    @Volatile private var epubFile: File? = null
    @Volatile private var epubZip: ZipFile? = null

    // Teardown state — needed to release the openReconcile claims made during open(). Held here
    // rather than derived from matchedSync because matchedSync.sourceId is only set when a match
    // was resolved; a single-peer session still needs to release its own claim.
    @Volatile private var readerServerId: String? = null
    @Volatile private var readerItemId: String? = null

    private val closeSyncDone = AtomicBoolean(false)

    /**
     * Legacy raw-`epubcfi(...)` translation is passed as a suspending lambda so the lifecycle
     * stays free of the CFI-parsing helpers the VM already owns. A follow-up may lift these here.
     */
    suspend fun open(params: OpenParams): OpenOutcome {
        val item = libraryObserver.getItem(params.itemId)
            ?: return OpenOutcome.Error("Book not found")

        return when (val result = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> resolveReady(result, item.title, params)
            is EpubOpenResult.NetworkError -> OpenOutcome.Error("Network error: ${result.cause.message}")
            EpubOpenResult.Offline -> OpenOutcome.Error("Book not available offline")
        }
    }

    private suspend fun resolveReady(
        result: EpubOpenResult.Success,
        title: String,
        params: OpenParams,
    ): OpenOutcome {
        epubFile = result.epubFile
        val pub = openPublication(result.epubFile)
            ?: return OpenOutcome.Error("Failed to open EPUB")
        _publication.value = pub

        val activeServer: Source? = sourceRepository.getActive()
        val isStorytellerServer = activeServer?.serverType == ServerType.STORYTELLER

        // Matched ABS book → key the bundle by the linked Storyteller book id (the bundle is
        // stored under that id, not the ABS item id). Storyteller side keeps itemId.
        val link = if (!isStorytellerServer && activeServer != null) {
            readaloudLinkRepository.findByAbsItem(activeServer.id, params.itemId)
        } else {
            null
        }
        val resolvedAudioBookId = link?.storytellerBookId ?: params.itemId
        val resolvedAudioServerId = link?.storytellerSourceId ?: activeServer?.id ?: ""
        val resolvedReaderServerId = activeServer?.id

        // The audiobook to switch to on swipe-up: among this readaloud's ABS targets, the
        // listenable one (in a split library), or this same item if it's a combined ebook+audio.
        val resolvedAudiobookItemId = link?.let { l ->
            readaloudLinkRepository.findByStorytellerBook(l.storytellerSourceId, l.storytellerBookId)
                .firstOrNull { t ->
                    t.absLibraryItemId != params.itemId &&
                        libraryObserver.getItem(t.absSourceId, t.absLibraryItemId)?.isListenable == true
                }
                ?.absLibraryItemId
        }
        logger.d(LogChannel.Handoff) { "RA.audiobookItemId resolved=$resolvedAudiobookItemId (overlay can now mount)" }

        val resolvedAudioSettingsIdentity = if (link != null) {
            audioIdentityResolver.resolveForStorytellerBook(link.storytellerSourceId, link.storytellerBookId)
        } else {
            AudioIdentity(activeServer?.id ?: "", params.itemId)
        }
        val resolvedInitialSpeed = audioPlaybackPreferencesStore.load(resolvedAudioSettingsIdentity)
            ?: listeningPreferencesStore.defaultPlaybackSpeed.first()

        // Record teardown state BEFORE the markOpen so any suspend between here and the return
        // still gets its claim released via onCleared() — else a slow / failing DataStore read on
        // the initial-speed load below would leak an openReconcile claim forever.
        readerServerId = resolvedReaderServerId
        readerItemId = params.itemId
        // Claim this book so the durable sweep leaves it to this reader's own cycle (ADR 0030).
        activeServer?.id?.let { openReconcileTargets.markOpen(it, params.itemId) }

        val openAtCfiNonBlank = params.openAtCfi?.takeIf { it.isNotBlank() }
        // A search-result / annotation-tap open overrides the saved position. Requires `publication`
        // (set above) — cfiStringToLocator resolves against it.
        val openAtLocator = openAtCfiNonBlank?.let { cfiStringToLocator(it) }
        // openAtCfiNonBlank is non-null whenever openAtLocator is (openAtLocator is derived from it).
        val initialFocusAnnotationId = if (openAtLocator != null && resolvedReaderServerId != null && openAtCfiNonBlank != null) {
            runCatching {
                annotationStore.findByItemAndCfi(resolvedReaderServerId, params.itemId, openAtCfiNonBlank)
            }.getOrNull()?.id
        } else {
            null
        }

        // Stored lastPosition is Readium Locator JSON. Rows written before ADR 0030's translation
        // fix may still hold a raw ABS `epubcfi(...)` — heal those on open so legacy progress
        // isn't lost. A genuinely unusable value falls back to null.
        val storedLocator = result.lastPosition?.takeIf { it.isNotBlank() }?.let { stored ->
            runCatching { Locator.fromJSON(JSONObject(stored)) }.getOrNull()
                ?: cfiStringToLocator(stored)
        }

        // Effective initial locator — openAtCfi wins over stored. When a TOC entry is requested,
        // pass null so Readium's async restore doesn't race with the VM's navigateToEntry.
        val effectiveInitial = openAtLocator ?: storedLocator
        val initialLocator = if (params.startTocHref == null) effectiveInitial else null
        val effectiveFocusAnnotationId = if (params.startTocHref == null) initialFocusAnnotationId else null

        // Build matched-sync — a matched book runs the reconciliation cycle instead of the
        // single-peer ABS/Storyteller paths. When the full coordinator can't be built (no
        // cross-EPUB index yet), fall back to bundle-SMIL-only follow (ADR 0031).
        val readerSync = runCatching { readerSyncFactory.createIfApplicable(params.itemId) }.getOrNull()
        val audiobookFollow = if (readerSync == null) {
            runCatching { readerSyncFactory.createAudiobookFollowIfApplicable(params.itemId) }.getOrNull()
        } else {
            null
        }
        _matchedSync.value = MatchedSync(
            readerSync = readerSync,
            audiobookFollow = audiobookFollow,
            sourceId = resolvedReaderServerId,
        )
        // A matched book also drives the audiobook ABS record from this reader, so the sweep must
        // skip that (possibly split-library) item too while the reader is open (ADR 0030).
        resolvedReaderServerId?.let { sid ->
            (readerSync?.audioItemId ?: audiobookFollow?.audioItemId)?.let {
                openReconcileTargets.markOpen(sid, it)
            }
        }

        return OpenOutcome.Ready(
            publication = pub,
            title = title,
            initialLocator = initialLocator,
            initialFocusAnnotationId = effectiveFocusAnnotationId,
            effectiveInitialLocator = effectiveInitial,
            openAtLocator = openAtLocator,
            activeServer = activeServer,
            isStorytellerServer = isStorytellerServer,
            resolvedAudioBookId = resolvedAudioBookId,
            resolvedAudioServerId = resolvedAudioServerId,
            resolvedReaderServerId = resolvedReaderServerId,
            resolvedAudiobookItemId = resolvedAudiobookItemId,
            resolvedAudioSettingsIdentity = resolvedAudioSettingsIdentity,
            resolvedInitialSpeed = resolvedInitialSpeed,
        )
    }

    /**
     * Lazy zip handle for chapter-HTML extraction; cached across calls, closed by [onCleared].
     * Null before [open] succeeds.
     */
    fun zip(): ZipFile? {
        epubZip?.let { return it }
        val file = epubFile ?: return null
        return synchronized(this) {
            epubZip ?: runCatching { ZipFile(file) }.getOrNull()?.also { epubZip = it }
        }
    }

    /**
     * Guard for the ON_STOP / onDispose double-write on close. Returns true the first time it is
     * called within a session; subsequent calls (until [resetCloseSync]) return false so the caller
     * short-circuits its close-sync payload.
     */
    fun tryClaimCloseSync(): Boolean = closeSyncDone.compareAndSet(false, true)

    /** Called from `onReaderResumed` — arms the guard for the next backgrounding. */
    fun resetCloseSync() { closeSyncDone.set(false) }

    /**
     * Terminal teardown — mirrors the field-clearing block in `EpubReaderViewModel.onCleared`.
     * Releases open-reconcile claims, closes the cached zip and publication.
     */
    fun onCleared() {
        val sid = readerServerId
        val iid = readerItemId
        if (sid != null && iid != null) {
            openReconcileTargets.markClosed(sid, iid)
            val ms = _matchedSync.value
            (ms?.readerSync?.audioItemId ?: ms?.audiobookFollow?.audioItemId)?.let {
                openReconcileTargets.markClosed(sid, it)
            }
        }
        epubZip?.close()
        epubZip = null
        _publication.value?.close()
        _publication.value = null
    }

    data class OpenParams(
        val itemId: String,
        val openAtCfi: String?,
        val startTocHref: String?,
    )

    sealed interface OpenOutcome {
        data class Ready(
            val publication: Publication,
            val title: String,
            val initialLocator: Locator?,
            val initialFocusAnnotationId: String?,
            /** The initial locator without the TOC-nav null-out. Used by the initial sync cycle. */
            val effectiveInitialLocator: Locator?,
            val openAtLocator: Locator?,
            val activeServer: Source?,
            val isStorytellerServer: Boolean,
            val resolvedAudioBookId: String,
            val resolvedAudioServerId: String,
            val resolvedReaderServerId: String?,
            val resolvedAudiobookItemId: String?,
            val resolvedAudioSettingsIdentity: AudioIdentity,
            val resolvedInitialSpeed: Float,
        ) : OpenOutcome

        data class Error(val message: String) : OpenOutcome
    }

    data class MatchedSync(
        val readerSync: ReaderSyncCoordinator?,
        val audiobookFollow: AudiobookFollow?,
        val sourceId: String?,
    )
}
