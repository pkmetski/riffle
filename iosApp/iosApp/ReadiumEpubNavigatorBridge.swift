import UIKit
import WebKit
import Riffle
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

// MARK: - ReadiumEpubNavigatorBridge

/// Implements IosEpubNavigatorBridge (generated from Kotlin iosMain's IosEpubNavigatorBridge).
/// The spine payload a bridge reports before a publication is open, and again after dispose.
private let emptySpineJson = "{\"hrefs\":[],\"positionCounts\":[]}"

/// Wraps Readium Swift 3.x EPUBNavigatorViewController, bridging it to the KMP shared layer.
@objc class ReadiumEpubNavigatorBridge: NSObject, IosEpubNavigatorBridge {

    private let hostViewController = UIViewController()
    private var epubNavigator: EPUBNavigatorViewController?
    private var publication: Publication?
    // Cached on main thread by the delegate; read from snapshotLocatorJson() on any thread.
    private var cachedLocatorJson: String?
    // TOC fetched asynchronously after open; getTocJson() returns from this cache.
    private var cachedTocJson: String = "[]"
    // Reading order + per-resource position counts, fetched asynchronously after open;
    // getSpineJson() returns from this cache. Readium computes positions off the main actor and
    // it can take a moment on a large EPUB, so the reader re-reads this until it stops being empty.
    private var cachedSpineJson: String = emptySpineJson

    // Callbacks registered by ReadiumSwiftNavigator
    private var locatorCallback: ((String) -> Void)?
    private var pageLoadCallback: (() -> Void)?
    private var tapCallback: (() -> Void)?
    private var errorCallback: ((String) -> Void)?
    private var selectionCallback: ((String?) -> Void)?
    private var decorationActivatedCallback: ((String) -> Void)?
    private var figureTapCallback: ((String) -> Void)?
    /// WKUserContentControllers that have had the RiffleFigureBridge message handler registered.
    /// Held weakly so the WKWebView lifecycle is not extended; cleared on disposeNavigator to
    /// remove the handler and break the retain cycle that WKUserContentController's strong
    /// reference to a WKScriptMessageHandler would otherwise create.
    private var figureHandlerControllers: NSHashTable<WKUserContentController> = .weakObjects()
    /// Decoration groups the Kotlin side asked to make tappable. Re-registered on every open,
    /// because `observeDecorationInteractions` lives on the navigator instance, not on us.
    private var activableGroups: Set<String> = []
    /// Seam for `presentExternalURL`. Production opens the URL in Safari; tests swap it to observe.
    var urlOpener: (URL) -> Void = { UIApplication.shared.open($0) }
    // Track the last-loaded resource href so pageLoadCallback fires only on resource
    // boundary crossings (chapter/spread loads), not on every intra-resource scroll event.
    private var lastLoadedHref: String?

    // Pending preferences — stored before the navigator exists so openEpub can use them.
    private var pendingPreferences: EPUBPreferences = EPUBPreferences()

    // Active search task — cancelled on cancelSearch() and on each new startSearch() call.
    private var activeSearchTask: Task<Void, Never>?

    // Test observation properties
    fileprivate var _lastAppliedDecorationsJson: String?
    fileprivate var _lastAppliedGroup: String?

    // MARK: - IosEpubNavigatorBridge

    func viewController() -> UIViewController { hostViewController }

    func openEpub(filePath: String, locatorJson: String?) {
        Task { @MainActor in
            do {
                guard let fileURL = FileURL(path: filePath, isDirectory: false) else { return }

                // Readium 3.x: retrieve an Asset from a local file, then open into a Publication.
                let httpClient = DefaultHTTPClient()
                let assetRetriever = AssetRetriever(httpClient: httpClient)
                let assetResult = await assetRetriever.retrieve(url: fileURL)
                guard case .success(let asset) = assetResult else { return }

                let opener = PublicationOpener(parser: CompositePublicationParser([EPUBParser()]))
                let pubResult = await opener.open(asset: asset, allowUserInteraction: false)
                guard case .success(let pub) = pubResult else { return }
                self.publication = pub
                self.prefetchToc(pub)
                self.prefetchSpine(pub)

                var initialLocator: Locator?
                if let json = locatorJson,
                   let jsonValue = try? JSONValue(jsonString: json) {
                    initialLocator = try? Locator(json: jsonValue, warnings: nil)
                }

                try self.attach(publication: pub, initialLocator: initialLocator)
            } catch {
                // Ignore open errors — reader shows blank state
            }
        }
    }

