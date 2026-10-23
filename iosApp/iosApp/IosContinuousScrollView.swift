import UIKit
import WebKit
import Riffle
import ReadiumShared

// MARK: - IosContinuousScrollView

/// UIView that renders an EPUB in iOS Continuous mode using a sliding window of WKWebViews.
///
/// Equivalent to Android's `ContinuousReaderView` (`NestedScrollView` + `ChapterWebView` window).
/// Key design decisions shared with the Android implementation:
/// - The outer `UIScrollView` drives scrolling; per-WebView scroll is disabled.
/// - Chapter height is measured via JS after each load; the scroll-content height is the sum.
/// - A sliding window keeps at most `windowSize` WebViews live; chapters outside are recycled.
/// - Position (spine index + progression) is derived from the scroll offset in `scrollViewDidScroll`.
@MainActor
final class IosContinuousScrollView: UIView {

    // MARK: - Public callbacks (mirroring the ReadiumEpubNavigatorBridge callback properties)

    var onLocator: ((String) -> Void)?
    var onPageLoad: (() -> Void)?
    var onScrollStart: (() -> Void)?
    var onTap: ((CGPoint) -> Void)?

    // MARK: - Private state

    private let outerScrollView = UIScrollView()
    private let contentContainer = UIView()
    private let schemeHandler = IosContinuousEpubSchemeHandler()

    private var spineHrefs: [String] = []
    private var slotHeights: [CGFloat] = []
    private var slotOffsets: [CGFloat] = []

    private var loadedWebViews: [Int: WKWebView] = [:]
    private var heightMeasured: [Int: Bool] = [:]

    private var currentPrefs: IosReaderPreferences?
    private var isScrolling = false
    private var hasReportedScrollStart = false
    private var lastReportedIndex = -1
    private var lastReportedProgression = -1.0

    // Pending initial scroll position applied once heights are known.
    private var pendingInitialIndex: Int = 0
    private var pendingInitialProgression: Float = 0
    private var initialScrollApplied = false

    private let windowRadius = 1
    private let defaultSlotHeight: CGFloat = 2000

