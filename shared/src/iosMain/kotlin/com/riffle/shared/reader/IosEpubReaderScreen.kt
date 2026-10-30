package com.riffle.shared.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.common.Clock
import com.riffle.core.data.AnnotationSyncController
import com.riffle.core.data.ItemProgressPuller
import com.riffle.core.data.annotationLocatorJson
import com.riffle.core.data.hrefLocatorJson
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EmphasisPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.HighlightsResumeStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionCoordinator
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReadingSpeedSession
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.TocRepository
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.withResolvedTheme
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.PauseCause
import com.riffle.core.domain.autoscroll.layoutContextFor
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.Feature
import com.riffle.core.domain.cadence.currentRunningFeature
import com.riffle.core.domain.cadence.runArbiter
import com.riffle.core.domain.effectiveOrientation
import com.riffle.core.domain.normalizeEpubHref
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.logging.Logger
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ScreenDimensionBucket
import com.riffle.core.models.ScreenDimensionBucket.SizeClass
import com.riffle.core.models.SessionPayload
import com.riffle.core.models.TocEntry
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.downloads.formatBytesOrUnknown
import com.riffle.feature.player.ui.ReadaloudDownloadDialog
import com.riffle.feature.player.ui.ReadaloudMiniPlayer
import com.riffle.feature.player.ui.ReadaloudPeek
import com.riffle.feature.reader.AnnotationSession
import com.riffle.feature.reader.AutoScrollStall
import com.riffle.feature.reader.BoundaryAdvance
import com.riffle.feature.reader.ChapterMapUiState
import com.riffle.feature.reader.ContinuousBoundaryAdvancePolicy
import com.riffle.feature.reader.FigureLongPressMessageParser
import com.riffle.feature.reader.FigureTapMessageParser
import com.riffle.feature.reader.FigureZoomState
import com.riffle.feature.reader.FootnoteContent
import com.riffle.feature.reader.FootnotePopupState
import com.riffle.feature.reader.ImmersiveModeState
import com.riffle.feature.reader.NOTE_GLYPH_DECORATION_GROUP
import com.riffle.feature.reader.NarratedColumnProgression
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorSearchMatch
import com.riffle.feature.reader.PositionSaveCoordinator
import com.riffle.feature.reader.PullBoundaryGestureState
import com.riffle.feature.reader.ReadaloudSession
import com.riffle.feature.reader.activeTocHref
import com.riffle.feature.reader.autoScrollStallAction
import com.riffle.feature.reader.autoscroll.AutoScrollController
import com.riffle.feature.reader.autoscroll.nudgeSpeedAndPersistableWpm
import com.riffle.feature.reader.bookmarkRailPosition
import com.riffle.feature.reader.buildRailSegments
import com.riffle.feature.reader.cadence.CadenceController
import com.riffle.feature.reader.cadence.CadenceInjector
import com.riffle.feature.reader.cadence.CadenceSession
import com.riffle.feature.reader.chapterMapUiState
import com.riffle.feature.reader.chapterMapVisible
import com.riffle.feature.reader.highlights.ChapterElision
import com.riffle.feature.reader.highlights.ReaderSource
import com.riffle.feature.reader.highlights.buildChapterElisionsFromAnnotations
import com.riffle.feature.reader.highlights.buildCombinedHtml
import com.riffle.feature.reader.highlights.buildPdfFileName
import com.riffle.feature.reader.highlights.figureHrefsFromChapters
import com.riffle.feature.reader.highlights.highlightsResumeAnnotationIdForHref
import com.riffle.feature.reader.highlights.highlightsResumeChapterHref
import com.riffle.feature.reader.highlights.joinEmphasisStylesToAnnotations
import com.riffle.feature.reader.highlights.mimeForHref
import com.riffle.feature.reader.highlights.parseAnnotationTapUrl
import com.riffle.feature.reader.highlights.shouldShowOpenInBook
import com.riffle.feature.reader.highlights.shouldShowShareHighlights
import com.riffle.feature.reader.readiumFontFamilyName
import com.riffle.feature.reader.spineIndexOfHref
import com.riffle.feature.reader.toCssRgba
import com.riffle.feature.reader.toReadiumTextStyling
import com.riffle.feature.reader.ui.AnnotationActionsSheet
import com.riffle.feature.reader.ui.AutoScrollHudPill
import com.riffle.feature.reader.ui.AutoScrollToggleIcon
import com.riffle.feature.reader.ui.CadenceHudPill
import com.riffle.feature.reader.ui.CadenceToggleIcon
import com.riffle.feature.reader.ui.ChapterMapOverlay
import com.riffle.feature.reader.ui.FigureZoomOverlay
import com.riffle.feature.reader.ui.FootnotePopup
import com.riffle.feature.reader.ui.NoteEditorSheet
import com.riffle.feature.reader.ui.NoteOnlyPopup
import com.riffle.feature.reader.ui.PullChip
import com.riffle.feature.reader.ui.ReaderTopBar
import com.riffle.feature.reader.ui.ReturnToPositionCard
import com.riffle.feature.reader.ui.SharedAnnotationsPanel
import com.riffle.feature.reader.ui.SharedSearchTopBar
import com.riffle.feature.reader.ui.annotationSheetLabels
import com.riffle.feature.reader.ui.cadenceHudLabels
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.readerPalette
import com.riffle.feature.reader.ui.readerSwatchBackdropColor
import com.riffle.feature.reader.ui.speedHudLabels
import com.riffle.feature.reader.weightedRailCursorPosition
import com.riffle.feature.settings.ui.readersettings.TocPanel
import com.riffle.feature.source.ui.CornerBookmarkIndicator
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_could_not_download_book
import com.riffle.shared.generated.resources.ui_error
import com.riffle.shared.generated.resources.ui_no_highlights_to_show
import com.riffle.shared.generated.resources.ui_readaloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.TimeSource
import com.riffle.core.domain.cadence.PauseCause as CadencePauseCause

