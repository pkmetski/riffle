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
    var selectionCallback: ((String?) -> Void)?
    var footnoteCallback: ((String) -> Void)?
    var urlOpener: ((URL) -> Void)?
    var figureTapCallback: ((String) -> Void)?
    var figureLongPressCallback: ((String) -> Void)?

    // Stored decorations per group applied before a slot WebView finishes loading.
    // Re-applied in didFinish so slots that load after applyDecorationsJs is called pick them up.
    private var pendingDecorationScripts: [String: String] = [:]

    // Sequences external navigateTo calls against pending above-viewport height compensations.
    // Mirrors Android's AboveSlotCompensationGate (feature:reader commonMain): when a chapter
    // above the viewport reports a new height measurement, the slot geometry is updated and the
    // scroll is compensated — but that measurement arrives asynchronously (JS message handler).
    // If navigateTo is called between chapter load and measurement, it uses stale slotOffsets.
    // The gate defers external navigations until all pending above-viewport measurements land.
    private let compensationGate = IosAboveSlotCompensationGate()

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
    private var lastReportedTotalProgression = -1.0

    // Pending initial scroll position applied once heights are known.
    private var pendingInitialIndex: Int = 0
    private var pendingInitialProgression: Float = 0
    private var initialScrollApplied = false

    // MARK: - Decoration state (continuous-mode annotation/search highlights)
    // Each entry is the raw decoration JSON received from the Kotlin navigator for that group.
    // All three annotation groups are merged on each update; search is applied independently.
    private var highlightsGroupJson: String = "[]"
    private var noteGlyphsGroupJson: String = "[]"
    private var emphasisGroupJson: String = "[]"
    private var searchGroupJson: String = "[]"

    // Exposed for regression tests: lets tests verify the eviction guard keeps the pending chapter
    // loaded across a simulated contentOffset-reset-to-0 that would otherwise evict it.
    internal var loadedChapterIndices: Set<Int> { Set(loadedWebViews.keys) }

    // Window radius of 2 keeps ±2 chapters live. This means a chapter 2 ahead starts loading
    // while the user is still reading the previous one, eliminating the 1-2 second blank flash
    // that occurred when only the immediate neighbour was preloaded (radius=1).
    private let windowRadius = 2
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
        // contentContainer uses manual frames, NOT Auto Layout pinned to contentLayoutGuide.
        // Pinning to contentLayoutGuide with translatesAutoresizingMaskIntoConstraints=false would
        // cause UIScrollView to derive contentSize.height from Auto Layout (0, since WebViews use
        // manual frames), overriding the manually-set contentSize and clamping contentOffset to 0
        // on every layout pass — which evicts the target chapter before onHeightMeasured fires.
        outerScrollView.addSubview(contentContainer)

        // UIKit tap recognizer on the outer scroll view to reliably detect taps for chrome toggle.
        // JS click events inside WKWebViews embedded in UIScrollViews are unreliable: when the
        // user taps to stop a decelerating scroll, UIScrollView absorbs the touch and WKWebView's
        // JS engine never sees a click event. A UITapGestureRecognizer on the scroll view itself
        // fires in all cases. We use elementFromPoint JS to distinguish background taps (toggle
        // chrome) from link/button taps (do not toggle — let the WebView's own handling fire).
        let tapRecognizer = UITapGestureRecognizer(target: self, action: #selector(handleScrollViewTap(_:)))
        tapRecognizer.cancelsTouchesInView = false
        tapRecognizer.delegate = self
        outerScrollView.addGestureRecognizer(tapRecognizer)
    }

    @objc private func handleScrollViewTap(_ gesture: UITapGestureRecognizer) {
        let tapInScrollView = gesture.location(in: outerScrollView)
        let absoluteY = tapInScrollView.y + outerScrollView.contentOffset.y
        let tapInSelf = gesture.location(in: self)

        // Find which chapter slot the tap landed in.
        let idx = slotIndexAt(yPos: absoluteY)
        guard idx < slotOffsets.count, idx < slotHeights.count,
              let webView = loadedWebViews[idx] else {
            onTap?(tapInSelf)
            return
        }

        // Convert to CSS px within the WebView's document coordinate space.
        // WKWebView's internal scroll is disabled (contentOffset = 0,0), so
        // CSS (0,0) aligns with the WebView frame's top-left corner.
        let cssX = tapInScrollView.x
        let cssY = absoluteY - slotOffsets[idx]

        let js = """
        (function(){
          var el=document.elementFromPoint(\(cssX),\(cssY));
          while(el){
            var t=el.tagName?el.tagName.toLowerCase():'';
            if(t==='a'||t==='button'||t==='input'||t==='select'||t==='textarea'||t==='label')return'interactive';
            el=el.parentElement;
          }
          return'background';
        })();
        """
        webView.evaluateJavaScript(js) { [weak self] result, _ in
            guard let self else { return }
            if let hitType = result as? String, hitType == "interactive" { return }
            self.onTap?(tapInSelf)
        }
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
        setupSlots(spineHrefs: spineHrefs, initialHref: initialHref, initialProgression: initialProgression, prefs: prefs)
    }

    // Separated from initialize() so tests can exercise slot geometry and pre-scroll without
    // a real Publication. scrollTo() pre-scrolls to the approximate initial position using
    // default slot heights so the user sees the right chapter immediately, not chapter 1.
    internal func setupSlots(
        spineHrefs: [String],
        initialHref: String,
        initialProgression: Float,
        prefs: IosReaderPreferences
    ) {
        self.spineHrefs = spineHrefs
        self.currentPrefs = prefs
        applyThemeBackground(theme: prefs.theme)
        compensationGate.reset()

        let initialIndex = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(initialHref) }) ?? 0
        pendingInitialIndex = initialIndex
        pendingInitialProgression = initialProgression

        slotHeights = Array(repeating: defaultSlotHeight, count: spineHrefs.count)
        slotOffsets = computeOffsets(from: slotHeights)
        updateContentHeight()
        // Pre-scroll to the approximate initial position using default slot heights.
        // Without this, the user sees chapter 1 for 1–2 s until onHeightMeasured fires.
        // scrollTo() also calls loadWindow(), so no separate loadWindow() call is needed.
        scrollTo(index: initialIndex, progression: initialProgression)
    }

    func navigateTo(href: String, progression: Float) {
        let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }) ?? 0
        // Defer until all pending above-viewport slot compensations are settled so the navigation
        // uses up-to-date slotOffsets. Mirrors Android's AboveSlotCompensationGate usage in
        // ContinuousWindowController.landOnAnnotationOffset (feature:reader commonMain, #1109).
        compensationGate.runWhenSettled { [weak self] in
            self?.scrollTo(index: index, progression: progression)
        }
    }

    // MARK: - Cache integration

    /// Prime the initial chapter with cached height and bytes, mirroring Android's
    /// ContinuousWindowController.primeWithCachedHeight.  Must be called after setupSlots().
    func primeWithCachedHeight(href: String, cachedData: Data, heightPx: Int) {
        guard let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }) else { return }
        guard slotHeights[index] == defaultSlotHeight else { return }
        schemeHandler.cachedChapterData[normalizeHref(href)] = cachedData
        slotHeights[index] = CGFloat(heightPx)
        heightMeasured[index] = true
        slotOffsets = computeOffsets(from: slotHeights)
        updateAllWebViewFrames()
        updateContentHeight()
        if !initialScrollApplied, index == pendingInitialIndex {
            initialScrollApplied = true
            scrollTo(index: pendingInitialIndex, progression: pendingInitialProgression)
        }
    }

    /// Measured height for a chapter, nil if not yet measured.
    func measuredHeight(forHref href: String) -> CGFloat? {
        guard let index = spineHrefs.firstIndex(where: { normalizeHref($0) == normalizeHref(href) }),
              heightMeasured[index] == true else { return nil }
        return slotHeights[index]
    }

    /// The href of the chapter currently at the viewport midpoint.
    var currentChapterHref: String? {
        guard lastReportedIndex >= 0, lastReportedIndex < spineHrefs.count else { return nil }
        return spineHrefs[lastReportedIndex]
    }

    func updatePreferences(_ prefs: IosReaderPreferences) {
        currentPrefs = prefs
        applyThemeBackground(theme: prefs.theme)
        let styleJs = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        for (_, webView) in loadedWebViews {
            webView.evaluateJavaScript(styleJs, completionHandler: nil)
        }
    }

    // MARK: - Decoration application (continuous-mode annotation/search highlights)

    /// Route a decoration group received from the Kotlin navigator into the correct JS injection
    /// path. Annotation groups (highlights / noteGlyphs / emphasis) are stored and merged on each
    /// update; search is applied independently. Unrecognised groups are silently dropped.
    func applyDecorationsGroup(json: String, group: String) {
        switch group {
        case ReaderDecorationGroups.shared.highlights:
            highlightsGroupJson = json
            reapplyAnnotationHighlights()
        case ReaderDecorationGroups.shared.noteGlyphs:
            noteGlyphsGroupJson = json
            reapplyAnnotationHighlights()
        case ReaderDecorationGroups.shared.emphasis:
            emphasisGroupJson = json
            reapplyAnnotationHighlights()
        case ReaderDecorationGroups.shared.search:
            searchGroupJson = json
            reapplySearchHighlights()
        default:
            break
        }
    }

    // MARK: - Window management

    private func loadWindow(around centerIndex: Int) {
        let low = max(0, centerIndex - windowRadius)
        let high = min(spineHrefs.count - 1, centerIndex + windowRadius)

        // Evict WebViews outside the new window, but never evict the pending initial chapter
        // before its height is measured and the initial scroll is applied. Without this guard,
        // a transient contentOffset reset to 0 (from a UIScrollView layout pass) causes
        // updatePosition → loadWindow(0), which evicts the target chapter before its
        // onHeightMeasured fires — leaving the view permanently at the beginning.
        let keysToEvict = loadedWebViews.keys.filter { key in
            guard key < low || key > high else { return false }
            return initialScrollApplied || key != pendingInitialIndex
        }
        for key in keysToEvict {
            loadedWebViews[key]?.removeFromSuperview()
            loadedWebViews.removeValue(forKey: key)
            // Release any pending gate lock for the evicted slot so the gate can't deadlock.
            compensationGate.end(key)
        }

        for idx in low...high {
            guard loadedWebViews[idx] == nil else { continue }
            let webView = makeWebView()
            loadedWebViews[idx] = webView
            contentContainer.addSubview(webView)
            positionWebView(webView, at: idx)
            loadChapter(index: idx, in: webView)
        }

        // Ensure the pending initial chapter is always loaded even when outside the current
        // window (e.g. the window has drifted to idx=0 due to a layout-pass contentOffset reset).
        if !initialScrollApplied,
           pendingInitialIndex < spineHrefs.count,
           loadedWebViews[pendingInitialIndex] == nil {
            let webView = makeWebView()
            loadedWebViews[pendingInitialIndex] = webView
            contentContainer.addSubview(webView)
            positionWebView(webView, at: pendingInitialIndex)
            loadChapter(index: pendingInitialIndex, in: webView)
        }
    }

    // MARK: - WebView creation

    private func makeWebView() -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(schemeHandler, forURLScheme: IosContinuousEpubSchemeHandler.scheme)
        let handler = WeakScriptMessageHandler(target: self)
        config.userContentController.add(handler, name: "riffleChapterHeight")
        config.userContentController.add(handler, name: "RiffleFigureBridge")
        config.userContentController.add(handler, name: "RiffleFigureBridgeLongPress")
        config.userContentController.add(handler, name: "riffleSelection")
        let figureBridgeShim = """
        window.RiffleFigureBridge = {
            onFigureTap: function(p) {
                window.webkit.messageHandlers.RiffleFigureBridge.postMessage(p);
            },
            onFigureLongPress: function(p) {
                window.webkit.messageHandlers.RiffleFigureBridgeLongPress.postMessage(p);
            }
        };
        """
        let selectionShim = """
        document.addEventListener('selectionchange', function() {
            var sel = window.getSelection();
            if (sel && sel.toString().length > 0) {
                window.webkit.messageHandlers.riffleSelection.postMessage(sel.toString());
            }
        });
        """
        config.userContentController.addUserScript(WKUserScript(
            source: figureBridgeShim, injectionTime: .atDocumentStart, forMainFrameOnly: false
        ))
        config.userContentController.addUserScript(WKUserScript(
            source: selectionShim, injectionTime: .atDocumentEnd, forMainFrameOnly: false
        ))
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.scrollView.scrollsToTop = false
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.navigationDelegate = self
        return webView
    }

    private func loadChapter(index: Int, in webView: WKWebView) {
        guard index < spineHrefs.count else { return }
        // Gate this slot: it may report a height that shifts above-viewport content.
        // onHeightMeasured will call compensationGate.end(index) once measured.
        compensationGate.begin(index)
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
        // Only compensate when the chapter is entirely above the viewport: if its bottom is within
        // the viewport the user is reading this chapter and should see it reflow naturally.
        let delta = newHeight - oldHeight
        if delta != 0 {
            let viewportY = outerScrollView.contentOffset.y
            let oldChapterBottom = oldChapterTop + oldHeight
            if oldChapterBottom <= viewportY {
                let compensated = max(0, viewportY + delta)
                outerScrollView.setContentOffset(CGPoint(x: 0, y: compensated), animated: false)
            }
        }

        // Release the gate for this slot: its height is settled and any above-viewport scroll
        // compensation has been applied. External navigateTo calls held by the gate can now fire.
        compensationGate.end(index)

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

        // totalProgression is the viewport midpoint as a fraction of the total stacked height.
        // Using height geometry rather than a uniform (chapterIndex / count) approximation gives
        // correct weighting when chapters vary in length — the chapter map cursor tracks exactly
        // where on the reading rail the user is, matching Android's continuous mode behaviour.
        let totalHeight = outerScrollView.contentSize.height
        let totalProgression = totalHeight > 0
            ? Double(midY / totalHeight).clamped(to: 0...1)
            : 0

        // Emit when: chapter changed, or in-chapter progression moved enough, or totalProgression
        // moved enough (drives the chapter-map cursor independently of chapter boundaries).
        let totalProgressionMoved = abs(totalProgression - lastReportedTotalProgression) > 0.002
        if idx != lastReportedIndex || abs(progression - lastReportedProgression) > 0.001 || totalProgressionMoved {
            lastReportedIndex = idx
            lastReportedProgression = progression
            lastReportedTotalProgression = totalProgression
            let href = spineHrefs[idx]
            let json = makeLocatorJson(href: href, progression: progression, totalProgression: totalProgression)
            onLocator?(json)
        }

        // When past 60% of the current chapter, bias the preload window one chapter forward so
        // the chapter after the immediate neighbour starts loading before the user reaches it.
        // This mirrors Android's deferred-load strategy and eliminates the blank-for-a-second
        // flash that appeared when radius=1 only started loading the next chapter on arrival.
        let preloadCenter = progression > 0.6
            ? min(idx + 1, spineHrefs.count - 1)
            : idx
        loadWindow(around: preloadCenter)
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
        // Re-establish contentSize and contentContainer.frame after bounds change (rotation,
        // first layout pass). Without this, the manually-set contentSize would be stale after
        // any bounds change that changes the scroll view's width.
        updateContentHeight()
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

    private func makeLocatorJson(href: String, progression: Double, totalProgression: Double) -> String {
        let clean = href.replacingOccurrences(of: "\"", with: "\\\"")
        return """
        {"href":"\(clean)","type":"application/xhtml+xml",\
        "locations":{"progression":\(progression),"totalProgression":\(totalProgression),"position":null},"text":{}}
        """
    }
}

