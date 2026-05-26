@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SessionPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    private val readerStateHolder: ReaderStateHolder,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

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

    // Optimistic local state: updates immediately so the navigator receives changes without
    // waiting for the Room write to complete.
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    private val _hasBookOverrides = MutableStateFlow(false)
    val hasBookOverrides: StateFlow<Boolean> = _hasBookOverrides

    init {
        viewModelScope.launch {
            // Sequential: prefs must be available before openBook() so initialPreferences is set correctly.
            loadFormattingPreferences()
            openBook()
        }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverProgressToLocator(serverProgress)?.let { _serverLocatorChannel.trySend(it) }
            }
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
        val bookPrefs = bookFormattingPreferencesStore.load(itemId)
        if (bookPrefs != null) {
            _formattingPreferences.value = bookPrefs
            _hasBookOverrides.value = true
        } else {
            _formattingPreferences.value = formattingPreferencesStore.preferences.first()
            _hasBookOverrides.value = false
        }
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
                val locator = result.lastPosition?.let { Locator.fromJSON(JSONObject(it)) }
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

    val railSegments: StateFlow<List<RailSegment>> = tocEntries
        .map { buildRailSegments(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeRailSegmentIndex: StateFlow<Int> = combine(
        railSegments,
        currentLocatorHref,
    ) { segments, href ->
        if (href == null) 0 else findActiveSegmentIndex(segments, href)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Cursor position within the rail (0..1). Derived from active segment + within-chapter
    // progression so the cursor is always inside the highlighted (active) segment, regardless
    // of whether chapter lengths match the equal-width segment layout.
    val railCursorPosition: StateFlow<Float> = combine(
        activeRailSegmentIndex,
        railSegments,
        currentLocatorProgression,
    ) { activeIndex, segments, progression ->
        if (segments.isEmpty()) 0f
        else ((activeIndex + progression.coerceIn(0f, 1f)) / segments.size).coerceIn(0f, 1f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

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
        val iterator = service.search(query)
        val results = mutableListOf<Locator>()
        while (true) {
            val page = iterator.next().getOrNull() ?: break
            results.addAll(page.locators)
        }
        iterator.close()
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
        _formattingPreferences.value = prefs
        _hasBookOverrides.value = true
        viewModelScope.launch { bookFormattingPreferencesStore.save(itemId, prefs) }
    }

    fun resetToGlobalDefaults() {
        viewModelScope.launch {
            bookFormattingPreferencesStore.clear(itemId)
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
}
