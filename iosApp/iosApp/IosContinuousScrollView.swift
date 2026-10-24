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
        let styleJs = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        for (_, webView) in loadedWebViews {
            webView.evaluateJavaScript(styleJs, completionHandler: nil)
        }
    }

    // MARK: - Window management

    private func loadWindow(around centerIndex: Int) {
        let low = max(0, centerIndex - windowRadius)
        let high = min(spineHrefs.count - 1, centerIndex + windowRadius)

        // Evict WebViews outside the new window.
        let keysToEvict = loadedWebViews.keys.filter { $0 < low || $0 > high }
        for key in keysToEvict {
            loadedWebViews[key]?.removeFromSuperview()
            loadedWebViews.removeValue(forKey: key)
        }

        for idx in low...high {
            guard loadedWebViews[idx] == nil else { continue }
            let webView = makeWebView()
            loadedWebViews[idx] = webView
            contentContainer.addSubview(webView)
            positionWebView(webView, at: idx)
            loadChapter(index: idx, in: webView)
        }
    }

    private func positionWebView(_ webView: WKWebView, at index: Int) {
        let yPos = slotOffsets[index]
        let cht = slotHeights[index]
        webView.frame = CGRect(x: 0, y: yPos, width: contentContainer.bounds.width, height: cht)
    }

    private func updateAllWebViewFrames() {
        for (index, webView) in loadedWebViews {
            positionWebView(webView, at: index)
        }
    }

    private func updateContentHeight() {
        let total = (slotOffsets.last ?? 0) + (slotHeights.last ?? defaultSlotHeight)
        contentContainer.frame = CGRect(x: 0, y: 0, width: bounds.width, height: total)
        outerScrollView.contentSize = CGSize(width: bounds.width, height: total)
    }

    // MARK: - WebView creation

    private func makeWebView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(schemeHandler, forURLScheme: IosContinuousEpubSchemeHandler.scheme)
        let heightHandler = WeakScriptMessageHandler(target: self)
        config.userContentController.add(heightHandler, name: "riffleChapterHeight")
        let tapHandler = WeakScriptMessageHandler(target: self)
        config.userContentController.add(tapHandler, name: "riffleTap")
        let typographyScript = WKUserScript(
            source: TypographyOverrideKt.TYPOGRAPHY_OVERRIDE_INSTALL_SCRIPT,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true
        )
        config.userContentController.addUserScript(typographyScript)
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.navigationDelegate = self
        return webView
    }

    private func loadChapter(index: Int, in webView: WKWebView) {
        guard index < spineHrefs.count else { return }
        let href = spineHrefs[index]
        let encodedHref = href.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? href
        let urlString = "\(IosContinuousEpubSchemeHandler.scheme)://pub/\(encodedHref)"
        guard let url = URL(string: urlString) else { return }
        webView.load(URLRequest(url: url))
    }

    // MARK: - Height measurement

    private func onHeightMeasured(href: String, height: CGFloat) {
        guard let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }) else { return }
        let oldHeight = slotHeights[index]
        let oldChapterTop = slotOffsets[index]
        slotHeights[index] = max(height, 1)
        let newHeight = slotHeights[index]
        heightMeasured[index] = true
        slotOffsets = computeOffsets(from: slotHeights)
        updateAllWebViewFrames()
        updateContentHeight()

        // Compensate scroll offset so content above the viewport doesn't shift visible content.
        let delta = newHeight - oldHeight
        if delta != 0 {
            let viewportY = outerScrollView.contentOffset.y
            let oldChapterBottom = oldChapterTop + oldHeight
            if oldChapterBottom <= viewportY {
                let compensated = max(0, viewportY + delta)
                outerScrollView.setContentOffset(CGPoint(x: 0, y: compensated), animated: false)
            }
        }

        if !initialScrollApplied, heightMeasured[pendingInitialIndex] == true {
            initialScrollApplied = true
            scrollTo(index: pendingInitialIndex, progression: pendingInitialProgression)
        }
        onPageLoad?()
    }

    // MARK: - Position tracking

    private func updatePosition() {
        let yPos = outerScrollView.contentOffset.y
        let viewH = outerScrollView.bounds.height
        let midY = yPos + viewH * 0.5

        let idx = slotIndexAt(yPos: midY)
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

    private func slotIndexAt(yPos: CGFloat) -> Int {
        var low = 0; var high = slotOffsets.count - 1
        while low < high {
            let mid = (low + high + 1) / 2
            if slotOffsets[mid] <= yPos { low = mid } else { high = mid - 1 }
        }
        return low
    }

    private func scrollTo(index: Int, progression: Float) {
        guard index < slotOffsets.count, index < slotHeights.count else { return }
        let yPos = slotOffsets[index] + CGFloat(progression) * slotHeights[index]
        outerScrollView.setContentOffset(CGPoint(x: 0, y: max(0, yPos)), animated: false)
        loadWindow(around: index)
    }

    // MARK: - Layout

    override func layoutSubviews() {
        super.layoutSubviews()
        outerScrollView.frame = bounds
        for (index, webView) in loadedWebViews {
            webView.frame = CGRect(x: 0, y: slotOffsets[index], width: bounds.width, height: slotHeights[index])
        }
    }

    // MARK: - Helpers

    private func computeOffsets(from heights: [CGFloat]) -> [CGFloat] {
        var offsets = [CGFloat](repeating: 0, count: heights.count)
        for idx in 1..<heights.count { offsets[idx] = offsets[idx - 1] + heights[idx - 1] }
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
        if message.name == "riffleChapterHeight",
           let body = message.body as? [String: Any],
           let href = body["href"] as? String,
           let height = body["height"] as? Double {
            onHeightMeasured(href: href, height: CGFloat(height))
        } else if message.name == "riffleTap",
                  let body = message.body as? [String: Any],
                  let clientX = body["x"] as? Double,
                  let clientY = body["y"] as? Double,
                  let webView = message.webView,
                  let index = loadedWebViews.first(where: { $0.value === webView })?.key {
            let screenX = CGFloat(clientX)
            let screenY = (slotOffsets[index] + CGFloat(clientY) - outerScrollView.contentOffset.y)
                .clamped(to: 0...bounds.height)
            onTap?(CGPoint(x: screenX, y: screenY))
        }
    }
}