    /// Build the navigator, install it in the host controller and re-register every decoration
    /// group the Kotlin side asked to observe.
    ///
    /// Shared by the file-based and lazy (O'Reilly) open paths so the two cannot drift: before
    /// this existed the lazy path was a copy of the file path, and any wiring added to one would
    /// silently not apply to the other.
    @MainActor
    private func attach(publication pub: Publication, initialLocator: Locator?) throws {
        let config = EPUBNavigatorViewController.Configuration(
            preferences: pendingPreferences,
            decorationTemplates: RiffleDecorationTemplates.all()
        )
        let navigator = try EPUBNavigatorViewController(
            publication: pub,
            initialLocation: initialLocator,
            config: config
        )
        navigator.delegate = self
        epubNavigator = navigator
        hostViewController.addChild(navigator)
        navigator.view.frame = hostViewController.view.bounds
        navigator.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        hostViewController.view.addSubview(navigator.view)
        navigator.didMove(toParent: hostViewController)
        for group in activableGroups {
            registerDecorationObserver(navigator, group: group)
        }
    }

    func goForward() {
        Task { @MainActor in _ = await epubNavigator?.goForward(options: .animated) }
    }

    func goBackward() {
        Task { @MainActor in _ = await epubNavigator?.goBackward(options: .animated) }
    }

    func goToLocator(locatorJson: String) {
        guard let jsonValue = try? JSONValue(jsonString: locatorJson),
              let locator = try? Locator(json: jsonValue, warnings: nil) else { return }
        Task { @MainActor in _ = await epubNavigator?.go(to: locator, options: .animated) }
    }

    /// Thread-safe: returns the cached locator JSON last emitted by the delegate on the main thread.
    func snapshotLocatorJson() -> String? { cachedLocatorJson }

    func setLocatorCallback(callback: ((String) -> Void)?) {
        locatorCallback = callback
    }

    func setPageLoadCallback(callback: (() -> Void)?) {
        pageLoadCallback = callback
    }

    func setTapCallback(callback: (() -> Void)?) {
        tapCallback = callback
    }

    func setFigureTapCallback(callback: ((String) -> Void)?) {
        figureTapCallback = callback
    }

    func setErrorCallback(callback: ((String) -> Void)?) {
        errorCallback = callback
    }

