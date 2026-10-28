package com.riffle.shared.reader

import com.riffle.feature.reader.SystemBarsController

/**
 * iOS implementation of [SystemBarsController] that bridges to [IosStatusBarBridge].
 *
 * On iOS there is no navigation bar to hide, only the status bar. Immersive behavior is applied
 * by calling [IosStatusBarBridge.setHidden] which notifies the SwiftUI host to toggle
 * `.statusBarHidden()`. [reapplyRaw] is a no-op because the iOS host reads a single published
 * state (no compat-cache-stale issue like Android's WindowInsetsControllerCompat).
 * [applyImmersiveBehavior] is also a no-op — on iOS "sticky immersive" is not a separate
 * behavior flag, the host simply keeps the bar hidden until the reader leaves.
 */
internal object IosSystemBarsController : SystemBarsController {
    override fun hide() { IosStatusBarBridge.setHidden(true) }
    override fun show() { IosStatusBarBridge.setHidden(false) }
    override fun applyImmersiveBehavior() { /* iOS has no equivalent behavior flag */ }
    override fun reapplyRaw() { /* iOS host reads published state directly; no cache to bypass */ }
}
