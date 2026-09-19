package com.riffle.app.feature.library

import androidx.annotation.StringRes
import com.riffle.app.R
import com.riffle.feature.library.BookImportState

@StringRes
internal fun bookImportSnackbarMessage(state: BookImportState): Int? =
    if (state is BookImportState.Completed) R.string.ui_upload_completed else null
