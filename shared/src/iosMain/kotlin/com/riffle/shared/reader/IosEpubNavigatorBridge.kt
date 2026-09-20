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
     * @param fontSizePercent Scale factor relative to the EPUB's default font size (1.0 = 100%).
     * @param scrollMode True for vertical scroll (Readium "scroll" preference), false for paginated columns.
     * @param theme One of: "light", "dark", "sepia". "dim" maps to "dark" (Readium has no Dim variant).
     * @param fontFamilyCss CSS font-family string, or empty string to keep the publisher's font.
     * @param lineHeightMultiplier CSS line-height multiplier (e.g. 1.2). 0.0 means use Readium default.
     * @param pageMargins Margin scale factor (1.0 = default). Maps to Readium pageMargins preference.
     * @param justifyText True to apply `text-align: justify`.
     * @param textColorArgb Body text colour as ARGB, or 0 to leave it to the theme. Non-zero only
     *   for DarkDim, whose muted body colour is what distinguishes it from Dark.
     * @param publisherStyles False to let Riffle's typography win over the publisher's stylesheet,
     *   which is what makes line-height and text-align take effect at all.
     * @param columnCount Column count to pin, or 0 for Readium's default. Android pins 1 because
     *   Readium 3.3.0's two-column default mispositions decorations.
     */
    fun applyReaderPreferences(
        fontSizePercent: Float,
        scrollMode: Boolean,
        theme: String,
        fontFamilyCss: String,
        lineHeightMultiplier: Float,
        pageMargins: Double,
        justifyText: Boolean,
        textColorArgb: Long,
        publisherStyles: Boolean,
        columnCount: Int,
    )

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
