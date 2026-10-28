package com.riffle.shared.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.reader.CbzReaderState
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.ImmersiveModeState
import com.riffle.feature.reader.ui.CbzReaderScreen
import com.riffle.feature.reader.ui.PanelReportSheet
import com.riffle.feature.reader.ui.PanelReportViewModel
import com.riffle.feature.reader.ui.generated.resources.error_mark_false_panel
import com.riffle.feature.reader.ui.generated.resources.error_select_failure_type
import com.riffle.feature.settings.ui.panels.ComicFormattingSheet
import com.riffle.shared.IosAppActiveEvents
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.error_comic_page_load_failed
import com.riffle.shared.generated.resources.ui_more_options
import com.riffle.shared.generated.resources.ui_report_panel_detection_issue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import com.riffle.feature.reader.ui.generated.resources.Res as ReaderRes
import org.jetbrains.skia.Image as SkiaImage

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val vm = koinInject<CbzReaderViewModel> { parametersOf(item.id, item.sourceId) }
    val appActiveEvents = koinInject<IosAppActiveEvents>()
    val panelReportRepository = koinInject<PanelReportRepository>()
    val effectiveComicFormatting by vm.effectiveComicFormatting.collectAsState()
    val hasComicOverrides by vm.hasComicOverrides.collectAsState()
    val developerModeEnabled by vm.developerModeEnabled.collectAsState()
    val currentPage by vm.currentPage.collectAsState()

    val immersiveClock = remember { kotlin.time.TimeSource.Monotonic.markNow() }
    val immersiveState = remember {
        ImmersiveModeState(
            controller = IosSystemBarsController,
            clock = { immersiveClock.elapsedNow().inWholeMilliseconds },
            initialIsImmersive = false,
        )
    }
    val isImmersive by immersiveState.isImmersive.collectAsState()
    val ready by vm.state.collectAsState()
    val readyState = ready as? CbzReaderState.Ready
    val pageCache = remember(readyState?.imageSource) { mutableMapOf<Int, ImageBitmap>() }

    LaunchedEffect(currentPage, readyState) {
        if (readyState == null) return@LaunchedEffect
        val next = currentPage + 1
        if (next < readyState.pageCount && !pageCache.containsKey(next)) {
            withContext(Dispatchers.IO) {
                iosDecodeWithRetry(attempts = readyState.imageSource.decodeRetries) {
                    runCatching {
                        SkiaImage.makeFromEncoded(readyState.imageSource.imageBytes(next)).toComposeImageBitmap()
                    }.getOrNull()
                }
            }?.let { pageCache[next] = it }
        }
        if (pageCache.size > 5) {
            val toRemove = pageCache.keys.filter { it < currentPage - 1 }
            toRemove.forEach { pageCache.remove(it) }
        }
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
            vm.onReaderClosed()
        }
    }

    LaunchedEffect(appActiveEvents) {
        appActiveEvents.becameActive.collect { vm.onReaderResumed() }
    }

    DisposableEffect(immersiveState) {
        onDispose { immersiveState.show() }
    }

    val isReduceMotion = remember { UIAccessibilityIsReduceMotionEnabled() }

    val coroutineScope = rememberCoroutineScope()
    var reportSheetOpen by remember { mutableStateOf(false) }
    var reportData by remember { mutableStateOf<Pair<PanelBinaryMask, ByteArray>?>(null) }
    val currentPagePanels by vm.currentPagePanels.collectAsState()

    val pageContent: @Composable (Modifier, Int) -> Unit = { modifier, page ->
        val source = readyState?.imageSource
        if (source != null) {
            IosComicPageContent(modifier = modifier, imageSource = source, pageIndex = page, cache = pageCache)
        }
    }

    CbzReaderScreen(
        viewModel = vm,
        onNavigateBack = onBack,
        isImmersive = isImmersive,
        onToggleImmersive = immersiveState::toggle,
        pageContent = pageContent,
        thumbnailContent = pageContent,
        extraTopBarActions = {
            if (developerModeEnabled && ready is CbzReaderState.Ready) {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(RiffleIcons.MoreVert, contentDescription = stringResource(Res.string.ui_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.ui_report_panel_detection_issue)) },
                        onClick = {
                            menuOpen = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val result = vm.generateMaskPng(currentPage)
                                if (result != null) {
                                    reportData = result
                                    reportSheetOpen = true
                                }
                            }
                        },
                    )
                }
            }
        },
        isReduceMotion = isReduceMotion,
        formattingSheet = { onDismiss ->
            ComicFormattingSheet(
                formatting = effectiveComicFormatting,
                hasBookOverrides = hasComicOverrides,
                onUpdate = vm::updateComicFormatting,
                onReset = vm::resetComicFormattingToDefaults,
                onDismiss = onDismiss,
            )
        },
    )

    val data = reportData
    if (reportSheetOpen && data != null) {
        val (mask, maskPng) = data
        val selectFailureTypeMessage = stringResource(ReaderRes.string.error_select_failure_type)
        val markFalsePanelMessage = stringResource(ReaderRes.string.error_mark_false_panel)
        val maskBitmap = remember(mask) {
            SkiaImage.makeFromEncoded(maskPng).toComposeImageBitmap()
        }
        val panelReportVm = remember(currentPage, selectFailureTypeMessage) {
            PanelReportViewModel(
                bookId = vm.bookId,
                pageIndex = currentPage,
                imageWidth = currentPagePanels?.imageWidth ?: mask.width,
                imageHeight = currentPagePanels?.imageHeight ?: mask.height,
                detectedPanels = currentPagePanels?.panels ?: emptyList(),
                detectedSource = currentPagePanels?.source ?: PanelSource.Fallback,
                repository = panelReportRepository,
                selectFailureTypeMessage = selectFailureTypeMessage,
                markFalsePanelMessage = markFalsePanelMessage,
                scope = coroutineScope,
            )
        }
        PanelReportSheet(
            viewModel = panelReportVm,
            maskBitmap = maskBitmap,
            maskWidth = mask.width,
            maskHeight = mask.height,
            onSubmit = { panelReportVm.submit(maskPng) },
            onDismiss = { reportSheetOpen = false; reportData = null },
        )
    }
}

