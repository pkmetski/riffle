package com.riffle.shared.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.reader.CbzReaderState
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.ui.CbzReaderScreen
import com.riffle.feature.settings.ui.panels.ComicFormattingSheet
import com.riffle.shared.IosAppActiveEvents
import com.riffle.shared.generated.resources.Res
import com.riffle.shared.generated.resources.error_comic_page_load_failed
import com.riffle.shared.generated.resources.ui_cancel
import com.riffle.shared.generated.resources.ui_more_options
import com.riffle.shared.generated.resources.ui_report_panel_detection_issue
import com.riffle.shared.generated.resources.ui_submit
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

    var isImmersive by remember { mutableStateOf(false) }
    val ready by vm.state.collectAsState()

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

    LaunchedEffect(isImmersive) {
        IosStatusBarBridge.setHidden(isImmersive)
    }
    DisposableEffect(Unit) {
        onDispose { IosStatusBarBridge.setHidden(false) }
    }

    val isReduceMotion = remember { UIAccessibilityIsReduceMotionEnabled() }

    val coroutineScope = rememberCoroutineScope()
    var reportSheetOpen by remember { mutableStateOf(false) }
    var reportMaskPng by remember { mutableStateOf<ByteArray?>(null) }

    val pageContent: @Composable (Modifier, Int) -> Unit = { modifier, page ->
        val source = (ready as? CbzReaderState.Ready)?.imageSource
        if (source != null) {
            IosComicPageContent(modifier = modifier, imageSource = source, pageIndex = page)
        }
    }

    CbzReaderScreen(
        viewModel = vm,
        onNavigateBack = onBack,
        isImmersive = isImmersive,
        onToggleImmersive = { isImmersive = !isImmersive },
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
                                    reportMaskPng = result.second
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

    val maskPng = reportMaskPng
    if (reportSheetOpen && maskPng != null) {
        IosPanelReportSheet(
            bookId = vm.bookId,
            pageIndex = currentPage,
            maskPng = maskPng,
            repository = panelReportRepository,
            onDismiss = { reportSheetOpen = false; reportMaskPng = null },
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
    modifier: Modifier = Modifier,
) {
    var state by remember(imageSource, pageIndex) { mutableStateOf<PageState>(PageState.Loading) }

    LaunchedEffect(imageSource, pageIndex) {
        state = PageState.Loading
        val result = withContext(Dispatchers.IO) {
            iosDecodeWithRetry(attempts = imageSource.decodeRetries) {
                runCatching {
                    SkiaImage.makeFromEncoded(imageSource.imageBytes(pageIndex)).toComposeImageBitmap()
                }.getOrNull()
            }
        }
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

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IosPanelReportSheet(
    bookId: String,
    pageIndex: Int,
    maskPng: ByteArray,
    repository: PanelReportRepository,
    onDismiss: () -> Unit,
) {
    var selectedFailureType by remember { mutableStateOf(PanelDetectionFailureType.MissedPanel) }
    var failureTypeMenuOpen by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(Res.string.ui_report_panel_detection_issue),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Box {
                    OutlinedButton(onClick = { failureTypeMenuOpen = true }) {
                        Text(selectedFailureType.label)
                    }
                    DropdownMenu(
                        expanded = failureTypeMenuOpen,
                        onDismissRequest = { failureTypeMenuOpen = false },
                    ) {
                        PanelDetectionFailureType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label) },
                                onClick = { selectedFailureType = type; failureTypeMenuOpen = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_cancel)) }
                    TextButton(onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            repository.submit(
                                report = PanelDetectionReport(
                                    bookId = bookId,
                                    pageIndex = pageIndex,
                                    imageWidth = 0,
                                    imageHeight = 0,
                                    detectedPanels = emptyList(),
                                    detectedSource = PanelSource.Fallback,
                                    failureType = selectedFailureType,
                                    notes = "",
                                    tappedX = null,
                                    tappedY = null,
                                    tappedPanelIndex = null,
                                ),
                                maskPng = maskPng,
                            )
                        }
                        onDismiss()
                    }) { Text(stringResource(Res.string.ui_submit)) }
                }
            }
        }
    }
}
