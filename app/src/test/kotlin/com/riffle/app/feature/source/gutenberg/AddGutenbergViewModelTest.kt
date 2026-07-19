package com.riffle.app.feature.source.gutenberg

import com.riffle.core.data.websource.SingletonWebSourceInstaller
import com.riffle.core.models.SourceType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the install-flow state machine for the zero-config Gutenberg Source install screen.
 * Mirrors [com.riffle.app.feature.source.chitanka.AddChitankaViewModelTest]; both VMs delegate
 * to the generic [SingletonWebSourceInstaller] (ADR 0044 Phase 4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddGutenbergViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `install transitions Idle to Installing to Success on happy path`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        coEvery { installer.install(SourceType.GUTENBERG) } returns "gb-1"
        val vm = AddGutenbergViewModel(installer)

        assertEquals(AddGutenbergViewModel.State.Idle, vm.state.value)

        vm.install()
        assertEquals(AddGutenbergViewModel.State.Installing, vm.state.value)

        advanceUntilIdle()
        assertEquals(AddGutenbergViewModel.State.Success("gb-1"), vm.state.value)
    }

    @Test
    fun `install transitions to Error when installer throws`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        coEvery { installer.install(SourceType.GUTENBERG) } throws RuntimeException("connectivity down")
        val vm = AddGutenbergViewModel(installer)

        vm.install()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Error state, got $s", s is AddGutenbergViewModel.State.Error)
        assertEquals("connectivity down", (s as AddGutenbergViewModel.State.Error).message)
    }

    @Test
    fun `double-invocation while Installing is a no-op — installer runs exactly once`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        var calls = 0
        coEvery { installer.install(SourceType.GUTENBERG) } coAnswers { calls++; "gb-1" }
        val vm = AddGutenbergViewModel(installer)

        vm.install()
        vm.install()
        vm.install()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(AddGutenbergViewModel.State.Success("gb-1"), vm.state.value)
    }
}