private sealed interface PageState {
    data object Loading : PageState
    data class Loaded(val bitmap: ImageBitmap) : PageState
    data object Error : PageState
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun IosComicPageContent(
    imageSource: com.riffle.core.domain.comic.ComicPageSource,
    pageIndex: Int,
    cache: MutableMap<Int, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    var state by remember(imageSource, pageIndex) { mutableStateOf<PageState>(PageState.Loading) }

    LaunchedEffect(imageSource, pageIndex) {
        val cached = cache[pageIndex]
        if (cached != null) {
            state = PageState.Loaded(cached)
            return@LaunchedEffect
        }
        state = PageState.Loading
        val result = withContext(Dispatchers.IO) {
            iosDecodeWithRetry(attempts = imageSource.decodeRetries) {
                runCatching {
                    SkiaImage.makeFromEncoded(imageSource.imageBytes(pageIndex)).toComposeImageBitmap()
                }.getOrNull()
            }
        }
        result?.let { cache[pageIndex] = it }
        state = if (result != null) PageState.Loaded(result) else PageState.Error
    }

    when (val s = state) {
        is PageState.Loaded -> {
            Image(
                bitmap = s.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier.fillMaxSize(),
            )
        }
        is PageState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is PageState.Error -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.error_comic_page_load_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal suspend fun <T : Any> iosDecodeWithRetry(
    attempts: Int = 3,
    retryDelayMs: Long = 2_000,
    decode: suspend () -> T?,
): T? {
    repeat(attempts - 1) {
        decode()?.let { return it }
        delay(retryDelayMs)
    }
    return decode()
}
