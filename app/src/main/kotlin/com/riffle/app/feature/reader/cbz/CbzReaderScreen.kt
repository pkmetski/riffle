package com.riffle.app.feature.reader.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.WindowManager
import android.provider.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.reader.CbzReaderState
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.ui.CbzReaderScreen
import com.riffle.app.feature.reader.rememberImmersiveModeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.riffle.core.data.comic.panel.PanelMaskEncoder
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelSource
import com.riffle.feature.reader.ui.CbzPanelViewer
import com.riffle.feature.reader.ui.ChapterMapOverlay
import com.riffle.feature.reader.ui.chapterMapProgressLabelTemplates
import com.riffle.feature.reader.ui.readerThemeLabelColor
import org.koin.androidx.compose.koinViewModel

private const val MAX_PAGE_DIMENSION = 4096
private const val MAX_THUMB_DIMENSION = 256

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CbzReaderScreen(
    onNavigateBack: () -> Unit,
    viewModel: CbzReaderViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val effectiveComicFormatting by viewModel.effectiveComicFormatting.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsState()
    val hasComicOverrides by viewModel.hasComicOverrides.collectAsState()

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val immersiveState = rememberImmersiveModeState()

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onReaderResumed()
                Lifecycle.Event.ON_STOP -> viewModel.onReaderClosed()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(keepScreenOn) {
        val window = (context as? FragmentActivity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val ready = state as? CbzReaderState.Ready
    val effectiveThumbnailSource = ready?.let { it.thumbnailSource ?: it.imageSource }
    val thumbnailCache = remember(effectiveThumbnailSource) { LruCache<Int, Bitmap>(50) }

    LaunchedEffect(effectiveThumbnailSource) {
        if (effectiveThumbnailSource == null) return@LaunchedEffect
        delay(2_000)
        val startPage = currentPage
        val source = effectiveThumbnailSource
        val pageCount = ready?.pageCount ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            prewarmThumbnailCache(startPage, pageCount, thumbnailCache) { index ->
                runCatching { decodeSampledBitmap(source, index, MAX_THUMB_DIMENSION) }.getOrNull()
            }
        }
    }

    val reduceMotion = remember(context) { isReduceMotionEnabled(context) }
    val coroutineScope = rememberCoroutineScope()
    var reportSheetOpen by remember { mutableStateOf(false) }
    var reportData by remember { mutableStateOf<Pair<PanelBinaryMask, ByteArray>?>(null) }

    CbzReaderScreen(
        viewModel = viewModel,
        onNavigateBack = onNavigateBack,
        isImmersive = immersiveState.isImmersive,
        onToggleImmersive = immersiveState::toggle,
        pageContent = { modifier, page ->
            if (ready != null) {
                CbzAndroidPageContent(
                    modifier = modifier,
                    imageSource = ready.imageSource,
                    page = page,
                )
            }
        },
        formattingSheet = { onDismiss ->
            ComicFormattingSheet(
                formatting = effectiveComicFormatting,
                hasBookOverrides = hasComicOverrides,
                onUpdate = viewModel::updateComicFormatting,
                onReset = viewModel::resetComicFormattingToDefaults,
                onDismiss = onDismiss,
            )
        },
        thumbnailContent = { modifier, page ->
            if (effectiveThumbnailSource != null) {
                CbzAndroidThumbnailContent(
                    modifier = modifier,
                    imageSource = effectiveThumbnailSource,
                    page = page,
                    cache = thumbnailCache,
                )
            }
        },
        extraTopBarActions = {
            if (developerModeEnabled && state is CbzReaderState.Ready) {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(RiffleIcons.MoreVert, contentDescription = stringResource(com.riffle.app.R.string.ui_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(com.riffle.app.R.string.ui_report_panel_detection_issue)) },
                        onClick = {
                            menuOpen = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val result = viewModel.generateMaskPng(currentPage)
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
        isReduceMotion = reduceMotion,
    )

    val data = reportData
    if (reportSheetOpen && data != null) {
        val (mask, maskPng) = data
        val selectFailureTypeMessage = stringResource(com.riffle.app.R.string.error_select_failure_type)
        val markFalsePanelMessage = stringResource(com.riffle.app.R.string.error_mark_false_panel)
        val maskBitmap = remember(mask) {
            val pixels = PanelMaskEncoder.toArgbPixels(mask)
            android.graphics.Bitmap.createBitmap(pixels, mask.width, mask.height, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
        val rawPanels = viewModel.currentPagePanels.collectAsState().value
        val panelReportVm = remember(currentPage, selectFailureTypeMessage) {
            PanelReportViewModel(
                bookId = viewModel.bookId,
                pageIndex = currentPage,
                imageWidth = rawPanels?.imageWidth ?: mask.width,
                imageHeight = rawPanels?.imageHeight ?: mask.height,
                detectedPanels = rawPanels?.panels ?: emptyList(),
                detectedSource = rawPanels?.source ?: PanelSource.Fallback,
                repository = viewModel.panelReportRepository,
                selectFailureTypeMessage = selectFailureTypeMessage,
                markFalsePanelMessage = markFalsePanelMessage,
            )
        }
        PanelReportSheet(
            viewModel = panelReportVm,
            mask = mask,
            maskBitmap = maskBitmap,
            onSubmit = { panelReportVm.submit(maskPng) },
            onDismiss = { reportSheetOpen = false },
        )
    }
}

// ── Android-specific page content slots ───────────────────────────────────────

@Composable
private fun CbzAndroidPageContent(
    modifier: Modifier,
    imageSource: ComicPageSource,
    page: Int,
) {
    val rawDecode by produceState(initialValue = CbzPageDecodeState(), key1 = page, key2 = imageSource) {
        value = CbzPageDecodeState()
        val result = decodeWithRetry(attempts = decodeAttemptsFor(imageSource)) {
            withContext(Dispatchers.IO) {
                runCatching { decodeSampledBitmap(imageSource, page, MAX_PAGE_DIMENSION) }.getOrNull()
            }
        }
        value = CbzPageDecodeState(bitmap = result, settled = true, forPage = page)
    }
    val decode = decodeForPage(rawDecode, page)
    val bitmap = decode.bitmap
    val context = LocalContext.current
    val imageRequest = remember(bitmap) { ImageRequest.Builder(context).data(bitmap).build() }
    when (cbzPageContent(bitmap != null, decode.settled)) {
        CbzPageContent.Loading -> CircularProgressIndicator()
        CbzPageContent.Error -> Text(stringResource(com.riffle.app.R.string.error_comic_page_load_failed))
        CbzPageContent.Image -> SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = stringResource(com.riffle.app.R.string.ui_comic_page_number, page + 1),
            loading = { CircularProgressIndicator() },
            modifier = modifier,
        )
    }
}

@Composable
private fun CbzAndroidThumbnailContent(
    modifier: Modifier,
    imageSource: ComicPageSource,
    page: Int,
    cache: LruCache<Int, Bitmap>,
) {
    val rawDecode by produceState(initialValue = CbzPageDecodeState(), key1 = page, key2 = imageSource) {
        val cached = cache.get(page)
        if (cached != null) {
            value = CbzPageDecodeState(bitmap = cached, settled = true, forPage = page)
            return@produceState
        }
        val result = decodeWithRetry(attempts = decodeAttemptsFor(imageSource)) {
            withContext(Dispatchers.IO) {
                runCatching { decodeSampledBitmap(imageSource, page, MAX_THUMB_DIMENSION) }.getOrNull()
            }
        }
        result?.let { cache.put(page, it) }
        value = CbzPageDecodeState(bitmap = result, settled = true, forPage = page)
    }
    val decode = decodeForPage(rawDecode, page)
    val bitmap = decode.bitmap
    val context = LocalContext.current
    val imageRequest = remember(bitmap) { ImageRequest.Builder(context).data(bitmap).build() }
    when (cbzPageContent(bitmap != null, decode.settled)) {
        CbzPageContent.Loading -> CircularProgressIndicator()
        CbzPageContent.Error -> Unit
        CbzPageContent.Image -> SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = null,
            loading = { CircularProgressIndicator() },
            modifier = modifier,
        )
    }
}

/** Android implementation of panel page content — decoded via BitmapFactory, rendered via Coil. */
@Composable
internal fun CbzAndroidPanelPageContent(
    modifier: Modifier,
    imageSource: ComicPageSource,
    page: Int,
) {
    CbzAndroidPageContent(modifier = modifier, imageSource = imageSource, page = page)
}

// ── Android-only decode helpers ────────────────────────────────────────────────

private fun isReduceMotionEnabled(context: android.content.Context): Boolean {
    val cr = context.contentResolver
    fun getScale(name: String): Float = try {
        Settings.Global.getFloat(cr, name, 1f)
    } catch (_: Throwable) {
        1f
    }
    return getScale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
        getScale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f ||
        getScale(Settings.Global.WINDOW_ANIMATION_SCALE) == 0f
}

internal enum class CbzPageGestureAction { Ignore, Zoom, PanZoomed }

internal enum class CbzPageContent { Loading, Image, Error }

internal data class CbzPageDecodeState(
    val bitmap: Bitmap? = null,
    val settled: Boolean = false,
    val forPage: Int = -1,
)

internal fun decodeForPage(decode: CbzPageDecodeState, currentPage: Int): CbzPageDecodeState =
    if (decode.forPage == currentPage) decode else CbzPageDecodeState()

internal fun cbzPageContent(
    hasBitmap: Boolean,
    decodeSettled: Boolean,
    panelsReady: Boolean = true,
): CbzPageContent = when {
    hasBitmap && panelsReady -> CbzPageContent.Image
    hasBitmap -> CbzPageContent.Loading
    decodeSettled -> CbzPageContent.Error
    else -> CbzPageContent.Loading
}

internal fun decodeAttemptsFor(source: ComicPageSource): Int = source.decodeRetries

internal suspend fun <T : Any> decodeWithRetry(
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

internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > maxDimension) sampleSize *= 2
    return sampleSize
}

internal fun decodeSampledBitmap(source: ComicPageSource, pageIndex: Int, maxDimension: Int): Bitmap? {
    val bytes = try {
        source.imageBytes(pageIndex)
    } catch (_: Throwable) {
        return null
    }
    val startSampleSize = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        calculateInSampleSize(opts.outWidth, opts.outHeight, maxDimension)
    } catch (_: Throwable) {
        1
    }
    var sampleSize = startSampleSize
    while (sampleSize <= 64) {
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: OutOfMemoryError) {
            null
        }
        if (bitmap != null) return bitmap
        sampleSize *= 2
    }
    return null
}

internal fun prewarmThumbnailCache(
    startPage: Int,
    pageCount: Int,
    cache: LruCache<Int, Bitmap>,
    decode: (Int) -> Bitmap?,
) {
    val capacity = cache.maxSize()
    var loaded = 0
    outer@ for (offset in 0..pageCount) {
        for (candidate in listOf(startPage + offset, startPage - offset).distinct()) {
            if (loaded >= capacity) break@outer
            if (candidate in 0 until pageCount && cache.get(candidate) == null) {
                decode(candidate)?.let {
                    cache.put(candidate, it)
                    loaded++
                }
            }
        }
    }
}