// MARK: - Decoration helpers

extension IosContinuousScrollView {

    fileprivate func reapplyAnnotationHighlights() {
        let inputsByHref = buildAnnotationInputs()
        for (index, webView) in loadedWebViews {
            guard index < spineHrefs.count else { continue }
            let href = normalizeHref(spineHrefs[index])
            let inputs = inputsByHref[href] ?? []
            let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: inputs)
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    fileprivate func reapplySearchHighlights() {
        guard let decoded = try? JSONSerialization.jsonObject(with: Data(searchGroupJson.utf8)) as? [[String: Any]] else { return }
        let inputs = buildSearchInputsByHref(from: decoded)
        let clearJs = ContinuousAnnotationJs.shared.CLEAR_SEARCH_HIGHLIGHTS_JS
        for (index, webView) in loadedWebViews {
            guard index < spineHrefs.count else { continue }
            let href = normalizeHref(spineHrefs[index])
            let items = inputs[href] ?? []
            if items.isEmpty {
                webView.evaluateJavaScript(clearJs, completionHandler: nil)
            } else {
                let inactiveTexts = items.filter { !$0.isCurrent }.compactMap { $0.text }
                let activeItem = items.first { $0.isCurrent }
                let js = ContinuousAnnotationJs.shared.applySearchHighlightsJs(
                    inactiveTexts: inactiveTexts,
                    inactiveCssColor: "rgba(255,235,59,0.45)",
                    activeText: activeItem?.text,
                    activeProgression: activeItem?.progression ?? -1,
                    activeCssColor: "rgba(255,152,0,0.55)"
                )
                webView.evaluateJavaScript(js, completionHandler: nil)
            }
        }
    }

    fileprivate func buildAnnotationInputs() -> [String: [ContinuousAnnotationInput]] {
        guard let highlights = try? JSONSerialization.jsonObject(with: Data(highlightsGroupJson.utf8)) as? [[String: Any]] else { return [:] }
        let noteGlyphIds: Set<String> = {
            guard let ng = try? JSONSerialization.jsonObject(with: Data(noteGlyphsGroupJson.utf8)) as? [[String: Any]] else { return [] }
            return Set(ng.compactMap { $0["id"] as? String })
        }()
        let emphasisByAnnotationId: [String: String] = {
            guard let em = try? JSONSerialization.jsonObject(with: Data(emphasisGroupJson.utf8)) as? [[String: Any]] else { return [:] }
            var result: [String: String] = [:]
            for item in em {
                guard let id = item["id"] as? String, let styles = item["styles"] as? String else { continue }
                result[id] = styles
            }
            return result
        }()
        var byHref: [String: [ContinuousAnnotationInput]] = [:]
        for item in highlights {
            guard let id = item["id"] as? String,
                  let locator = item["locator"] as? [String: Any],
                  let rawHref = locator["href"] as? String else { continue }
            let href = normalizeHref(rawHref)
            let textDict = locator["text"] as? [String: Any]
            let text = textDict?["highlight"] as? String ?? ""
            let before = textDict?["before"] as? String ?? ""
            let after = textDict?["after"] as? String ?? ""
            let colorHex = item["color"] as? String ?? "#FFEB3B"
            let alpha = (item["alpha"] as? NSNumber)?.floatValue ?? 0.45
            let cssColor = hexToCssRgba(hex: colorHex, alpha: alpha)
            let hasNote = noteGlyphIds.contains(id)
            let emphasisTokens = emphasisByAnnotationId[id] ?? ""
            let input = ContinuousAnnotationInput(
                id: id, text: text, cssColor: cssColor,
                hasNote: hasNote, before: before, after: after,
                suppressMarkClick: false, emphasisTokens: emphasisTokens
            )
            byHref[href, default: []].append(input)
        }
        return byHref
    }

    private struct SearchItem { let text: String; let isCurrent: Bool; let progression: Float }

    private func buildSearchInputsByHref(from items: [[String: Any]]) -> [String: [SearchItem]] {
        var result: [String: [SearchItem]] = [:]
        for item in items {
            guard let locator = item["locator"] as? [String: Any],
                  let rawHref = locator["href"] as? String else { continue }
            let href = normalizeHref(rawHref)
            let text = (locator["text"] as? [String: Any])?["highlight"] as? String ?? ""
            let isCurrent = (item["isCurrent"] as? Bool) == true
            let locs = locator["locations"] as? [String: Any]
            let progression = Float((locs?["progression"] as? NSNumber)?.floatValue ?? 0)
            result[href, default: []].append(SearchItem(text: text, isCurrent: isCurrent, progression: progression))
        }
        return result
    }

    private func hexToCssRgba(hex: String, alpha: Float) -> String {
        let hexStr = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
        guard hexStr.count == 6,
              let red = UInt8(hexStr.prefix(2), radix: 16),
              let grn = UInt8(hexStr.dropFirst(2).prefix(2), radix: 16),
              let blu = UInt8(hexStr.dropFirst(4).prefix(2), radix: 16) else {
            return "rgba(255,235,59,0.45)"
        }
        return "rgba(\(red),\(grn),\(blu),\(String(format: "%.2f", alpha)))"
    }

    fileprivate func applyThemeBackground(theme: String) {
        let bg: UIColor
        switch theme {
        case "dark":  bg = UIColor(red: 0.071, green: 0.071, blue: 0.071, alpha: 1)
        case "sepia": bg = UIColor(red: 0.992, green: 0.965, blue: 0.91,  alpha: 1)
        default:      bg = .white
        }
        outerScrollView.backgroundColor = bg
        contentContainer.backgroundColor = bg
    }
}

// MARK: - Frame and content helpers

extension IosContinuousScrollView {

