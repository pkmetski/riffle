import UIKit
import Riffle
import ReadiumShared
import ReadiumStreamer
import ReadiumNavigator

// MARK: - ReadiumEpubNavigatorBridge

/// Implements SharedIosEpubNavigatorBridge (generated from Kotlin iosMain's IosEpubNavigatorBridge).
/// Wraps Readium Swift's EPUBNavigatorViewController, bridging it to the KMP shared layer.
@objc class ReadiumEpubNavigatorBridge: NSObject, SharedIosEpubNavigatorBridge {

    private let hostViewController = UIViewController()
    private var epubNavigator: EPUBNavigatorViewController?
    private var publication: Publication?
    // Updated on main thread by the delegate; read from snapshotLocatorJson() on any thread.
    private var cachedLocatorJson: String?

    // Callbacks registered by ReadiumSwiftNavigator
    private var locatorCallback: ((String) -> Void)?
    private var pageLoadCallback: (() -> Void)?
    private var tapCallback: (() -> Void)?

    // MARK: - SharedIosEpubNavigatorBridge

    func viewController() -> UIViewController { hostViewController }

    func openEpub(filePath: String, locatorJson: String?) {
        Task { @MainActor in
            do {
                let fileUrl = URL(fileURLWithPath: filePath)
                let asset = FileAsset(url: fileUrl)
                let streamer = Streamer()
                let result = await streamer.open(asset: asset, allowUserInteraction: false)
                switch result {
                case .failure:
                    return
                case .success(let pub):
                    self.publication = pub
                    var initialLocator: Locator?
                    if let json = locatorJson, let data = json.data(using: .utf8) {
                        initialLocator = try? JSONDecoder().decode(Locator.self, from: data)
                    }
                    let config = EPUBNavigatorViewController.Configuration()
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
                }
            } catch {
                // Ignore open errors — reader shows blank state
            }
        }
    }

    func goForward() {
        Task { @MainActor in _ = try? await epubNavigator?.goForward(animated: true) }
    }

    func goBackward() {
        Task { @MainActor in _ = try? await epubNavigator?.goBackward(animated: true) }
    }

    func goToLocator(_ locatorJson: String) {
        guard let data = locatorJson.data(using: .utf8),
              let locator = try? JSONDecoder().decode(Locator.self, from: data) else { return }
        Task { @MainActor in _ = try? await epubNavigator?.go(to: locator, animated: true) }
    }

    /// Thread-safe: returns the cached locator JSON last emitted by the delegate (main thread),
    /// rather than querying epubNavigator.currentLocation which requires the main thread.
    func snapshotLocatorJson() -> String? { cachedLocatorJson }

    func setLocatorCallback(_ callback: ((String) -> Void)?) {
        locatorCallback = callback
    }

    func setPageLoadCallback(_ callback: (() -> Void)?) {
        pageLoadCallback = callback
    }

    func setTapCallback(_ callback: (() -> Void)?) {
        tapCallback = callback
    }

    func release() {
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
}

// MARK: - EPUBNavigatorDelegate

extension ReadiumEpubNavigatorBridge: EPUBNavigatorDelegate {
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        guard let data = try? JSONEncoder().encode(locator),
              let json = String(data: data, encoding: .utf8) else { return }
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
}

// MARK: - ReadiumEpubNavigatorBridgeFactory

@objc class ReadiumEpubNavigatorBridgeFactory: NSObject, SharedIosEpubNavigatorBridgeFactory {
    func create() -> any SharedIosEpubNavigatorBridge {
        ReadiumEpubNavigatorBridge()
    }
}
