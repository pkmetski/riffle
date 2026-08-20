package com.riffle.app.feature.reader.cbz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.comic.panel.PanelBoundaryLine
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PanelReportUiState(
    val failureType: PanelDetectionFailureType? = null,
    val notes: String = "",
    val tappedX: Int? = null,
    val tappedY: Int? = null,
    val tappedPanelIndex: Int? = null,
    val drawnPanels: List<PanelRegion> = emptyList(),
    val drawnBoundaries: List<PanelBoundaryLine> = emptyList(),
    val orderedPanelIndices: List<Int> = emptyList(),
    val submitting: Boolean = false,
    val submittedIssueUrl: String? = null,
    val error: String? = null,
)

class PanelReportViewModel(
    val bookId: String,
    val pageIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val detectedPanels: List<PanelRegion>,
    val detectedSource: PanelSource,
    private val repository: PanelReportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PanelReportUiState())
    val state: StateFlow<PanelReportUiState> = _state.asStateFlow()

    fun setFailureType(type: PanelDetectionFailureType) {
        _state.update { it.copy(failureType = type, error = null, drawnPanels = emptyList(), drawnBoundaries = emptyList(), orderedPanelIndices = emptyList()) }
    }

    fun addOrRemoveOrderedPanel(panelIndex: Int) {
        _state.update { s ->
            val existing = s.orderedPanelIndices.indexOf(panelIndex)
            val updated = if (existing >= 0) s.orderedPanelIndices.take(existing) else s.orderedPanelIndices + panelIndex
            s.copy(orderedPanelIndices = updated)
        }
    }

    fun setNotes(notes: String) {
        _state.update { it.copy(notes = notes) }
    }

    fun onTap(tappedImageX: Int, tappedImageY: Int) {
        val panelIndex = detectedPanels.indexOfFirst { p ->
            tappedImageX in p.x until p.right && tappedImageY in p.y until p.bottom
        }.takeIf { it >= 0 }
        _state.update { it.copy(tappedX = tappedImageX, tappedY = tappedImageY, tappedPanelIndex = panelIndex) }
    }

    fun addDrawnPanel(x1: Int, y1: Int, x2: Int, y2: Int) {
        val left = minOf(x1, x2).coerceIn(0, imageWidth - 1)
        val top = minOf(y1, y2).coerceIn(0, imageHeight - 1)
        val right = maxOf(x1, x2).coerceIn(0, imageWidth - 1)
        val bottom = maxOf(y1, y2).coerceIn(0, imageHeight - 1)
        if (right <= left || bottom <= top) return
        val panel = PanelRegion(x = left, y = top, width = right - left, height = bottom - top)
        _state.update { it.copy(drawnPanels = it.drawnPanels + panel) }
    }

    fun clearLastDrawnPanel() {
        _state.update { it.copy(drawnPanels = it.drawnPanels.dropLast(1)) }
    }

    fun addDrawnBoundary(x1: Int, y1: Int, x2: Int, y2: Int) {
        val line = PanelBoundaryLine(
            x1 = x1.coerceIn(0, imageWidth - 1),
            y1 = y1.coerceIn(0, imageHeight - 1),
            x2 = x2.coerceIn(0, imageWidth - 1),
            y2 = y2.coerceIn(0, imageHeight - 1),
        )
        _state.update { it.copy(drawnBoundaries = it.drawnBoundaries + line) }
    }

    fun clearLastDrawnBoundary() {
        _state.update { it.copy(drawnBoundaries = it.drawnBoundaries.dropLast(1)) }
    }

    fun submit(maskPng: ByteArray) {
        val ft = _state.value.failureType
        if (ft == null) {
            _state.update { it.copy(error = "Select a failure type before submitting") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val s = _state.value
            val report = PanelDetectionReport(
                bookId = bookId,
                pageIndex = pageIndex,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                detectedPanels = detectedPanels,
                detectedSource = detectedSource,
                failureType = ft,
                notes = s.notes,
                tappedX = s.tappedX,
                tappedY = s.tappedY,
                tappedPanelIndex = s.tappedPanelIndex,
                drawnPanels = s.drawnPanels,
                drawnBoundaries = s.drawnBoundaries,
                expectedPanelOrder = s.orderedPanelIndices.takeIf { it.isNotEmpty() },
            )
            repository.submit(report, maskPng).fold(
                onSuccess = { url -> _state.update { it.copy(submitting = false, submittedIssueUrl = url) } },
                onFailure = { e -> _state.update { it.copy(submitting = false, error = e.message ?: "Unknown error") } },
            )
        }
    }
}
