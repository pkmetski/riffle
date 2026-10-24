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

    let hostViewController = UIViewController()
    var epubNavigator: EPUBNavigatorViewController?
    private var publication: Publication?
    // Cached on main thread by the delegate; read from snapshotLocatorJson() on any thread.
    var cachedLocatorJson: String?
    // TOC fetched asynchronously after open; getTocJson() returns from this cache.
    private var cachedTocJson: String = "[]"
    // Reading order + per-resource position counts, fetched asynchronously after open;
    // getSpineJson() returns from this cache. Readium computes positions off the main actor and
    // it can take a moment on a large EPUB, so the reader re-reads this until it stops being empty.
    private var cachedSpineJson: String = emptySpineJson

    // Callbacks registered by ReadiumSwiftNavigator
    var locatorCallback: ((String) -> Void)?
    var pageLoadCallback: (() -> Void)?
    var tapCallback: ((TapCoords) -> Void)?
    var errorCallback: ((String) -> Void)?
    var selectionCallback: ((String?) -> Void)?
    private var decorationActivatedCallback: ((String) -> Void)?
    private var figureTapCallback: ((String) -> Void)?
    var footnoteCallback: ((String) -> Void)?
    var internalLinkCallback: ((String, String) -> Void)?
    var riffleUrlCallback: ((String) -> Void)?
    var scrollStartCallback: (() -> Void)?
    var pullMoveCallback: ((Float, Int64) -> Void)?
    var pullResetCallback: (() -> Void)?
    var pullGestureEnabled = false
    private var lastPanTranslationY: Float = 0
    private var publicationOpenErrorCallback: ((String) -> Void)?
    /// WKUserContentControllers that have had the RiffleFigureBridge message handler registered.
    /// Held weakly so the WKWebView lifecycle is not extended; cleared on disposeNavigator to
    /// remove the handler and break the retain cycle that WKUserContentController's strong
    /// reference to a WKScriptMessageHandler would otherwise create.
    var figureHandlerControllers: NSHashTable<WKUserContentController> = .weakObjects()
    /// Decoration groups the Kotlin side asked to make tappable. Re-registered on every open,
    /// because `observeDecorationInteractions` lives on the navigator instance, not on us.
    private var activableGroups: Set<String> = []
    /// Seam for `presentExternalURL`. Production opens the URL in Safari; tests swap it to observe.
    var urlOpener: (URL) -> Void = { UIApplication.shared.open($0) }
    // Track the last-loaded resource href so pageLoadCallback fires only on resource
    // boundary crossings (chapter/spread loads), not on every intra-resource scroll event.
    var lastLoadedHref: String?

    // Pending preferences — stored before the navigator exists so openEpub can use them.
    var pendingPreferences: EPUBPreferences = EPUBPreferences()

    // Native Continuous mode (multi-WebView sliding window, iOS-only).
    var continuousScrollView: IosContinuousScrollView?
    var pendingIsContinuousMode = false

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

    func openSyntheticEpub(dirPath: String, locatorJson: String?) {
        Task { @MainActor in
            do {
                guard let fileURL = FileURL(path: dirPath, isDirectory: true) else {
                    let msg = "openSyntheticEpub: invalid directory path: \(dirPath)"
                    NSLog("[Riffle] %@", msg)
                    publicationOpenErrorCallback?(msg)
                    return
                }

                // AssetRetriever cannot open directories — FileResource explicitly rejects them.
                // Use DirectoryContainer directly and pass it to AssetRetriever.retrieve(container:).
                let container: DirectoryContainer
                do {
                    container = try await DirectoryContainer(directory: fileURL)
                } catch {
                    let msg = "openSyntheticEpub: DirectoryContainer failed for \(dirPath): \(error)"
                    NSLog("[Riffle] %@", msg)
                    publicationOpenErrorCallback?(msg)
                    return
                }

                let httpClient = DefaultHTTPClient()
                let assetRetriever = AssetRetriever(httpClient: httpClient)
                let assetResult = await assetRetriever.retrieve(container: container)
                guard case .success(let asset) = assetResult else {
                    let msg = "openSyntheticEpub: asset retrieval failed for \(dirPath): \(assetResult)"
                    NSLog("[Riffle] %@", msg)
                    publicationOpenErrorCallback?(msg)
                    return
                }

                let opener = PublicationOpener(parser: CompositePublicationParser([EPUBParser()]))
                let pubResult = await opener.open(asset: asset, allowUserInteraction: false)
                guard case .success(let pub) = pubResult else {
                    let msg = "openSyntheticEpub: publication parsing failed: \(pubResult)"
                    NSLog("[Riffle] %@", msg)
                    publicationOpenErrorCallback?(msg)
                    return
                }
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
                let msg = "openSyntheticEpub: navigator attach failed: \(error)"
                NSLog("[Riffle] %@", msg)
                publicationOpenErrorCallback?(msg)
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
        if pendingIsContinuousMode {
            attachContinuous(publication: pub, initialLocator: initialLocator)
            return
        }
        let config = makeNavigatorConfiguration()
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
        installScrollStartGesture(on: navigator.view)
        installBookmarkCornerBlocker(on: hostViewController.view)
        disableScrollsToTop(in: navigator.view)
    }

    /// Most recent IosReaderPreferences seen — used when attaching the continuous view
    /// before applyReaderPreferences fires.
    var currentContinuousPrefs: IosReaderPreferences?

    // Adds a transparent UIView over the top-right corner of the host view so that taps on the
    // Compose CornerBookmarkIndicator are NOT also delivered to the Readium WKWebView underneath.
    // UIKit hit-testing finds this view first (it is added after the navigator's view, so it sits
    // on top in z-order); the WKWebView never sees the touch. Compose's gesture recognizer fires
    // independently (it is attached to the root ComposeView, a parent of this host), so the
    // bookmark's onClick still works correctly. Size is 48×48 pt — generous over the 24×32 dp
    // ribbon plus its 8 dp right padding.
    @MainActor
    private func installBookmarkCornerBlocker(on view: UIView) {
        let blocker = UIView()
        blocker.backgroundColor = .clear
        blocker.isUserInteractionEnabled = true
        blocker.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(blocker)
        NSLayoutConstraint.activate([
            blocker.topAnchor.constraint(equalTo: view.topAnchor),
            blocker.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            blocker.widthAnchor.constraint(equalToConstant: 48),
            blocker.heightAnchor.constraint(equalToConstant: 48),
        ])
    }

    // Disables the iOS "tap status-bar area to scroll to top" behaviour for every UIScrollView
    // (including WKWebView.scrollView) in the navigator's view tree. Android's Readium integration
    // has no equivalent — there, tapping the top of the screen never scrolls to chapter beginning.
    // Must be called after setup AND after each chapter navigation because Readium creates fresh
    // WKWebViews for preloaded spreads; locationDidChange re-applies the setting when new views
    // enter the hierarchy.
    @MainActor
    internal func disableScrollsToTop(in view: UIView) {
        if let scrollView = view as? UIScrollView {
            scrollView.scrollsToTop = false
        }
        for subview in view.subviews {
            disableScrollsToTop(in: subview)
        }
    }

    // Adds a pan recognizer that fires scrollStartCallback at gesture-start — mirrors Android's
    // onScrollStart → ImmersiveModeState.dismissOverlay() which enters immersive on drag begin.
    @MainActor
    private func installScrollStartGesture(on view: UIView) {
        let pan = UIPanGestureRecognizer(target: self, action: #selector(onPanGesture(_:)))
        pan.delegate = self
        view.addGestureRecognizer(pan)
    }

    @objc func onPanGesture(_ recognizer: UIPanGestureRecognizer) {
        if recognizer.state == .began {
            scrollStartCallback?()
            lastPanTranslationY = 0
        }
        guard pullGestureEnabled else { return }
        let translation = recognizer.translation(in: recognizer.view)
        switch recognizer.state {
        case .changed:
            guard abs(translation.y) > abs(translation.x) else { return }
            let currentY = Float(translation.y)
            let dy = currentY - lastPanTranslationY
            lastPanTranslationY = currentY
            let nowMs = Int64(ProcessInfo.processInfo.systemUptime * 1000)
            pullMoveCallback?(dy, nowMs)
        case .ended, .cancelled, .failed:
            pullResetCallback?()
            lastPanTranslationY = 0
        default:
            break
        }
    }

    func goForward() {
        Task { @MainActor in _ = await epubNavigator?.goForward(options: .animated) }
    }

    func goBackward() {
        Task { @MainActor in _ = await epubNavigator?.goBackward(options: .animated) }
    }

    // Boundary-triggered chapter crossings in Continuous mode must not animate — the animated
    // variant uses PaginationView.slideToView which produces a 300 ms horizontal swipe that is
    // wrong for vertical continuous reading. NavigatorGoOptions(animated: false) produces an
    // instant chapter swap (a ≤ 100 ms snapshot flash while the preloaded WKWebView swaps in).
    func goForwardInstant() {
        Task { @MainActor in _ = await epubNavigator?.goForward(options: NavigatorGoOptions(animated: false)) }
    }

    func goBackwardInstant() {
        Task { @MainActor in _ = await epubNavigator?.goBackward(options: NavigatorGoOptions(animated: false)) }
    }

    func goToLocator(locatorJson: String) {
        guard let jsonValue = try? JSONValue(jsonString: locatorJson),
              let locator = try? Locator(json: jsonValue, warnings: nil) else { return }
        Task { @MainActor in
            if let csv = continuousScrollView {
                csv.navigateTo(href: "\(locator.href)", progression: Float(locator.locations.progression ?? 0))
            } else {
                _ = await epubNavigator?.go(to: locator, options: .animated)
            }
        }
    }

    /// Thread-safe: returns the cached locator JSON last emitted by the delegate on the main thread.
    func snapshotLocatorJson() -> String? { cachedLocatorJson }

    func setLocatorCallback(callback: ((String) -> Void)?) {
        locatorCallback = callback
    }

    func setPageLoadCallback(callback: (() -> Void)?) {
        pageLoadCallback = callback
    }

    func setTapCallback(callback: ((TapCoords) -> Void)?) {
        tapCallback = callback
    }

    func setFigureTapCallback(callback: ((String) -> Void)?) {
        figureTapCallback = callback
    }

    func setFootnoteCallback(callback: ((String) -> Void)?) {
        footnoteCallback = callback
    }

    /// Test seam: exercises the footnote-stripping + callback path without a live Readium Navigator.
    /// Returns `false` when a callback is registered (same semantics as the delegate), `true` otherwise.
    func simulateFootnoteTap(content: String) -> Bool {
        guard let cb = footnoteCallback else { return true }
        let plain = content
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        cb(plain.isEmpty ? content : plain)
        return false
    }

    func setInternalLinkCallback(callback: ((String, String) -> Void)?) {
        internalLinkCallback = callback
    }

    func setErrorCallback(callback: ((String) -> Void)?) {
        errorCallback = callback
    }

    func setPublicationOpenErrorCallback(callback: ((String) -> Void)?) {
        publicationOpenErrorCallback = callback
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
            self.continuousScrollView?.removeFromSuperview()
            self.continuousScrollView = nil
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

    func setRiffleUrlCallback(callback: ((String) -> Void)?) {
        riffleUrlCallback = callback
    }

    func setScrollStartCallback(callback: (() -> Void)?) {
        scrollStartCallback = callback
    }

    func setPullMoveCallback(callback: ((Float, Int64) -> Void)?) {
        pullMoveCallback = callback
    }

    func setPullResetCallback(callback: (() -> Void)?) {
        pullResetCallback = callback
    }

    func setPullGestureEnabled(enabled: Bool) {
        pullGestureEnabled = enabled
        if !enabled {
            lastPanTranslationY = 0
        }
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

    // MARK: - Bundled font registration

}

// MARK: - Decoration parsing

extension ReadiumEpubNavigatorBridge {
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

// MARK: - Bundled fonts + test seams

extension ReadiumEpubNavigatorBridge {
    /// Font families bundled in the app and registered with Readium so the WebView can load them
    /// by name. The three names must match ReadiumFormattingMapping.kt exactly ("Literata",
    /// "Merriweather", "OpenDyslexic"). UIAppFonts in Info.plist is also required; this covers
    /// the Readium/WebView side.
    static var bundledFontFamilyDeclarations: [AnyHTMLFontFamilyDeclaration] {
        // Bundle(for:) finds resources in both the app bundle and the unit test bundle,
        // whereas Bundle.main in a hostless XCTest points to the runner, not this class's bundle.
        let bundle = Bundle(for: ReadiumEpubNavigatorBridge.self)
        return [
            ("Literata", "Literata-Regular.ttf"),
            ("Merriweather", "Merriweather-Regular.ttf"),
            ("OpenDyslexic", "OpenDyslexic-Regular.otf"),
        ].compactMap { (familyName, fileName) -> AnyHTMLFontFamilyDeclaration? in
            // Split name and extension: passing the full filename with withExtension: nil is
            // not reliable across all bundle layouts.
            let baseName = (fileName as NSString).deletingPathExtension
            let ext = (fileName as NSString).pathExtension
            guard let url = bundle.url(forResource: baseName, withExtension: ext),
                  let fileURL = FileURL(url: url) else { return nil }
            let face = CSSFontFace(file: fileURL, preload: true)
            let decl = CSSFontFamilyDeclaration(
                fontFamily: FontFamily(rawValue: familyName),
                fontFaces: [face]
            )
            return decl.eraseToAnyHTMLFontFamilyDeclaration()
        }
    }

    /// Test seam: exercises the internal-link callback path without a live Readium Navigator.
    /// Returns `true` (Readium follows the link) regardless of whether a callback is registered.
    func simulateInternalLinkTap(href: String, originLocatorJson: String) -> Bool {
        if let cb = internalLinkCallback {
            cb(href, originLocatorJson)
        }
        return true
    }
}

// MARK: - EPUBNavigatorDelegate (see ReadiumEpubNavigatorBridge+Delegate.swift)


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
    /// Simulate a center tap (x=50%, y=50%) with a 360×800 view — lands in the immersive-toggle
    /// zone, not an edge zone. Pass explicit values via [simulateTapAt] for edge-zone tests.
    @objc func simulateTap() { tapCallback?(TapCoords(x: 180, y: 400, viewWidth: 360, viewHeight: 800)) }
    func simulateTapAt(tapX: Double, tapY: Double, viewWidth: Double, viewHeight: Double) {
        tapCallback?(TapCoords(x: tapX, y: tapY, viewWidth: viewWidth, viewHeight: viewHeight))
    }
    @objc func simulateScrollStart() { scrollStartCallback?() }
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
            guard let nav = self.epubNavigator else { onResult(nil); return }
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
    func prefetchSpine(_ pub: Publication) {
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
            let stripped = Self.stripOrigin(from: href)
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

    /// Strips any `scheme://host/` origin prefix from a WebView resource URL and removes any
    /// fragment, leaving only the publication-relative path (e.g. `EPUB/images/cover.jpg`).
    ///
    /// Readium Swift 3.x serves publication resources using a custom `readium://` scheme
    /// (`readium://{uuid}/EPUB/images/cover.jpg`), so the strip must handle any URL scheme, not
    /// just `http://` or `https://`.
    internal static func stripOrigin(from href: String) -> String {
        href
            .replacingOccurrences(of: #"^[a-zA-Z][a-zA-Z0-9+\-.]*://[^/]+/"#, with: "", options: .regularExpression)
            .components(separatedBy: "#").first ?? ""
    }
}

// MARK: - UIGestureRecognizerDelegate

extension ReadiumEpubNavigatorBridge: UIGestureRecognizerDelegate {
    // Allow the scroll-start pan to fire alongside Readium's own scroll and swipe recognizers so
    // it never blocks page-turns or vertical scrolling.
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool { true }
}
