package com.riffle.shared.reader

import platform.UIKit.UIViewController

/**
 * Obj-C-compatible seam between iosMain and the Swift-side Readium Swift wrapper.
 *
 * Swift implementation: ReadiumEpubNavigatorBridge (in iosApp/iosApp/).
 * Registered at startup via [IosEpubNavigatorBridgeFactory] passed to startKoin().
 *
 * All callbacks are invoked on the main thread by the Swift implementation.
 */
interface IosEpubNavigatorBridge {
    /** The UIViewController that hosts EPUBNavigatorViewController. Embed via UIKitViewController. */
    fun viewController(): UIViewController

    /** Open the EPUB at [filePath], optionally restoring [locatorJson]. */
    fun openEpub(filePath: String, locatorJson: String?)

    fun goForward()
    fun goBackward()

    /** Navigate to the Readium Locator encoded as JSON. */
    fun goToLocator(locatorJson: String)

    /** Returns the last locator JSON emitted, or null if no position yet. */
    fun snapshotLocatorJson(): String?

    /**
     * Register a callback for position changes.  Called on the main thread with the full
     * Readium Locator serialised to JSON whenever the reader moves to a new position.
     */
    fun setLocatorCallback(callback: ((locatorJson: String) -> Unit)?)

    /** Called once after each chapter finishes loading and layout has settled. */
    fun setPageLoadCallback(callback: (() -> Unit)?)

    /** Called when the user taps on the book body (not a link). */
    fun setTapCallback(callback: (() -> Unit)?)

    /** Release Readium resources. Renamed to avoid clash with NSObject.release on the Swift side. */
    fun disposeNavigator()

    /**
     * Deliver a JSON-encoded decoration list to the Swift-side Readium navigator.
     * [decorationsJson] is a JSON array of decoration objects; [group] is the decoration group
     * identifier (e.g. "highlights", "bookmarks"). See [ReadiumSwiftNavigator] for the schema.
     */
    fun applyDecorations(decorationsJson: String, group: String)
}

/** Factory so Koin can produce one bridge instance per reader open. */
interface IosEpubNavigatorBridgeFactory {
    fun create(): IosEpubNavigatorBridge
}
