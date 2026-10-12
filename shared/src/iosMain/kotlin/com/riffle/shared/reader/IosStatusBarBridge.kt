package com.riffle.shared.reader

/**
 * Bridges the reader's immersive-mode chrome visibility to the iOS host so the system status bar
 * can be hidden / shown in step with the reader chrome — matching Android's
 * `ImmersiveModeState.hide()` / `ImmersiveModeState.show()` calls.
 *
 * The host (ContentView.swift) registers a [Listener] once on launch.  The reader calls
 * [setHidden] whenever `chromeVisible` changes.  Thread safety: Compose runs on the main
 * thread on iOS, so listeners are always invoked on the main thread.
 */
object IosStatusBarBridge {

    interface Listener {
        fun onHiddenChanged(hidden: Boolean)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    internal fun setHidden(hidden: Boolean) {
        listener?.onHiddenChanged(hidden)
    }
}
