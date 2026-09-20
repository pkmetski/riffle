import UIKit
import Riffle
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

// MARK: - ReadiumEpubNavigatorBridge

/// Implements IosEpubNavigatorBridge (generated from Kotlin iosMain's IosEpubNavigatorBridge).
/// Wraps Readium Swift 3.x EPUBNavigatorViewController, bridging it to the KMP shared layer.
@objc class ReadiumEpubNavigatorBridge: NSObject, IosEpubNavigatorBridge {

    private let hostViewController = UIViewController()
    private var epubNavigator: EPUBNavigatorViewController?
    private var publication: Publication?
    // Cached on main thread by the delegate; read from snapshotLocatorJson() on any thread.
    private var cachedLocatorJson: String?
    // TOC fetched asynchronously after open; getTocJson() returns from this cache.
    private var cachedTocJson: String = "[]"

    // Callbacks registered by ReadiumSwiftNavigator
    private var locatorCallback: ((String) -> Void)?
    private var pageLoadCallback: (() -> Void)?
    private var tapCallback: (() -> Void)?
    private var errorCallback: ((String) -> Void)?
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

                var initialLocator: Locator?
                if let json = locatorJson,
                   let jsonValue = try? JSONValue(jsonString: json) {
                    initialLocator = try? Locator(json: jsonValue, warnings: nil)
                }

                let config = EPUBNavigatorViewController.Configuration(
                    preferences: self.pendingPreferences
                )
                let navigator = try EPUBNavigatorViewController(
                    publication: pub,
                    initialLocation: initialLocator,
                    config: config
                )
                navigator.delegate = self
                self.epubNavigator = navigator
                self.hostViewController.addChild(navigator)
                navigator.view.frame = self.hostViewController.view.bounds
                navigator.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                self.hostViewController.view.addSubview(navigator.view)
                navigator.didMove(toParent: self.hostViewController)
            } catch {
                // Ignore open errors — reader shows blank state
            }
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

    func setErrorCallback(callback: ((String) -> Void)?) {
        errorCallback = callback
    }

    func openLazyEpub(shapeJson: String, locatorJson: String?, fetcher: any IosLazyChapterFetcher) {
        Task { @MainActor in
            do {
                let (pub, _) = try OReillyPublicationBuilder.build(shapeJson: shapeJson, fetcher: fetcher)
                self.publication = pub
                self.prefetchToc(pub)

                var initialLocator: Locator?
                if let json = locatorJson,
                   let jsonValue = try? JSONValue(jsonString: json) {
                    initialLocator = try? Locator(json: jsonValue, warnings: nil)
                }

                let config = EPUBNavigatorViewController.Configuration(
                    preferences: self.pendingPreferences
                )
                let navigator = try EPUBNavigatorViewController(
                    publication: pub,
                    initialLocation: initialLocator,
                    config: config
                )
                navigator.delegate = self
                self.epubNavigator = navigator
                self.hostViewController.addChild(navigator)
                navigator.view.frame = self.hostViewController.view.bounds
                navigator.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                self.hostViewController.view.addSubview(navigator.view)
                navigator.didMove(toParent: self.hostViewController)
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
        }
    }

    func applyDecorations(decorationsJson: String, group: String) {
        _lastAppliedDecorationsJson = decorationsJson
        _lastAppliedGroup = group
        Task { @MainActor in
            guard let nav = epubNavigator else { return }
            let decorations = parseDecorations(decorationsJson)
            nav.apply(decorations: decorations, in: group)
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
        return array.compactMap { parseDecoration($0) }
    }

    private func parseDecoration(_ dict: [String: Any]) -> Decoration? {
        guard let id = dict["id"] as? String,
              let type = dict["type"] as? String,
              let locatorDict = dict["locator"] as? [String: Any],
              let locatorData = try? JSONSerialization.data(withJSONObject: locatorDict),
              let locatorString = String(data: locatorData, encoding: .utf8),
              let jsonValue = try? JSONValue(jsonString: locatorString),
              let locator = try? Locator(json: jsonValue, warnings: nil)
        else { return nil }

        let style: Decoration.Style
        switch type {
        case "highlight":
            // NavigatorDecoration.Highlight carries a non-null colour and alpha, both resolved
            // from HighlightColor by AnnotationDecorationMapper, so a dict missing either one is
            // a broken bridge payload rather than a highlight to guess a tint for. The old
            // "#FFFF00"/0.4 fallback had drifted away from HighlightColor.DEFAULT (#FBBF24 with a
            // baked 0x80 alpha) and would have painted the wrong colour while reading as correct.
            guard let colorHex = dict["color"] as? String,
                  let alpha = (dict["alpha"] as? NSNumber)?.floatValue
            else { return nil }
            style = .highlight(tint: UIColor(hex: colorHex).withAlphaComponent(CGFloat(alpha)))
        case "bookmark":
            style = .highlight(tint: UIColor.systemBlue.withAlphaComponent(0.3))
        case "noteGlyph":
            style = .highlight(tint: UIColor.systemOrange.withAlphaComponent(0.3))
        case "searchMark":
            let isCurrent = dict["isCurrent"] as? Bool ?? false
            style = .highlight(tint: (isCurrent ? UIColor.systemYellow : UIColor.systemGray).withAlphaComponent(0.5))
        default:
            return nil
        }
        return Decoration(id: id, locator: locator, style: style)
    }
}

// MARK: - EPUBNavigatorDelegate

extension ReadiumEpubNavigatorBridge: EPUBNavigatorDelegate {
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        guard let json = try? locator.jsonString() else { return }
        cachedLocatorJson = json
        locatorCallback?(json)
        let href = locator.href.string
        if href != lastLoadedHref {
            lastLoadedHref = href
            pageLoadCallback?()
        }
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        tapCallback?()
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
    @objc func simulateNavigatorError(_ message: String) { errorCallback?(message) }

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