    func openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: any IosLazyChapterFetcher) {
        Task { @MainActor in
            do {
                let (pub, _) = try OReillyPublicationBuilder.build(shapeJson: shapeJson, fetcher: fetcher)
                self.publication = pub
                self.prefetchToc(pub)
                self.prefetchSpine(pub)

                var initialLocator: Locator?
                if let json = locatorJson,
                   let jsonValue = try? JSONValue(jsonString: json) {
                    initialLocator = try? Locator(json: jsonValue, warnings: nil)
                }

                try self.attach(publication: pub, initialLocator: initialLocator)
            } catch {
                // Ignore open errors — reader shows blank state
            }
        }
    }

    func disposeNavigator() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.epubNavigator?.willMove(toParent: nil)
            self.epubNavigator?.view.removeFromSuperview()
            self.epubNavigator?.removeFromParent()
            self.epubNavigator = nil
            self.publication = nil
            self.cachedLocatorJson = nil
            self.cachedTocJson = "[]"
            self.cachedSpineJson = emptySpineJson
            // Remove the figure-tap message handler from every spread's WKWebView to break the
            // strong WKUserContentController → WKScriptMessageHandler retain cycle.
            for controller in self.figureHandlerControllers.allObjects {
                controller.removeScriptMessageHandler(forName: "RiffleFigureBridge")
            }
            self.figureHandlerControllers.removeAllObjects()
        }
    }

    func applyDecorations(decorationsJson: String, group: String) {
        _lastAppliedDecorationsJson = decorationsJson
        _lastAppliedGroup = group
        Task { @MainActor in
            guard let nav = epubNavigator else { return }
            let decorations = parseDecorations(decorationsJson)
            nav.apply(decorations: decorations, in: group)
            if group == ReaderDecorationGroups.shared.noteGlyphs, !decorations.isEmpty {
                // Readium lays decorations out on its own animation frame, so the clamp has to
                // run after — it retries for a bounded number of frames on its own.
                _ = await nav.evaluateJavaScript(RiffleDecorationTemplates.noteGlyphClampJs())
            }
        }
    }

    func applyReaderPreferences(preferences: IosReaderPreferences) {
        // Theme strings are owned by the Kotlin layer (IosEpubReaderScreen.kt).
        // This switch is a Readium-Swift type adapter only — move any string-value logic there.
        let resolvedTheme: Theme? = switch preferences.theme {
        case "dark": .dark
        case "sepia": .sepia
        default: .light
        }

        var fontFamily: FontFamily? = nil
        if !preferences.fontFamilyCss.isEmpty {
            fontFamily = FontFamily(rawValue: preferences.fontFamilyCss)
        }

        let textAlign: TextAlignment? = preferences.justifyText ? .justify : nil
        let lineHeight: Double? = preferences.lineHeightMultiplier > 0 ? Double(preferences.lineHeightMultiplier) : nil

        // 0 means "leave it to the theme". Non-zero only for DarkDim, whose muted body colour is
        // the only thing distinguishing it from Dark — without this it renders as plain Dark.
        let textColor: ReadiumNavigator.Color? = preferences.textColorArgb != 0
            ? ReadiumNavigator.Color(uiColor: UIColor(argb: preferences.textColorArgb))
            : nil
        // 0 means "Readium's default". Android pins one column because Readium 3.3.0's
        // two-column default mispositions decorations.
        let columns: ColumnCount? = switch preferences.columnCount {
        case 1: .one
        case 2: .two
        default: nil
        }

        let prefs = EPUBPreferences(
            columnCount: columns,
            fontFamily: fontFamily,
            fontSize: Double(preferences.fontSizePercent),
            lineHeight: lineHeight,
            pageMargins: preferences.pageMargins > 0 ? preferences.pageMargins : nil,
            publisherStyles: preferences.publisherStyles,
            scroll: preferences.scrollMode,
            textAlign: textAlign,
            textColor: textColor,
            theme: resolvedTheme
        )
        pendingPreferences = prefs
        Task { @MainActor in
            epubNavigator?.submitPreferences(prefs)
        }
    }

    func getTocJson() -> String { cachedTocJson }

    private func prefetchToc(_ pub: Publication) {
        Task { @MainActor in
            guard case .success(let links) = await pub.tableOfContents(), !links.isEmpty else { return }
            self.cachedTocJson = self.serializeTocLinks(links)
        }
    }

    func startSearch(
        query: String,
        onBatch: ((String) -> Void)?,
        onDone: (() -> Void)?
    ) {
        activeSearchTask?.cancel()
        guard let pub = publication else { onDone?(); return }
        activeSearchTask = Task { @MainActor in
            guard let service = pub.findService(SearchService.self) else { onDone?(); return }
            let searchResult = await service.search(query: query, options: SearchOptions())
            guard case .success(let iterator) = searchResult else { onDone?(); return }
            defer { iterator.close() }
            while !Task.isCancelled {
                let batchResult = await iterator.next()
                guard case .success(let collection) = batchResult, let collection else { break }
                if collection.locators.isEmpty { continue }
                let batchJson = serializeSearchMatches(collection.locators)
                onBatch?(batchJson)
            }
            onDone?()
        }
    }

    func cancelSearch() {
        activeSearchTask?.cancel()
        activeSearchTask = nil
    }

    private func serializeTocLinks(_ links: [Link]) -> String {
        let items = links.map { serializeTocLink($0) }.joined(separator: ",")
        return "[\(items)]"
    }

    private func serializeTocLink(_ link: Link) -> String {
        let title = (link.title ?? "").jsonEscaped
        let href = link.href.jsonEscaped
        let children = link.children.isEmpty ? "[]" : serializeTocLinks(link.children)
        return #"{"title":"\#(title)","href":"\#(href)","children":\#(children)}"#
    }

    private func serializeSearchMatches(_ locators: [Locator]) -> String {
        let items = locators.compactMap { locator -> String? in
            guard let locatorJson = try? locator.jsonString() else { return nil }
            let snippet = (locator.text.highlight ?? "").jsonEscaped
            let escaped = locatorJson.jsonEscaped
            return #"{"locatorJson":"\#(escaped)","snippet":"\#(snippet)"}"#
        }.joined(separator: ",")
        return "[\(items)]"
    }

    // Internal (not private) so the unit-test target, which compiles this file directly,
    // can assert that malformed decoration JSON parses to zero decorations.
    func parseDecorations(_ json: String) -> [Decoration] {
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array.flatMap { parseDecoration($0) }
    }

    /// One payload entry can produce more than one `Decoration`: an emphasis row carrying both
    /// `underline` and `strike` needs a decoration per style, because a Readium decoration has
    /// exactly one style.
    private func parseDecoration(_ dict: [String: Any]) -> [Decoration] {
        guard let id = dict["id"] as? String,
              let type = dict["type"] as? String,
              let locatorDict = dict["locator"] as? [String: Any],
              let locatorData = try? JSONSerialization.data(withJSONObject: locatorDict),
              let locatorString = String(data: locatorData, encoding: .utf8),
              let jsonValue = try? JSONValue(jsonString: locatorString),
              let locator = try? Locator(json: jsonValue, warnings: nil)
        else { return [] }

        switch type {
        case "highlight":
            // The fallback comes from Kotlin, not from a literal here. The old "#FFFF00"/0.4 pair
            // had drifted away from HighlightColor.DEFAULT and would have painted a colour the
            // palette does not contain, while reading as correct in review.
            let defaults = ReaderHighlightDefaults.shared
            let colorHex = dict["color"] as? String ?? defaults.highlightHex
            let alpha = (dict["alpha"] as? NSNumber)?.floatValue ?? defaults.highlightAlpha
            let tint = UIColor(hex: colorHex).withAlphaComponent(CGFloat(alpha))
            return [Decoration(id: id, locator: locator, style: .highlight(tint: tint))]
        case "bookmark":
            // A gutter bar, not a wash: Android marks a bookmarked page with a corner ribbon and
            // never paints over the text, and the `fragmentAnchor` in this locator means the bar
            // lands on the paragraph the reader actually bookmarked.
            return [Decoration(
                id: id,
                locator: locator,
                style: Decoration.Style(
                    id: RiffleDecorationTemplates.sidemarkStyleId,
                    config: Decoration.Style.HighlightConfig(tint: .systemBlue)
                )
            )]
        case "noteGlyph":
            return [Decoration(
                id: id,
                locator: locator,
                style: Decoration.Style(id: RiffleDecorationTemplates.noteGlyphStyleId, config: nil)
            )]
        case "searchMark":
            let isCurrent = dict["isCurrent"] as? Bool ?? false
            let base = isCurrent ? UIColor.systemYellow : UIColor.systemGray
            return [Decoration(
                id: id,
                locator: locator,
                style: .highlight(tint: base.withAlphaComponent(0.5))
            )]
        case "emphasis":
            // Only the two styles that can be drawn over text without changing its metrics reach
            // here; bold and italic reflow the line and are applied by EmphasisDomInjector.
            let tokens = (dict["styles"] as? String ?? "")
                .split(separator: ",")
                .map(String.init)
            return tokens.compactMap { token in
                let styleId: Decoration.Style.Id
                switch token {
                case "underline": styleId = .underline
                case "strike": styleId = RiffleDecorationTemplates.strikeStyleId
                default: return nil
                }
                return Decoration(
                    id: "\(id)#\(token)",
                    locator: locator,
                    style: Decoration.Style(
                        id: styleId,
                        config: Decoration.Style.HighlightConfig(tint: .label)
                    )
                )
            }
        default:
            return []
        }
    }
}

