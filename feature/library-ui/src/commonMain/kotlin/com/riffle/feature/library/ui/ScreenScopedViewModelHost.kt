package com.riffle.feature.library.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/**
 * Gives a screen-scoped [ViewModel] the `onCleared()` call it would get from a hosting
 * `ViewModelStoreOwner`. Used where screens are composed from a plain `when (route)` with no
 * navigation library on iOS, so nothing else owns a [ViewModelStore].
 *
 * Usage:
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