    fileprivate func positionWebView(_ webView: WKWebView, at index: Int) {
        let yPos = slotOffsets[index]
        let cht = slotHeights[index]
        webView.frame = CGRect(x: 0, y: yPos, width: contentContainer.bounds.width, height: cht)
    }

    fileprivate func updateAllWebViewFrames() {
        for (index, webView) in loadedWebViews {
            positionWebView(webView, at: index)
        }
    }

    fileprivate func updateContentHeight() {
        let total = (slotOffsets.last ?? 0) + (slotHeights.last ?? defaultSlotHeight)
        contentContainer.frame = CGRect(x: 0, y: 0, width: bounds.width, height: total)
        outerScrollView.contentSize = CGSize(width: bounds.width, height: total)
    }
}

// MARK: - Feature seams

extension IosContinuousScrollView {

    func scrollByPx(pixels: Int) -> Bool {
        let before = outerScrollView.contentOffset.y
        let after = max(0, min(before + CGFloat(pixels), outerScrollView.contentSize.height - outerScrollView.bounds.height))
        guard after != before else { return false }
        outerScrollView.setContentOffset(CGPoint(x: 0, y: after), animated: false)
        return true
    }

    func clearSelection() {
        let script = "window.getSelection().removeAllRanges();"
        for (_, webView) in loadedWebViews {
            webView.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    func applyDecorationsJs(group: String, script: String) {
        pendingDecorationScripts[group] = script
        for (_, webView) in loadedWebViews {
            webView.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    func evaluateJavaScript(script: String, completionHandler: ((Any?, Error?) -> Void)?) {
        let midY = outerScrollView.contentOffset.y + outerScrollView.bounds.height * 0.5
        let idx = slotIndexAt(yPos: midY)
        guard let webView = loadedWebViews[idx] else {
            completionHandler?(nil, nil)
            return
        }
        webView.evaluateJavaScript(script, completionHandler: completionHandler)
    }

}

// MARK: - UIGestureRecognizerDelegate

extension IosContinuousScrollView: UIGestureRecognizerDelegate {
    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        return true
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
        switch message.name {
        case "riffleChapterHeight":
            if let body = message.body as? [String: Any],
               let href = body["href"] as? String,
               let height = body["height"] as? Double {
                onHeightMeasured(href: href, height: CGFloat(height))
            }
        case "RiffleFigureBridge":
            if let payload = message.body as? String {
                figureTapCallback?(payload)
            }
        case "RiffleFigureBridgeLongPress":
            if let payload = message.body as? String {
                figureLongPressCallback?(payload)
            }
        case "riffleSelection":
            if let text = message.body as? String {
                selectionCallback?(text)
            }
        default:
            break
        }
    }
}

// MARK: - WKNavigationDelegate

extension IosContinuousScrollView: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard navigationAction.navigationType != .other,
              let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let urlString = url.absoluteString
        if urlString.hasPrefix("\(IosContinuousEpubSchemeHandler.scheme)://pub/") {
            let href = urlString.replacingOccurrences(of: "\(IosContinuousEpubSchemeHandler.scheme)://pub", with: "")
            navigateTo(href: href, progression: 0)
            decisionHandler(.cancel)
            return
        }
        if url.scheme == "http" || url.scheme == "https" {
            urlOpener?(url)
            decisionHandler(.cancel)
            return
        }
        if urlString.contains("noteref") {
            footnoteCallback?(urlString)
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let rawPath = webView.url?.path.removingPercentEncoding ?? ""
        let escapedPath = rawPath
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        // Mirrors Android HEIGHT_MEASUREMENT_JS: ResizeObserver re-reports on every layout change
        // (font swap, image decode, ReadiumCSS settle) so late growth is compensated rather than
        // clipping the end of the chapter. Font-ready gate prevents measuring with fallback-font
        // metrics. Safety re-measures at 300ms and 1000ms cover browsers without ResizeObserver.
        let heightJs = """
        (function(){
          if(window.__riffleMeasureWired)return;
          window.__riffleMeasureWired=true;
          var href='\(escapedPath)';
          function measure(){
            var de=document.documentElement,b=document.body;
            var bodyBottom=b?(b.getBoundingClientRect().bottom+(window.pageYOffset||0)):0;
            var rootPadBottom=0;
            if(de){var pb=parseFloat(window.getComputedStyle(de).paddingBottom);if(pb===pb)rootPadBottom=pb;}
            return Math.max(bodyBottom+rootPadBottom,de?de.offsetHeight:0,b?b.offsetHeight:0,1);
          }
          var last=-1;
          var held=false;
          function report(){
            if(held)return;
            var h=measure();
            if(h>0&&h!==last){last=h;window.webkit.messageHandlers.riffleChapterHeight.postMessage({href:href,height:h});}
          }
          window.__riffleReport=report;
          void(document.documentElement&&document.documentElement.offsetHeight);
          if(document.fonts&&document.fonts.ready&&document.fonts.status!=='loaded'){
            held=true;
            document.fonts.ready.then(function(){held=false;requestAnimationFrame(report);});
          } else {
            report();
          }
          if(window.ResizeObserver){
            var ro=new ResizeObserver(function(){report();});
            ro.observe(document.documentElement);
            if(document.body)ro.observe(document.body);
          }
          if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){requestAnimationFrame(report);});}
          setTimeout(report,300);
          setTimeout(report,1000);
        })();
        """
        webView.evaluateJavaScript(heightJs, completionHandler: nil)
        for (_, script) in pendingDecorationScripts {
            webView.evaluateJavaScript(script, completionHandler: nil)
        }
        // Re-apply any pending annotation highlights to this newly loaded chapter.
        let chapterHref = normalizeHref(rawPath)
        let inputsByHref = buildAnnotationInputs()
        let inputs = inputsByHref[chapterHref] ?? []
        if !inputs.isEmpty {
            let annJs = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: inputs)
            webView.evaluateJavaScript(annJs, completionHandler: nil)
        }
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

// MARK: - IosAboveSlotCompensationGate

/// Swift port of `AboveSlotCompensationGate` (feature:reader commonMain).
/// Sequences external `navigateTo` calls against pending above-viewport slot height measurements.
/// When a chapter above the viewport reports a new height asynchronously (JS message handler),
/// any `navigateTo` call that arrives in the window between chapter load and height measurement
/// would use stale `slotOffsets`. The gate defers those navigations until the slot is settled.
///
/// Each `loadChapter` call opens a gate with `begin(index)`; `onHeightMeasured` closes it with
/// `end(index)`. Eviction also calls `end(index)` so evicted slots can't deadlock the gate.
/// `navigateTo(href:progression:)` goes through `runWhenSettled`; the scroll fires immediately
/// when no compensation is pending, or once the last slot reports its height.
///
/// Design rules (mirror the Kotlin class): only the LATEST `runWhenSettled` block is kept;
/// older deferred calls are dropped because they were computed for stale geometry.
final class IosAboveSlotCompensationGate {
    private var pendingIndices = Set<Int>()
    private var deferred: (() -> Void)?

    var pending: Int { pendingIndices.count }

    func begin(_ index: Int) {
        pendingIndices.insert(index)
    }

    func end(_ index: Int) {
        guard pendingIndices.remove(index) != nil, pendingIndices.isEmpty else { return }
        let block = deferred
        deferred = nil
        block?()
    }

    func runWhenSettled(_ block: @escaping () -> Void) {
        if !pendingIndices.isEmpty { deferred = block } else { block() }
    }

    func cancelDeferred() { deferred = nil }

    func reset() {
        pendingIndices.removeAll()
        deferred = nil
    }
}

// MARK: - Comparable extension

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