// MARK: - EPUBNavigatorDelegate

extension ReadiumEpubNavigatorBridge: EPUBNavigatorDelegate {
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        guard let json = try? locator.jsonString() else { return }
        cachedLocatorJson = json
        locatorCallback?(json)
        emitSelectionIfCleared()
        let href = locator.href.string
        if href != lastLoadedHref {
            lastLoadedHref = href
            pageLoadCallback?()
        }
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        emitSelectionIfCleared()
        tapCallback?()
    }

    /// Called by Readium for each spread's WKWebView before its page content loads.
    ///
    /// Injects a thin shim that maps Android's `window.RiffleFigureBridge.onFigureTap(payload)`
    /// call convention to WKWebView's `window.webkit.messageHandlers.*` API, then registers
    /// `self` as the `WKScriptMessageHandler` so the message arrives in `userContentController`.
    ///
    /// The message handler name must match `FigureTapScript.PAGED_BRIDGE_NAME` ("RiffleFigureBridge").
    func navigator(
        _ navigator: EPUBNavigatorViewController,
        setupUserScripts userContentController: WKUserContentController
    ) {
        let shim = """
            window.RiffleFigureBridge = {
                onFigureTap: function(p) {
                    window.webkit.messageHandlers.RiffleFigureBridge.postMessage(p);
                }
            };
            """
        userContentController.add(self, name: "RiffleFigureBridge")
        userContentController.addUserScript(WKUserScript(
            source: shim,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        ))
        figureHandlerControllers.add(userContentController)
    }

    /// Report a *cleared* selection.
    ///
    /// `shouldShowMenuForSelection` only fires for a new, non-nil selection —
    /// `EditingActionsController` sets `isEnabled = false` and notifies nobody when the WKWebView
    /// reports the selection gone. Without this the annotate sheet would stay on screen over a
    /// page the user has already deselected, or already turned away from. These two delegate
    /// callbacks are exactly the moments a selection can disappear: a tap elsewhere, and a page
    /// turn.
    private func emitSelectionIfCleared() {
        guard epubNavigator?.currentSelection == nil else { return }
        selectionCallback?(nil)
    }

    /// Tapping an external link in a book did nothing until #1071 §17 — this delegate method was
    /// an empty stub. Android hands the URL to `Intent.ACTION_VIEW`
    /// (`EpubReaderScreen.kt:1625-1632`); the iOS equivalent is `UIApplication.open`.
    func navigator(_ navigator: Navigator, presentExternalURL url: URL) {
        urlOpener(url)
    }

    /// Navigator errors (e.g. `.copyForbidden`) were swallowed by an empty stub. Forward them to
    /// the Kotlin side so they reach the RIFFLE_READER log channel instead of vanishing.
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        errorCallback?(String(describing: error))
    }

    /// The selection seam.
    ///
    /// Readium-Swift has no "selection changed" delegate callback. `EditingActionsController`
    /// does, however, ask this question from its `selection` property observer every time the
    /// WKWebView reports a new selection, which makes it the one place the host learns that the
    /// user selected text. Forwarding it here is what gives iOS an annotate affordance at all.
    ///
    /// Returns `true`: Riffle's sheet is shown *alongside* the system menu rather than replacing
    /// it, so Copy / Look Up / Share keep working. Returning `false` (the documented
    /// custom-pop-up route) would suppress the system menu and silently remove three features to
    /// add one.
    func navigator(_ navigator: SelectableNavigator, shouldShowMenuForSelection selection: Selection) -> Bool {
        if let json = Self.selectionJson(locator: selection.locator, frame: selection.frame) {
            selectionCallback?(json)
        }
        return true
    }
}

