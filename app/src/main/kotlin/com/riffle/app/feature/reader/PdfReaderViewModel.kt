package com.riffle.app.feature.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.withResolvedTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

private const val PDF_SYNC_INTERVAL_MS = 30_000L

@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val pdfRepository: PdfRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val readingSessionRepository: ReadingSessionRepository,
    private val volumeNavigationController: VolumeNavigationController,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val timeProvider: TimeProvider,
    private val readerStateHolder: ReaderStateHolder,
) : AndroidViewModel(application) {

    private val itemId: String = checkNotNull(savedStateHandle["itemId"])

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    val keepScreenOn: StateFlow<Boolean> = wakeLockPreferencesStore.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit)
    }

    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FormattingPreferences())

    val volumeNavEvents: SharedFlow<VolumeNavEvent> = volumeNavigationController.events

    private val _syncErrorEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val syncErrorEvents: SharedFlow<Unit> = _syncErrorEvents.asSharedFlow()

    private val progressSyncController = ProgressSyncController(
        repository = readingSessionRepository,
        scope = viewModelScope,
        onSyncError = { _syncErrorEvents.tryEmit(Unit) },
    )

    private val positionSaveCoordinator = PositionSaveCoordinator(
        savePosition = { cfi -> pdfRepository.saveReadingPosition(itemId, cfi) },
        updateProgress = { progress -> libraryRepository.updateReadingProgress(itemId, progress) },
    )

    private val _serverLocatorChannel = Channel<Locator>(Channel.CONFLATED)
    val serverLocatorEvents: Flow<Locator> = _serverLocatorChannel.receiveAsFlow()

    private var lastLocator: Locator? = null
    val latestLocator: Locator? get() = lastLocator
    private var syncJob: Job? = null
    private var closeSyncDone = false
    private var initialLocatorSeen = false

    init {
        viewModelScope.launch {
            loadFormattingPreferences()
            openBook()
        }
        viewModelScope.launch {
            combine(
                formattingPreferencesStore.preferences,
                _bookOverrides,
            ) { global, overrides -> overrides.applyTo(global) }
                .collect { _formattingPreferences.value = it }
        }
        viewModelScope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val delayMs = (((next.toSecondOfDay() - now.toSecondOfDay() + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
                }
        }
        viewModelScope.launch {
            progressSyncController.serverPositionEvents.collect { serverProgress ->
                serverLocationToLocator(serverProgress.ebookLocation)?.let { _serverLocatorChannel.trySend(it) }
            }
        }
    }

    private suspend fun loadFormattingPreferences() {
        val overrides = bookFormattingPreferencesStore.load(itemId)
        val global = formattingPreferencesStore.preferences.first()
        _bookOverrides.value = overrides
        _formattingPreferences.value = overrides.applyTo(global)
    }

    private suspend fun openBook() {
        val item = libraryRepository.getItem(itemId)
        if (item == null) {
            _state.value = ReaderState.Error("Book not found")
            return
        }
        when (val result = pdfRepository.openPdf(item)) {
            is PdfOpenResult.Success -> {
                val publicationResult = openPublication(result.pdfFile)
                val publication = publicationResult.getOrElse {
                    _state.value = ReaderState.Error("Failed to open PDF: ${it.message}")
                    return
                }
                val locator = result.lastPosition
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Locator.fromJSON(JSONObject(it)) }
                _state.value = ReaderState.Ready(
                    publication = publication,
                    title = item.title,
                    initialLocator = locator,
                )
                progressSyncController.sync(itemId, locator?.toPayload() ?: SessionPayload("", 0f))
                startPeriodicSync()
            }
            is PdfOpenResult.NetworkError -> _state.value = ReaderState.Error("Network error: ${result.cause.message}")
            PdfOpenResult.Offline -> _state.value = ReaderState.Error("Book not available offline")
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                delay(PDF_SYNC_INTERVAL_MS)
                syncCurrentPosition()
            }
        }
    }

    private fun syncCurrentPosition() {
        val locator = lastLocator ?: return
        progressSyncController.sync(itemId, locator.toPayload())
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

    private suspend fun openPublication(file: File): Result<Publication> {
        val url = AbsoluteUrl("file://${file.absolutePath}")
            ?: return Result.failure(IllegalArgumentException("Invalid file path: ${file.absolutePath}"))
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return Result.failure(Exception(r.value.message))
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> Result.success(r.value)
            is Try.Failure -> Result.failure(Exception(r.value.message))
        }
    }

    private val _currentPage = MutableStateFlow<Int?>(null)
    val currentPage: StateFlow<Int?> = _currentPage

    fun onPageChanged(locator: Locator) {
        lastLocator = locator
        _currentPage.value = locator.locations.position
        if (!initialLocatorSeen) {
            initialLocatorSeen = true
            return
        }
        viewModelScope.launch {
            positionSaveCoordinator.onChanged(locator.toJSON().toString())
        }
    }

    private fun Locator.toPayload() = SessionPayload(
        ebookLocation = toJSON().toString(),
        ebookProgress = locations.progression?.toFloat() ?: 0f,
    )

    private fun serverLocationToLocator(location: String): Locator? =
        try { Locator.fromJSON(JSONObject(location)) } catch (_: Exception) { null }
}
