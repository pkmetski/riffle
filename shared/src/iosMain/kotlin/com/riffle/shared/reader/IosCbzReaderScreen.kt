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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.models.LibraryItem
import com.riffle.feature.reader.CbzReaderState
import com.riffle.feature.reader.CbzReaderViewModel
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

/**
 * iOS comic (CBZ) reader. Hosts the shared [CbzReaderViewModel] — the VM opens the book (resolving
 * source/credentials and streaming or downloading pages via the injected iOS CbzRepository) and
 * exposes [CbzReaderState]; this screen renders the current page and drives page turns.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
actual fun CbzReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    val vm = koinInject<CbzReaderViewModel> { parametersOf(item.id, item.sourceId) }

    DisposableEffect(vm) {
        vm.onReaderResumed()
        onDispose { vm.onReaderClosed() }
    }

    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            CbzReaderState.BookNotFound -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Book not found")
            }
            is CbzReaderState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(s.message)
            }
            CbzReaderState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Opening…")
            }
            is CbzReaderState.Ready -> CbzPager(vm = vm, imageSource = s.imageSource, pageCount = s.pageCount)
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

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CbzPager(vm: CbzReaderViewModel, imageSource: ComicImageSource, pageCount: Int) {
    val currentPage by vm.currentPage.collectAsState()
    val pagerState = rememberPagerState(initialPage = currentPage, pageCount = { pageCount })

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            vm.jumpToPage(page)
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
        ComicPageView(imageSource = imageSource, pageIndex = pageIndex)
    }
}

@Suppress("ktlint:standard:function-naming")
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