// MARK: - WKScriptMessageHandler (figure-tap bridge)

extension ReadiumEpubNavigatorBridge: WKScriptMessageHandler {
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == "RiffleFigureBridge",
              let body = message.body as? String else { return }
        figureTapCallback?(body)
    }
}

// MARK: - Test helpers

extension ReadiumEpubNavigatorBridge {
    @objc func simulateLocatorUpdate(_ json: String) {
        cachedLocatorJson = json
        locatorCallback?(json)
        pageLoadCallback?()
    }
    @objc func simulatePageLoad() { pageLoadCallback?() }
    @objc func simulateTap() { tapCallback?() }
    @objc func simulateFigureTap(_ payload: String) { figureTapCallback?(payload) }
    @objc func simulateNavigatorError(_ message: String) { errorCallback?(message) }
    @objc func simulateSelection(_ json: String?) { selectionCallback?(json) }
    @objc func simulateDecorationActivated(_ json: String) { decorationActivatedCallback?(json) }

    /// The groups `observeDecorationGroup` has been asked to make tappable. Readium only
    /// dispatches taps for registered groups, so a test that asserts "the highlight is
    /// tappable" has to be able to see this.
    var observedDecorationGroups: Set<String> { activableGroups }

    var lastAppliedDecorationsJson: String? { _lastAppliedDecorationsJson }
    var lastAppliedGroup: String? { _lastAppliedGroup }
}