    // MARK: - Setup

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupScrollView()
    }

    required init?(coder: NSCoder) { fatalError() }

    private func setupScrollView() {
        outerScrollView.delegate = self
        outerScrollView.showsVerticalScrollIndicator = true
        outerScrollView.showsHorizontalScrollIndicator = false
        outerScrollView.alwaysBounceVertical = true
        outerScrollView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(outerScrollView)
        NSLayoutConstraint.activate([
            outerScrollView.topAnchor.constraint(equalTo: topAnchor),
            outerScrollView.bottomAnchor.constraint(equalTo: bottomAnchor),
            outerScrollView.leadingAnchor.constraint(equalTo: leadingAnchor),
            outerScrollView.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        contentContainer.translatesAutoresizingMaskIntoConstraints = false
        outerScrollView.addSubview(contentContainer)
        NSLayoutConstraint.activate([
            contentContainer.topAnchor.constraint(equalTo: outerScrollView.contentLayoutGuide.topAnchor),
            contentContainer.bottomAnchor.constraint(equalTo: outerScrollView.contentLayoutGuide.bottomAnchor),
            contentContainer.leadingAnchor.constraint(equalTo: outerScrollView.contentLayoutGuide.leadingAnchor),
            contentContainer.trailingAnchor.constraint(equalTo: outerScrollView.contentLayoutGuide.trailingAnchor),
            contentContainer.widthAnchor.constraint(equalTo: outerScrollView.frameLayoutGuide.widthAnchor),
        ])
    }

    // MARK: - Public API

    func initialize(
        publication: Publication,
        spineHrefs: [String],
        initialHref: String,
        initialProgression: Float,
        prefs: IosReaderPreferences
    ) {
        schemeHandler.publication = publication
        schemeHandler.htmlTransformer = { [weak self] href, html in
            guard let prefs = self?.currentPrefs else { return html }
            return IosContinuousCssInjector.injectInto(html: html, href: href, prefs: prefs)
        }

        self.spineHrefs = spineHrefs
        self.currentPrefs = prefs

        let initialIndex = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(initialHref) }) ?? 0
        pendingInitialIndex = initialIndex
        pendingInitialProgression = initialProgression

        slotHeights = Array(repeating: defaultSlotHeight, count: spineHrefs.count)
        slotOffsets = computeOffsets(from: slotHeights)
        updateContentHeight()
        loadWindow(around: initialIndex)
    }

    func navigateTo(href: String, progression: Float) {
        let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }) ?? 0
        scrollTo(index: index, progression: progression)
    }

    func updatePreferences(_ prefs: IosReaderPreferences) {
        currentPrefs = prefs
        let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        for (_, wv) in loadedWebViews {
            wv.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    // MARK: - Window management

    private func loadWindow(around centerIndex: Int) {
        let lo = max(0, centerIndex - windowRadius)
        let hi = min(spineHrefs.count - 1, centerIndex + windowRadius)

        // Evict WebViews outside the new window.
        let keysToEvict = loadedWebViews.keys.filter { $0 < lo || $0 > hi }
        for key in keysToEvict {
            loadedWebViews[key]?.removeFromSuperview()
            loadedWebViews.removeValue(forKey: key)
        }

        for i in lo...hi {
            guard loadedWebViews[i] == nil else { continue }
            let wv = makeWebView()
            loadedWebViews[i] = wv
            contentContainer.addSubview(wv)
            positionWebView(wv, at: i)
            loadChapter(index: i, in: wv)
        }
    }

    private func positionWebView(_ wv: WKWebView, at index: Int) {
        let y = slotOffsets[index]
        let h = slotHeights[index]
        wv.frame = CGRect(x: 0, y: y, width: contentContainer.bounds.width, height: h)
    }

    private func updateAllWebViewFrames() {
        for (index, wv) in loadedWebViews {
            positionWebView(wv, at: index)
        }
    }

    private func updateContentHeight() {
        let total = slotOffsets.last.flatMap { slotHeights.last.map { $0 + $1 } } ?? defaultSlotHeight
        contentContainer.frame = CGRect(x: 0, y: 0, width: bounds.width, height: total)
        outerScrollView.contentSize = CGSize(width: bounds.width, height: total)
    }

    // MARK: - WebView creation

    private func makeWebView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(schemeHandler, forURLScheme: IosContinuousEpubSchemeHandler.scheme)
        let msgHandler = WeakScriptMessageHandler(target: self)
        config.userContentController.add(msgHandler, name: "riffleChapterHeight")

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.scrollView.isScrollEnabled = false
        wv.scrollView.bounces = false
        wv.isOpaque = false
        wv.backgroundColor = .clear
        wv.navigationDelegate = self
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleWebViewTap(_:)))
        wv.addGestureRecognizer(tapGesture)
        return wv
    }

    private func loadChapter(index: Int, in wv: WKWebView) {
        guard index < spineHrefs.count else { return }
        let href = spineHrefs[index]
        let urlString = "\(IosContinuousEpubSchemeHandler.scheme)://pub/\(href)"
        guard let url = URL(string: urlString) else { return }
        wv.load(URLRequest(url: url))
    }

    // MARK: - Height measurement

    private func onHeightMeasured(href: String, height: CGFloat) {
        guard let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }) else { return }
        slotHeights[index] = max(height, 1)
        heightMeasured[index] = true
        slotOffsets = computeOffsets(from: slotHeights)
        updateAllWebViewFrames()
        updateContentHeight()

        if !initialScrollApplied, heightMeasured[pendingInitialIndex] == true {
            initialScrollApplied = true
            scrollTo(index: pendingInitialIndex, progression: pendingInitialProgression)
        }
        onPageLoad?()
    }

    // MARK: - Position tracking

    private func updatePosition() {
        let y = outerScrollView.contentOffset.y
        let viewH = outerScrollView.bounds.height
        let midY = y + viewH * 0.5

        let idx = slotIndexAt(y: midY)
        let chapterY = midY - slotOffsets[idx]
        let chapterH = slotHeights[idx]
        let progression = chapterH > 0 ? Double(chapterY / chapterH).clamped(to: 0...1) : 0

        if idx != lastReportedIndex || abs(progression - lastReportedProgression) > 0.001 {
            lastReportedIndex = idx
            lastReportedProgression = progression
            let href = spineHrefs[idx]
            let json = makeLocatorJson(href: href, progression: progression)
            onLocator?(json)
        }

        loadWindow(around: idx)
    }

    private func slotIndexAt(y: CGFloat) -> Int {
        var lo = 0; var hi = slotOffsets.count - 1
        while lo < hi {
            let mid = (lo + hi + 1) / 2
            if slotOffsets[mid] <= y { lo = mid } else { hi = mid - 1 }
        }
        return lo
    }

    private func scrollTo(index: Int, progression: Float) {
        guard index < slotOffsets.count, index < slotHeights.count else { return }
        let y = slotOffsets[index] + CGFloat(progression) * slotHeights[index]
        outerScrollView.setContentOffset(CGPoint(x: 0, y: max(0, y)), animated: false)
        loadWindow(around: index)
    }

    // MARK: - Tap

    @objc private func handleWebViewTap(_ gr: UITapGestureRecognizer) {
        let pt = gr.location(in: self)
        onTap?(pt)
    }

    // MARK: - Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        outerScrollView.frame = bounds
        for (index, wv) in loadedWebViews {
            wv.frame = CGRect(x: 0, y: slotOffsets[index], width: bounds.width, height: slotHeights[index])
        }
    }

    // MARK: - Helpers

    private func computeOffsets(from heights: [CGFloat]) -> [CGFloat] {
        var offsets = [CGFloat](repeating: 0, count: heights.count)
        for i in 1..<heights.count { offsets[i] = offsets[i - 1] + heights[i - 1] }
        return offsets
    }

    private func normalizeHref(_ href: String) -> String {
        href.hasPrefix("/") ? String(href.dropFirst()) : href
    }

    private func makeLocatorJson(href: String, progression: Double) -> String {
        let clean = href.replacingOccurrences(of: "\"", with: "\\\"")
        return """
        {"href":"\(clean)","type":"application/xhtml+xml",\
        "locations":{"progression":\(progression),"position":null},"text":{}}
        """
    }
}

