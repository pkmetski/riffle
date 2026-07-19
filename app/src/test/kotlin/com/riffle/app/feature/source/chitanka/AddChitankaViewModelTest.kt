package com.riffle.app.feature.source.chitanka

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
 * Pins the install-flow state machine for the zero-config Chitanka Source install screen.
 *
 * - Idle → Installing → Success on happy path.
 * - Idle → Installing → Error on installer failure.
 * - Double-invocation while Installing / after Success is a no-op — no second network call, no
 *   duplicate source rows if the user rage-taps "Add source" mid-flight or after landing on the
 *   Success state (before the screen navigates away).
 *
 * Post-ADR-0044: the VM delegates to the generic [SingletonWebSourceInstaller], passing
 * [SourceType.CHITANKA]. The mock in these tests is stubbed on the descriptor-typed
 * `install(CHITANKA)` overload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddChitankaViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `install transitions Idle to Installing to Success on happy path`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        coEvery { installer.install(SourceType.CHITANKA) } returns "chit-1"
        val vm = AddChitankaViewModel(installer)

        assertEquals(AddChitankaViewModel.State.Idle, vm.state.value)

        vm.install()
        assertEquals(AddChitankaViewModel.State.Installing, vm.state.value)

        advanceUntilIdle()
        assertEquals(AddChitankaViewModel.State.Success("chit-1"), vm.state.value)
    }

    @Test
    fun `install transitions to Error when installer throws`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        coEvery { installer.install(SourceType.CHITANKA) } throws RuntimeException("connectivity down")
        val vm = AddChitankaViewModel(installer)

        vm.install()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue("expected Error state, got $s", s is AddChitankaViewModel.State.Error)
        assertEquals("connectivity down", (s as AddChitankaViewModel.State.Error).message)
    }

    @Test
    fun `double-invocation while Installing is a no-op — installer runs exactly once`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        var calls = 0
        coEvery { installer.install(SourceType.CHITANKA) } coAnswers { calls++; "chit-1" }
        val vm = AddChitankaViewModel(installer)

        vm.install()  // enters Installing
        vm.install()  // ignored — still Installing
        vm.install()  // ignored — still Installing
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(AddChitankaViewModel.State.Success("chit-1"), vm.state.value)
    }

    @Test
    fun `re-invocation after Success is a no-op — no re-install`() = runTest(dispatcher) {
        val installer = mockk<SingletonWebSourceInstaller>()
        var calls = 0
        coEvery { installer.install(SourceType.CHITANKA) } coAnswers { calls++; "chit-1" }
        val vm = AddChitankaViewModel(installer)

        vm.install(); advanceUntilIdle()
        vm.install(); advanceUntilIdle()

        assertEquals(1, calls)
    }
}