// MARK: - WKNavigationDelegate

extension IosContinuousScrollView: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let rawPath = webView.url?.path.removingPercentEncoding ?? ""
        let escapedPath = rawPath
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        // Mirrors Android's HEIGHT_MEASUREMENT_JS: avoids scrollHeight viewport-clamp on short chapters.
        let heightJs = """
        (function(){
          var href='\(escapedPath)';
          var de=document.documentElement,b=document.body;
          var bodyBottom=b?(b.getBoundingClientRect().bottom+(window.pageYOffset||0)):0;
          var rootPadBottom=0;
          if(de){var pb=parseFloat(window.getComputedStyle(de).paddingBottom);if(pb===pb)rootPadBottom=pb;}
          var h=Math.max(bodyBottom+rootPadBottom,de?de.offsetHeight:0,b?b.offsetHeight:0,1);
          window.webkit.messageHandlers.riffleChapterHeight.postMessage({href:href,height:h});
        })();
        """
        webView.evaluateJavaScript(heightJs, completionHandler: nil)
        // JS tap listener mirrors Android's TAP_LISTENER_JS, routing via message handler instead of JSInterface.
        let tapJs = """
        (function(){
          if(window._riffleTapWired)return;
          window._riffleTapWired=true;
          document.addEventListener('click',function(e){
            if(window.getSelection&&window.getSelection().toString().length>0)return;
            var el=e.target;
            while(el){
              var tag=el.tagName?el.tagName.toLowerCase():'';
              if(tag==='a'||tag==='button'||tag==='input'||tag==='select'||tag==='textarea'||tag==='label')return;
              el=el.parentElement;
            }
            window.webkit.messageHandlers.riffleTap.postMessage({x:e.clientX,y:e.clientY});
          });
        })();
        """
        webView.evaluateJavaScript(tapJs, completionHandler: nil)
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
