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

    /**
     * Called when Readium reports a navigator error (e.g. `copyForbidden`). Before #1071 §17 the
     * Swift delegate's `presentError` was an empty body, so these were silently discarded;
     * [ReadiumSwiftNavigator] now logs them on [com.riffle.core.logging.LogChannel.Reader].
     */
    fun setErrorCallback(callback: ((message: String) -> Unit)?)

    /** Release Readium resources. Renamed to avoid clash with NSObject.release on the Swift side. */
    fun disposeNavigator()

    /**
     * Deliver a JSON-encoded decoration list to the Swift-side Readium navigator.
     * [decorationsJson] is a JSON array of decoration objects; [group] is the decoration group
     * identifier (e.g. "highlights", "bookmarks"). See [ReadiumSwiftNavigator] for the schema.
     */
    fun applyDecorations(decorationsJson: String, group: String)

    /**
     * Open an O'Reilly lazy publication: build a Readium Swift [Publication] from [shapeJson]
     * (the serialised [LazyPublicationShape]) backed by [fetcher], then open the navigator.
     * This bypasses file-based EPUB parsing entirely — no download is required.
     *
     * [shapeJson] must be the JSON produced by [IosLazyChapterFetcherImpl.serializeShape].
     */
    fun openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: IosLazyChapterFetcher)

    /**
     * Apply formatting preferences to the Readium Swift navigator.
     *
     * Call before [openEpub]/[openLazyEpub] so the initial render uses the right settings, and
     * again whenever preferences change while a book is open. Thread-safe — the Swift
     * implementation dispatches to the main actor internally.
     *
     */
    fun applyReaderPreferences(preferences: IosReaderPreferences)

    /**
     * Returns the table of contents of the open publication serialised as a JSON array.
     * Each entry: `{"title":"…","href":"…","children":[…]}`.
     * Returns `"[]"` if no publication is open or the TOC is empty.
     */
    fun getTocJson(): String

    /**
     * Start a full-text search over the open publication. [onBatch] is called on the main thread
     * with a JSON array of matches each time Readium returns a page:
     * `[{"locatorJson":"…","snippet":"…"},…]`. [onDone] is called when the search finishes or is
     * cancelled. Call [cancelSearch] to abort early.
     */
    fun startSearch(query: String, onBatch: ((matchesJson: String) -> Unit)?, onDone: (() -> Unit)?)

    /** Cancel the in-progress search started by [startSearch]. No-op if idle. */
    fun cancelSearch()
}

/** Factory so Koin can produce one bridge instance per reader open. */
interface IosEpubNavigatorBridgeFactory {
    fun create(): IosEpubNavigatorBridge
}

/**
 * Everything Readium needs to render a page the way the user's preferences say.
 *
 * One object rather than ten positional parameters: the set grows every time the shared
 * `ReadiumTextStyling` mapping learns something new, and a ten-argument Obj-C selector is both
 * unreadable at the call site and easy to mis-order silently.
 */
data class IosReaderPreferences(
    val fontSizePercent: Float,
    val scrollMode: Boolean,
    /** Readium theme name: "light", "dark" or "sepia". */
    val theme: String,
    /** CSS font-family, or empty to keep the publisher's font. */
    val fontFamilyCss: String,
    /** CSS line-height multiplier; 0.0 means Readium's default. */
    val lineHeightMultiplier: Float,
    /** Margin scale factor; 1.0 is Readium's default. */
    val pageMargins: Double,
    val justifyText: Boolean,
    /** Body text colour as ARGB, or 0 to leave it to the theme. Non-zero only for DarkDim. */
    val textColorArgb: Long,
    /** False lets Riffle's typography win over the publisher's stylesheet. */
    val publisherStyles: Boolean,
    /** Columns to pin, or 0 for Readium's default. Android pins 1 (Readium 3.3.0 decorations). */
    val columnCount: Int,
)
