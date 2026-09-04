package com.riffle.shared.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.KomgaCbzApi
import com.riffle.feature.reader.CbzReaderViewModel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

@Suppress("ktlint:standard:function-naming")
@Composable
actual fun CbzReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val cbzApi = koinInject<KomgaCbzApi>()
    val cbzDownloader = koinInject<IosCbzDownloader>()
    val sourceRepository = koinInject<SourceRepository>()
    val tokenStorage = koinInject<TokenStorage>()

    var imageSource by remember { mutableStateOf<ComicImageSource?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) {
            runCatching {
                val source = sourceRepository.getById(item.sourceId)
                    ?: sourceRepository.getActive()
                    ?: run { loadError = "Source unavailable"; return@runCatching }
                val token = tokenStorage.getToken(source.id)
                    ?: run { loadError = "No credentials"; return@runCatching }

                val src: ComicImageSource = if (item.ebookFileIno != null) {
                    val bytes = cbzDownloader.downloadBytes(item)
                        ?: run { loadError = "Download failed"; return@runCatching }
                    IosCbzArchive(bytes)
                } else {
                    val count = cbzApi.fetchCbzPageCount(
                        baseUrl = source.url.value,
                        bookId = item.id,
                        token = token,
                        insecureAllowed = source.insecureConnectionAllowed,
                    )
                    IosKomgaCbzImageSource(
                        api = cbzApi,
                        baseUrl = source.url.value,
                        bookId = item.id,
                        token = token,
                        insecureAllowed = source.insecureConnectionAllowed,
                        pageCount = count,
                    )
                }
                imageSource = src
            }.onFailure { loadError = "Failed to open book: ${it.message}" }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(loadError ?: "Error")
            }
            imageSource == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Opening…")
            }
            else -> {
                val src = imageSource!!
                val savedPage = NSUserDefaults.standardUserDefaults
                    .integerForKey(positionKey(item)).toInt().coerceIn(0, src.pageCount - 1)
                val vm = remember(item.id, src) {
                    CbzReaderViewModel(
                        imageSource = src,
                        panelEngine = IosNoOpPanelEngine,
                        bookId = item.id,
                        onPositionChanged = { pageIndex ->
                            NSUserDefaults.standardUserDefaults
                                .setInteger(pageIndex.toLong(), forKey = positionKey(item))
                        },
                    )
                }
                LaunchedEffect(vm) {
                    if (savedPage > 0) vm.gotoPage(savedPage)
                }
                CbzPager(vm = vm, imageSource = src)
            }
        }

        Box(
            modifier = Modifier
                .systemBarsPadding()
                .padding(12.dp)
                .align(Alignment.TopStart),
        ) {
            BasicText(
                text = "← Back",
                modifier = Modifier
                    .padding(8.dp)
                    .clickable(onClick = onBack),
            )
        }
    }
}

@Composable
private fun CbzPager(vm: CbzReaderViewModel, imageSource: ComicImageSource) {
    val pagerState = rememberPagerState(
        initialPage = vm.currentPage.value,
        pageCount = { vm.pageCount },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            vm.gotoPage(page)
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
        ComicPageView(imageSource = imageSource, pageIndex = pageIndex)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun ComicPageView(imageSource: ComicImageSource, pageIndex: Int) {
    var bytes by remember(pageIndex) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            bytes = runCatching { imageSource.imageBytes(pageIndex) }.getOrNull()
        }
    }

    val currentBytes = bytes
    if (currentBytes != null) {
        UIKitView(
            factory = {
                UIImageView().apply {
                    contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                }
            },
            update = { view ->
                currentBytes.usePinned { pinned ->
                    val nsData = NSData.create(
                        bytes = pinned.addressOf(0),
                        length = currentBytes.size.toULong(),
                    )
                    view.image = UIImage.imageWithData(nsData)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Loading…")
        }
    }
}

private fun positionKey(item: LibraryItem) = "cbz_pos_${item.sourceId}_${item.id}"
