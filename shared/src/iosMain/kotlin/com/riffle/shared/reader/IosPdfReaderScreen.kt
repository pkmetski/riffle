package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.usecase.UpdateReadingProgress
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.SessionPayload
import com.riffle.feature.reader.PositionSaveCoordinator
import com.riffle.feature.reader.PdfLocatorCodec
import com.riffle.feature.reader.RailSegment
import com.riffle.feature.reader.buildPdfRailSegments
import com.riffle.feature.reader.findActivePdfSegmentIndex
import com.riffle.feature.reader.pdfActiveHref
import com.riffle.feature.reader.pdfOutlineToFlatRailEntries
import com.riffle.feature.reader.pdfOutlineToTocEntries
import com.riffle.feature.reader.pdfProgressionWithinActiveSegment
import com.riffle.feature.reader.weightedRailCursorPosition
import com.riffle.feature.reader.ui.ChapterNavigationRail
import com.riffle.feature.reader.ui.ReaderTopBar
import com.riffle.feature.reader.ui.SharedAnnotationsPanel
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.chapterRailProgressPercent
import com.riffle.feature.reader.ui.formatTemplate
import com.riffle.feature.settings.ui.readersettings.ReaderSettingsSheet
import com.riffle.feature.settings.ui.readersettings.TocPanel
import com.riffle.feature.settings.ui.readersettings.formatting.RenderCapabilities
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_could_not_download_pdf
import com.riffle.shared.generated.resources.ui_error
import com.riffle.shared.generated.resources.ui_opening_pdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * iOS PDF reader composable. Downloads the PDF if not cached, then embeds PDFKit's
 * PDFView via UIKitViewController. Provides TOC, annotations panel, reader settings,
 * chapter rail, and chrome toggle on tap — parity with Android's PdfReaderScreen.
 *
 * Position is persisted via [PositionSaveCoordinator] (hot-path: locator on every page
 * change; cold-path: progress float on close). Server sync runs via
 * [ReadingSessionRepository] on close, same as Android.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val bridgeFactory = koinInject<IosPdfNavigatorBridgeFactory>()
    val downloader = koinInject<IosPdfDownloader>()
    val positionStore = koinInject<ReadingPositionStore>()
    val updateReadingProgress = koinInject<UpdateReadingProgress>()
    val sessionRepository = koinInject<ReadingSessionRepository>()
    val annotationStore = koinInject<AnnotationStore>()
    val formattingPreferencesStore = koinInject<FormattingPreferencesStore>()

    val couldNotDownloadPdf = stringResource(Res.string.ui_could_not_download_pdf)
    val errorText = stringResource(Res.string.ui_error)
    val openingPdfText = stringResource(Res.string.ui_opening_pdf)

    var localPath by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val bridge = remember { bridgeFactory.create() }
    var lastTrackedPage by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }
    var tocOpen by remember { mutableStateOf(false) }
    var annotationsPanelOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var railSegments by remember { mutableStateOf<List<RailSegment>>(emptyList()) }
    var tocEntries by remember { mutableStateOf<List<com.riffle.core.models.TocEntry>>(emptyList()) }

    val annotations by annotationStore.observeAnnotations(item.sourceId, item.id)
        .collectAsState(initial = emptyList())
    val formattingPrefs by formattingPreferencesStore.preferences
        .collectAsState(initial = FormattingPreferences())

    val scope = rememberCoroutineScope()

    val coordinator = remember {
        PositionSaveCoordinator<String>(
            updateProgress = { progress ->
                updateReadingProgress(item.sourceId, item.id, progress)
            },
            savePosition = { locatorJson ->
                positionStore.save(item.sourceId, item.id, locatorJson)
            },
        )
    }

    val activeRailSegmentIndex by remember {
        derivedStateOf {
            if (railSegments.isEmpty()) -1
            else findActivePdfSegmentIndex(railSegments, lastTrackedPage.coerceAtLeast(0))
        }
    }
    val cursorPosition by remember {
        derivedStateOf {
            if (railSegments.isEmpty() || totalPages <= 0 || activeRailSegmentIndex < 0) 0f
            else {
                val withinSeg = pdfProgressionWithinActiveSegment(
                    segments = railSegments,
                    activeIndex = activeRailSegmentIndex,
                    currentPageIndex = lastTrackedPage.coerceAtLeast(0),
                    intraPageOffset = 0f,
                    totalPages = totalPages,
                )
                weightedRailCursorPosition(activeRailSegmentIndex, railSegments, withinSeg)
            }
        }
    }

    LaunchedEffect(item.id) {
        val path = downloader.localPath(item)
        if (path == null) {
            loadError = couldNotDownloadPdf
            return@LaunchedEffect
        }
        localPath = path
        val savedLocator = positionStore.load(item.sourceId, item.id)
        val savedPage = savedLocator?.let { PdfLocatorCodec.decode0Based(it) } ?: 0
        lastTrackedPage = savedPage
        bridge.setPageChangeCallback(object : IosPdfPageChangeCallback {
            override fun onPageChanged(page: Int) {
                lastTrackedPage = page
                val count = bridge.pageCount()
                if (count > 0) totalPages = count
                val locatorJson = PdfLocatorCodec.encode(page, count)
                scope.launch { runCatching { coordinator.onChanged(locatorJson) } }
            }
        })
        bridge.setTapCallback(object : IosPdfTapCallback {
            override fun onTap() {
                chromeVisible = !chromeVisible
            }
        })
        bridge.openPdf(path, savedPage)
        // Load outline after opening so PDFKit has parsed the document.
        val outline = bridge.outline()
        tocEntries = pdfOutlineToTocEntries(outline)
        val count = bridge.pageCount()
        if (count > 0) totalPages = count
        railSegments = buildPdfRailSegments(
            pdfTocEntries = pdfOutlineToFlatRailEntries(outline),
            totalPages = count,
        )
    }

    DisposableEffect(item.id) {
        onDispose {
            bridge.setPageChangeCallback(null)
            bridge.setTapCallback(null)
            val page = lastTrackedPage.takeIf { it > 0 } ?: bridge.currentPage()
            val count = totalPages.takeIf { it > 0 } ?: bridge.pageCount()
            if (page > 0 || count > 0) {
                CoroutineScope(SupervisorJob()).launch {
                    runCatching {
                        val locatorJson = PdfLocatorCodec.encode(page, count)
                        val progress = if (count > 0) page.toFloat() / count else 0f
                        coordinator.onClose(progress)
                        val payload = SessionPayload(
                            ebookLocation = locatorJson,
                            ebookProgress = progress,
                        )
                        sessionRepository.runSyncCycle(item.id, payload, item.sourceId)
                    }
                }
            }
            bridge.disposePdf()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loadError != null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(loadError ?: errorText)
            }
            localPath == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(openingPdfText)
            }
            else -> UIKitViewController(
                factory = { bridge.viewController() },
                modifier = Modifier.fillMaxSize(),
                update = {},
            )
        }

        if (tocOpen) {
            TocPanel(
                entries = tocEntries,
                activeHref = pdfActiveHref((lastTrackedPage - 1).coerceAtLeast(0)),
                onEntryClick = { entry ->
                    val pageIdx = entry.href.removePrefix("page=").toIntOrNull() ?: -1
                    if (pageIdx >= 0) bridge.goToPage(pageIdx)
                    tocOpen = false
                },
                onDismiss = { tocOpen = false },
            )
        }

        if (annotationsPanelOpen) {
            SharedAnnotationsPanel(
                annotations = annotations,
                onNavigate = { id ->
                    val cfi = annotations.firstOrNull { it.id == id }?.cfi
                    val pageIdx = if (cfi != null) PdfLocatorCodec.decode0Based(cfi) ?: -1 else -1
                    if (pageIdx >= 0) bridge.goToPage(pageIdx)
                    annotationsPanelOpen = false
                },
                onDelete = { id -> scope.launch { annotationStore.delete(id) } },
                onRename = { id, title -> scope.launch { annotationStore.renameBookmark(id, title) } },
                onDismiss = { annotationsPanelOpen = false },
            )
        }

        if (settingsOpen) {
            ReaderSettingsSheet(
                prefs = formattingPrefs,
                capabilities = RenderCapabilities.PDF,
                hasBookOverrides = false,
                onPrefsChange = { newPrefs ->
                    scope.launch { formattingPreferencesStore.update(newPrefs) }
                },
                onReset = {},
                onDismiss = { settingsOpen = false },
            )
        }

        ReaderTopBar(
            visible = chromeVisible,
            title = item.title,
            isReady = localPath != null,
            onBack = onBack,
            onSearch = {},
            onToc = { tocOpen = !tocOpen; annotationsPanelOpen = false },
            onAnnotations = { annotationsPanelOpen = !annotationsPanelOpen; tocOpen = false },
            onFormat = { settingsOpen = true },
        )

        if (localPath != null && formattingPrefs.showChapterMap && railSegments.isNotEmpty()) {
            val templates = chapterMapProgressLabelTemplates()
            ChapterNavigationRail(
                segments = railSegments,
                activeIndex = activeRailSegmentIndex,
                cursorPosition = cursorPosition,
                readerTheme = ReaderTheme.Light,
                railContentDescription = formatTemplate(
                    templates.activeRailSegmentProgress,
                    railSegments.getOrNull(activeRailSegmentIndex)?.title.orEmpty(),
                    chapterRailProgressPercent(cursorPosition),
                ),
                onSegmentClick = { seg ->
                    val pageIdx = seg.href.removePrefix("page=").toIntOrNull() ?: return@ChapterNavigationRail
                    bridge.goToPage(pageIdx)
                },
                coloredChapterMap = formattingPrefs.coloredChapterMap,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}
