package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import com.riffle.core.domain.comic.panel.PanelBoundaryLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelReportViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `setFailureType updates state`() = runTest {
        val vm = makeVm()
        assertNull(vm.state.value.failureType)
        vm.setFailureType(PanelDetectionFailureType.MergedPanels)
        assertEquals(PanelDetectionFailureType.MergedPanels, vm.state.value.failureType)
    }

    @Test
    fun `tap on known panel region selects it`() = runTest {
        val region = PanelRegion(x = 100, y = 100, width = 200, height = 150)
        val vm = makeVm(panels = listOf(region))
        vm.onTap(tappedImageX = 150, tappedImageY = 125)
        assertEquals(0, vm.state.value.tappedPanelIndex)
        assertEquals(150, vm.state.value.tappedX)
        assertEquals(125, vm.state.value.tappedY)
    }

    @Test
    fun `tap outside all panels creates free-point marker`() = runTest {
        val vm = makeVm()
        vm.onTap(tappedImageX = 5, tappedImageY = 5)
        assertNull(vm.state.value.tappedPanelIndex)
        assertEquals(5, vm.state.value.tappedX)
        assertEquals(5, vm.state.value.tappedY)
    }

    @Test
    fun `submit calls repository and sets result`() = runTest {
        var submitted = false
        val repo = object : PanelReportRepository {
            override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> {
                submitted = true
                return Result.success("https://github.com/pkmetski/riffle/issues/1")
            }
        }
        val vm = makeVm(repository = repo)
        vm.setFailureType(PanelDetectionFailureType.MissedPanel)
        vm.submit(maskPng = ByteArray(0))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(submitted)
        assertEquals("https://github.com/pkmetski/riffle/issues/1", vm.state.value.submittedIssueUrl)
    }

    @Test
    fun `addDrawnPanel appends a panel region`() = runTest {
        val vm = makeVm()
        vm.addDrawnPanel(10, 20, 110, 120)
        assertEquals(1, vm.state.value.drawnPanels.size)
        val p = vm.state.value.drawnPanels.first()
        assertEquals(10, p.x); assertEquals(20, p.y)
        assertEquals(100, p.width); assertEquals(100, p.height)
    }

    @Test
    fun `clearLastDrawnPanel removes the most-recent panel`() = runTest {
        val vm = makeVm()
        vm.addDrawnPanel(0, 0, 50, 50)
        vm.addDrawnPanel(60, 60, 110, 110)
        vm.clearLastDrawnPanel()
        assertEquals(1, vm.state.value.drawnPanels.size)
        assertEquals(0, vm.state.value.drawnPanels.first().x)
    }

    @Test
    fun `addDrawnBoundary appends a boundary line`() = runTest {
        val vm = makeVm()
        vm.addDrawnBoundary(0, 100, 799, 100)  // imageWidth=800 → max x = 799
        assertEquals(1, vm.state.value.drawnBoundaries.size)
        val b = vm.state.value.drawnBoundaries.first()
        assertEquals(PanelBoundaryLine(0, 100, 799, 100), b)
    }

    @Test
    fun `clearLastDrawnBoundary removes the most-recent boundary`() = runTest {
        val vm = makeVm()
        vm.addDrawnBoundary(0, 100, 800, 100)
        vm.addDrawnBoundary(0, 200, 800, 200)
        vm.clearLastDrawnBoundary()
        assertEquals(1, vm.state.value.drawnBoundaries.size)
        assertEquals(100, vm.state.value.drawnBoundaries.first().y1)
    }

    @Test
    fun `setFailureType clears drawn panels and boundaries`() = runTest {
        val vm = makeVm()
        vm.addDrawnPanel(0, 0, 50, 50)
        vm.addDrawnBoundary(0, 100, 800, 100)
        vm.setFailureType(PanelDetectionFailureType.FalsePanel)
        assertTrue(vm.state.value.drawnPanels.isEmpty())
        assertTrue(vm.state.value.drawnBoundaries.isEmpty())
    }

    @Test
    fun `SplitPanel routes drawn region to drawnPanels not drawnBoundaries`() = runTest {
        // Regression: SplitPanel must use addDrawnPanel (rectangle), not addDrawnBoundary (line).
        val vm = makeVm()
        vm.setFailureType(PanelDetectionFailureType.SplitPanel)
        vm.addDrawnPanel(10, 20, 110, 120)
        assertEquals(1, vm.state.value.drawnPanels.size)
        assertTrue(vm.state.value.drawnBoundaries.isEmpty())
    }

    @Test
    fun `submit requires failure type`() = runTest {
        val vm = makeVm()
        vm.submit(maskPng = ByteArray(0))
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.submitting)
        assertEquals("Select a failure type before submitting", vm.state.value.error)
    }

    private fun makeVm(
        panels: List<PanelRegion> = emptyList(),
        repository: PanelReportRepository = object : PanelReportRepository {
            override suspend fun submit(r: PanelDetectionReport, m: ByteArray) =
                Result.success("https://github.com/pkmetski/riffle/issues/0")
        },
    ) = PanelReportViewModel(
        bookId = "test-book",
        pageIndex = 0,
        imageWidth = 800,
        imageHeight = 1200,
        detectedPanels = panels,
        detectedSource = PanelSource.Auto,
        repository = repository,
    )
}
