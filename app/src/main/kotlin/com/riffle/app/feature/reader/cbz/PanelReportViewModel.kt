package com.riffle.app.feature.reader.cbz

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        _state.update { it.copy(failureType = type, error = null) }
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

    fun submit(maskPng: ByteArray) {
        val ft = _state.value.failureType
        if (ft == null) {
            _state.update { it.copy(error = "Select a failure type before submitting") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val report = PanelDetectionReport(
                bookId = bookId,
                pageIndex = pageIndex,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                detectedPanels = detectedPanels,
                detectedSource = detectedSource,
                failureType = ft,
                notes = _state.value.notes,
                tappedX = _state.value.tappedX,
                tappedY = _state.value.tappedY,
                tappedPanelIndex = _state.value.tappedPanelIndex,
            )
            repository.submit(report, maskPng).fold(
                onSuccess = { url -> _state.update { it.copy(submitting = false, submittedIssueUrl = url) } },
                onFailure = { e -> _state.update { it.copy(submitting = false, error = e.message ?: "Unknown error") } },
            )
        }
    }
}