// MARK: - String JSON-escape helper

// Mirror of the canonical Kotlin implementation in JsonStringUtils.kt (commonMain).
// Kept here because it operates on Readium-Swift types (Link, Locator) that never cross
// the KMP boundary.  Any change to the escaping logic must be applied to both sides.
private extension String {
    var jsonEscaped: String {
        replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
    }
}

// MARK: - UIColor hex extension

// Internal (not private) so the unit-test target can verify hex → RGB conversion.
extension UIColor {
    convenience init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: .init(charactersIn: "#"))
        let scanner = Scanner(string: cleaned)
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        let red = CGFloat((rgb >> 16) & 0xFF) / 255
        let green = CGFloat((rgb >> 8) & 0xFF) / 255
        let blue = CGFloat(rgb & 0xFF) / 255
        self.init(red: red, green: green, blue: blue, alpha: 1)
    }

    /// ARGB packed into an Int64, the form `HighlightColor.argb` and `ReaderThemePalette` use on
    /// the Kotlin side. Alpha is honoured: DarkDim's muted body colour carries one.
    convenience init(argb: Int64) {
        let value = UInt64(bitPattern: argb) & 0xFFFF_FFFF
        let alpha = CGFloat((value >> 24) & 0xFF) / 255
        let red = CGFloat((value >> 16) & 0xFF) / 255
        let green = CGFloat((value >> 8) & 0xFF) / 255
        let blue = CGFloat(value & 0xFF) / 255
        self.init(red: red, green: green, blue: blue, alpha: alpha == 0 ? 1 : alpha)
    }
}

// MARK: - ReadiumEpubNavigatorBridgeFactory

@objc class ReadiumEpubNavigatorBridgeFactory: NSObject, IosEpubNavigatorBridgeFactory {
    func create() -> any IosEpubNavigatorBridge {
        ReadiumEpubNavigatorBridge()
    }
}

// MARK: - Spine and scrolling
//
// In an extension rather than the class body: these are what the chapter map and auto-scroll
// need from the navigator, and swiftlint's type_body_length limit is a real signal that the
// class body has grown past what one screen can hold.
extension ReadiumEpubNavigatorBridge {
    func getSpineJson() -> String { cachedSpineJson }

    /// Scroll the visible resource by `pixels` device pixels, reporting whether the document moved.
    ///
    /// Readium owns the scrolling element inside its WKWebView, so auto-scroll drives it through
    /// `window.scrollBy` rather than the hosting view — the same reason Android's vertical mode
    /// scrolls via JS instead of `View.scrollBy`. The script compares scrollTop before and after
    /// so the caller can tell "moved" from "already at the bottom of this resource" and stop the
    /// ticker instead of spinning.
    func scrollByPx(pixels: Int32, onResult: @escaping (KotlinBoolean) -> Void) {
        Task { @MainActor in
            guard let nav = self.epubNavigator else {
                onResult(KotlinBoolean(bool: false))
                return
            }
            let script = """
            (function(){\
            var e=document.scrollingElement||document.documentElement;\
            var before=e.scrollTop;window.scrollBy(0,\(pixels));\
            return e.scrollTop>before;})()
            """
            let result = await nav.evaluateJavaScript(script)
            switch result {
            case let .success(value):
                let moved = (value as? Bool) ?? ((value as? NSNumber)?.boolValue ?? false)
                onResult(KotlinBoolean(bool: moved))
            case .failure:
                onResult(KotlinBoolean(bool: false))
            }
        }
    }

