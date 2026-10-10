package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.core.models.LibraryItem
import com.riffle.feature.reader.CbzReaderViewModel
import com.riffle.feature.reader.ui.CbzReaderScreen
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
 * iOS thin wrapper around the shared [CbzReaderScreen]. Acquires the VM via Koin, manages
 * keep-screen-on and immersive state, and wires iOS-specific image rendering into the slots.
 *
 * The function name and signature are preserved so that [com.riffle.shared.LibraryNav] can call
 * this without modification.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun CbzReaderScreen(item: LibraryItem, onBack: () -> Unit) {
    KeepReaderScreenOn()
    val vm = koinInject<CbzReaderViewModel> { parametersOf(item.id, item.sourceId) }
    val effectiveComicFormatting by vm.effectiveComicFormatting.collectAsState()
    val hasComicOverrides by vm.hasComicOverrides.collectAsState()

    DisposableEffect(vm) {
        vm.onReaderResumed()
        onDispose { vm.onReaderClosed() }
    }

    var isImmersive by remember { mutableStateOf(false) }

    val ready by vm.state.collectAsState()

    CbzReaderScreen(
        viewModel = vm,
        onNavigateBack = onBack,
        isImmersive = isImmersive,
        onToggleImmersive = { isImmersive = !isImmersive },
        pageContent = { modifier, page ->
            val source = (ready as? com.riffle.feature.reader.CbzReaderState.Ready)?.imageSource
            if (source != null) {
                IosComicPageContent(modifier = modifier, imageSource = source, pageIndex = page)
            }
        },
        formattingSheet = { onDismiss ->
            IosComicFormattingSheet(
                formatting = effectiveComicFormatting,
                hasBookOverrides = hasComicOverrides,
                onUpdate = vm::updateComicFormatting,
                onReset = vm::resetComicFormattingToDefaults,
                onDismiss = onDismiss,
            )
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun IosComicPageContent(
    imageSource: com.riffle.core.domain.comic.ComicImageSource,
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(pageIndex) { mutableStateOf<ByteArray?>(null) }

    androidx.compose.runtime.LaunchedEffect(pageIndex) {
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
            modifier = modifier.fillMaxSize(),
        )
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
