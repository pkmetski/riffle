package com.riffle.app.feature.reader

// Hot path (every scroll event): save CFI only — no writes that trigger Room invalidation on
// library_items. Cold path (once per session close): save CFI + update the progress float.
class PositionSaveCoordinator(
    private val savePosition: suspend (cfi: String) -> Unit,
    private val updateProgress: suspend (progress: Float) -> Unit,
) {
    suspend fun onChanged(cfi: String) {
        savePosition(cfi)
    }

    suspend fun onClose(cfi: String, progress: Float) {
        savePosition(cfi)
        updateProgress(progress)
    }
}