    /// Evaluate arbitrary JavaScript in the visible resource and return its result as a string.
    ///
    /// The generic twin of Android's `RendererBridge.evaluateJavascript`. Cadence needs four
    /// different scripts — the `Intl.Segmenter` feature detect, the per-chapter sentence-span
    /// tokenisation, the start-position probe and the paginated column measure/snap — and every
    /// one of them is authored in shared Kotlin, so the only thing missing on iOS was somewhere
    /// to run them.
    ///
    /// Marshalling: a JS string comes back as `NSString` and is passed through verbatim; a
    /// boolean (the feature detect) is stringified to `"true"`/`"false"` so the Kotlin side sees
    /// the same token Android's JSON-encoded `evaluateJavascript` produces. `nil`, a JS `null`
    /// and a thrown script all return nil, which every shared parser treats as "unsupported"
    /// rather than crashing.
    func evaluateJavaScript(script: String, onResult: @escaping (String?) -> Void) {
        Task { @MainActor in
            guard let nav = self.epubNavigator else {
                onResult(nil)
                return
            }
            let result = await nav.evaluateJavaScript(script)
            switch result {
            case let .success(value):
                onResult(Self.stringifyJavaScriptResult(value))
            case .failure:
                onResult(nil)
            }
        }
    }

    /// Internal (not private) so the unit-test target can pin the marshalling directly: a `true`
    /// that arrived as `"1"` would silently fail `CadenceDomScript`'s feature-detect comparison
    /// and hide the toggle on every device.
    static func stringifyJavaScriptResult(_ value: Any?) -> String? {
        switch value {
        case nil, is NSNull:
            return nil
        case let text as String:
            return text
        case let number as NSNumber:
            // CFBoolean bridges to NSNumber; distinguish it so `true` does not become "1".
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                return number.boolValue ? "true" : "false"
            }
            return number.stringValue
        default:
            return String(describing: value!)
        }
    }

    /// Reading order + position count per resource: the weights the shared rail generator needs
    /// to size chapter-map segments. `positionsByReadingOrder()` is index-aligned with
    /// `readingOrder`, which is the invariant `buildRailSegments` and
    /// `weightSegmentsByChapterLength` rely on. When Readium cannot compute positions the counts
    /// stay empty and the Kotlin side falls back to unweighted segments rather than mis-weighting
    /// them.
    private func prefetchSpine(_ pub: Publication) {
        Task { @MainActor in
            let hrefs = pub.readingOrder
                .map { "\"\($0.href.jsonEscaped)\"" }
                .joined(separator: ",")
            var counts = ""
            if case .success(let positions) = await pub.positionsByReadingOrder() {
                counts = positions.map { "\($0.count)" }.joined(separator: ",")
            }
            self.cachedSpineJson = "{\"hrefs\":[\(hrefs)],\"positionCounts\":[\(counts)]}"
        }
    }
}

// MARK: - Annotations
//
// The selection, decoration-activation and chapter-source seam. In an extension rather than the
// class body for the same reason the spine/scroll block below is: swiftlint's type_body_length
// limit is a real signal that the class has grown past what one screen can hold.
extension ReadiumEpubNavigatorBridge {

    func setSelectionCallback(callback: ((String?) -> Void)?) {
        selectionCallback = callback
    }

    func clearSelection() {
        Task { @MainActor in
            epubNavigator?.clearSelection()
            selectionCallback?(nil)
        }
    }

    func setDecorationActivatedCallback(callback: ((String) -> Void)?) {
        decorationActivatedCallback = callback
    }

    /// Registers `group` with Readium so its decorations become tap targets, and remembers it so
    /// a later `openEpub` re-registers it on the new navigator.
    ///
    /// Readium gates tap dispatch per group: `DecorationGroup.setActivable()` only runs for
    /// groups passed to `observeDecorationInteractions`, and `findDecorationTarget` skips every
    /// group that is not activable. Without this a highlight paints and the tap falls straight
    /// through to `didTapAt`, toggling the chrome instead of opening the actions sheet.
    func observeDecorationGroup(group: String) {
        activableGroups.insert(group)
        Task { @MainActor in
            guard let nav = epubNavigator else { return }
            self.registerDecorationObserver(nav, group: group)
        }
    }

