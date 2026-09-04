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

    // Callbacks registered by ReadiumSwiftNavigator
    private var locatorCallback: ((String) -> Void)?
    private var pageLoadCallback: (() -> Void)?
    private var tapCallback: (() -> Void)?

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

                var initialLocator: Locator?
                if let json = locatorJson,
                   let jsonValue = try? JSONValue(jsonString: json) {
                    initialLocator = try? Locator(json: jsonValue, warnings: nil)
                }

                let navigator = try EPUBNavigatorViewController(
                    publication: pub,
                    initialLocation: initialLocator
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

    func disposeNavigator() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.epubNavigator?.willMove(toParent: nil)
            self.epubNavigator?.view.removeFromSuperview()
            self.epubNavigator?.removeFromParent()
            self.epubNavigator = nil
            self.publication = nil
            self.cachedLocatorJson = nil
        }
    }

    func applyDecorations(decorationsJson: String, group: String) {
        _lastAppliedDecorationsJson = decorationsJson
        _lastAppliedGroup = group
        Task { @MainActor in
            guard let nav = epubNavigator else { return }
            let decorations = parseDecorations(decorationsJson)
            nav.apply(decorations: decorations, forGroup: group)
        }
    }

    private func parseDecorations(_ json: String) -> [Decoration] {
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
            let colorHex = dict["color"] as? String ?? "#FFFF00"
            let alpha = (dict["alpha"] as? NSNumber)?.floatValue ?? 0.4
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
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        tapCallback?()
    }

    func navigator(_ navigator: Navigator, presentExternalURL url: URL) {}

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}
}

// MARK: - Test helpers

extension ReadiumEpubNavigatorBridge {
    @objc func simulateLocatorUpdate(_ json: String) {
        cachedLocatorJson = json
        locatorCallback?(json)
    }
    @objc func simulatePageLoad() { pageLoadCallback?() }
    @objc func simulateTap() { tapCallback?() }

    var lastAppliedDecorationsJson: String? { _lastAppliedDecorationsJson }
    var lastAppliedGroup: String? { _lastAppliedGroup }
    @objc func simulateApplyDecorations(_ json: String, group: String) {
        _lastAppliedDecorationsJson = json
        _lastAppliedGroup = group
    }
}

// MARK: - UIColor hex extension

private extension UIColor {
    convenience init(hex: String) {
        let cleaned = hex.trimmingCharacters(in: .init(charactersIn: "#"))
        let scanner = Scanner(string: cleaned)
        var rgb: UInt64 = 0
        scanner.scanHexInt64(&rgb)
        let r = CGFloat((rgb >> 16) & 0xFF) / 255
        let g = CGFloat((rgb >> 8) & 0xFF) / 255
        let b = CGFloat(rgb & 0xFF) / 255
        self.init(red: r, green: g, blue: b, alpha: 1)
    }
}

// MARK: - ReadiumEpubNavigatorBridgeFactory

@objc class ReadiumEpubNavigatorBridgeFactory: NSObject, IosEpubNavigatorBridgeFactory {
    func create() -> any IosEpubNavigatorBridge {
        ReadiumEpubNavigatorBridge()
    }
}
