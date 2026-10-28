package com.riffle.feature.reader.ui

import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePanelReportRepository(
    private val result: Result<String> = Result.success("https://github.com/test/issues/1"),
) : PanelReportRepository {
    var lastReport: PanelDetectionReport? = null
    var lastMaskPng: ByteArray? = null

    override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> {
        lastReport = report
        lastMaskPng = maskPng
        return result
    }
}

private val samplePanels = listOf(
    PanelRegion(x = 0, y = 0, width = 100, height = 100),
    PanelRegion(x = 100, y = 0, width = 100, height = 100),
)

private fun TestScope.makeVm(
    repository: PanelReportRepository = FakePanelReportRepository(),
    selectMsg: String = "Select a failure type",
    markFalseMsg: String = "Mark a false panel",
): PanelReportViewModel = PanelReportViewModel(
    bookId = "book-1",
    pageIndex = 3,
    imageWidth = 200,
    imageHeight = 100,
    detectedPanels = samplePanels,
    detectedSource = PanelSource.Auto,
    repository = repository,
    selectFailureTypeMessage = selectMsg,
    markFalsePanelMessage = markFalseMsg,
    scope = this,
)

class PanelReportViewModelTest {

    @Test
    fun setFailureType_updatesState() = runTest {
        val vm = makeVm()
        vm.setFailureType(PanelDetectionFailureType.MissedPanel)
        assertEquals(PanelDetectionFailureType.MissedPanel, vm.state.value.failureType)
    }

    @Test
    fun setFailureType_clearsInteractionState() = runTest {
        val vm = makeVm()
        vm.setFailureType(PanelDetectionFailureType.WrongPanelOrder)
        vm.tapForOrder(50, 50)
        vm.setFailureType(PanelDetectionFailureType.MissedPanel)
        val s = vm.state.value
        assertTrue(s.orderedPanelIndices.isEmpty())
        assertTrue(s.drawnPanels.isEmpty())
        assertNull(s.tappedPanelIndex)
        assertNull(s.error)
    }

    @Test
    fun tapForOrder_addsAndRemovesPanelInOrder() = runTest {
        val vm = makeVm()
        vm.setFailureType(PanelDetectionFailureType.WrongPanelOrder)
        vm.tapForOrder(50, 50)
        assertEquals(listOf(0), vm.state.value.orderedPanelIndices)
        vm.tapForOrder(150, 50)
        assertEquals(listOf(0, 1), vm.state.value.orderedPanelIndices)
        // Re-tap panel 1 (at position 1 in [0, 1]): take(1) = [0]
        vm.tapForOrder(150, 50)
        assertEquals(listOf(0), vm.state.value.orderedPanelIndices)
    }

    @Test
    fun toggleFalsePanel_marksAndUnmarks() = runTest {
        val vm = makeVm()
        vm.setFailureType(PanelDetectionFailureType.FalsePanel)
        vm.toggleFalsePanel(50, 50)
        assertTrue(0 in vm.state.value.falsePanelIndices)
        vm.toggleFalsePanel(50, 50)
        assertTrue(0 !in vm.state.value.falsePanelIndices)
    }

    @Test
    fun addDrawnPanel_addsAndClearsLast() = runTest {
        val vm = makeVm()
        vm.addDrawnPanel(10, 10, 50, 50)
        assertEquals(1, vm.state.value.drawnPanels.size)
        vm.addDrawnPanel(20, 20, 60, 60)
        assertEquals(2, vm.state.value.drawnPanels.size)
        vm.clearLastDrawnPanel()
        assertEquals(1, vm.state.value.drawnPanels.size)
    }

    @Test
    fun addDrawnBoundary_addsAndClearsLast() = runTest {
        val vm = makeVm()
        vm.addDrawnBoundary(0, 50, 200, 50)
        assertEquals(1, vm.state.value.drawnBoundaries.size)
        vm.clearLastDrawnBoundary()
        assertTrue(vm.state.value.drawnBoundaries.isEmpty())
    }

    @Test
    fun submit_withoutFailureType_setsError() = runTest {
        val repo = FakePanelReportRepository()
        val vm = makeVm(repository = repo, selectMsg = "pick type")
        vm.submit(ByteArray(0))
        assertEquals("pick type", vm.state.value.error)
        assertNull(repo.lastReport)
    }

    @Test
    fun submit_falsePanel_withoutMarkedPanels_setsError() = runTest {
        val repo = FakePanelReportRepository()
        val vm = makeVm(repository = repo, markFalseMsg = "mark panel")
        vm.setFailureType(PanelDetectionFailureType.FalsePanel)
        vm.submit(ByteArray(0))
        assertEquals("mark panel", vm.state.value.error)
        assertNull(repo.lastReport)
    }

    @Test
    fun submit_success_setsIssueUrl() = runTest {
        val repo = FakePanelReportRepository(Result.success("https://github.com/test/issues/42"))
        val vm = makeVm(repository = repo)
        vm.setFailureType(PanelDetectionFailureType.MissedPanel)
        vm.addDrawnPanel(0, 0, 50, 50)
        vm.submit(ByteArray(4))
        testScheduler.advanceUntilIdle()
        assertEquals("https://github.com/test/issues/42", vm.state.value.submittedIssueUrl)
        assertNull(vm.state.value.error)
        assertNotNull(repo.lastReport)
    }

    @Test
    fun submit_failure_setsError() = runTest {
        val repo = FakePanelReportRepository(Result.failure(RuntimeException("network error")))
        val vm = makeVm(repository = repo)
        vm.setFailureType(PanelDetectionFailureType.MissedPanel)
        vm.submit(ByteArray(0))
        testScheduler.advanceUntilIdle()
        assertEquals("network error", vm.state.value.error)
        assertNull(vm.state.value.submittedIssueUrl)
    }

    @Test
    fun panelIndexAt_hitsCorrectPanel() = runTest {
        val vm = makeVm()
        vm.onTap(50, 50)
        assertEquals(0, vm.state.value.tappedPanelIndex)
        vm.onTap(150, 50)
        assertEquals(1, vm.state.value.tappedPanelIndex)
    }

    @Test
    fun panelIndexAt_misses_returnsNull() = runTest {
        val vm = makeVm()
        // y=100 is outside all panels (panels span y 0..99)
        vm.onTap(50, 100)
        assertNull(vm.state.value.tappedPanelIndex)
    }
}
