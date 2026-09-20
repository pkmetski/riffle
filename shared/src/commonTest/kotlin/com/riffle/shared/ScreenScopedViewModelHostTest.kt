package com.riffle.shared

import androidx.lifecycle.ViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS has no navigation-supplied `ViewModelStoreOwner`, so leaving a screen never reached
 * `ViewModelScreen.onCleared()`. For the audiobook player that meant `controller.stop()` —
 * and therefore the AVQueuePlayer teardown and the final progress push — never ran.
 */
class ScreenScopedViewModelHostTest {

    private class RecordingViewModel : ViewModel() {
        var clearedCount = 0
            private set

        override fun onCleared() {
            clearedCount++
        }
    }

    @Test
    fun adoptReturnsTheSameInstance() {
        val host = ScreenScopedViewModelHost()
        val vm = RecordingViewModel()
        assertSame(vm, host.adopt(vm))
    }

    @Test
    fun adoptedViewModelIsNotClearedBeforeTheScreenLeaves() {
        val host = ScreenScopedViewModelHost()
        val vm = host.adopt(RecordingViewModel())
        assertFalse(vm.clearedCount > 0, "the ViewModel must stay alive while the screen is composed")
    }

    @Test
    fun clearInvokesOnClearedExactlyOnce() {
        val host = ScreenScopedViewModelHost()
        val vm = host.adopt(RecordingViewModel())
        host.clear()
        assertEquals(1, vm.clearedCount, "leaving the screen must run the ViewModel teardown")
    }

    @Test
    fun clearIsIdempotent() {
        val host = ScreenScopedViewModelHost()
        val vm = host.adopt(RecordingViewModel())
        host.clear()
        host.clear()
        assertTrue(vm.clearedCount >= 1, "teardown must have run")
        assertEquals(1, vm.clearedCount, "a second dispose must not re-run teardown")
    }
}
