package com.riffle.shared.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.riffle.core.models.LibraryItem
import com.riffle.feature.designsystem.RiffleIcons
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
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
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

    // Shared CbzReaderScreen already calls onReaderClosed() in its own DisposableEffect(viewModel).
    // This side only needs the resume signal, which has no Android-lifecycle equivalent on iOS.
    LaunchedEffect(vm) { vm.onReaderResumed() }

    var isImmersive by remember { mutableStateOf(false) }

    val ready by vm.state.collectAsState()

    // UIAccessibility.isReduceMotionEnabled is the iOS equivalent of Android's
    // ANIMATOR_DURATION_SCALE / TRANSITION_ANIMATION_SCALE gate.
    val isReduceMotion = remember { UIAccessibilityIsReduceMotionEnabled() }

    val pageContent: @Composable (Modifier, Int) -> Unit = { modifier, page ->
        val source = (ready as? com.riffle.feature.reader.CbzReaderState.Ready)?.imageSource
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
        // Thumbnail content reuses the same composable — UIImageView with ScaleAspectFit scales
        // the image down to whatever Modifier size the strip imposes, same as Android's Coil.
        thumbnailContent = pageContent,
        extraTopBarActions = {},
        isReduceMotion = isReduceMotion,
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

private sealed interface PageState {
    data object Loading : PageState
    data class Loaded(val bytes: ByteArray) : PageState
    data object Error : PageState
}

@Suppress("ktlint:standard:function-naming")
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
private fun IosComicPageContent(
    imageSource: com.riffle.core.domain.comic.ComicImageSource,
    pageIndex: Int,
    modifier: Modifier = Modifier,
) {
    var state by remember(pageIndex) { mutableStateOf<PageState>(PageState.Loading) }

    LaunchedEffect(pageIndex) {
        state = PageState.Loading
        val result = withContext(Dispatchers.IO) {
            runCatching { imageSource.imageBytes(pageIndex) }.getOrNull()
        }
        state = if (result != null) PageState.Loaded(result) else PageState.Error
    }

    when (val s = state) {
        is PageState.Loaded -> {
            UIKitView(
                factory = {
                    UIImageView().apply {
                        contentMode = UIViewContentMode.UIViewContentModeScaleAspectFit
                    }
                },
                update = { view ->
                    s.bytes.usePinned { pinned ->
                        val nsData = NSData.create(
                            bytes = pinned.addressOf(0),
                            length = s.bytes.size.toULong(),
                        )
                        view.image = UIImage.imageWithData(nsData)
                    }
                },
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
                Icon(
                    imageVector = RiffleIcons.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