/**
 * iOS EPUB reader composable. For O'Reilly lazy publications, opens directly via
 * [IosLazyChapterFetcherImpl] without downloading the full EPUB. For all other sources,
 * downloads the EPUB and uses the file-based open path. Persists the reading position via
 * [ReadingPositionStore] and syncs progress to the server via [ReadingSessionRepository].
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun EpubReaderScreen(
    item: LibraryItem,
    onBack: () -> Unit,
    source: ReaderSource = ReaderSource.FullBook,
    initialLocatorJson: String? = null,
    startHref: String? = null,
    startReadaloudAtSec: Double = -1.0,
    /** Called when the user taps "Switch to audiobook" in the readaloud mini-player. */
    onSwitchToAudiobook: (() -> Unit)? = null,
    onOpenInBook: ((locatorJson: String) -> Unit)? = null,
) {
    KeepReaderScreenOn()
    val bridgeFactory = koinInject<IosEpubNavigatorBridgeFactory>()
    val downloader = koinInject<IosEpubDownloader>()
    val annotationStore = koinInject<AnnotationStore>()
    val catalogRegistry = koinInject<CatalogRegistry>()
    val positionStore = koinInject<ReadingPositionStore>()
    val sessionRepository = koinInject<ReadingSessionRepository>()
    val updateReadingProgress = koinInject<UpdateReadingProgress>()
    val formattingPreferencesStore = koinInject<FormattingPreferencesStore>()
    val bookFormattingPreferencesStore = koinInject<BookFormattingPreferencesStore>()
    val appearanceCoordinator = koinInject<AppearanceCoordinator>()
    val publicationInspector = koinInject<IosPublicationInspector>()
    val readingSpeedStore = koinInject<ReadingSpeedStore>()
    val dispatchers = koinInject<DispatcherProvider>()
    val logger = koinInject<Logger>()
    val readaloudSessionFactory = koinInject<ReadaloudSession.Factory>()
    val readaloudLinkRepository = koinInject<ReadaloudLinkRepository>()
    val listeningPreferencesStore = koinInject<ListeningPreferencesStore>()
    val annotationSessionFactory = koinInject<AnnotationSession.Factory>()
    val annotationSyncController = koinInject<AnnotationSyncController>()
    val emphasisPreferencesStore = koinInject<EmphasisPreferencesStore>()
    val progressPuller = koinInject<ItemProgressPuller>()
    val clock = koinInject<Clock>()
    val elidedPdfBridge = koinInject<IosElidedPdfBridge>()
    val highlightsResumeStore = koinInject<HighlightsResumeStore>()
    val tocRepository = koinInject<TocRepository>()
    // Landscape flag — derived from the Compose container so it updates on rotation.
    val containerSize = LocalWindowInfo.current.containerSize
    val isLandscape = containerSize.width > containerSize.height
    // Rotation-invariant screen-size key for per-book formatting overrides (ADR 0031).
    val screenDimensionBucket = remember(containerSize) {
        ScreenDimensionBucket.of(
            a = containerSize.width.dpToSizeClass(),
            b = containerSize.height.dpToSizeClass(),
        )
    }
    var bookOverrides by remember { mutableStateOf(BookFormattingOverrides()) }
    val noHighlightsText = stringResource(Res.string.ui_no_highlights_to_show)
    val couldNotDownloadBook = stringResource(Res.string.ui_could_not_download_book)
    val errorText = stringResource(Res.string.ui_error)
    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLazyPublication by remember { mutableStateOf(false) }
    var tocOpen by remember { mutableStateOf(false) }
    var tocEntries by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    // Current locator href (no fragment) — updated from the navigator position flow.
    var locatorHref by remember { mutableStateOf<String?>(null) }
    // Full href of the last TOC entry the user explicitly tapped (may include #fragment).
    var lastTocNavigatedHref by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NavigatorSearchMatch>>(emptyList()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var chapterMap by remember { mutableStateOf(ChapterMapUiState.Empty) }
    // Continuous-mode override: the chapter-map cursor position derived from the real-time scroll
    // probe (120 ms cadence), which is more frequent than Readium's 300 ms post-scroll-end
    // locationDidChange. Null in non-Continuous modes so the rail falls back to chapterMap.cursorPosition.
    var continuousCursorOverride by remember { mutableStateOf<Float?>(null) }
    // The resolved (Auto already collapsed) preferences the chapter map paints itself with.
    var resolvedPrefs by remember { mutableStateOf<FormattingPreferences?>(null) }
    // Reader viewport width in device pixels — auto-scroll's pace depends on how many words fit
    // on a line, so it has to be measured, not assumed.
    var viewportWidthPx by remember { mutableStateOf(0) }
    // The *stored* preferences, before Auto is resolved. Anything written back must start from
    // these: persisting the resolved copy would silently collapse ReaderTheme.Auto into whatever
    // it happened to resolve to at that moment.
    var storedPrefs by remember { mutableStateOf<FormattingPreferences?>(null) }
    var spine by remember { mutableStateOf(SpinePositions.Empty) }
    var figureZoomState by remember { mutableStateOf<FigureZoomState?>(null) }
    val scope = rememberCoroutineScope()
    val bridge = remember { bridgeFactory.create() }
    val navigator = remember(bridge) { ReadiumSwiftNavigator(bridge) }
    val annotationSession = remember(navigator) {
        annotationSessionFactory.create(
            scope = scope,
            startLiveSync = { sid, ns, iid -> annotationSyncController.startLiveSync(sid, ns, iid) },
            scheduleSync = { sid, ns, iid -> annotationSyncController.scheduleDebounce(sid, ns, iid) },
            syncOnOpen = { sid, ns, iid -> annotationSyncController.syncOnOpen(sid, ns, iid) },
            syncOnClose = { sid, ns, iid -> annotationSyncController.syncOnClose(sid, ns, iid) },
            mergeAfterEdit = { _, _, _ -> },
            commitDraftWithNote = { _ -> },
        )
    }
    val annotations by annotationSession.annotations.collectAsState()
    val spineRef = remember { mutableStateOf(SpinePositions.Empty) }
    val orientationRef = remember { mutableStateOf(ReaderOrientation.Horizontal) }
    val viewportFractionRef = remember { mutableStateOf(emptyMap<String, Double>()) }
    val tocEntriesRef = remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    val selection by navigator.selectionFlow.collectAsState()
    val readaloudSession = remember(item.sourceId, item.id) {
        readaloudSessionFactory.create(scope) { null }.also { session ->
            session.bind(
                sourceId = item.sourceId,
                itemId = item.id,
                isStorytellerService = false,
                audioBookId = item.id,
                audioServerId = item.sourceId,
                audioSettingsIdentity = com.riffle.core.models.AudioIdentity(item.sourceId, item.id),
                audiobookItemId = if (item.isListenable) item.id else null,
                effectiveFormattingPreferencesFlow = kotlinx.coroutines.flow.MutableStateFlow(FormattingPreferences()),
                readerSyncProvider = { null },
                audiobookFollowProvider = { null },
            )
        }
    }
    DisposableEffect(item.sourceId, item.id) {
        onDispose { readaloudSession.onBookClosed() }
    }
    LaunchedEffect(item.sourceId, item.id) {
        val link = readaloudLinkRepository.findByAbsItem(item.sourceId, item.id)
        if (link != null) {
            val audiobookItemId = if (item.isListenable) item.id else null
            readaloudSession.resolveMatchedAbs(
                audioBookId = link.storytellerBookId,
                audioServerId = link.storytellerSourceId,
                audiobookItemId = audiobookItemId,
            )
        }
    }
    val readaloudVisible by readaloudSession.readaloudVisible.collectAsState()
    val readaloudOpen by readaloudSession.readaloudOpen.collectAsState()
    val readaloudPlayback by readaloudSession.playbackState.collectAsState()
    val readaloudAvailable by readaloudSession.readaloudAvailable.collectAsState()
    val readaloudDownloadPromptBytes by readaloudSession.downloadPromptBytes.collectAsState()
    val readaloudDownloadProgress by readaloudSession.downloadProgress.collectAsState()
    val readaloudBarMessage by readaloudSession.readaloudBarMessage.collectAsState()
    val readaloudSkipIntervalSeconds by listeningPreferencesStore.skipIntervalSeconds
        .collectAsState(ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)
    val readaloudRewindIntervalSeconds by listeningPreferencesStore.rewindIntervalSeconds
        .collectAsState(ListeningPreferencesStore.DEFAULT_REWIND_INTERVAL_SECONDS)
    val readaloudAudiobookItemId by readaloudSession.audiobookItemId.collectAsState()
    val readaloudHighlightColor by readaloudSession.readaloudHighlightColor.collectAsState()
    var showAudiobookOverlay by remember { mutableStateOf(false) }
    // The annotation whose actions sheet is open, or null. Set by a decoration tap and by a
    // fresh create so the sheet switches from "annotate this selection" to "edit this
    // annotation" without the user having to tap the new highlight.
    var editTargetId by remember { mutableStateOf<String?>(null) }
    // True when `editTargetId` was set by tapping a note glyph rather than the highlight body.
    // When true and the annotation has a note, shows NoteOnlyPopup instead of the full sheet —
    // mirrors Android's HighlightActionsPopup(noteOnly=true) path.
    var editTargetWasGlyphTap by remember { mutableStateOf(false) }
    // Non-null while the note editor is up; the value is the annotation id, or "" for a note
    // being written on a selection that has not been persisted yet.
    var noteEditorFor by remember { mutableStateOf<String?>(null) }
    var annotationsPanelOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    val immersiveClock = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    val immersiveState = remember {
        ImmersiveModeState(
            controller = IosSystemBarsController,
            clock = { immersiveClock.elapsedNow().inWholeMilliseconds },
            initialIsImmersive = false,
        )
    }
    val isImmersive by immersiveState.isImmersive.collectAsState()
    var searchResultIndex by remember { mutableStateOf(0) }
    var footnotePopupState by remember { mutableStateOf<com.riffle.feature.reader.FootnotePopupState?>(null) }
    var returnToPositionTarget by remember { mutableStateOf<String?>(null) }
    var currentBookmark by remember { mutableStateOf<Annotation?>(null) }
    // Styles picked on a selection before it is persisted. Applied by `createHighlight`.
    var pendingStyles by remember { mutableStateOf(emptySet<EmphasisStyle>()) }
    // Retained so DisposableEffect can cancel in-flight fetches on close.
    var lazyFetcher by remember { mutableStateOf<IosLazyChapterFetcher?>(null) }
    // Cached publication shape for prefetch index lookups — avoids re-fetching on every position.
    var lazyShape by remember { mutableStateOf<LazyPublicationShape?>(null) }
    // ReadingSessionCoordinator drives the 30-s sync heartbeat; ReadingSpeedSession tracks speed.
    // Both use an independent scope (not rememberCoroutineScope) so their onClosed() launches
    // survive Composition teardown cancelling the composition scope.
    val sessionCoordinatorScope = remember(item.id) { CoroutineScope(SupervisorJob()) }
    val sessionCoordinator = remember(item.id) {
        ReadingSessionCoordinator(scope = sessionCoordinatorScope)
    }
    val speedSession = remember(item.id) {
        ReadingSpeedSession(clock = clock, readingSpeedStore = readingSpeedStore, scope = sessionCoordinatorScope)
    }
    // Populated in Highlights mode so "Open in Book" can resolve the tapped annotation's locator.
    var highlightsAnnotations by remember { mutableStateOf<List<Annotation>>(emptyList()) }
    // Kept as a ref so the resume-tracking LaunchedEffect can read the latest chapters without
    // being reconstructed on every rebuild (same pattern as spineRef / tocEntriesRef).
    val highlightsRenderedChaptersRef = remember { mutableStateOf<List<ChapterElision>>(emptyList()) }
    // Figure data-URIs computed at open time — reused by the PDF share button.
    var highlightsDataUriByHref by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Publisher font-face CSS extracted from the EPUB at open time — reused by the PDF share button.
    var highlightsPublisherFontFaceCss by remember { mutableStateOf("") }

    // Load per-book formatting overrides on open. When the dimension bucket changes (fold/rotate),
    // reload the row for the new bucket — each screen-size class has its own settings (ADR 0031).
    LaunchedEffect(item.id, screenDimensionBucket) {
        bookOverrides = bookFormattingPreferencesStore.load(item.sourceId, item.id, screenDimensionBucket)
            ?: BookFormattingOverrides()
    }

    // Theme-derived CSS for the ∅-colour bar — derived here (composable scope, where
    // MaterialTheme is available) so the LaunchedEffect below can capture it by closure.
    // Matches what Android's EpubReaderScreen pushes via SideEffect → setEmphasisBarCss().
    val emphasisBarCss = MaterialTheme.colorScheme.onSurfaceVariant.toArgb().toCssRgba()

    LaunchedEffect(item.id) {
        // Pull the latest server position before opening, matching Android's ReaderSessionLifecycle.
        progressPuller.pullItem(item.sourceId, item.id)
        if (source == ReaderSource.Highlights) {
            // Elided Annotations View: build a synthetic EPUB from the book's highlights and
            // open it from a temp directory. Observes live changes: deletes trigger a rebuild
            // (or close when the last highlight is gone); recolour/note edits also rebuild the
            // HTML so the baked snapshot stays in sync with the annotation store.
            var isFirstOpen = true
            var renderedFingerprints: Set<Pair<String, Long>> = emptySet()
            annotationStore.observeAnnotations(item.sourceId, item.id).collect { annotations ->
                highlightsAnnotations = annotations
                val annotationsWithEmphasis = joinEmphasisStylesToAnnotations(annotations)
                val bookToc = tocRepository.getCachedToc(item.sourceId, item.id)?.second.orEmpty()
                val chapters = buildChapterElisionsFromAnnotations(annotationsWithEmphasis, bookToc)

                if (chapters.isEmpty()) {
                    onBack()
                    return@collect
                }

                val incomingFingerprints = annotations
                    .filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT || it.type == AnnotationEntity.TYPE_IMAGE }
                    .map { it.id to it.updatedAt }
                    .toSet()
                if (!isFirstOpen && incomingFingerprints == renderedFingerprints) return@collect

                // Embed figure bytes as data-URIs — mirrors Android's HighlightsPublicationFactory.
                // Skipped for lazy publications (no local EPUB file to read from).
                val cap = catalogRegistry.forSourceId(item.sourceId) as? LazyPublicationCapability
                val epubPath = if (cap?.lazyPublication(item.id) != null) null else downloader.localPath(item)
                val dataUriByHref: Map<String, String> = if (epubPath != null) {
                    val hrefs = figureHrefsFromChapters(chapters)
                    hrefs.mapNotNull { href ->
                        val base64 = readEpubResourceBase64(publicationInspector, epubPath, href)
                            ?: return@mapNotNull null
                        href to "data:${mimeForHref(href)};base64,$base64"
                    }.toMap()
                } else {
                    emptyMap()
                }
                highlightsDataUriByHref = dataUriByHref
                // Extract publisher font-face CSS once at first open — reused by assemble() and PDF share.
                if (isFirstOpen && epubPath != null) {
                    highlightsPublisherFontFaceCss = extractPublisherFontFaceCss(publicationInspector, epubPath)
                }

                // Resume: on first open navigate to the chapter the user last read.
                val resumeLocator: String? = if (isFirstOpen) {
                    val lastId = highlightsResumeStore.lastHighlightId(item.sourceId, item.id)
                    if (lastId != null) highlightsResumeChapterHref(chapters, lastId, chapters.size) else null
                } else {
                    null
                }

                val dirPath = IosElidedEpubAssembler.assemble(
                    item, chapters, emphasisBarCss = emphasisBarCss, dataUriByHref = dataUriByHref,
                    publisherFontFaceCss = highlightsPublisherFontFaceCss,
                )
                if (dirPath == null) {
                    loadError = noHighlightsText
                    return@collect
                }
                if (isFirstOpen) {
                    localPath = dirPath
                }
                navigator.openSyntheticEpub(dirPath, resumeLocator)
                highlightsRenderedChaptersRef.value = chapters

                if (isFirstOpen) {
                    annotationSession.bind(
                        sourceId = item.sourceId,
                        namespace = "",
                        itemId = item.id,
                        highlightRenderResolver = { emptyList() },
                        cfiLocatorResolver = { null },
                    )
                    isFirstOpen = false
                }
                renderedFingerprints = incomingFingerprints
            }
            return@LaunchedEffect
        }

        val savedLocator = positionStore.load(item.sourceId, item.id)

        val cap = catalogRegistry.forSourceId(item.sourceId) as? LazyPublicationCapability
        if (cap != null) {
            val shape = cap.lazyPublication(item.id)
            if (shape != null) {
                isLazyPublication = true
                lazyShape = shape
                val fetcher = IosLazyChapterFetcherImpl(cap, shape, item.id)
                lazyFetcher = fetcher
                val shapeJson = IosLazyChapterFetcherImpl.serializeShape(shape)
                navigator.openLazy(shapeJson, savedLocator, fetcher)
                annotationSession.bind(
                    sourceId = item.sourceId,
                    namespace = "",
                    itemId = item.id,
                    highlightRenderResolver = { emptyList() },
                    cfiLocatorResolver = { null },
                )
                localPath = "lazy" // sentinel: triggers the reader UI without a real file path
                return@LaunchedEffect
            }
        }

        // Normal file-based path.
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = couldNotDownloadBook
            return@LaunchedEffect
        }
        localPath = path
        // Fallback inbound-sync path (ADR-0013): with no locally-saved locator, a server position
        // that arrived as a bare `readingProgress` float is all we have. Resolve it through
        // Readium's locate(progression:) so the book opens where the other device left off
        // instead of at page one. The primary CFI path, when present, still wins.
        val openAt = initialLocatorJson
            ?: startHref?.let { href -> hrefLocatorJson(href) }
            ?: savedLocator
            ?: locatorForProgression(publicationInspector, path, item.readingProgress.toDouble())
        navigator.open(path, openAt)
        annotationSession.bind(
            sourceId = item.sourceId,
            namespace = "",
            itemId = item.id,
            highlightRenderResolver = { emptyList() },
            cfiLocatorResolver = { null },
        )
    }

    // Apply formatting preferences (font, theme, scroll mode) to the Readium navigator.
    // Called before open so the initial render respects user settings, and whenever prefs change.
    //
    // The theme and font mapping come from `feature:reader`'s ReadiumFormattingMapping, the same
    // derivation Android's FormattingPreferencesMapper uses. The private copy that used to live
    // here had drifted on every branch: DarkDim collapsed to plain dark and lost its muted body
    // colour, and Auto was hardcoded to light instead of being resolved.
    //
    // Auto resolution comes from AppearanceCoordinator — the one place that combines the reader
    // theme, the Auto schedule and the live system-dark flag, and re-emits at each day/night
    // crossing so a book left open across the threshold repaints. `readerTheme` is already
    // concrete, so `toReadiumThemeName` never sees Auto here.
    LaunchedEffect(item.id, isLandscape) {
        combine(
            formattingPreferencesStore.preferences,
            appearanceCoordinator.resolved,
            snapshotFlow { bookOverrides },
        ) { prefs, appearance, overrides ->
            Triple(prefs, prefs.withResolvedTheme(appearance), overrides)
        }
            .collect { (stored, prefs, overrides) ->
                val effective = overrides.applyTo(prefs)
                val effectiveOrient = effective.effectiveOrientation(isLandscape)
                storedPrefs = stored
                resolvedPrefs = effective
                orientationRef.value = effectiveOrient
                val isDoublePage = effective.doublePageSpread &&
                    effectiveOrient == ReaderOrientation.Horizontal &&
                    isLandscape
                val styling = effective.toReadiumTextStyling(isLandscape = isLandscape, isDoublePage = isDoublePage)
                navigator.applyReaderPreferences(
                    fontSizePercent = effective.fontSize,
                    scrollMode = epubScrollMode(effectiveOrient),
                    theme = styling.theme.value,
                    // The bridge takes "" for "leave the publisher's font alone"; the shared
                    // mapping expresses that as null.
                    fontFamilyCss = effective.fontFamily.readiumFontFamilyName() ?: "",
                    lineHeightMultiplier = effective.lineSpacing,
                    pageMargins = effective.margins.toDouble(),
                    justifyText = effective.justifyText,
                    // The shared mapping computes these; the bridge carries them so iOS renders
                    // what Android renders — DarkDim's muted body colour, Riffle's typography
                    // winning over the publisher stylesheet, and the single-column pin that
                    // Readium 3.3.0 needs or decorations land in the wrong place.
                    textColorArgb = styling.textColorArgb ?: 0L,
                    publisherStyles = styling.publisherStyles,
                    columnCount = styling.columnCount ?: 0,
                    // Signals the bridge to activate IosContinuousScrollView (native multi-WebView
                    // stacking) instead of EPUBNavigatorViewController for this mode.
                    isContinuousMode = effectiveOrient == ReaderOrientation.Continuous,
                )
            }
    }

    // Load the TOC and the spine once the book is open (localPath becomes non-null). Readium
    // computes both asynchronously after the publication opens, so re-read on every page-load
    // event until each has arrived rather than sampling once and drawing a permanently empty
    // (or unweighted) rail.
    //
    // Spine readiness race: prefetchSpine calls positionsByReadingOrder() which is async. If the
    // user stays on the first chapter and never triggers a page-load event, getSpine() would
    // return an unusable spine (empty positionCounts) forever — the chapter map then shows only
    // the top-level TOC structure (e.g. 3 parts instead of 30 chapters). The poller below
    // retries every 200 ms until positionCounts arrives, complementing the page-load collector.
    LaunchedEffect(localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.getToc().takeIf { it.isNotEmpty() }?.let { toc -> tocEntries = toc; tocEntriesRef.value = toc }
        navigator.getSpine().takeIf { it.isUsable }?.let { spine = it; spineRef.value = it }
        if (tocEntries.isNotEmpty() && spine.isUsable) return@LaunchedEffect
        launch {
            while (!spine.isUsable) {
                delay(200L)
                navigator.getSpine().takeIf { it.isUsable }?.let { s -> spine = s; spineRef.value = s }
            }
        }
        navigator.pageLoadEvents.collect {
            if (tocEntries.isEmpty()) {
                navigator.getToc().takeIf { toc -> toc.isNotEmpty() }?.let { toc -> tocEntries = toc; tocEntriesRef.value = toc }
            }
            if (!spine.isUsable) {
                navigator.getSpine().takeIf { it.isUsable }?.let { spine = it; spineRef.value = it }
            }
        }
    }

    // ---- Scroll-state probes -------------------------------------------------------------------
    //
    // `viewportSize / chapterSize` for the resource on screen. Two consumers: the bookmark
    // epsilon (`bookmarkEpsFor`, which is what decides whether the corner ribbon is lit and
    // therefore whether tapping it deletes or creates) and, indirectly, the honesty of every
    // bookmark round trip. iOS published none of it, so the epsilon always fell through to the
    // position-count proxy.
    //
    // Measured on page load and after a typography change — the two moments Readium re-lays the
    // document out — and never on scroll, which is the rule Android's `publishViewportFraction`
    // follows for the same reason (the value does not change with scroll position, and
    // re-emitting per frame is what flaked issue #399).
    LaunchedEffect(navigator) {
        navigator.viewportFractionEvents.collect { (href, fraction) ->
            val current = viewportFractionRef.value
            if (current[href] == fraction) return@collect
            viewportFractionRef.value = current + (href to fraction)
        }
    }
    LaunchedEffect(navigator, localPath, resolvedPrefs?.fontSize, resolvedPrefs?.margins, resolvedPrefs?.lineSpacing, resolvedPrefs?.orientation) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            val href = navigator.snapshotPosition()?.href ?: return@collect
            navigator.publishViewportFraction(normalizeEpubHref(href))
        }
    }

    // ---- Continuous mode ------------------------------------------------------------------------
    //
    // What makes Continuous continuous on a renderer that paginates per resource.
    //
    // Readium-Swift's EPUBNavigatorViewController has one scrolling mode and it renders a single
    // resource, so `epubScrollMode` mapping both Vertical and Continuous to `scroll = true` gave
    // iOS two identical modes: in each of them the reader hit the end of a chapter and had to
    // page across. Android does not have this problem because its Continuous mode is a different
    // view entirely (`ContinuousReaderView` stacks several chapters' WebViews), so a chapter
    // boundary is not an event there at all.
    //
    // This closes the gap from the other side: probe the scroll boundary, and when the reader
    // crosses it, cross the resource for them. The decision is the shared, edge-triggered
    // [ContinuousBoundaryAdvancePolicy] — reading the last paragraph at rest must not advance,
    // a chapter shorter than the viewport must not cascade, and the landing must not re-trigger.
    //
    // Backward crossings land at the *bottom* of the previous resource, which is the half that
    // makes it read as one document — and Readium-Swift already does that for free:
    // `go(to: .left)` resolves to `PageLocation.end`, and `EPUBReflowableSpreadView.scroll(
    // toProgression: 1)` sets `contentOffset.y` to the bottom natively in scroll mode (it
    // deliberately does NOT go through JS, because the JS layer cannot see the scroll view's
    // content inset). So `pageBy(Backward)` is the whole backward crossing; adding a JS
    // scroll-to-bottom on top would fight that and land short by the inset.
    //
    // Vertical deliberately keeps the page-across; the policy answers `None` for it.
    val boundaryPolicy = remember(item.id) { ContinuousBoundaryAdvancePolicy() }
    // A monotonic origin, not a wall clock: the policy's cooldown is a duration, and a wall clock
    // can jump backwards (NTP, a timezone change) and disarm it for the rest of the session.
    val clockOrigin = remember(item.id) { TimeSource.Monotonic.markNow() }

    // Every navigation the reader did not make by scrolling goes through here. A TOC tap, a
    // bookmark jump, a chapter-map segment and a search hit all land at a position the reader did
    // not scroll to — usually the top of a resource, which is a backward boundary. Left
    // unsuppressed, Continuous would read that landing as a crossing and bounce them into the
    // chapter before the one they asked for.
    val goTo: suspend (NavigatorNavigationTarget) -> Unit = { target ->
        boundaryPolicy.suppressUntilTheReaderLeavesTheBoundary()
        navigator.navigateTo(target)
    }
    LaunchedEffect(navigator, localPath, resolvedPrefs?.effectiveOrientation(isLandscape)) {
        val orientation = resolvedPrefs?.effectiveOrientation(isLandscape) ?: return@LaunchedEffect
        if (localPath == null || orientation != ReaderOrientation.Continuous) {
            continuousCursorOverride = null
            return@LaunchedEffect
        }
        boundaryPolicy.suppressUntilTheReaderLeavesTheBoundary()
        while (true) {
            val position = navigator.snapshotPosition()
            if (position != null && spine.hrefs.isNotEmpty()) {
                val index = spineIndexOfHref(spine.hrefs, position.href)
                val (boundary, scrollProgression) = navigator.scrollBoundaryAndProgression()
                val advance = boundaryPolicy.decide(
                    orientation = orientation,
                    boundary = boundary,
                    canGoForward = index in 0 until spine.hrefs.size - 1,
                    canGoBackward = index > 0,
                    nowMs = clockOrigin.elapsedNow().inWholeMilliseconds,
                )
                when (advance) {
                    BoundaryAdvance.Forward -> navigator.pageBy(NavigatorPageDirection.Forward)
                    BoundaryAdvance.Backward -> navigator.pageBy(NavigatorPageDirection.Backward)
                    BoundaryAdvance.None -> Unit
                }
                val segments = chapterMap.segments
                if (segments.isNotEmpty()) {
                    continuousCursorOverride = weightedRailCursorPosition(
                        chapterMap.activeIndex,
                        segments,
                        scrollProgression,
                    )
                }
            }
            delay(BOUNDARY_POLL_INTERVAL_MS)
        }
    }

    // ---- Vertical mode pull-to-navigate ------------------------------------------------------
    //
    // Mirrors Android's ScrollBoundaryNavigationContainer: polls the JS scroll boundary every
    // BOUNDARY_POLL_INTERVAL_MS in Vertical mode and delivers pan gesture deltas from the Swift
    // bridge to the shared PullBoundaryGestureState. When the accumulated pull crosses the
    // threshold the PullChip fills and navigator.pageBy fires.
    val density = LocalDensity.current.density
    var pullActive by remember { mutableStateOf(false) }
    var pullForward by remember { mutableStateOf(true) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(navigator, localPath, resolvedPrefs?.effectiveOrientation(isLandscape)) {
        val orientation = resolvedPrefs?.effectiveOrientation(isLandscape)
        if (localPath == null || orientation != ReaderOrientation.Vertical) {
            navigator.enablePullGesture(false)
            pullActive = false
            return@LaunchedEffect
        }
        navigator.enablePullGesture(true)
        val gestureState = PullBoundaryGestureState(density = density)
        gestureState.onPullStarted = { fwd -> pullForward = fwd; pullProgress = 0f; pullActive = true }
        gestureState.onPullEnded = { pullActive = false }
        gestureState.onPullProgress = { p -> pullProgress = p }
        gestureState.onNavigate = { fwd ->
            scope.launch {
                if (fwd) {
                    navigator.pageBy(NavigatorPageDirection.Forward)
                } else {
                    navigator.pageBy(NavigatorPageDirection.Backward)
                }
            }
        }
        var atFwd = false
        var atBwd = false
        var canFwd = false
        var canBwd = false
        coroutineScope {
            launch {
                while (true) {
                    val position = navigator.snapshotPosition()
                    if (position != null && spine.hrefs.isNotEmpty()) {
                        val index = spineIndexOfHref(spine.hrefs, position.href)
                        val boundary = navigator.scrollBoundary()
                        atFwd = boundary.atForwardBoundary
                        atBwd = boundary.atBackwardBoundary
                        canFwd = index in 0 until spine.hrefs.size - 1
                        canBwd = index > 0
                    }
                    delay(BOUNDARY_POLL_INTERVAL_MS)
                }
            }
            launch {
                navigator.pullResetEvents.collect { gestureState.reset() }
            }
            navigator.pullMoveEvents.collect { (dy, nowMs) ->
                gestureState.onMove(dy, atFwd, atBwd, canFwd, canBwd, nowMs)
            }
        }
    }

    // ---- Annotations -------------------------------------------------------------------------
    //
    // Works in all three reading modes. Paginated and the two scroll modes (Vertical and
    // Continuous both map to Readium's `scroll` via [epubScrollMode]) differ in exactly two
    // places, and both degrade the way Android's do:
    //  - the bookmark's `fragmentAnchor`: `CAPTURE_PAGE_FRAGMENT_ANCHOR_JS` deliberately
    //    answers null for a scrolling document, which is the legacy "no anchor" shape every
    //    consumer already handles, so a scroll-mode bookmark falls back to its progression;
    //  - the note glyph's column clamp, which is a no-op where there are no columns.
    // Everything else — the selection callback, the decoration groups, the text-quote anchor,
    // the merge policy — is mode-independent because Readium resolves a decoration the same way
    // in both layouts.
    //
    // Readium gates tap dispatch per group: without registering a group as activable, taps on
    // its decorations fall through to didTapAt as ordinary BodyTap events, toggling the chrome
    // instead of opening the actions sheet. Must be called before openEpub so the bridge stores
    // them and re-registers on every new navigator open.
    LaunchedEffect(navigator) {
        ReaderDecorationGroups.activable.forEach { group ->
            navigator.observeDecorationGroup(group)
        }
    }
    // A tap on a rendered decoration opens the actions sheet on that annotation. Readium reports
    // the decoration id, which is the annotation id — except for an emphasis layer, whose id
    // carries a `#<style>` suffix because one row can paint two decorations. Android strips a
    // `#segN` suffix at the same seam (`annotationIdOf`) for the same reason.
    LaunchedEffect(navigator) {
        navigator.decorationActivations.collect { activation ->
            editTargetId = activation.id.substringBefore('#')
            editTargetWasGlyphTap = activation.group == NOTE_GLYPH_DECORATION_GROUP
            annotationsPanelOpen = false
        }
    }

    // A tap on the body dismisses the actions sheet and toggles chrome. Readium's decorator
    // consumes a tap that landed on a decoration before it ever becomes a body tap, so this
    // cannot race with the collector above and close the sheet it just opened.
    LaunchedEffect(navigator) {
        navigator.eventFlow.collect { event ->
            when (event) {
                is NavigatorEvent.BodyTap -> {
                    // Compose overlay taps (e.g. CornerBookmarkIndicator) leak through the
                    // UIKitViewController to Readium, which fires BodyTap. The overlay's onClick
                    // calls suppressNextBodyTap() synchronously; by the time this coroutine resumes,
                    // that call has already happened, so the counter is non-zero and we discard.
                    if (navigator.consumeSuppressedBodyTap()) return@collect
                    editTargetId = null
                    editTargetWasGlyphTap = false
                    pendingStyles = emptySet()
                    immersiveState.toggle()
                }
                is NavigatorEvent.Footnote -> {
                    footnotePopupState = FootnotePopupState(FootnoteContent(event.contentHtml))
                }
                is NavigatorEvent.InternalLink -> {
                    returnToPositionTarget = event.originLocatorJson
                }
                else -> Unit
            }
        }
    }

    // Track the current spine-item href so the TOC can highlight the active entry.
    LaunchedEffect(navigator) {
        navigator.positionFlow.collect { position -> locatorHref = position.href }
    }

    // In Highlights mode: persist the current chapter as the resume position so reopening the
    // same book returns to the chapter the user last read (mirrors Android's ViewModel observer).
    if (source == ReaderSource.Highlights) {
        LaunchedEffect(navigator) {
            navigator.positionFlow.collect { position ->
                val chapters = highlightsRenderedChaptersRef.value
                if (chapters.isEmpty()) return@collect
                val annotationId = highlightsResumeAnnotationIdForHref(chapters, position.href)
                    ?: return@collect
                highlightsResumeStore.setLastHighlightId(item.sourceId, item.id, annotationId)
            }
        }
    }

    // Enter immersive mode when the user begins dragging — mirrors Android's onScrollStart →
    // ImmersiveModeState.dismissOverlay() which fires at gesture-start, not at scroll-end.
    // Wait for the first page-load so Readium's initial position-restore does not hide chrome
    // before the reader has even finished rendering.
    LaunchedEffect(navigator) {
        navigator.pageLoadEvents.first()
        navigator.scrollStartEvents.collect {
            if (orientationRef.value != ReaderOrientation.Horizontal) {
                immersiveState.dismissOverlay()
            }
        }
    }

    DisposableEffect(immersiveState) {
        onDispose { immersiveState.show() }
    }

    // Keep the corner ribbon in step with the page. Recomputed on every position change and
    // whenever the annotation set changes, because both can flip the answer.
    LaunchedEffect(navigator, annotations) {
        currentBookmark = bookmarkOnCurrentPage(navigator, annotations, orientationRef, spineRef, viewportFractionRef)
        navigator.positionFlow.collect {
            currentBookmark = bookmarkOnCurrentPage(navigator, annotations, orientationRef, spineRef, viewportFractionRef)
        }
    }

    // Search results painted in the page, not just listed. `searchMark` had no producer on iOS
    // even though the Swift bridge already knew the type. `searchResultIndex` tracks which
    // result is current so the page highlights one match at a time (same as Android).
    LaunchedEffect(searchResults, searchOpen, searchResultIndex) {
        if (!searchOpen || searchResults.isEmpty()) {
            navigator.applyDecorations(ReaderDecorationGroups.search, emptyList())
            return@LaunchedEffect
        }
        navigator.applyDecorations(
            ReaderDecorationGroups.search,
            searchResults.mapIndexed { index, match ->
                NavigatorDecoration.SearchMark(
                    id = "search_$index",
                    locatorJson = match.locatorJson,
                    isCurrent = index == searchResultIndex,
                )
            },
        )
        // Navigate to the current result each time the index changes.
        searchResults.getOrNull(searchResultIndex)?.let { match ->
            goTo(NavigatorNavigationTarget.ToLocatorJson(match.locatorJson))
        }
    }

    // The chapter map. Android derives these six values from six StateFlows on its reader
    // ViewModel; iOS has none, so it assembles them with the same shared arithmetic
    // (`chapterMapUiState`) off the navigator's position flow. Recomputed on every position
    // because that is the only thing that moves the cursor.
    LaunchedEffect(tocEntries, spine, item.id) {
        if (tocEntries.isEmpty()) return@LaunchedEffect
        combine(
            navigator.positionFlow,
            readingSpeedStore.speedSecPerPosition,
        ) { position, speed -> position to speed }.collect { (position, speed) ->
            chapterMap = chapterMapUiState(
                tocEntries = tocEntries,
                bookTitle = item.title,
                spineHrefs = spine.hrefs,
                positionCounts = spine.positionCounts,
                currentHref = position.href,
                chapterProgression = position.progression,
                totalProgression = position.totalProgression,
                speedSecPerPosition = speed,
            )
        }
    }

    // Local persistence policy is the shared PositionSaveCoordinator, the same one Android's
    // PositionOrchestrator drives: the locator on every change (hot path), the readingProgress
    // float once on close (cold path). iOS previously wrote both in onDispose only, so a
    // force-quit or a crash mid-book lost the whole session — and writing the locator on close
    // risks clobbering a freshly adopted server position (#528).
    val positionSaver = remember(item.id) {
        PositionSaveCoordinator<NavigatorPosition>(
            updateProgress = { progress -> updateReadingProgress(item.sourceId, item.id, progress) },
            savePosition = { position -> positionStore.save(item.sourceId, item.id, position.locatorJson) },
        )
    }

    LaunchedEffect(item.id) {
        if (source == ReaderSource.Highlights) return@LaunchedEffect
        observeReaderPositions(
            positions = navigator.positionFlow,
            positionSaver = positionSaver,
            lazyShape = { if (lazyFetcher != null) lazyShape else null },
            prefetchNext = { index -> lazyFetcher?.prefetchNext(index) },
        )
    }

    // 30s progress-sync heartbeat + reading-speed session — now driven by ReadingSessionCoordinator.
    // Restarts when localPath becomes non-null (i.e. when the book finishes opening).
    LaunchedEffect(localPath) {
        if (localPath == null || source == ReaderSource.Highlights) return@LaunchedEffect
        val initialPosition = navigator.snapshotPosition()
        speedSession.onResumed(initialTotalProgression = initialPosition?.totalProgression)
        sessionCoordinator.onResumed(onTick = {
            val pos = navigator.snapshotPosition() ?: return@onResumed
            val progress = com.riffle.core.domain.finishAwareEbookProgress(
                totalProgression = pos.totalProgression,
                progression = pos.progression,
                positionCounts = spineRef.value.positionCounts,
            )
            sessionRepository.runSyncCycle(
                item.id,
                SessionPayload(ebookLocation = pos.locatorJson, ebookProgress = progress),
                item.sourceId,
            )
        })
    }

    // Annotation decorations: apply highlights, bookmarks, note glyphs, emphasis and figure borders
    // to the navigator whenever annotations change or a page loads. Replaces the defunct
    // AnnotationDecorationCoordinator — the shared AnnotationSession now drives observation and the
    // three preference stores, so decoration-application is the only iOS-specific concern left.
    LaunchedEffect(navigator) {
        combine(
            annotationSession.annotations,
            annotationSession.emphasisPool,
            navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) },
        ) { annotations, emphasis, _ -> annotations to emphasis }
            .collect { (annotations, emphasis) ->
                applyAnnotationDecorations(navigator, annotations, emphasis)
            }
    }

    // ---- Auto-scroll -------------------------------------------------------------------------
    // One controller per open book (Android's is a process singleton bound in Koin, which is why
    // its FormattingSession has to defensively Stop on bind; scoping it to the composition makes
    // that unnecessary here). The ticker, the WPM→px/s conversion and the state machine are all
    // the shared ones — only the thing that consumes the pixel deltas is platform-specific.
    val autoScroll = remember(item.id) { AutoScrollController(dispatchers) }
    val autoScrollState by autoScroll.state.collectAsState()

    LaunchedEffect(autoScroll, resolvedPrefs?.autoScrollWpm) {
        resolvedPrefs?.let { autoScroll.setDefaultSpeed(AutoScrollSpeed.of(it.autoScrollWpm)) }
    }
    LaunchedEffect(autoScroll) {
        // A supplier rather than a value: the pace has to follow a font-size change or a rotation
        // without restarting the session, exactly as Android's FormattingSession wires it.
        autoScroll.setLayoutContext {
            val prefs = resolvedPrefs ?: FormattingPreferences()
            layoutContextFor(prefs, viewportWidthPx, density)
        }
    }
    LaunchedEffect(autoScroll, item.id) {
        autoScroll.scrollDeltas.collect { px ->
            // `false` means the document did not move — the bottom of the resource.
            //
            // What that means depends on the mode, which is the shared [autoScrollStallAction]
            // decision. Vertical stops, because the chapter end is a wall there — the same thing
            // Android's Vertical does. Continuous crosses into the next resource and keeps
            // scrolling, which Android gets for free because its Continuous view has no resource
            // boundary at all. Collapsing the two (which is what this did) stopped hands-free
            // reading dead at every chapter end.
            if (navigator.scrollByPx(px)) return@collect
            val position = navigator.snapshotPosition()
            val index = position?.let { spineIndexOfHref(spineRef.value.hrefs, it.href) } ?: -1
            val action = autoScrollStallAction(
                orientation = orientationRef.value,
                canGoForward = index in 0 until spineRef.value.hrefs.size - 1,
            )
            when (action) {
                AutoScrollStall.AdvanceResource -> navigator.pageBy(NavigatorPageDirection.Forward)
                AutoScrollStall.EndOfBook -> autoScroll.dispatch(AutoScrollEvent.ReachedEndOfBook)
            }
        }
    }
    DisposableEffect(autoScroll) {
        onDispose { autoScroll.release() }
    }

    // ---- Cadence -----------------------------------------------------------------------------
    // Sentence-at-a-time hands-free reading (issue #403 / ADR 0047). Everything above the
    // JavaScript seam is shared: `CadenceSession` accumulates the tokenised sentences, rebinds
    // the `DomSentenceSource` and resolves the start position; `CadenceDomScript` tokenises the
    // DOM and probes the page top; `ColumnSnap` does the paginated column arithmetic;
    // `NarratedColumnProgression` decides when a sentence that wraps a column needs a page turn.
    // Only running the scripts and painting the decoration is iOS's.
    //
    // Works in all three reading modes. Paginated gets the full treatment — start-of-sentence
    // column snap plus the intra-sentence turn when a sentence wraps. Vertical and Continuous
    // both map to Readium's scroll mode (`epubScrollMode`), where `ColumnSnap`'s JS answers
    // "scroll" and the measure returns an empty list: there is no column grid, the decoration
    // scroll-into-view Readium performs is the whole follow, and that is exactly what Android's
    // Vertical/Continuous do too.
    val cadenceController = remember(item.id) { CadenceController(dispatchers) }
    // `persistWpm` is why a HUD nudge survives the reader, the same contract Android's
    // FormattingSession gives Auto-Scroll. `storedPrefs`, not `resolvedPrefs`: writing back the
    // resolved copy would silently collapse ReaderTheme.Auto.
    val cadence = remember(cadenceController) {
        CadenceSession(
            controller = cadenceController,
            scope = scope,
            logger = logger,
            persistWpm = { wpm ->
                storedPrefs?.let { stored ->
                    scope.launch { formattingPreferencesStore.update(stored.copy(cadenceWpm = wpm)) }
                }
            },
        )
    }
    val cadenceState by cadence.state.collectAsState()
    // The WebView `Intl.Segmenter` gate. Cadence has no fallback tokeniser, so a false answer
    // hides the toggle here AND is persisted so the Settings drill-in (reachable with no book
    // open) hides its row too.
    var cadenceSupported by remember(item.id) { mutableStateOf(true) }

    // Pause Auto-Scroll and Cadence while any reader panel is open (TOC / Formatting / Search /
    // Annotations); resume on close. Mirrors Android's setAutoScrollPaused(paused, cause) logic
    // (ADR 0053). Scoped resume: only dispatch Resume when the current pause cause is PanelOpen
    // so a user-initiated UserPausedPill stop is never un-parked by a panel close.
    LaunchedEffect(annotationsPanelOpen, settingsOpen, searchOpen, tocOpen) {
        val anyOpen = annotationsPanelOpen || settingsOpen || searchOpen || tocOpen
        if (anyOpen) {
            autoScroll.dispatch(AutoScrollEvent.Pause(PauseCause.PanelOpen))
            cadence.pauseFor(CadencePauseCause.PanelOpen)
        } else {
            val s = autoScroll.state.value
            if (s is AutoScrollState.Paused && s.cause == PauseCause.PanelOpen) {
                autoScroll.dispatch(AutoScrollEvent.Resume)
            }
            cadence.resumeIfPaused()
        }
    }

    LaunchedEffect(cadence) {
        // Defect fixed here and on Android in the same change: `cadenceWpm` never reached a
        // running session, so Cadence always ticked at AutoScrollSpeed.Default.
        cadence.bindDefaultSpeed(formattingPreferencesStore.preferences.map { it.cadenceWpm })
    }

    // Per-chapter DOM tokenisation. Re-runs on every page-load event because Readium reports one
    // per resource and again after a reflow; the script is idempotent (it re-reads the spans it
    // already injected) so a repeat is cheap and keeps the merged map correct after a backward
    // turn. Seeded with onStart so the chapter the book opened on is tokenised without waiting
    // for the reader to turn a page.
    LaunchedEffect(cadence, localPath, resolvedPrefs?.showCadence) {
        if (localPath == null || resolvedPrefs?.showCadence != true) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.cadenceFeatureDetect()?.let { supported ->
                cadenceSupported = supported
                formattingPreferencesStore.setCadencePlatformSupported(supported)
                if (!supported) return@collect
            }
            val href = navigator.snapshotPosition()?.href ?: return@collect
            // null locale: Readium-Swift's metadata language is not surfaced at the navigator
            // bridge, and the shared script falls back to the rendered document's own
            // xml:lang/lang, which is where an EPUB declares it anyway.
            when (val parsed = navigator.cadenceTokeniseChapter(href, localeTag = null)) {
                is CadenceInjector.Result.Ready ->
                    cadence.onChapterTokenised(parsed.quotes, parsed.chapterHrefs)
                CadenceInjector.Result.Unsupported -> Unit
            }
        }
    }

    // Figure-tap: inject the tap-interceptor script on every page load and collect the resulting
    // payloads to show the zoom overlay. The shim that wires `window.RiffleFigureBridge` to
    // `window.webkit.messageHandlers.*` is already injected by the Readium setupUserScripts
    // delegate; this installs the per-page click listener on top of it.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectFigureTapScript()
        }
    }

    // Paginated layout lock: prevent hostile publisher CSS (e.g. O'Reilly EPUB3 `height: auto
    // !important`) from collapsing Readium's column grid and allowing vertical scrolling. The
    // injected CSS is gated on `:root:not([style*="readium-scroll-on"])` so it is a no-op in
    // scroll/continuous mode. Mirrors Android's PaginatedLayoutLock RendererCapability.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectPaginatedLayoutLockScript()
        }
    }
    // Table fit: force publisher fixed-pixel-width tables to reflow within the page so they
    // don't overflow horizontally. Applies in all three reading modes (paginated, vertical,
    // continuous). Mirrors Android's TableFit RendererCapability.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectTableFitScript()
        }
    }
    // Typography overrides: force user-controlled properties (justify, line-height, font-family)
    // to win against hostile publisher stylesheets. Readium's own after.css uses
    // `text-align: inherit !important` with high specificity (0,2,4), which means a publisher
    // rule setting text-align on a div ancestor of `p` beats Readium even when the user has
    // chosen justify. The injected CSS uses triple [style*="--USER__textAlign"] repetition to
    // reach (0,3,2) — same gate as Android's DefaultRendererBridge and ContinuousStyleInjector.
    LaunchedEffect(navigator, localPath) {
        if (localPath == null) return@LaunchedEffect
        navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) }.collect {
            navigator.injectTypographyOverrideScript()
        }
    }
    LaunchedEffect(navigator) {
        navigator.figureTapPayloads.collect { payload ->
            figureZoomState = FigureTapMessageParser.parse(payload)
        }
    }
    LaunchedEffect(navigator, item.sourceId, item.id) {
        navigator.figureLongPressPayloads.collect { payload ->
            val p = runCatching { FigureLongPressMessageParser.parse(payload) }.getOrNull() ?: return@collect
            val existing = annotationStore.findImageAnnotationForFigure(
                sourceId = item.sourceId,
                itemId = item.id,
                chapterHref = navigator.snapshotPosition()?.href ?: return@collect,
                imageHref = p.href,
                imageSvg = p.svg,
            )
            if (existing != null) {
                editTargetId = existing.id
                editTargetWasGlyphTap = false
            } else {
                val position = navigator.snapshotPosition() ?: return@collect
                val href = position.href
                val spineIndex = spineIndexOfHref(spineRef.value.hrefs, href).coerceAtLeast(0)
                val created = annotationStore.createImageAnnotation(
                    sourceId = item.sourceId,
                    itemId = item.id,
                    cfi = position.locatorJson,
                    textSnippet = p.caption,
                    chapterHref = href,
                    spineIndex = spineIndex,
                    progression = position.progression.toDouble(),
                    imageHref = p.href,
                    imageSvg = p.svg,
                    imageBytes = p.imageBytes,
                )
                editTargetId = created.id
                editTargetWasGlyphTap = false
            }
        }
    }
    // Accent-bar tap in Highlights mode: open the actions sheet for the tapped annotation.
    // The synthetic EPUB's chapter HTML contains `<span class="riffle-hl-tap">` elements that
    // navigate to riffle://annotation-tap/<id>. Readium classifies these as external URLs and
    // the Swift bridge fires riffleUrls instead of opening Safari.
    LaunchedEffect(navigator, source) {
        if (source != ReaderSource.Highlights) return@LaunchedEffect
        navigator.riffleUrls.collect { url ->
            parseAnnotationTapUrl(url)?.let { id -> editTargetId = id }
        }
    }
    LaunchedEffect(navigator) {
        navigator.publicationOpenErrors.collect { _ ->
            if (loadError == null) loadError = couldNotDownloadBook
        }
    }

    // Paint the current sentence and keep it on screen. One decoration group of its own so it
    // replaces atomically and never fights the annotation highlights.
    LaunchedEffect(cadence, navigator) {
        var followedRef: String? = null
        combine(cadence.currentFragment, cadence.quotes) { ref, quotes -> ref to quotes }
            .collect { (ref, quotes) ->
                if (ref == null) {
                    navigator.applyDecorations(DECORATION_GROUP_CADENCE, emptyList())
                    followedRef = null
                    return@collect
                }
                navigator.applyDecorations(
                    DECORATION_GROUP_CADENCE,
                    listOf(
                        cadenceDecoration(
                            fragmentRef = ref,
                            quote = quotes[ref],
                            color = (resolvedPrefs ?: FormattingPreferences()).cadenceHighlightColor,
                        ),
                    ),
                )
                // Follow only when the SENTENCE changes. This flow also re-emits when a new
                // chapter is tokenised (the quote map grows), and re-snapping then would yank a
                // reader who had just paged ahead back to the highlight.
                if (ref == followedRef) return@collect
                followedRef = ref
                // "absent" means the sentence is in another resource — the ticker crossed a
                // chapter boundary before the navigator did — so navigate to its chapter.
                val spanId = ref.substringAfter('#', "")
                if (spanId.isNotEmpty() &&
                    navigator.followCadenceSpan(spanId) == NavigatorFollowResult.OffPage
                ) {
                    goTo(NavigatorNavigationTarget.ToHref(ref.substringBefore('#')))
                }
            }
    }
    // Colour-change re-paint: when the user changes cadenceHighlightColor in Settings and
    // returns to the reader, re-apply the current sentence decoration with the new colour.
    // This is a separate effect (not keyed into the main follow LaunchedEffect above) so that
    // a colour change does NOT reset followedRef — resetting it would cause a spurious goTo
    // call for the already-visible sentence the next time combine emits.
    LaunchedEffect(cadence, navigator, resolvedPrefs?.cadenceHighlightColor) {
        val ref = cadence.currentFragment.value ?: return@LaunchedEffect
        val quotes = cadence.quotes.value
        navigator.applyDecorations(
            DECORATION_GROUP_CADENCE,
            listOf(
                cadenceDecoration(
                    fragmentRef = ref,
                    quote = quotes[ref],
                    color = (resolvedPrefs ?: FormattingPreferences()).cadenceHighlightColor,
                ),
            ),
        )
    }

    // Intra-sentence page follow (paginated only): a sentence that wraps a column boundary leaves
    // its tail on the next page while the highlight is still on it. The ticker's per-sentence
    // dwell fraction maps onto the sentence's measured column layout, and the page turns at the
    // estimated crossing — the same `NarratedColumnProgression` Android drives from audio timing.
    val cadenceColumns = remember(item.id) { NarratedColumnProgression() }
    LaunchedEffect(cadence, navigator) {
        var measuredRef: String? = null
        combine(cadence.currentFragment, cadence.currentProgress) { ref, p -> ref to p }
            .collect { (ref, progress) ->
                if (ref == null || progress == null) {
                    cadenceColumns.reset()
                    measuredRef = null
                    return@collect
                }
                val spanId = ref.substringAfter('#', "")
                if (spanId.isEmpty()) return@collect
                if (ref != measuredRef) {
                    // Empty in scroll mode (Vertical/Continuous) — the progression then never
                    // advances, which is the right answer for a document with no column grid.
                    cadenceColumns.onSentence(navigator.measureCadenceColumns(spanId))
                    measuredRef = ref
                }
                cadenceColumns.advance(progress)?.let { navigator.snapCadenceColumn(spanId, it) }
            }
    }

    // End-of-chapter auto-advance. The session's state stays Running across the turn, so the next
    // chapter's tokenisation rebinds the source and the ticker carries on with no user tap.
    LaunchedEffect(cadence, navigator) {
        cadence.endOfChapterEvents.collect { navigator.pageBy(NavigatorPageDirection.Forward) }
    }

    // Readaloud sentence highlight. Mirrors the cadence decoration effect above: the group replaces
    // atomically so it never fights annotation highlights, and we follow only when the SENTENCE
    // changes — quote-map growth (new chapter tokenised) re-emits without yanking the user.
    LaunchedEffect(readaloudSession, navigator) {
        var followedRef: String? = null
        combine(
            readaloudSession.activeFragmentRef,
            readaloudSession.sentenceQuotes,
            readaloudSession.readaloudHighlightColor,
        ) { ref, quotes, color -> Triple(ref, quotes, color) }
            .collect { (ref, quotes, color) ->
                if (ref == null) {
                    navigator.applyDecorations(DECORATION_GROUP_READALOUD, emptyList())
                    followedRef = null
                    return@collect
                }
                navigator.applyDecorations(
                    DECORATION_GROUP_READALOUD,
                    listOf(readaloudDecoration(fragmentRef = ref, quote = quotes[ref], color = color)),
                )
                if (ref == followedRef) return@collect
                followedRef = ref
                val quote = quotes[ref]
                if (quote != null &&
                    navigator.followReadaloudSentence(quote.highlight) == NavigatorFollowResult.OffPage
                ) {
                    goTo(NavigatorNavigationTarget.ToHref(ref.substringBefore('#')))
                }
            }
    }
    // Clear readaloud decoration when session closes so no stale highlight lingers.
    LaunchedEffect(readaloudSession, navigator) {
        readaloudSession.readaloudOpen.collect { open ->
            if (!open) navigator.applyDecorations(DECORATION_GROUP_READALOUD, emptyList())
        }
    }
    // Intra-sentence column follow for readaloud in paginated mode: when a narrated sentence spans
    // more than one column, turn the page as narration crosses the column boundary. Mirrors Android's
    // EpubReaderScreen.kt:2475–2503 using the same NarratedColumnProgression + measureReadaloudColumns.
    // Empty in scroll mode (Vertical/Continuous) — the progression never advances, which is correct.
    val readaloudColumns = remember(item.id) { NarratedColumnProgression() }
    LaunchedEffect(readaloudSession, navigator) {
        var measuredRef: String? = null
        readaloudSession.narrationProgress.collect { progress ->
            if (progress == null) {
                readaloudColumns.reset()
                measuredRef = null
                return@collect
            }
            val ref = progress.fragmentRef
            val sid = ref.substringAfter('#', "")
            val quotes = readaloudSession.sentenceQuotes.value
            val quote = quotes[ref] ?: quotes[sid] ?: return@collect
            if (ref != measuredRef) {
                readaloudColumns.onSentence(navigator.measureReadaloudColumns(quote.highlight))
                measuredRef = ref
            }
            readaloudColumns.advance(progress.fraction)?.let { column ->
                navigator.snapReadaloudColumn(quote.highlight, column)
            }
        }
    }
    // Handoff from audiobook player: open readaloud at the given global second once the reader
    // is ready (navigator non-null). Fires exactly once per screen open.
    if (startReadaloudAtSec >= 0.0) {
        LaunchedEffect(readaloudSession) {
            readaloudSession.startReadaloudAtSecond(startReadaloudAtSec)
        }
    }

    DisposableEffect(cadenceController) {
        onDispose {
            cadence.reset()
            cadenceController.release()
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            annotationSession.onBookClosed()
            val position = navigator.snapshotPosition()
            if (position != null && source != ReaderSource.Highlights) {
                val totalPositions = spineRef.value.positionCounts.sum().toFloat()
                // Cancel the heartbeat and flush reading-speed data on the survivable scope.
                sessionCoordinator.onClosed()
                speedSession.onClosed(
                    currentTotalProgression = position.totalProgression,
                    totalPositions = totalPositions,
                )
                // rememberCoroutineScope is cancelled during composition teardown; use an
                // independent scope so the position write and sync survive past onDispose.
                CoroutineScope(SupervisorJob()).launch {
                    runCatching {
                        // The locator was already written by the last onChanged; on close we
                        // persist only the progress float, per PositionSaveCoordinator's contract.
                        // finishAwareEbookProgress clamps to 1.0 when on the last page: Readium
                        // never emits totalProgression=1 in paginated/vertical mode because
                        // positions mark page starts ((N-1)/N for the last of N pages), so without
                        // the clamp a completed book syncs as 99% rather than 100%.
                        val closeProgress = com.riffle.core.domain.finishAwareEbookProgress(
                            totalProgression = position.totalProgression,
                            progression = position.progression,
                            positionCounts = spineRef.value.positionCounts,
                        )
                        positionSaver.onClose(closeProgress)
                        val payload = SessionPayload(
                            ebookLocation = position.locatorJson,
                            ebookProgress = closeProgress,
                        )
                        sessionRepository.runSyncCycle(item.id, payload, item.sourceId)
                    }
                }
            }
            sessionCoordinatorScope.cancel()
            navigator.close()
            lazyFetcher?.dispose()
            lazyFetcher = null
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewportWidthPx = it.width }) {
        when {
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = loadError ?: errorText,
                    modifier = Modifier.testTag(TestTags.READER_ERROR_STATE),
                )
            }
            localPath == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag(TestTags.READER_LOADING))
            }
            else -> UIKitViewController(
                factory = { bridge.viewController() },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
        }

        // Figure zoom overlay — fullscreen, above all reader layers including chrome. Shared
        // gesture logic lives in FigureZoomOverlay (reader-ui); iOS-specific image/SVG rendering
        // is injected via the imageContent/svgContent lambdas.
        FigureZoomOverlay(
            state = figureZoomState,
            onDismiss = { figureZoomState = null },
            modifier = Modifier.fillMaxSize(),
            imageContent = { href, imgModifier -> IosEpubFigureImage(href, navigator, imgModifier) },
            svgContent = { svgMarkup, imgModifier -> IosEpubSvgView(svgMarkup, imgModifier) },
        )

        // Pull-to-navigate chip — Vertical mode only. Slides in from the boundary edge being pulled.
        if (localPath != null) {
            AnimatedVisibility(
                visible = pullActive,
                enter = slideInVertically(initialOffsetY = { if (pullForward) it else -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { if (pullForward) it else -it }) + fadeOut(),
                modifier = Modifier
                    .align(if (pullForward) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(
                        bottom = if (pullForward) 56.dp else 0.dp,
                        top = if (pullForward) 0.dp else 56.dp,
                    ),
            ) {
                PullChip(forward = pullForward, progress = pullProgress)
            }
        }

        // The corner bookmark ribbon — placed before the top bar so it sits below the bar in
        // z-order and the top-bar buttons (formatting, TOC, etc.) receive taps first when chrome
        // is visible. Pinned flush to the top edge; no systemBarsPadding — matching Android, where
        // the ribbon starts at y=0 of the reader Box.
        if (localPath != null) {
            CornerBookmarkIndicator(
                isBookmarked = currentBookmark != null,
                isVisible = source != ReaderSource.Highlights,
                onToggle = {
                    if (source != ReaderSource.Highlights) {
                        // The Compose tap also leaks to the Readium UIKit view underneath;
                        // suppress the resulting BodyTap so it doesn't toggle chrome.
                        navigator.suppressNextBodyTap()
                        scope.launch {
                            toggleBookmark(
                                navigator = navigator,
                                annotationStore = annotationStore,
                                annotationSession = annotationSession,
                                annotations = annotationSession.annotations.value,
                                sourceId = item.sourceId,
                                itemId = item.id,
                                spineRef = spineRef,
                                orientationRef = orientationRef,
                                viewportFractionRef = viewportFractionRef,
                                tocEntriesRef = tocEntriesRef,
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp)
                    .testTag(TestTags.READER_BOOKMARK),
            )
        }

        // Shared M3 top bar (slides in/out with chromeVisible).
        // Auto-scroll and cadence toggles are extra actions injected by the host.
        val prefsForChrome = resolvedPrefs
        ReaderTopBar(
            visible = !isImmersive && !searchOpen,
            title = item.title,
            isReady = localPath != null,
            onBack = onBack,
            onSearch = { searchOpen = true; tocOpen = false; annotationsPanelOpen = false },
            onToc = { tocOpen = !tocOpen; searchOpen = false; annotationsPanelOpen = false },
            onAnnotations = { annotationsPanelOpen = !annotationsPanelOpen; tocOpen = false; searchOpen = false },
            onFormat = { settingsOpen = true },
            extraActions = {
                if (prefsForChrome != null &&
                    prefsForChrome.showAutoScroll &&
                    prefsForChrome.orientation != ReaderOrientation.Horizontal
                ) {
                    AutoScrollToggleIcon(
                        isRunning = autoScrollState is AutoScrollState.Running,
                        modifier = Modifier.testTag(TestTags.READER_AUTOSCROLL),
                        onClick = {
                            if (autoScrollState is AutoScrollState.Running) {
                                autoScroll.dispatch(AutoScrollEvent.Stop)
                            } else {
                                runArbiter(
                                    currentRunning = currentRunningFeature(
                                        cadenceRunning = cadenceState is CadenceState.Running,
                                        autoScrollRunning = false,
                                        readaloudPlaying = readaloudPlayback.isPlaying,
                                    ),
                                    starting = Feature.AutoScroll,
                                    stopAutoScroll = { autoScroll.dispatch(AutoScrollEvent.Stop) },
                                    pauseCadence = cadence::pauseFor,
                                    pauseReadaloud = readaloudSession::pause,
                                )
                                autoScroll.dispatch(AutoScrollEvent.Start)
                            }
                        },
                    )
                }
                if (prefsForChrome != null && prefsForChrome.showCadence && cadenceSupported) {
                    val cadenceRunning = cadenceState is CadenceState.Running
                    CadenceToggleIcon(
                        isRunning = cadenceRunning,
                        modifier = Modifier.testTag(TestTags.READER_CADENCE),
                        onClick = {
                            if (cadenceRunning) {
                                cadence.stop()
                            } else {
                                runArbiter(
                                    currentRunning = currentRunningFeature(
                                        cadenceRunning = false,
                                        autoScrollRunning = autoScrollState is AutoScrollState.Running,
                                        readaloudPlaying = readaloudPlayback.isPlaying,
                                    ),
                                    starting = Feature.Cadence,
                                    stopAutoScroll = { autoScroll.dispatch(AutoScrollEvent.Stop) },
                                    pauseCadence = cadence::pauseFor,
                                    pauseReadaloud = readaloudSession::pause,
                                )
                                scope.launch { startCadenceFromCurrentPage(navigator, cadence) }
                            }
                        },
                    )
                }
                if (readaloudVisible && source != ReaderSource.Highlights) {
                    IconButton(
                        onClick = {
                            if (!readaloudOpen) readaloudSession.openReadaloud()
                        },
                        enabled = readaloudAvailable,
                        modifier = Modifier.testTag(TestTags.READER_READALOUD),
                    ) {
                        Icon(
                            imageVector = RiffleIcons.Readaloud,
                            contentDescription = stringResource(Res.string.ui_readaloud),
                        )
                    }
                }
                // Share-as-PDF: matches Android order (AutoScroll → Cadence → Readaloud → Share).
                if (shouldShowShareHighlights(source)) {
                    var isPdfExporting by remember { mutableStateOf(false) }
                    androidx.compose.material3.IconButton(
                        onClick = {
                            if (!isPdfExporting) {
                                isPdfExporting = true
                                val chapters = buildChapterElisionsFromAnnotations(highlightsAnnotations)
                                // Use the data-URIs cached at open time so figures appear in the PDF.
                                val bookBodyFont = highlightsAnnotations
                                    .mapNotNull { it.originFontFamily?.ifBlank { null } }
                                    .groupingBy { it }
                                    .eachCount()
                                    .maxByOrNull { it.value }
                                    ?.key
                                val html = buildCombinedHtml(
                                    chapters = chapters,
                                    bookTitle = item.title,
                                    figureBytesByHref = highlightsDataUriByHref,
                                    publisherFontFaceCss = highlightsPublisherFontFaceCss,
                                    bookBodyFontFamily = bookBodyFont,
                                )
                                val fileName = buildPdfFileName(item.title, item.id)
                                elidedPdfBridge.exportAndShare(html, fileName) {
                                    isPdfExporting = false
                                }
                            }
                        },
                        modifier = Modifier.testTag(TestTags.READER_SHARE_HIGHLIGHTS),
                    ) {
                        if (isPdfExporting) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                imageVector = RiffleIcons.Share,
                                contentDescription = null,
                            )
                        }
                    }
                }
            },
        )

        // Search bar (replaces the BasicTextField list approach — results nav via prev/next).
        if (searchOpen) {
            SharedSearchTopBar(
                query = searchQuery,
                resultCount = searchResults.size,
                currentIndex = searchResultIndex,
                onQueryChange = { q ->
                    searchQuery = q
                    searchResults = emptyList()
                    searchResultIndex = 0
                    searchJob?.cancel()
                    searchJob = null
                    if (q.length >= 2) {
                        searchJob = scope.launch {
                            delay(300)
                            navigator.search(q).collect { batch ->
                                searchResults = searchResults + batch
                            }
                        }
                    }
                },
                onPrev = {
                    if (searchResults.isNotEmpty()) {
                        searchResultIndex = (searchResultIndex - 1 + searchResults.size) % searchResults.size
                    }
                },
                onNext = {
                    if (searchResults.isNotEmpty()) {
                        searchResultIndex = (searchResultIndex + 1) % searchResults.size
                    }
                },
                onClose = { searchOpen = false; searchQuery = ""; searchResults = emptyList(); searchResultIndex = 0 },
                onNavigateBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            )
        }

        // The annotate sheet. Shown for a live selection, or for the annotation the reader just
        // tapped. One sheet for both because the actions are the same — which is how Android's
        // `HighlightActionsPopup` works too. For live selections we anchor near the selection
        // rect (above when there's room, below otherwise), matching Android's
        // HighlightPopupPositionProvider. For existing annotation edits we dock at the bottom.
        val editTarget = annotations.firstOrNull { it.id == editTargetId }
        val liveSelection = selection

        // Selection action strip: Copy / Search / Share — mirrors Android's ActionMode items.
        // Shown while text is selected but before the user taps Annotate or the full sheet opens.
        if (liveSelection != null && editTarget == null && noteEditorFor == null) {
            val selText = liveSelection.text
            val selRect = liveSelection.rect
            val statusBarTopDp = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
            val stripTargetY = if (selRect != null) {
                val screenHeightDp = LocalWindowInfo.current.containerSize.height.toFloat()
                annotationSheetTargetY(
                    selectionY = selRect.y,
                    selectionHeight = selRect.height,
                    minAboveY = statusBarTopDp.value + 8f,
                    maxBelowY = screenHeightDp,
                )
            } else {
                null
            }
            val stripModifier = if (stripTargetY != null) {
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = stripTargetY.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            } else {
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            }
            SelectionActionStrip(
                onCopy = {
                    com.riffle.shared.reader.IosClipboard.copyToClipboard(selText)
                    navigator.clearSelection()
                },
                onLookUp = {
                    com.riffle.shared.reader.IosSystemActions.lookUp(selText)
                    navigator.clearSelection()
                },
                onSearchWeb = {
                    com.riffle.shared.reader.IosSystemActions.searchWeb(selText)
                    navigator.clearSelection()
                },
                onShare = {
                    com.riffle.shared.reader.IosSystemActions.share(selText)
                    navigator.clearSelection()
                },
                onPlayFromHere = if (readaloudVisible) {
                    {
                        val href = liveSelection.href
                        navigator.clearSelection()
                        scope.launch {
                            readaloudSession.ensureSentenceQuotesReady()
                            readaloudSession.playFromHere(href)
                        }
                    }
                } else {
                    null
                },
                modifier = stripModifier,
            )
        }

        // NoteOnlyPopup: when the user taps a note glyph on an annotation that has a note, show
        // a compact read-only card matching Android's noteOnly=true popup path.
        if (noteEditorFor == null && editTargetWasGlyphTap && editTarget != null && liveSelection == null) {
            val readerBackground = (resolvedPrefs ?: FormattingPreferences()).readerSwatchBackdropColor
            NoteOnlyPopup(
                note = editTarget.note,
                labels = annotationSheetLabels(),
                readerBackground = readerBackground,
                onOpenNoteEditor = {
                    noteEditorFor = editTarget.id
                    editTargetWasGlyphTap = false
                },
                onDismiss = {
                    editTargetId = null
                    editTargetWasGlyphTap = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }

        if (noteEditorFor == null && (editTarget != null || liveSelection != null) && !editTargetWasGlyphTap) {
            // The reader's own paper colour, not the app surface: an alpha-0x80 swatch composited
            // over the wrong backdrop previews a colour the book will never show. Same shared
            // derivation Android's popup reads.
            val readerBackground = (resolvedPrefs ?: FormattingPreferences()).readerSwatchBackdropColor
            // Position: near selection rect when a live selection is present; bottom otherwise.
            // Rect coordinates are in the navigator view's dp space (same origin as this Box, y=0
            // at the physical screen top). minAboveY accounts for the status-bar safe-area so
            // the sheet never slides behind the system chrome.
            val statusBarTopDp = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
            val annotationSheetModifier = run {
                val selRect = liveSelection?.rect
                if (selRect != null && editTarget == null) {
                    val screenHeightDp = LocalWindowInfo.current.containerSize.height.toFloat()
                    val targetY = annotationSheetTargetY(
                        selectionY = selRect.y,
                        selectionHeight = selRect.height,
                        minAboveY = statusBarTopDp.value + 8f,
                        maxBelowY = screenHeightDp,
                    )
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(top = targetY.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp)
                }
            }
            AnnotationActionsSheet(
                selectedColor = editTarget?.let {
                    if (it.color.isEmpty()) null else HighlightColor.fromToken(it.color)
                },
                emphasisStyles = editTarget?.let { emphasisStylesForAnnotation(it, annotationSession.emphasisPool.value) } ?: pendingStyles,
                note = editTarget?.note,
                readerBackground = readerBackground,
                labels = annotationSheetLabels(),
                onPickColor = { color ->
                    scope.launch {
                        if (editTarget != null) {
                            annotationSession.recolorHighlight(editTarget.id, color)
                        } else if (liveSelection != null) {
                            if (source == ReaderSource.Highlights) return@launch
                            createIosHighlight(
                                liveSelection, color, pendingStyles, null,
                                navigator, annotationStore, annotationSession,
                                item.sourceId, item.id, spineRef,
                            )?.let { editTargetId = it.id }
                            annotationSession.persistLastUsedColorToken(color.token)
                            pendingStyles = emptySet()
                        }
                    }
                },
                onRemoveColor = {
                    scope.launch {
                        if (editTarget != null) {
                            annotationSession.recolorHighlightRaw(editTarget.id, "")
                        } else if (liveSelection != null) {
                            if (source == ReaderSource.Highlights) return@launch
                            createIosHighlight(
                                liveSelection, null, pendingStyles, null,
                                navigator, annotationStore, annotationSession,
                                item.sourceId, item.id, spineRef,
                            )?.let { editTargetId = it.id }
                            annotationSession.persistLastUsedColorToken("")
                            pendingStyles = emptySet()
                        }
                    }
                },
                onToggleEmphasis = { style ->
                    scope.launch {
                        if (editTarget != null) {
                            val currentStyles = emphasisStylesForAnnotation(editTarget, annotationSession.emphasisPool.value)
                            val resultingStyles = if (style in currentStyles) currentStyles - style else currentStyles + style
                            toggleIosEmphasis(editTarget, style, annotationStore, annotationSession, item.sourceId, item.id, navigator)
                            emphasisPreferencesStore.setLastUsedStyles(item.sourceId, item.id, resultingStyles)
                        } else {
                            // ADR 0056 §4: a chip tapped on a bare selection persists the
                            // highlight (with no colour) plus its emphasis sibling, so the
                            // formatting has something to anchor to.
                            val next = if (style in pendingStyles) {
                                pendingStyles - style
                            } else {
                                pendingStyles + style
                            }
                            pendingStyles = next
                            emphasisPreferencesStore.setLastUsedStyles(item.sourceId, item.id, next)
                            if (liveSelection != null && next.isNotEmpty()) {
                                if (source == ReaderSource.Highlights) return@launch
                                createIosHighlight(
                                    liveSelection, null, next, null,
                                    navigator, annotationStore, annotationSession,
                                    item.sourceId, item.id, spineRef,
                                )?.let { editTargetId = it.id }
                                pendingStyles = emptySet()
                            }
                        }
                    }
                },
                onOpenNoteEditor = { noteEditorFor = editTarget?.id ?: "" },
                onDelete = editTarget?.let { target ->
                    {
                        scope.launch { annotationSession.deleteAnnotation(target.id) }
                        editTargetId = null
                    }
                },
                showOpenInBook = shouldShowOpenInBook(source),
                onOpenInBook = {
                    val targetId = editTargetId ?: return@AnnotationActionsSheet
                    val annotation = highlightsAnnotations.firstOrNull { it.id == targetId }
                        ?: return@AnnotationActionsSheet
                    val locatorJson = annotationLocatorJson(annotation.chapterHref, annotation.cfi, annotation.progression)
                    editTargetId = null
                    onOpenInBook?.invoke(locatorJson)
                },
                modifier = annotationSheetModifier,
            )
        }

        // The note editor. `""` means the note is being written on a selection that has not been
        // persisted yet — confirming it creates the highlight and the note in one go, which is
        // what Android's `commitDraftFromNoteEditor` does.
        noteEditorFor?.let { target ->
            val existing = annotations.firstOrNull { it.id == target }
            var currentNoteText by remember(target) { mutableStateOf(existing?.note.orEmpty()) }
            val commitCurrent: () -> Unit = {
                val text = currentNoteText
                scope.launch {
                    if (existing != null) {
                        annotationStore.updateNote(existing.id, text.takeIf { it.isNotBlank() })
                    } else {
                        if (source != ReaderSource.Highlights) {
                            selection?.let { sel ->
                                createIosHighlight(
                                    sel, null, pendingStyles, text,
                                    navigator, annotationStore, annotationSession,
                                    item.sourceId, item.id, spineRef,
                                )?.let { editTargetId = it.id }
                            }
                        }
                        pendingStyles = emptySet()
                    }
                }
                noteEditorFor = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = commitCurrent,
                    ),
            )
            NoteEditorSheet(
                initialNote = existing?.note.orEmpty(),
                labels = annotationSheetLabels(),
                onConfirm = { text -> currentNoteText = text; commitCurrent() },
                onDismiss = { noteEditorFor = null },
                onTextChange = { currentNoteText = it },
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }

        // Shared annotations panel — every highlight, note, and bookmark, tap to navigate.
        if (annotationsPanelOpen) {
            SharedAnnotationsPanel(
                annotations = annotations,
                onNavigate = { id ->
                    annotationsPanelOpen = false
                    if (source == ReaderSource.Highlights) {
                        // In Highlights mode the Readium navigator holds a synthetic EPUB whose
                        // hrefs are "highlights/chN.xhtml" — real-book locators cannot navigate it.
                        // Instead find the synthetic chapter that contains the tapped annotation.
                        val chapters = highlightsRenderedChaptersRef.value
                        val chapterIndex = chapters.indexOfFirst { ch -> ch.highlights.any { it.id == id } }
                        if (chapterIndex >= 0) {
                            scope.launch {
                                goTo(NavigatorNavigationTarget.ToHref("highlights/ch$chapterIndex.xhtml"))
                            }
                        }
                        editTargetId = id
                        return@SharedAnnotationsPanel
                    }
                    val target = annotations.firstOrNull { it.id == id }
                    if (target != null) {
                        scope.launch {
                            goTo(NavigatorNavigationTarget.ToLocatorJson(annotationDecorationLocator(target)))
                            editTargetId = id
                        }
                    }
                },
                onDelete = { id -> scope.launch { annotationSession.deleteAnnotation(id) } },
                onRename = { id, title -> scope.launch { annotationStore.renameBookmark(id, title) } },
                onDismiss = { annotationsPanelOpen = false },
                figureContent = { dataUri, _, imgModifier ->
                    IosAnnotationFigure(dataUri = dataUri, modifier = imgModifier)
                },
            )
        }

        // Shared TOC panel (same ModalBottomSheet Android's reader uses).
        if (tocOpen && tocEntries.isNotEmpty()) {
            val tocActiveHref = activeTocHref(locatorHref, lastTocNavigatedHref)
            TocPanel(
                entries = tocEntries,
                activeHref = tocActiveHref,
                onEntryClick = { entry ->
                    lastTocNavigatedHref = entry.href
                    tocOpen = false
                    immersiveState.hide()
                    scope.launch {
                        goTo(
                            NavigatorNavigationTarget.ToHref(
                                href = entry.href.substringBefore("#"),
                                fragment = entry.href.substringAfter("#", "").ifEmpty { null },
                            ),
                        )
                    }
                },
                onDismiss = { tocOpen = false },
            )
        }

        // Auto-scroll HUD pill — pause/resume and live WPM nudges, over everything else.
        // A nudge is persisted so it survives the reader, matching Android's
        // FormattingSession.nudgeAutoScroll.
        AutoScrollHudPill(
            state = autoScrollState,
            labels = speedHudLabels(),
            onPause = { autoScroll.dispatch(AutoScrollEvent.Pause(PauseCause.UserPausedPill)) },
            onResume = { autoScroll.dispatch(AutoScrollEvent.Resume) },
            onSlower = {
                val stored = storedPrefs
                val newWpm = autoScroll.nudgeSpeedAndPersistableWpm(-AutoScrollSpeed.STEP_WPM, stored?.autoScrollWpm ?: 0)
                if (stored != null && newWpm != null) {
                    scope.launch { formattingPreferencesStore.update(stored.copy(autoScrollWpm = newWpm)) }
                }
            },
            onFaster = {
                val stored = storedPrefs
                val newWpm = autoScroll.nudgeSpeedAndPersistableWpm(AutoScrollSpeed.STEP_WPM, stored?.autoScrollWpm ?: 0)
                if (stored != null && newWpm != null) {
                    scope.launch { formattingPreferencesStore.update(stored.copy(autoScrollWpm = newWpm)) }
                }
            },
        )

        // Cadence HUD pill — same shape and baseline as the auto-scroll pill; mutual exclusion
        // guarantees only one is ever on screen. A nudge persists, matching Android's
        // `EpubReaderViewModel.nudgeCadence` (which did not, until this change).
        CadenceHudPill(
            state = cadenceState,
            labels = cadenceHudLabels(),
            onPause = { cadence.pauseFor(CadencePauseCause.PanelOpen) },
            onResume = { cadence.resumeIfPaused() },
            onSlower = { cadence.nudge(-AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
            onFaster = { cadence.nudge(AutoScrollSpeed.STEP_WPM, storedPrefs?.cadenceWpm ?: 0) },
        )

        // Readaloud download confirmation dialog — shown as a dialog above everything.
        readaloudDownloadPromptBytes?.let { bytes ->
            ReadaloudDownloadDialog(
                sizeLabel = formatBytesOrUnknown(bytes),
                onConfirm = { wifiOnly -> readaloudSession.confirmDownloadAudio(wifiOnly) },
                onDismiss = readaloudSession::dismissDownloadPrompt,
            )
        }

        // Bottom stack: readaloud mini-player above the chapter rail, both anchored to the screen
        // bottom in one Column so the player never covers the rail. Mirrors Android's EpubReaderScreen
        // Column layout (EpubReaderScreen.kt:784–850).
        val prefs = resolvedPrefs
        val bookmarkPositions = remember(annotations, chapterMap.segments, spine.hrefs) {
            annotations
                .filter { it.type == AnnotationEntity.TYPE_BOOKMARK }
                .mapNotNull { bookmark ->
                    bookmarkRailPosition(
                        segments = chapterMap.segments,
                        chapterHref = bookmark.chapterHref,
                        progression = bookmark.progression,
                        spineHrefs = spine.hrefs,
                    )
                }
        }
        val showReadaloudUi = readaloudOpen && source != ReaderSource.Highlights
        val showRailOverlay = prefs != null && chapterMap.segments.isNotEmpty() && chapterMapVisible(prefs)
        if (showReadaloudUi || showRailOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showReadaloudUi) {
                    val readerPalette = (resolvedPrefs ?: FormattingPreferences()).theme.readerPalette
                    ReadaloudPeek(
                        handleColor = readerPalette.foreground,
                        enabled = readaloudAudiobookItemId != null,
                        onSwipeUp = {
                            if (readaloudAudiobookItemId != null) {
                                readaloudSession.prepareAudiobookHandoff()
                                showAudiobookOverlay = true
                            }
                        },
                        onDragHint = { readaloudSession.hintAudiobookHandoff() },
                        onDragAbandoned = { readaloudSession.cancelHandoffHint() },
                    ) {
                        ReadaloudMiniPlayer(
                            isPlaying = readaloudPlayback.isPlaying,
                            speed = readaloudPlayback.speed,
                            skipIntervalSeconds = readaloudSkipIntervalSeconds,
                            rewindIntervalSeconds = readaloudRewindIntervalSeconds,
                            canPreviousChapter = readaloudPlayback.currentChapterIndex > 0,
                            canNextChapter = readaloudPlayback.currentChapterIndex < readaloudPlayback.chapterCount - 1,
                            barMessage = readaloudBarMessage,
                            downloadProgress = readaloudDownloadProgress,
                            containerColor = readerPalette.background.copy(alpha = 0.65f),
                            contentColor = readerPalette.foreground,
                            onPlayPause = readaloudSession::togglePlayPause,
                            onRewind = readaloudSession::rewind,
                            onForward = readaloudSession::forward,
                            onPreviousChapter = readaloudSession::previousChapter,
                            onNextChapter = readaloudSession::nextChapter,
                            onSpeedChange = readaloudSession::setSpeed,
                            onClose = readaloudSession::closeReadaloud,
                            onSwitchToAudiobook = if (readaloudAudiobookItemId != null) {
                                {
                                    readaloudSession.prepareAudiobookHandoff()
                                    showAudiobookOverlay = true
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (showRailOverlay && prefs != null) {
                    ChapterMapOverlay(
                        segments = chapterMap.segments,
                        activeIndex = chapterMap.activeIndex,
                        cursorPosition = continuousCursorOverride ?: chapterMap.cursorPosition,
                        totalProgress = chapterMap.labelProgress,
                        readerTheme = prefs.theme,
                        showRail = prefs.showChapterMap,
                        coloredChapterMap = prefs.coloredChapterMap,
                        showCurrentChapterLabel = prefs.showCurrentChapterLabel,
                        showProgressLabels = prefs.showReadingProgressLabels,
                        showReadingTimeEstimate = prefs.showReadingTimeEstimate,
                        templates = chapterMapProgressLabelTemplates(),
                        bookmarkPositions = bookmarkPositions,
                        chapterTimeRemaining = chapterMap.chapterTimeRemaining,
                        bookTimeRemaining = chapterMap.bookTimeRemaining,
                        onSegmentClick = { segment ->
                            scope.launch {
                                goTo(
                                    NavigatorNavigationTarget.ToHref(
                                        href = segment.href.substringBefore("#"),
                                        fragment = segment.href.substringAfter("#", "").ifEmpty { null },
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        railHeight = 3.dp,
                    )
                }
            }
        }

        if (readaloudAudiobookItemId != null) {
            IosAudiobookPlayerOverlay(
                item = item,
                visible = showAudiobookOverlay,
                onDismiss = {
                    showAudiobookOverlay = false
                    readaloudSession.onAudiobookOverlayDismissed()
                },
                onSwitchToReadaloud = { _, atSec ->
                    showAudiobookOverlay = false
                    readaloudSession.startReadaloudAtSecond(atSec)
                },
            )
        }

        KoFiNudgeOverlay(
            positionFlow = navigator.positionFlow,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Reader settings sheet ("Aa" button).
        if (settingsOpen) {
            val storedForSettings = storedPrefs
            if (storedForSettings != null) {
                val effectiveForSettings = bookOverrides.applyTo(storedForSettings)
                IosReaderSettingsSheet(
                    prefs = effectiveForSettings,
                    hasBookOverrides = !bookOverrides.isEmpty,
                    onPrefsChange = { updated ->
                        scope.launch {
                            // Save the delta as per-book overrides.
                            val updatedOverrides = bookOverrides.withChanges(effectiveForSettings, updated)
                            bookOverrides = updatedOverrides
                            bookFormattingPreferencesStore.save(
                                item.sourceId,
                                item.id,
                                screenDimensionBucket,
                                updatedOverrides,
                            )
                            // Global prefs are not touched — book overrides layer on top.
                        }
                    },
                    onReset = {
                        scope.launch {
                            bookFormattingPreferencesStore.clear(item.sourceId, item.id, screenDimensionBucket)
                            bookOverrides = BookFormattingOverrides()
                        }
                    },
                    onDismiss = { settingsOpen = false },
                )
            }
        }

        // Footnote popup.
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        footnotePopupState?.let { state ->
            FootnotePopup(
                state = state,
                onDismiss = { footnotePopupState = null },
                onLinkTap = { url ->
                    footnotePopupState = null
                    uriHandler.openUri(url)
                },
            )
        }

        // Return-to-position card (shown after internal-link navigation).
        returnToPositionTarget?.let {
            ReturnToPositionCard(
                onReturn = {
                    scope.launch {
                        goTo(NavigatorNavigationTarget.ToLocatorJson(it))
                        returnToPositionTarget = null
                    }
                },
                onDismiss = { returnToPositionTarget = null },
            )
        }
    }
}

/**
 * Start Cadence at the sentence the reader is currently looking at.
 *
 * The probe is the shared, section-aware [com.riffle.feature.reader.cadence.CadenceDomScript]
 * rule: a heading at the top of the page wins, otherwise the section the reader is inside,
 * otherwise the first visible sentence. Starting without it would drop the ticker on
 * `orderedFragments[0]` — the first sentence of whichever chapter was tokenised first this
 * session — and Readium would then scroll the reader back to it.
 *
 * `internal` and top-level rather than a lambda inside the Composable so the start decision is
 * reachable from a test.
 */
/**
 * How often Continuous re-reads the scroll boundary.
 *
 * The same 120 ms Android's Vertical boundary poll uses (`EpubReaderScreen.BOUNDARY_POLL_INTERVAL_MS`),
 * and for the same reason: Readium's locator progression is not a usable boundary signal — it
 * keeps re-emitting while a touch moves nothing, and it stops emitting exactly when the reader is
 * wedged against the end, which is the moment that matters. A direct read of the scroll state is
 * the only honest answer, and 120 ms is fast enough that the crossing feels like part of the
 * gesture rather than a delayed jump.
 */
private const val BOUNDARY_POLL_INTERVAL_MS = 120L

// Maps a Compose logical-pixel dimension to a Material3-style window size class, using the same
// breakpoints Android's WindowSizeClass uses (600 dp = Compact/Medium, 840 dp = Medium/Expanded).
// LocalWindowInfo.containerSize is already in logical pixels on iOS (UIKit points ≈ dp).
private fun Int.dpToSizeClass(): SizeClass = when {
    this < 600 -> SizeClass.Compact
    this < 840 -> SizeClass.Medium
    else -> SizeClass.Expanded
}

@Composable
private fun KoFiNudgeOverlay(
    positionFlow: kotlinx.coroutines.flow.Flow<com.riffle.feature.reader.NavigatorPosition>,
    modifier: Modifier = Modifier,
) {
    val shownFlow = remember { MutableStateFlow(false) }
    val shown by shownFlow.collectAsState()
    LaunchedEffect(Unit) {
        com.riffle.feature.designsystem.collectKoFiProgressionNudge(
            positionFlow.map { it.totalProgression },
        ) { shownFlow.value = true }
    }
    com.riffle.feature.designsystem.KoFiNudgeCard(
        visible = shown,
        onNotNow = { shownFlow.value = false },
        onSupport = { shownFlow.value = false },
        modifier = modifier,
    )
}

internal suspend fun startCadenceFromCurrentPage(
    navigator: ReadiumSwiftNavigator,
    cadence: CadenceSession,
) {
    val href = navigator.snapshotPosition()?.href
    if (href == null) {
        cadence.startWithoutProbe()
    } else {
        cadence.onPageTopResolved(href, navigator.cadenceStartSpanId())
    }
}

internal fun emphasisStylesForAnnotation(
    annotation: com.riffle.core.models.Annotation,
    emphasisPool: List<com.riffle.core.models.Annotation>,
): Set<EmphasisStyle> = emphasisPool
    .filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_EMPHASIS && it.cfi == annotation.cfi }
    .flatMap { it.emphasisStyles.orEmpty() }
    .toSet()

internal suspend fun createIosHighlight(
    selection: com.riffle.feature.reader.NavigatorSelection,
    color: HighlightColor?,
    styles: Set<EmphasisStyle>,
    note: String?,
    navigator: ReadiumSwiftNavigator,
    annotationStore: com.riffle.core.domain.AnnotationStore,
    annotationSession: com.riffle.feature.reader.AnnotationSession,
    sourceId: String,
    itemId: String,
    spineRef: androidx.compose.runtime.MutableState<SpinePositions>,
): com.riffle.core.models.Annotation? {
    val href = selection.href
    val spineHrefs = spineRef.value.hrefs
    val spineIndex = com.riffle.feature.reader.spineIndexOfHref(spineHrefs, href).takeIf { it >= 0 } ?: return null
    val html = navigator.readChapterHtml(href) ?: return null
    val anchor = com.riffle.feature.reader.buildHighlightAnchor(
        html = html,
        spineIndex = spineIndex,
        snippet = selection.text,
        textBefore = selection.before,
        pageProgression = selection.progression,
    ) ?: return null

    val figures = com.riffle.feature.reader.findEnclosedFiguresInHtml(
        html,
        anchor.figureRangeStartChar,
        anchor.figureRangeEndChar,
    )
    val colorToken = color?.token ?: ""
    val chapterAnnotations = annotationSession.annotations.value.filter {
        com.riffle.core.domain.normalizeEpubHref(it.chapterHref) == com.riffle.core.domain.normalizeEpubHref(href)
    }
    val plan = com.riffle.feature.reader.planHighlightCommit(
        html = html,
        draftFields = com.riffle.feature.reader.draftFieldsOf(
            cfiRange = anchor.cfiRange,
            textSnippet = selection.text,
            textBefore = selection.before,
            textAfter = selection.after,
            progression = anchor.progression,
            embeddedFigures = figures.ifEmpty { null },
        ),
        draftSpineIndex = spineIndex,
        draftChapterHref = href,
        draftColor = colorToken,
        draftEmphasisStyles = styles,
        candidates = chapterAnnotations.filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_HIGHLIGHT },
        emphasisPool = annotationSession.emphasisPool.value.filter {
            com.riffle.core.domain.normalizeEpubHref(it.chapterHref) == com.riffle.core.domain.normalizeEpubHref(href)
        },
        imageAnnotations = chapterAnnotations.filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_IMAGE },
    )
    plan.deleteEmphasisIds.forEach { annotationStore.delete(it) }
    plan.deleteHighlightIds.forEach { annotationStore.delete(it) }
    plan.deleteImageIds.forEach { annotationStore.delete(it) }

    val fields = plan.fields
    val originFont = navigator.computedBodyFontFamily()?.takeIf { it.isNotBlank() }
        ?: com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY
    val created = annotationStore.createHighlight(
        sourceId = sourceId,
        itemId = itemId,
        cfi = fields.cfiRange,
        textSnippet = fields.textSnippet,
        chapterHref = href,
        textBefore = fields.textBefore,
        textAfter = fields.textAfter,
        color = colorToken,
        spineIndex = spineIndex,
        progression = fields.progression,
        embeddedFigures = fields.embeddedFigures,
        originFontFamily = originFont,
        textSnippetHtml = null,
    )
    if (!note.isNullOrBlank()) annotationStore.updateNote(created.id, note)
    if (styles.isNotEmpty()) {
        annotationStore.createEmphasis(
            sourceId = sourceId,
            itemId = itemId,
            cfi = fields.cfiRange,
            textSnippet = fields.textSnippet,
            chapterHref = href,
            styles = styles,
            textBefore = fields.textBefore,
            textAfter = fields.textAfter,
            spineIndex = spineIndex,
            progression = fields.progression,
            originFontFamily = originFont,
        )
    }
    navigator.clearSelection()
    return created
}

internal suspend fun toggleIosEmphasis(
    annotation: com.riffle.core.models.Annotation,
    style: EmphasisStyle,
    annotationStore: com.riffle.core.domain.AnnotationStore,
    annotationSession: com.riffle.feature.reader.AnnotationSession,
    sourceId: String,
    itemId: String,
    navigator: ReadiumSwiftNavigator,
) {
    val existing = annotationSession.emphasisPool.value
        .firstOrNull { it.type == com.riffle.core.database.AnnotationEntity.TYPE_EMPHASIS && it.cfi == annotation.cfi }
    val current = existing?.emphasisStyles.orEmpty()
    val next = if (style in current) current - style else current + style
    val originFont = navigator.computedBodyFontFamily()?.takeIf { it.isNotBlank() }
        ?: com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY
    when {
        existing == null && next.isNotEmpty() -> annotationStore.createEmphasis(
            sourceId = sourceId,
            itemId = itemId,
            cfi = annotation.cfi,
            textSnippet = annotation.textSnippet,
            chapterHref = annotation.chapterHref,
            styles = next,
            textBefore = annotation.textBefore,
            textAfter = annotation.textAfter,
            spineIndex = annotation.spineIndex,
            progression = annotation.progression,
            originFontFamily = annotation.originFontFamily ?: originFont,
        )
        existing != null && next.isEmpty() -> annotationStore.delete(existing.id)
        existing != null -> annotationStore.updateEmphasisStyles(existing.id, next)
    }
}

internal fun bookmarkOnCurrentPage(
    navigator: ReadiumSwiftNavigator,
    annotations: List<com.riffle.core.models.Annotation>,
    orientationRef: androidx.compose.runtime.MutableState<com.riffle.core.domain.ReaderOrientation>,
    spineRef: androidx.compose.runtime.MutableState<SpinePositions>,
    viewportFractionRef: androidx.compose.runtime.MutableState<Map<String, Double>>,
): com.riffle.core.models.Annotation? {
    val position = navigator.snapshotPosition() ?: return null
    val hrefNorm = com.riffle.core.domain.normalizeEpubHref(position.href)
    val eps = com.riffle.feature.reader.bookmarkEpsFor(
        orientation = orientationRef.value,
        spineCounts = spineRef.value.hrefs to spineRef.value.positionCounts,
        viewportFractionByHref = viewportFractionRef.value,
        chapterHref = hrefNorm,
    )
    return annotations.firstOrNull { a ->
        a.type == com.riffle.core.database.AnnotationEntity.TYPE_BOOKMARK &&
            com.riffle.core.domain.normalizeEpubHref(a.chapterHref) == hrefNorm &&
            kotlin.math.abs(a.progression - position.progression) <= eps
    }
}

internal suspend fun toggleBookmark(
    navigator: ReadiumSwiftNavigator,
    annotationStore: com.riffle.core.domain.AnnotationStore,
    annotationSession: com.riffle.feature.reader.AnnotationSession,
    annotations: List<com.riffle.core.models.Annotation>,
    sourceId: String,
    itemId: String,
    spineRef: androidx.compose.runtime.MutableState<SpinePositions>,
    orientationRef: androidx.compose.runtime.MutableState<com.riffle.core.domain.ReaderOrientation>,
    viewportFractionRef: androidx.compose.runtime.MutableState<Map<String, Double>>,
    tocEntriesRef: androidx.compose.runtime.MutableState<List<com.riffle.core.models.TocEntry>>,
): Boolean {
    val existing = bookmarkOnCurrentPage(navigator, annotations, orientationRef, spineRef, viewportFractionRef)
    if (existing != null) {
        annotationStore.delete(existing.id)
        return false
    }
    val position = navigator.snapshotPosition() ?: return false
    val href = position.href
    // Synthetic Annotations View hrefs must never produce real-book bookmarks.
    if (href.startsWith("highlights/")) return false
    val spineHrefs = spineRef.value.hrefs
    val spineIndex = com.riffle.feature.reader.spineIndexOfHref(spineHrefs, href).takeIf { it >= 0 } ?: 0
    val progression = position.progression.toDouble()
    val tocSegments = com.riffle.feature.reader.buildRailSegments(tocEntriesRef.value, spineHrefs = spineHrefs)
    val bookmarkTitle = com.riffle.feature.reader.buildEpubBookmarkTitle(
        tocSegments, href, spineHrefs, progression, position.totalProgression?.toDouble(),
    )
    val originFont = navigator.computedBodyFontFamily()?.takeIf { it.isNotBlank() }
        ?: com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY
    annotationStore.createBookmark(
        sourceId = sourceId,
        itemId = itemId,
        cfi = position.locatorJson,
        textSnippet = "",
        chapterHref = href,
        spineIndex = spineIndex,
        progression = progression,
        bookmarkTitle = bookmarkTitle,
        originFontFamily = originFont,
        fragmentAnchor = navigator.capturePageFragmentAnchor(),
    )
    return true
}

internal suspend fun applyAnnotationDecorations(
    navigator: ReadiumSwiftNavigator,
    annotations: List<com.riffle.core.models.Annotation>,
    emphasis: List<com.riffle.core.models.Annotation>,
) {
    val highlights = annotations.filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_HIGHLIGHT }
    val bookmarks = annotations.filter { it.type == com.riffle.core.database.AnnotationEntity.TYPE_BOOKMARK }
    navigator.applyDecorations(
        ReaderDecorationGroups.highlights,
        highlights.mapNotNull { annotationToHighlightDecoration(it) },
    )
    navigator.applyDecorations(
        ReaderDecorationGroups.noteGlyphs,
        highlights.mapNotNull { annotationToNoteGlyphDecoration(it) },
    )
    navigator.applyDecorations(
        ReaderDecorationGroups.bookmarks,
        bookmarks.map { annotationToBookmarkDecoration(it) },
    )
    navigator.applyDecorations(
        ReaderDecorationGroups.emphasis,
        emphasis.mapNotNull { annotationToEmphasisDecoration(it) },
    )
    navigator.evaluateJavaScriptForDecorations(com.riffle.feature.reader.decorations.figureBorderInjectionJs())
    navigator.evaluateJavaScriptForDecorations(
        com.riffle.feature.reader.decorations.figureBorderApplyJs(
            cssRules = com.riffle.feature.reader.decorations.FigureBorderDecoration.buildCssRules(annotations),
            svgMatches = com.riffle.feature.reader.decorations.FigureBorderDecoration.buildSvgMatches(annotations),
            rasterMarks = com.riffle.feature.reader.decorations.FigureBorderDecoration.buildRasterMarks(annotations),
        ),
    )
    navigator.evaluateJavaScriptForDecorations(
        com.riffle.feature.reader.EmphasisDomInjector.script(
            emphasis.mapNotNull { annotation ->
                val styles = annotation.emphasisStyles?.filter {
                    it == EmphasisStyle.BOLD || it == EmphasisStyle.ITALIC
                }?.toSet().orEmpty()
                if (styles.isEmpty()) {
                    null
                } else {
                    com.riffle.feature.reader.EmphasisDomInjector.EmphasisRange(
                        id = annotation.id,
                        textSnippet = annotation.textSnippet,
                        textBefore = annotation.textBefore,
                        styles = styles,
                    )
                }
            },
        ),
    )
}
