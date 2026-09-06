package com.riffle.feature.library

import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import kotlinx.coroutines.flow.StateFlow

interface BookImportManager {
    val states: StateFlow<Map<String, BookImportState>>
    fun start(
        key: String,
        work: suspend (
            onProgress: (CatalogImportProgress) -> Unit,
            claimItem: (String) -> Boolean,
        ) -> CatalogImportResult,
    )
}
