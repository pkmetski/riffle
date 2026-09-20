package com.riffle.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/**
 * Gives a screen-scoped [ViewModel] the `onCleared()` call it would get from a hosting
 * `ViewModelStoreOwner`.
 *
 * The iOS app composes its screens from a plain `when (route)` with no navigation library, and the
 * ViewModels come out of Koin as `factory` instances, so nothing owned a [ViewModelStore] and
 * `onCleared()` never ran. For the audiobook player that meant `controller.stop()` (and therefore
 * `bridge.dispose()`), the progress push and the now-playing clear were all skipped on Back: audio
 * kept playing and a second `AVQueuePlayer` could be created on top of the first.
 *
 * Usage from a Composable:
 * ```
 * val host = remember(key) { ScreenScopedViewModelHost() }
 * val vm = remember(key) { host.adopt(koin.get<FooViewModel> { parametersOf(...) }) }
 * DisposableEffect(key) { onDispose { host.clear() } }
 * ```
 */
class ScreenScopedViewModelHost {

    private val store = ViewModelStore()

    /** Puts [viewModel] under this host's store so [clear] will call its `onCleared()`. */
    fun <T : ViewModel> adopt(viewModel: T): T {
        store.put(KEY, viewModel)
        return viewModel
    }

    /** Clears the store, invoking `onCleared()` on the adopted ViewModel. Idempotent. */
    fun clear() {
        store.clear()
    }

    private companion object {
        const val KEY = "riffle-screen-scoped-vm"
    }
}
