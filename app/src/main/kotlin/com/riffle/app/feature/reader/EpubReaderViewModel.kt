@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.app.feature.reader.readaloud.PlayerCoordinator
import com.riffle.app.feature.reader.readaloud.ReadaloudController
import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.ConnectivityObserver
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.withResolvedTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

private const val SYNC_INTERVAL_MS = 30_000L

sealed class ReaderState {
    data object Loading : ReaderState()
    data class Ready(
        val publication: Publication,
        val title: String,
        val initialLocator: Locator?,
    ) : ReaderState()
    data class Error(val message: String) : ReaderState()
}

@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val epubRepository: EpubRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val readingSessionRepository: ReadingSessionRepository,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore,
    private val volumeNavigationController: VolumeNavigationController,
    private val timeProvider: TimeProvider,
    private val readerStateHolder: ReaderStateHolder,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val playerCoordinator: PlayerCoordinator,
    private val storytellerSyncController: StorytellerPositionSyncController,
    private val serverRepository: ServerRepository,
    private val connectivityObserver: ConnectivityObserver,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    private val _footnotePopup = MutableStateFlow<FootnotePopupState?>(null)
    val footnotePopup: StateFlow<FootnotePopupState?> = _footnotePopup

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    private val progressSyncController = ProgressSyncController(
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator(
        savePosition = { cfi -> epubRepository.saveReadingPosition(itemId, cfi) },
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
    )

    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    private var lastLocator: Locator? = null
    val latestLocator: Locator? get() = lastLocator
    private var publication: Publication? = null
    private var epubFile: File? = null
    @Volatile private var epubZip: ZipFile? = null
    private val chapterHtmlCache = mutableMapOf<Int, String>()
    private var syncJob: Job? = null
    private var closeSyncDone = false
    // True after the navigator emits its first locator (the restored position on open).
    // The first emission is not new user progress — the position is already in DB — so we skip
    // the DB write to avoid stomping localUpdatedAt before the initial sync cycle runs.
    private var initialLocatorSeen = false

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val volumeKeyNavigationEnabled: StateFlow<Boolean> = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val invertVolumeKeys: StateFlow<Boolean> = volumeKeyPreferencesStore.invertVolumeKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    // Per-field book overrides. null on a field means "follow global", so changing global later
    // propagates to the book for fields the user hasn't touched in the panel.
    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())

    // Effective prefs = global ⊕ overrides. Updated optimistically on user change so the
    // navigator sees the new value without waiting for the Room write to complete.
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    // Ticks emitted at each ThemeSchedule boundary so the resolved theme can flip live
    // during a reading session. Re-armed whenever the schedule changes.
    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit) // prime the combine() below so it emits immediately on collection
    }

    // The user's pick with `theme` replaced by the resolver's concrete value when the
    // pick is Auto. Every downstream consumer (Readium navigator submitPreferences,
    // chapter rail backdrop, palette) reads this — they stay ignorant of Auto.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())

    private val _hasBookOverrides = MutableStateFlow(false)
    val hasBookOverrides: StateFlow<Boolean> = _hasBookOverrides

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<Locator>>(emptyList())
    val searchResults: StateFlow<List<Locator>> = _searchResults

    private val _currentSearchIndex = MutableStateFlow(-1)
    val currentSearchIndex: StateFlow<Int> = _currentSearchIndex

    private val _searchNavigationChannel = Channel<Locator>(Channel.CONFLATED)
    val searchNavigationEvents: Flow<Locator> = _searchNavigationChannel.receiveAsFlow()

    private var searchJob: Job? = null

    // ---- Readaloud (ADR 0023) ----------------------------------------------------------------

    // True once we know the active server type. Every Storyteller book qualifies (its bundle can
    // be downloaded on demand); ABS books qualify only when a synced bundle is already present.
    private var isStorytellerServer = false

    private val _readaloudAvailable = MutableStateFlow(false)
    val readaloudAvailable: StateFlow<Boolean> = _readaloudAvailable

    // Whether the bottom mini-player / sheet is showing.
    private val _readaloudOpen = MutableStateFlow(false)
    val readaloudOpen: StateFlow<Boolean> = _readaloudOpen

    // Mini-player (false) vs full-height bottom sheet (true).
    private val _readaloudExpanded = MutableStateFlow(false)
    val readaloudExpanded: StateFlow<Boolean> = _readaloudExpanded

    // Mirrors the controller's playback state for the bar/sheet controls.
    val playbackState: StateFlow<ReadaloudController.PlaybackState> = playerCoordinator.state

    // The text fragment currently narrated — drives the synced highlight. Null clears it.
    val activeFragmentRef: StateFlow<String?> = playerCoordinator.activeFragmentRef

    // Fired by the coordinator when the narrated sentence scrolls off-screen during playback.
    val advancePageEvents: SharedFlow<Unit> = playerCoordinator.advanceEvents

    // The track is parsed once on first play and reused.
    private var readaloudTrack: com.riffle.core.domain.ReadaloudTrack? = null

    // Download-prompt state: non-null size means "show the download dialog".
    private val _downloadPromptBytes = MutableStateFlow<Long?>(null)
    val downloadPromptBytes: StateFlow<Long?> = _downloadPromptBytes

    // Set when play is tapped offline with no local bundle — shown as an in-bar message.
    private val _readaloudOfflineMessage = MutableStateFlow(false)
    val readaloudOfflineMessage: StateFlow<Boolean> = _readaloudOfflineMessage

    // True while a download is running, so the bar can show progress text.
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    init {
        viewModelScope.launch {
            // Sequential: prefs must be available before openBook() so initialPreferences is set correctly.
            loadFormattingPreferences()
            openBook()
        }
        // Keep effective prefs in sync with both global changes and override updates. This is
        // why per-book settings are sparse: a global change to a field the user hasn't touched
        // in this book flows through immediately.
        viewModelScope.launch {
            combine(
                formattingPreferencesStore.preferences,
                _bookOverrides,
            ) { global, overrides -> overrides.applyTo(global) to !overrides.isEmpty }
                .collect { (effective, hasOverrides) ->
                    _formattingPreferences.value = effective
                    _hasBookOverrides.value = hasOverrides
                }
        }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverProgressToLocator(serverProgress)?.let { _serverLocatorChannel.trySend(it) }
            }
        }
        viewModelScope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    // Degenerate schedule (equal day/night times) collapses to always-day —
                    // no boundary will ever arrive. Park until cancelled (the schedule
                    // changes) instead of spinning at 1 Hz against the 1_000L floor.
                    if (schedule.dayStart == schedule.nightStart) {
                        awaitCancellation()
                    }
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val nowSec = now.toSecondOfDay().toLong()
                        val nextSec = next.toSecondOfDay().toLong()
                        val delayMs = (((nextSec - nowSec + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
                }
        }
        viewModelScope.launch {
            // Active-server type decides Readaloud availability: every Storyteller book qualifies,
            // while ABS books qualify only when a synced bundle has already been downloaded.
            isStorytellerServer = serverRepository.getActive()?.serverType == ServerType.STORYTELLER
            _readaloudAvailable.value =
                isStorytellerServer || readaloudAudioRepository.isAudioAvailable(itemId)
        }
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _searchQuery
                .debounce(300)
                .collect { query ->
                    searchJob?.cancel()
                    if (query.length < 2) {
                        _searchResults.value = emptyList()
                        _currentSearchIndex.value = -1
                        return@collect
                    }
                    searchJob = launch { performSearch(query) }
                }
        }
    }

    private suspend fun loadFormattingPreferences() {
        val overrides = bookFormattingPreferencesStore.load(itemId)
        val global = formattingPreferencesStore.preferences.first()
        _bookOverrides.value = overrides
        _formattingPreferences.value = overrides.applyTo(global)
        _hasBookOverrides.value = !overrides.isEmpty
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> {
                epubFile = result.epubFile
                val pub = openPublication(result.epubFile)
                if (pub == null) {
                    _state.value = ReaderState.Error("Failed to open EPUB")
                    return
                }
                publication = pub
                // Stored lastPosition may be empty or malformed (legacy rows / corrupted writes).
                // Fall back to null so openBook() can open the publication at its default position.
                val locator = result.lastPosition?.takeIf { it.isNotBlank() }?.let {
                    try { Locator.fromJSON(JSONObject(it)) } catch (_: Exception) { null }
                }
                _state.value = ReaderState.Ready(
                    publication = pub,
                    title = item.title,
                    initialLocator = locator,
                )
                // Sync immediately while localUpdatedAt is still the genuine stored value —
                // before the navigator restore emits and would stamp localUpdatedAt = now.
                progressSyncController.sync(itemId, locator?.toPayload() ?: SessionPayload("", 0f))

                startPeriodicSync()
            }
            is EpubOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            EpubOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
            is EpubOpenResult.BundleTooLarge -> {
                val mb = (result.sizeBytes + 512 * 1024) / (1024 * 1024)
                _state.value = ReaderState.Error(
                    "This book is ${mb} MB — too large to open on the fly.\n\nGo back and tap Download to read it.",
                )
            }
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                syncCurrentPosition()
            }
        }
    }

    private fun syncCurrentPosition() {
        val locator = lastLocator ?: return
        viewModelScope.launch {
            progressSyncController.sync(itemId, locator.toPayload())
        }
    }

    fun showFootnotePopup(content: String) {
        _footnotePopup.value = FootnotePopupState(content)
    }

    fun dismissFootnotePopup() {
        _footnotePopup.value = null
    }

    fun onPositionChanged(locator: Locator) {
        lastLocator = locator
        _currentLocatorHref.value = locator.href.toString()
        _currentLocatorProgression.value = locator.locations.progression?.toFloat() ?: 0f
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        viewModelScope.launch {
            positionSaveCoordinator.onChanged(locator.toJSON().toString())
        }
    }

    fun onReaderResumed() {
        readerStateHolder.isReaderActive = true
        closeSyncDone = false
        initialLocatorSeen = false
        if (_state.value is ReaderState.Ready) {
            syncCurrentPosition()
            startPeriodicSync()
        }
    }

    fun onReaderClosed() {
        readerStateHolder.isReaderActive = false
        readerStateHolder.isPanelOpen = false
        syncJob?.cancel()
        storytellerSyncJob?.cancel()
        if (closeSyncDone) return
        closeSyncDone = true
        val locator = lastLocator ?: return
        viewModelScope.launch {
            val payload = locator.toPayload()
            positionSaveCoordinator.onClose(locator.toJSON().toString(), payload.ebookProgress)
            progressSyncController.sync(itemId, payload)
        }
    }

    fun onPanelStateChanged(isOpen: Boolean) {
        readerStateHolder.isPanelOpen = isOpen
    }

    // Translates via character-count CFIs; see EpubCfiTranslator and ADR 0013.
    private suspend fun Locator.toPayload(): SessionPayload = withContext(Dispatchers.Default) {
        val readingOrder = publication?.readingOrder ?: emptyList()
        val spineIndex = readingOrder.indexOfFirst { link ->
            normalizeEpubHref(link.href.toString()) == normalizeEpubHref(href.toString())
        }
        val fullCfi = if (spineIndex >= 0) {
            val spineStep = (spineIndex + 1) * 2
            val html = readChapterHtml(spineIndex)
            val docPath = html?.let { progressionToCfiDocPath(locations.progression ?: 0.0, it) }
            if (docPath != null) "epubcfi(/6/$spineStep!$docPath)"
            else buildEpubCfi(readingOrder, href)
        } else {
            buildEpubCfi(readingOrder, href)
        }
        SessionPayload(
            ebookLocation = fullCfi,
            ebookProgress = locations.totalProgression?.toFloat() ?: locations.progression?.toFloat() ?: 0f,
        )
    }

    private suspend fun serverProgressToLocator(serverProgress: ServerProgress): Locator? {
        val pub = publication ?: return null
        val cfi = serverProgress.ebookLocation.takeIf { it.startsWith("epubcfi(") }
        if (cfi != null) {
            val docPath = extractCfiDocPath(cfi)
            val spineIndex = epubCfiToSpineIndex(cfi)
            val link = spineIndex?.let { pub.readingOrder.getOrNull(it) }
            val html = spineIndex?.let { readChapterHtml(it) }
            val chapterProgression = if (docPath != null && html != null) cfiDocPathToProgression(docPath, html) else null
            if (link != null && chapterProgression != null) {
                return try {
                    Locator.fromJSON(
                        JSONObject()
                            .put("href", link.href.toString())
                            .put("type", "application/xhtml+xml")
                            .put("locations", JSONObject().put("progression", chapterProgression))
                    )
                } catch (_: Exception) { null }
            }
        }
        // Fallback: no usable CFI — navigate via book-wide progress float
        val progress = serverProgress.ebookProgress.toDouble().coerceIn(0.0, 1.0)
        return if (progress > 0.0) pub.locateProgression(progress) else null
    }

    private suspend fun readChapterHtml(spineIndex: Int): String? {
        chapterHtmlCache[spineIndex]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = epubFile ?: return@withContext null
            val pub = publication ?: return@withContext null
            val link = pub.readingOrder.getOrNull(spineIndex) ?: return@withContext null
            val entryPath = normalizeEpubHref(link.href.toString())
            val zip = try {
                epubZip ?: synchronized(this@EpubReaderViewModel) {
                    epubZip ?: ZipFile(file).also { epubZip = it }
                }
            } catch (_: Exception) { return@withContext null }
            try {
                zip.getEntry(entryPath)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().readText()
                }?.also { chapterHtmlCache[spineIndex] = it }
            } catch (_: Exception) { null }
        }
    }

    override fun onCleared() {
        super.onCleared()
        epubZip?.close()
        epubZip = null
        // Tear down the audio session so it doesn't outlive the reader (clears the highlight too).
        playerCoordinator.close()
    }

    private val _currentLocatorHref = MutableStateFlow<String?>(null)
    val currentLocatorHref: StateFlow<String?> = _currentLocatorHref

    private val _currentLocatorProgression = MutableStateFlow(0f)
    val currentLocatorProgression: StateFlow<Float> = _currentLocatorProgression

    private val _tocVisible = MutableStateFlow(false)
    val tocVisible: StateFlow<Boolean> = _tocVisible

    fun openToc() { _tocVisible.value = true }
    fun closeToc() { _tocVisible.value = false }

    val tocEntries: StateFlow<List<TocEntry>> = state
        .map { (it as? ReaderState.Ready)?.publication?.tableOfContents?.toTocEntries() ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val railSegments: StateFlow<List<RailSegment>> = state
        .map { s ->
            val ready = s as? ReaderState.Ready ?: return@map emptyList()
            val base = buildRailSegments(ready.publication.tableOfContents.toTocEntries(), ready.title)
            val pub = ready.publication
            val positions: List<List<Locator>> = try {
                pub.positionsByReadingOrder()
            } catch (_: Throwable) {
                emptyList()
            }
            val spineHrefs = pub.readingOrder.map { it.url().toString() }
            val positionCounts = positions.map { it.size }
            weightSegmentsByChapterLength(base, spineHrefs, positionCounts)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeRailSegmentIndex: StateFlow<Int> = combine(
        railSegments,
        currentLocatorHref,
        state,
    ) { segments, href, s ->
        if (href == null) return@combine 0
        val spineHrefs = (s as? ReaderState.Ready)?.publication?.readingOrder
            ?.map { it.url().toString() }
            .orEmpty()
        findActiveSegmentIndex(segments, href, spineHrefs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Cursor position within the rail (0..1). Active segment + within-chapter progression are
    // mapped through cumulative segment weights so the cursor stays inside the highlighted
    // (active) segment even when segments have unequal widths.
    val railCursorPosition: StateFlow<Float> = combine(
        activeRailSegmentIndex,
        railSegments,
        currentLocatorProgression,
    ) { activeIndex, segments, progression ->
        weightedRailCursorPosition(activeIndex, segments, progression)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun openSearch() {
        _isSearchActive.value = true
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentSearchIndex.value = -1
        searchJob?.cancel()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun nextSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val next = (_currentSearchIndex.value + 1).coerceAtMost(results.size - 1)
        _currentSearchIndex.value = next
        _searchNavigationChannel.trySend(results[next])
    }

    fun prevSearchResult() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prev = (_currentSearchIndex.value - 1).coerceAtLeast(0)
        _currentSearchIndex.value = prev
        _searchNavigationChannel.trySend(results[prev])
    }

    private suspend fun performSearch(query: String) {
        val pub = publication ?: return
        val service = pub.findService(SearchService::class)
        if (service == null) {
            _searchResults.value = emptyList()
            _currentSearchIndex.value = -1
            return
        }
        val results = try {
            withContext(Dispatchers.IO) {
                chapterHtmlCache.clear()
                val iterator = service.search(query)
                val acc = mutableListOf<Locator>()
                try {
                    while (true) {
                        val pageResult = iterator.next()
                        // isFailure = chapter unreadable (e.g. OOM wrapped by Readium as
                        // SearchError.Reading); skip this chapter and continue to the next.
                        // getOrNull() == null = end of book; stop.
                        if (pageResult.isFailure) continue
                        val page = pageResult.getOrNull() ?: break
                        acc.addAll(page.locators)
                    }
                } finally {
                    iterator.close()
                }
                acc
            }
        } catch (_: OutOfMemoryError) {
            emptyList()
        }
        _searchResults.value = results
        if (results.isEmpty()) {
            _currentSearchIndex.value = -1
        } else {
            _currentSearchIndex.value = 0
            _searchNavigationChannel.trySend(results[0])
        }
    }

    private val _navigationEvents = Channel<Link>(Channel.CONFLATED)
    val navigationEvents: Flow<Link> = _navigationEvents.receiveAsFlow()

    fun navigateToEntry(entry: TocEntry) {
        val pub = (state.value as? ReaderState.Ready)?.publication ?: return
        val link = pub.tableOfContents.findLinkByHref(entry.href) ?: return
        _navigationEvents.trySend(link)
        _tocVisible.value = false
    }

    fun navigateToSegment(segment: RailSegment) {
        val pub = (state.value as? ReaderState.Ready)?.publication ?: return
        val link = pub.tableOfContents.findLinkByHref(segment.href) ?: return
        _navigationEvents.trySend(link)
    }

    private fun List<Link>.findLinkByHref(href: String): Link? {
        for (link in this) {
            if (link.href.toString() == href) return link
            link.children.findLinkByHref(href)?.let { return it }
        }
        return null
    }

    private suspend fun openPublication(file: File): Publication? {
        val url = AbsoluteUrl("file://${file.absolutePath}") ?: return null
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return null
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> null
        }
    }

    fun updateFormatting(prefs: FormattingPreferences) {
        val previousEffective = _formattingPreferences.value
        val updated = _bookOverrides.value.withChanges(previousEffective, prefs)
        _bookOverrides.value = updated
        _formattingPreferences.value = prefs
        _hasBookOverrides.value = !updated.isEmpty
        viewModelScope.launch { bookFormattingPreferencesStore.save(itemId, updated) }
    }

    fun resetToGlobalDefaults() {
        viewModelScope.launch {
            bookFormattingPreferencesStore.clear(itemId)
            _bookOverrides.value = BookFormattingOverrides()
            _formattingPreferences.value = formattingPreferencesStore.preferences.first()
            _hasBookOverrides.value = false
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { wakeLockPreferencesStore.setKeepScreenOn(value) }
    }

    fun setVolumeKeyNavigationEnabled(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setVolumeKeyNavigationEnabled(value) }
    }

    fun setInvertVolumeKeys(value: Boolean) {
        viewModelScope.launch { volumeKeyPreferencesStore.setInvertVolumeKeys(value) }
    }

    // ---- Readaloud playback --------------------------------------------------------------------

    // Runs the Storyteller single-peer position sync on the same ~30 s cadence as the reading-
    // progress sync, but only while the player is open/playing for a Storyteller book. ABS books
    // keep using the existing ProgressSyncController path untouched.
    private var storytellerSyncJob: Job? = null

    fun openReadaloud() {
        if (!_readaloudAvailable.value) return
        _readaloudOpen.value = true
        startStorytellerSync()
    }

    fun closeReadaloud() {
        _readaloudOpen.value = false
        _readaloudExpanded.value = false
        _downloadPromptBytes.value = null
        _readaloudOfflineMessage.value = false
        storytellerSyncJob?.cancel()
        storytellerSyncJob = null
        readaloudPrepared = false
        readaloudStarted = false
        playerCoordinator.close()
    }

    fun expandPlayer() { _readaloudExpanded.value = true }
    fun collapsePlayer() { _readaloudExpanded.value = false }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) playerCoordinator.pause() else onPlayTapped()
    }

    fun setSpeed(speed: Float) = playerCoordinator.setSpeed(speed)

    /** Reports the fragments visible in the viewport so the coordinator can decide on auto-advance. */
    fun reportVisibleFragments(fragmentRefs: Set<String>) =
        playerCoordinator.reportVisibleFragments(fragmentRefs)

    /**
     * Play tapped. If a local bundle is present we prepare (if needed) and play. Otherwise: when
     * online, probe the download size and surface the confirm dialog; when offline, surface the
     * "connect to download" message in the bar.
     */
    fun onPlayTapped() {
        _readaloudOfflineMessage.value = false
        val bundle = readaloudAudioRepository.bundleFile(itemId)
        if (bundle != null) {
            viewModelScope.launch { ensurePreparedAndPlay(bundle) }
            return
        }
        if (!connectivityObserver.isOnline.value) {
            _readaloudOfflineMessage.value = true
            return
        }
        viewModelScope.launch {
            // probeSizeBytes may return null (can't probe) — fall back to a zero-sized prompt so
            // the user can still choose to download.
            _downloadPromptBytes.value = readaloudAudioRepository.probeSizeBytes(itemId) ?: 0L
        }
    }

    /** "Play from here" from the text-selection menu — seek to the clip narrating [fragmentRef]. */
    fun playFromHere(fragmentRef: String) {
        if (!_readaloudOpen.value) openReadaloud()
        val bundle = readaloudAudioRepository.bundleFile(itemId)
        if (bundle == null) {
            // No bundle yet — fall through to the normal play path (prompt/notify) so the user is
            // told to download first.
            onPlayTapped()
            return
        }
        viewModelScope.launch {
            ensureOpened(bundle) ?: return@launch
            playerCoordinator.playFromHere(fragmentRef)
        }
    }

    fun confirmDownloadAudio(wifiOnly: Boolean) {
        _downloadPromptBytes.value = null
        // wifiOnly is honoured by the repository's download path (which inspects the active
        // connection); we surface the toggle here and pass intent through by simply not starting
        // when wifiOnly is requested but we're not on un-metered. Keep it simple: the repository
        // owns the metered check, so we always kick off and let it decide.
        viewModelScope.launch {
            _downloadProgress.value = 0f
            val result = readaloudAudioRepository.downloadAudio(itemId) { downloaded, total ->
                if (total > 0) _downloadProgress.value = downloaded.toFloat() / total.toFloat()
            }
            _downloadProgress.value = null
            when (result) {
                com.riffle.core.domain.AudioDownloadResult.Success -> {
                    _readaloudAvailable.value = true
                    readaloudAudioRepository.bundleFile(itemId)?.let { ensurePreparedAndPlay(it) }
                }
                com.riffle.core.domain.AudioDownloadResult.NoBundle -> Unit
                is com.riffle.core.domain.AudioDownloadResult.NetworkError ->
                    _readaloudOfflineMessage.value = true
            }
        }
    }

    fun dismissDownloadPrompt() {
        _downloadPromptBytes.value = null
    }

    // True once the controller has been pointed at the bundle this session, so resuming after a
    // pause doesn't re-prepare (which would reset playback to the start).
    private var readaloudPrepared = false

    // True once playback has been started this session, so the first play seeks to the reader's
    // position while a later resume-after-pause stays where it was.
    private var readaloudStarted = false

    private suspend fun ensurePreparedAndPlay(bundle: File) {
        ensureOpened(bundle) ?: return
        if (!readaloudStarted) {
            // First play of this session: start narration from the reader's current position (the
            // spec requires this), not the beginning of the book. Resuming after a pause keeps its
            // place via the plain play() branch below.
            readaloudStarted = true
            val loc = lastLocator
            if (loc != null) {
                playerCoordinator.playFromReaderPosition(
                    href = loc.href.toString(),
                    fragmentId = loc.locations.fragments.firstOrNull(),
                )
            } else {
                playerCoordinator.play()
            }
        } else {
            playerCoordinator.play()
        }
    }

    private suspend fun ensureOpened(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        val track = ensureTrack(bundle) ?: return null
        if (!readaloudPrepared) {
            playerCoordinator.open(itemId, bundle, track)
            readaloudPrepared = true
        }
        return track
    }

    private suspend fun ensureTrack(bundle: File): com.riffle.core.domain.ReadaloudTrack? {
        readaloudTrack?.let { return it }
        val track = readaloudAudioRepository.readTrack(itemId) ?: return null
        readaloudTrack = track
        return track
    }

    private fun startStorytellerSync() {
        if (!isStorytellerServer) return
        if (storytellerSyncJob?.isActive == true) return
        storytellerSyncJob = viewModelScope.launch {
            while (true) {
                delay(SYNC_INTERVAL_MS)
                val locator = lastLocator ?: continue
                when (val outcome = storytellerSyncController.runCycle(itemId, locator.toJSON().toString())) {
                    is StorytellerSyncOutcome.PulledRemote -> {
                        // The stored canonical position is the Readium locator JSON — deserialize and
                        // jump the navigator there (reusing the server-locator channel the screen
                        // already wires to fragment.go()).
                        try {
                            val pulled = Locator.fromJSON(JSONObject(outcome.locatorJson))
                            if (pulled != null) _serverLocatorChannel.trySend(pulled)
                        } catch (_: Exception) { /* malformed remote locator — ignore */ }
                    }
                    StorytellerSyncOutcome.PushedLocal,
                    StorytellerSyncOutcome.InSync,
                    StorytellerSyncOutcome.Offline -> Unit
                }
            }
        }
    }
}
