package com.riffle.feature.source.ui.websource

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore

/**
 * Gives a screen-scoped [ViewModel] the `onCleared()` call it would get from a hosting
 * `ViewModelStoreOwner`. Used by [UnboundedBrowseScreen] which is composed from a plain
 * `when (route)` with no navigation library on iOS, so nothing else owns a [ViewModelStore].
 *
 * Usage:
 * ```
 * val host = remember(key) { ScreenScopedViewModelHost() }
 * val vm = remember(key) { host.adopt(koin.get<FooViewModel> { parametersOf(...) }) }
 * DisposableEffect(key) { onDispose { host.clear() } }
 * ```
 */
internal class ScreenScopedViewModelHost {

    private val store = ViewModelStore()

    fun <T : ViewModel> adopt(viewModel: T): T {
        store.put(KEY, viewModel)
        return viewModel
    }

    fun clear() {
        store.clear()
    }

    private companion object {
        const val KEY = "riffle-screen-scoped-vm"
    }
}
