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

    /**
     * The scope is the *screen*, not the app. Every host owns its own `ViewModelStore`, so
     * another screen being opened and left must not reach this screen's ViewModel — and this
     * screen being left must.
     *
     * The tempting wrong fix for the missing-`ViewModelStoreOwner` defect is one process-wide
     * store; because every host writes under the same key, that would make the second screen's
     * `put` clear the first screen's ViewModel and the second screen's `clear` stop a player the
     * user is still on. Sharing a store turns the first assertion red; dropping the teardown
     * turns the second one red.
     */
    @Test
    fun adoptedViewModelIsNotClearedBeforeTheScreenLeaves() {
        val host = ScreenScopedViewModelHost()
        val vm = host.adopt(RecordingViewModel())

        val otherScreen = ScreenScopedViewModelHost()
        otherScreen.adopt(RecordingViewModel())
        otherScreen.clear()
        assertFalse(vm.clearedCount > 0, "another screen's teardown must not clear this screen's ViewModel")

        host.clear()
        assertEquals(1, vm.clearedCount, "leaving this screen must run its ViewModel teardown")
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
