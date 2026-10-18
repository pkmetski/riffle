package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.PdfReaderState
import com.riffle.feature.reader.PdfReaderViewModel
import com.riffle.feature.reader.pdfActiveHref
import com.riffle.feature.reader.ui.ChapterNavigationRail
import com.riffle.feature.reader.ui.ReaderTopBar
import com.riffle.feature.reader.ui.SharedAnnotationsPanel
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.chapterRailProgressPercent
import com.riffle.feature.reader.ui.formatTemplate
import com.riffle.feature.settings.ui.readersettings.ReaderSettingsSheet
import com.riffle.feature.settings.ui.readersettings.TocPanel
import com.riffle.feature.settings.ui.readersettings.formatting.RenderCapabilities
import com.riffle.feature.source.ui.CornerBookmarkIndicator
import com.riffle.feature.source.ui.RiffleSnackbarHost
import com.riffle.feature.source.ui.rememberTransientMessages
import com.riffle.shared.IosAppActiveEvents
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.ui_could_not_download_pdf
import com.riffle.shared.generated.resources.ui_could_not_sync_reading_progress
import com.riffle.shared.generated.resources.ui_opening_pdf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

@Suppress("ktlint:standard:function-naming")
@Composable
fun PdfReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val vm = koinInject<PdfReaderViewModel> { parametersOf(item.id, item.sourceId) }
    val pdfRepo = koinInject<IosPdfSharedRepository>()
    val bridgeFactory = koinInject<IosPdfNavigatorBridgeFactory>()
    val appActiveEvents = koinInject<IosAppActiveEvents>()

    val state by vm.state.collectAsState()
    val currentPage by vm.currentPage.collectAsState()
    val tocVisible by vm.tocVisible.collectAsState()
    val annotationsPanelVisible by vm.annotationsPanelVisible.collectAsState()
    val formattingPrefs by vm.formattingPreferences.collectAsState()
    val hasBookOverrides by vm.hasBookOverrides.collectAsState()
    val annotations by vm.annotations.collectAsState()
    val railSegments by vm.railSegments.collectAsState()
    val activeRailIndex by vm.activeRailSegmentIndex.collectAsState()
    val railCursor by vm.railCursorPosition.collectAsState()
    val toc by vm.toc.collectAsState()
    val currentPageBookmarked by vm.currentPageBookmarked.collectAsState()
    val localPath by pdfRepo.localPath.collectAsState()

    var chromeVisible by remember { mutableStateOf(true) }
    var settingsVisible by remember { mutableStateOf(false) }

    val bridge = remember { bridgeFactory.create() }
    val messages = rememberTransientMessages()

    val openingPdfText = stringResource(Res.string.ui_opening_pdf)
    val couldNotDownloadPdf = stringResource(Res.string.ui_could_not_download_pdf)
    val syncErrorText = stringResource(Res.string.ui_could_not_sync_reading_progress)

    LaunchedEffect(state) {
        val awaiting = state as? PdfReaderState.AwaitingNavigator ?: return@LaunchedEffect
        val path = localPath ?: return@LaunchedEffect
        bridge.setPageChangeCallback(object : IosPdfPageChangeCallback {
            override fun onPageChanged(page: Int) {
                val count = bridge.pageCount()
                vm.onPageChanged(
                    page0Based = page,
                    totalPages = count,
                    progression = if (count > 0) page.toFloat() / count else 0f,
                )
                chromeVisible = false
            }
        })
        bridge.setTapCallback(handler = object : IosPdfTapCallback {
            override fun onTap() { chromeVisible = !chromeVisible }
        })
        bridge.openPdf(path, awaiting.initialPage)
        val outline = bridge.outline()
        val count = bridge.pageCount()
        vm.onNavigatorReady(totalPages = count, outline = outline)
    }

    LaunchedEffect(Unit) {
        vm.serverPageEvents.collect { page ->
            bridge.goToPage(page)
        }
    }

    LaunchedEffect(Unit) {
        vm.syncErrorEvents.collect { messages.show(syncErrorText) }
    }

    LaunchedEffect(appActiveEvents) {
        appActiveEvents.becameActive.collect { vm.onReaderResumed() }
    }

    DisposableEffect(item.id) {
        vm.onReaderResumed()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> vm.onReaderClosed() }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            bridge.setPageChangeCallback(null)
            bridge.setTapCallback(handler = null)
            vm.onReaderClosed()
            bridge.disposePdf()
        }
    }

    LaunchedEffect(chromeVisible) {
        IosStatusBarBridge.setHidden(!chromeVisible)
    }
    DisposableEffect(Unit) {
        onDispose { IosStatusBarBridge.setHidden(false) }
    }

    val isReady = state is PdfReaderState.Ready
    val title = (state as? PdfReaderState.Ready)?.title
        ?: (state as? PdfReaderState.AwaitingNavigator)?.title
        ?: item.title

    Box(Modifier.fillMaxSize().testTag(TestTags.PDF_READER_CONTAINER)) {
        when (val s = state) {
            is PdfReaderState.Error -> Box(
                Modifier.fillMaxSize().testTag(TestTags.PDF_READER_ERROR),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(s.message)
            }
            PdfReaderState.BookNotFound -> Box(
                Modifier.fillMaxSize().testTag(TestTags.PDF_READER_ERROR),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(couldNotDownloadPdf)
            }
            PdfReaderState.Loading -> Box(
                Modifier.fillMaxSize().testTag(TestTags.PDF_READER_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(openingPdfText)
            }
            is PdfReaderState.AwaitingNavigator, is PdfReaderState.Ready -> {
                if (localPath != null) {
                    UIKitViewController(
                        factory = { bridge.viewController() },
                        modifier = Modifier.fillMaxSize().testTag(TestTags.READER_READY),
                        update = {},
                    )
                    CornerBookmarkIndicator(
                        isBookmarked = currentPageBookmarked,
                        isVisible = isReady,
                        onToggle = vm::toggleBookmark,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(end = 12.dp)
                            .testTag(TestTags.PDF_READER_BOOKMARK),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().testTag(TestTags.PDF_READER_LOADING),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(openingPdfText)
                    }
                }
            }
        }

        if (tocVisible && isReady) {
            TocPanel(
                entries = toc,
                activeHref = currentPage?.let { pdfActiveHref(it.coerceAtLeast(0)) },
                onEntryClick = { entry -> vm.navigateToEntry(entry); chromeVisible = false },
                onDismiss = { vm.closeToc() },
            )
        }

        if (annotationsPanelVisible && isReady) {
            SharedAnnotationsPanel(
                annotations = annotations,
                onNavigate = { id -> vm.navigateToAnnotation(id) },
                onDelete = { id -> vm.deleteAnnotation(id) },
                onRename = { id, title -> vm.renameBookmark(id, title) },
                onDismiss = { vm.closeAnnotationsPanel() },
            )
        }

        if (settingsVisible) {
            ReaderSettingsSheet(
                prefs = formattingPrefs,
                capabilities = RenderCapabilities.PDF,
                hasBookOverrides = hasBookOverrides,
                onPrefsChange = { newPrefs -> vm.updateFormatting(newPrefs) },
                onReset = { vm.resetToGlobalDefaults() },
                onDismiss = { settingsVisible = false },
            )
        }

        ReaderTopBar(
            visible = chromeVisible,
            title = title,
            isReady = isReady,
            onBack = onBack,
            onSearch = {},
            onToc = { vm.openToc(); vm.closeAnnotationsPanel() },
            onAnnotations = { vm.openAnnotationsPanel(); vm.closeToc() },
            onFormat = { settingsVisible = true },
            extraActions = {},
        )

        RiffleSnackbarHost(messages)

        if (isReady && formattingPrefs.showChapterMap && railSegments.isNotEmpty()) {
            val templates = chapterMapProgressLabelTemplates()
            ChapterNavigationRail(
                segments = railSegments,
                activeIndex = activeRailIndex,
                cursorPosition = railCursor,
                readerTheme = ReaderTheme.Light,
                railContentDescription = formatTemplate(
                    templates.activeRailSegmentProgress,
                    railSegments.getOrNull(activeRailIndex)?.title.orEmpty(),
                    chapterRailProgressPercent(railCursor),
                ),
                onSegmentClick = { seg -> vm.navigateToSegment(seg) },
                coloredChapterMap = formattingPrefs.coloredChapterMap,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
    }
}
