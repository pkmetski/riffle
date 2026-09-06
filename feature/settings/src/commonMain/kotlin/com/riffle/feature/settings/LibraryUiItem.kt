package com.riffle.feature.settings

import com.riffle.core.models.Library

data class LibraryUiItem(
    val library: Library,
    val isVisible: Boolean,
    val switchEnabled: Boolean,
)
