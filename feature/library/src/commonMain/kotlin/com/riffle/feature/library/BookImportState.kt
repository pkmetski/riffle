package com.riffle.feature.library

import com.riffle.core.catalog.CatalogImportPhase

sealed interface BookImportState {
    data object Idle : BookImportState
    data class InProgress(
        val phase: CatalogImportPhase,
        val completedFiles: Int = 0,
        val totalFiles: Int = 0,
    ) : BookImportState
    data object Completed : BookImportState
    data class Failed(val message: String) : BookImportState
}