// MARK: - UIScrollViewDelegate

extension IosContinuousScrollView: UIScrollViewDelegate {
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        if !hasReportedScrollStart {
            hasReportedScrollStart = true
            onScrollStart?()
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        updatePosition()
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        hasReportedScrollStart = false
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { hasReportedScrollStart = false }
    }
}

// MARK: - WKScriptMessageHandler (via WeakScriptMessageHandler)

extension IosContinuousScrollView: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "riffleChapterHeight",
              let body = message.body as? [String: Any],
              let href = body["href"] as? String,
              let height = body["height"] as? Double else { return }
        onHeightMeasured(href: href, height: CGFloat(height))
    }
}

// MARK: - WKNavigationDelegate

extension IosContinuousScrollView: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let heightJs = """
        (function(){
          var href='\(webView.url?.path.removingPercentEncoding ?? "")';
          var h=Math.max(document.body.scrollHeight,document.documentElement.scrollHeight,1);
          window.webkit.messageHandlers.riffleChapterHeight.postMessage({href:href,height:h});
        })();
        """
        webView.evaluateJavaScript(heightJs, completionHandler: nil)
    }
}

// MARK: - WeakScriptMessageHandler

/// Breaks the retain cycle between WKUserContentController (strong → handler) and the view.
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: IosContinuousScrollView?
    init(target: IosContinuousScrollView) { self.target = target }

    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(ucc, didReceive: message)
    }
}

// MARK: - Comparable extension

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