    @MainActor
    private func registerDecorationObserver(_ nav: EPUBNavigatorViewController, group: String) {
        nav.observeDecorationInteractions(inGroup: group) { [weak self] event in
            guard let self, let callback = self.decorationActivatedCallback else { return }
            callback(Self.activationJson(id: event.decoration.id, group: event.group, rect: event.rect))
        }
    }

    /// `{"id":…,"group":…,"x":…,"y":…,"width":…,"height":…}`.
    ///
    /// Takes the three values rather than the `OnDecorationActivatedEvent` itself because that
    /// struct's memberwise initializer is `internal` to ReadiumNavigator — a test could not
    /// build one, and an untested hand-rolled JSON builder is exactly where a silent wire-shape
    /// break lives.
    static func activationJson(id: String, group: String, rect: CGRect?) -> String {
        let escapedId = id.jsonEscaped
        let escapedGroup = group.jsonEscaped
        guard let rect else {
            return #"{"id":"\#(escapedId)","group":"\#(escapedGroup)"}"#
        }
        return #"{"id":"\#(escapedId)","group":"\#(escapedGroup)","x":\#(rect.origin.x),"y":\#(rect.origin.y),"#
            + #""width":\#(rect.size.width),"height":\#(rect.size.height)}"#
    }

    /// `{"locatorJson":…,"href":…,"text":…,"before":…,"after":…,"progression":…,rect}`.
    ///
    /// `locatorJson` is carried as an escaped *string*, not a nested object, because the Kotlin
    /// side hands it straight back to `goToLocator` / the annotation domain without reparsing.
    /// Takes a `Locator` and a frame rather than a `Selection` for the same reason
    /// [activationJson] does: `Selection`'s initializer is internal to ReadiumNavigator.
    static func selectionJson(locator: Locator, frame: CGRect?) -> String? {
        guard let locatorJson = try? locator.jsonString() else { return nil }
        let text = locator.text
        var out = #"{"locatorJson":"\#(locatorJson.jsonEscaped)""#
        out += #","href":"\#(locator.href.string.jsonEscaped)""#
        out += #","text":"\#((text.highlight ?? "").jsonEscaped)""#
        out += #","before":"\#((text.before ?? "").jsonEscaped)""#
        out += #","after":"\#((text.after ?? "").jsonEscaped)""#
        out += #","progression":\#(locator.locations.progression ?? 0)"#
        if let rect = frame {
            out += #","x":\#(rect.origin.x),"y":\#(rect.origin.y)"#
            out += #","width":\#(rect.size.width),"height":\#(rect.size.height)"#
        }
        return out + "}"
    }

    /// The chapter's source XHTML, read straight out of the publication.
    ///
    /// Everything the shared annotation domain decides — the CFI range, the merge, the enclosed
    /// figures — is computed from these exact bytes, so this must NOT come from the live
    /// WKWebView: Readium has already injected its own scripts there, and once Cadence has run
    /// every sentence is wrapped in a span. The readable-character offsets would not match the
    /// ones Android derives for the same book and the CFI would point at the wrong text.
    func readResource(href: String, onResult: @escaping (String?) -> Void) {
        Task {
            guard let pub = self.publication,
                  let url = AnyURL(string: href),
                  let resource = pub.get(url)
            else {
                onResult(nil)
                return
            }
            guard case .success(let data) = await resource.read() else {
                onResult(nil)
                return
            }
            onResult(String(data: data, encoding: .utf8))
        }
    }

    func readResourceBase64(href: String, onResult: @escaping (String?) -> Void) {
        Task {
            guard let pub = self.publication else { onResult(nil); return }
            // Strip the Readium virtual-host origin (same normalization as readResource).
            let stripped = href
                .replacingOccurrences(of: #"^https?://[^/]+/"#, with: "", options: .regularExpression)
                .components(separatedBy: "#").first ?? ""
            guard let url = AnyURL(string: stripped),
                  let resource = pub.get(url)
            else { onResult(nil); return }
            guard case .success(let data) = await resource.read(), !data.isEmpty else {
                onResult(nil)
                return
            }
            onResult(data.base64EncodedString())
        }
    }
}
