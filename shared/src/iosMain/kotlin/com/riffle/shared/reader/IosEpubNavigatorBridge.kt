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

    /**
     * Called when the user taps on the book body (not a link).
     *
     * [x] and [y] are the tap coordinates in the navigator view's coordinate space (points on iOS,
     * same scale as [viewWidth]/[viewHeight]). [viewWidth] and [viewHeight] are the navigator
     * view's current size. All four values are passed so the Kotlin layer can compute edge-zone
     * membership without an extra bridge round-trip.
     */
    fun setTapCallback(callback: ((x: Float, y: Float, viewWidth: Float, viewHeight: Float) -> Unit)?)

    /**
     * Register a callback for figure-tap events posted by `figure-tap.js`.
     *
     * [payload] is the raw JSON string emitted by the JS bridge — the same shape that
     * [com.riffle.feature.reader.FigureTapMessageParser.parse] accepts. Called on the main thread.
     */
    fun setFigureTapCallback(callback: ((payload: String) -> Unit)?)

    /**
     * Register a callback for text-selection changes — the seam that makes annotation *creation*
     * possible at all.
     *
     * Readium-Swift has no "selection changed" delegate method. What it does have is
     * `SelectableNavigatorDelegate.navigator(_:shouldShowMenuForSelection:)`, which
     * `EditingActionsController` invokes from its `selection` property observer every time the
     * WKWebView reports a new selection. The Swift bridge implements it, forwards the selection
     * here and returns `true`, so the reader still gets the system Copy / Look Up / Share menu
     * on top of Riffle's own annotate sheet.
     *
     * [selectionJson] is `null` when the selection is cleared, otherwise:
     * ```json
     * {"locatorJson":"<escaped Readium Locator JSON>","href":"…","text":"…","before":"…",
     *  "after":"…","progression":0.42,"x":12.0,"y":340.0,"width":180.0,"height":22.0}
     * ```
     * The rect is in the navigator view's coordinate space, which is the reader Composable's own
     * space because the navigator fills it — that is what lets the sheet anchor to the selection.
     *
     * Callbacks arrive on the main thread.
     */
    fun setSelectionCallback(callback: ((selectionJson: String?) -> Unit)?)

    /** Drop the current text selection (after the user has acted on it). */
    fun clearSelection()

    /**
     * Register a callback for taps on a decoration — the seam that makes an existing annotation
     * *editable*.
     *
     * [activationJson] is `{"id":"…","group":"…","x":…,"y":…,"width":…,"height":…}`. The id is
     * the decoration id, which is the annotation id (Android's `annotationIdOf` strips a
     * `#segN` suffix for figure-split highlights; iOS emits one decoration per annotation so
     * there is no suffix to strip yet).
     */
    fun setDecorationActivatedCallback(callback: ((activationJson: String) -> Unit)?)

    /**
     * Make decorations in [group] respond to taps.
     *
     * Readium gates this per group: `DecorationGroup.setActivable()` is only run for groups
     * registered through `observeDecorationInteractions`, and `findDecorationTarget` skips every
     * group that is not activable. Without this call a highlight renders and swallows nothing —
     * the tap falls through to `didTapAt` and toggles the chrome instead of opening the sheet.
     *
     * Idempotent per group; call once after the navigator is open.
     */
    fun observeDecorationGroup(group: String)

    /**
     * Read a non-text resource (e.g. image) from the open publication and return its content
     * Base64-encoded, so binary data survives the Kotlin/Swift string boundary.
     *
     * [href] is the URL the WebView reported (Readium's virtual-host form). The Swift
     * implementation strips any readium-origin prefix before looking up in the [Publication].
     * [onResult] receives `null` when the publication is closed, the href is not in the
     * publication, or the resource is empty.
     */
    fun readResourceBase64(href: String, onResult: (base64: String?) -> Unit)

    /**
     * Read a spine resource's raw XHTML out of the open publication.
     *
     * This is what makes the shared annotation domain usable on iOS. Every merge, overlap and
     * CFI decision in `feature:reader` — `locateSnippetInBody`, `computeOverlapMerge`,
     * `buildHighlightCfiRange`, `findEnclosedFiguresInHtml` — is a pure function of the
     * chapter's *source* HTML. Reading it back out of the live WKWebView would not do: Readium
     * has already injected its own scripts and wrapper elements, and Cadence may have wrapped
     * every sentence in a span, so the character offsets would not match the ones Android
     * computes for the same book.
     *
     * [onResult] receives null when the publication is closed or the href is not in the
     * reading order.
     */
    fun readResource(href: String, onResult: (html: String?) -> Unit)

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
     * The open publication's spine, serialised as
     * `{"hrefs":["ch1.xhtml",…],"positionCounts":[12,…]}` — the reading order plus the number of
     * Readium positions in each resource.
     *
     * Both lists are what `buildRailSegments` / `weightSegmentsByChapterLength` need to decide
     * which TOC entries earn a rail segment and how wide each one is; without the counts the
     * shared generator silently degrades to its no-positions fallback and draws a different
     * (usually more collapsed) rail than Android does for the same book.
     *
     * Returns `{"hrefs":[],"positionCounts":[]}` until the publication has been opened and its
     * positions computed — Readium computes them asynchronously, so the reader re-reads this on
     * each page-load event until the lists are non-empty.
     */
    fun getSpineJson(): String

    /**
     * Scroll the visible resource down by [pixels] device pixels and report whether it actually moved.
     *
     * Auto-scroll's only output is a stream of whole-pixel deltas, and this is what consumes them.
     * It goes through `window.scrollBy` in Readium's WKWebView rather than a native scroll for the
     * same reason Android's vertical mode does: Readium owns the scrolling element, and a native
     * scroll on the hosting view is either intercepted or fights the navigator's own pagination.
     *
     * [onResult] receives `false` when the document did not move — the bottom of the resource, or
     * no navigator — which is how the reader knows to stop the ticker instead of spinning.
     */
    fun scrollByPx(pixels: Int, onResult: (moved: Boolean) -> Unit)

    /**
     * Evaluate [script] inside the visible resource's WKWebView and hand the result back as a
     * string, or null when there is no navigator or the script threw.
     *
     * This is the generic twin of Android's `RendererBridge.evaluateJavascript`, and every
     * Cadence JS call goes through it: the `Intl.Segmenter` feature detect, the per-chapter
     * sentence-span tokenisation, the start-position probe, and the paginated column
     * measure/snap. The scripts themselves are the shared ones in `feature:reader`
     * ([com.riffle.feature.reader.cadence.CadenceDomScript], [com.riffle.feature.reader.ColumnSnap])
     * so the two platforms tokenise and snap identically — only the evaluation is host-specific.
     *
     * Result marshalling matches what the shared parsers expect. Android's
     * `WebView.evaluateJavascript` JSON-encodes its return, so a JS string arrives quoted;
     * WKWebView hands back the native value. Every shared parser tolerates both forms
     * (`CadenceInjector.parse`, `CadenceDomScript.parseCadenceStartId`,
     * `ColumnSnap.parseNarratedColumnsResult` all unwrap an optional quote layer), so the Swift
     * side passes strings through verbatim and stringifies booleans as `"true"`/`"false"`.
     */
    fun evaluateJavaScript(script: String, onResult: (result: String?) -> Unit)

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
