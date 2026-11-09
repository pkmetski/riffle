package com.riffle.shared.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.library.LibraryItemDetailViewModel
import com.riffle.feature.library.ui.EditLocalFileMetadataDialog
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * iOS host for the shared [EditLocalFileMetadataDialog]. Loads the item from Koin's
 * [LibraryItemDetailViewModel] factory (identical to Android's `AndroidEditMetadataDialogHost`
 * in `MainScreen.kt`) and provides cover picking via [IosPhotoPicker] / [IosCoverImageCopier].
 */
@Composable
internal fun IosEditMetadataDialogHost(
    itemId: String,
    sourceId: String?,
    onDismiss: () -> Unit,
) {
    val vm = koinInject<LibraryItemDetailViewModel>(
        parameters = { parametersOf(itemId, sourceId) },
    )
    val uiState by vm.uiState.collectAsState()
    val readyState = uiState as? LibraryItemDetailUiState.Ready

    val picker = remember { IosPhotoPicker() }

    if (readyState != null) {
        EditLocalFileMetadataDialog(
            item = readyState.item,
            originalItem = readyState.originalItem,
            onPickCover = { onResult ->
                picker.pick { path -> if (path != null) onResult(path) }
            },
            onSave = { title, author, seriesName, seriesIndex, coverPath, clearCoverOverride ->
                vm.saveMetadataOverride(title, author, seriesName, seriesIndex, coverPath, clearCoverOverride)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}
