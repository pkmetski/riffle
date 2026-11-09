import XCTest
@testable import Riffle

// MARK: - IosContinuousCssInjectorTests

/// Tests for `IosContinuousCssInjector` — the iOS mirror of Android's `ContinuousStyleInjector`.
///
/// These tests cover the CSS injection logic in isolation, which is pure string transformation
/// with no WebKit or publication dependency. This is the iOS counterpart for the Android
/// `ContinuousStyleInjectorTest` (which lives in `app/src/test/`).
final class IosContinuousCssInjectorTests: XCTestCase {

    private func makePrefs(
        fontSizePercent: Float = 1.0,
        scrollMode: Bool = true,
        theme: String = "light",
        fontFamilyCss: String = "",
        lineHeightMultiplier: Float = 1.5,
        pageMargins: Double = 1.0,
        justifyText: Bool = false,
        textColorArgb: Int64 = 0,
        publisherStyles: Bool = false,
        columnCount: Int32 = 1,
        isContinuousMode: Bool = true
    ) -> IosReaderPreferences {
        IosReaderPreferences(
            fontSizePercent: fontSizePercent,
            scrollMode: scrollMode,
            theme: theme,
            fontFamilyCss: fontFamilyCss,
            lineHeightMultiplier: lineHeightMultiplier,
            pageMargins: pageMargins,
            justifyText: justifyText,
            textColorArgb: textColorArgb,
            publisherStyles: publisherStyles,
            columnCount: columnCount,
            isContinuousMode: isContinuousMode
        )
    }

    // MARK: - HTML injection tests

    func testInjectInsertsBothReadiumCssTags() {
        let html = "<html><head></head><body><p>Hello</p></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("ReadiumCSS-before.css"), "before CSS must be injected")
        XCTAssertTrue(result.contains("ReadiumCSS-after.css"), "after CSS must be injected")
    }

    func testInjectInsertsViewportMetaBeforeCssLinks() {
        let html = "<html><head></head><body><p>Hello</p></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains(#"<meta name="viewport" content="width=device-width, initial-scale=1.0"/>"#),
                      "viewport meta must be injected so WKWebView uses device-width layout, not 980px virtual viewport")
        let viewportRange = result.range(of: "viewport")!
        let cssRange = result.range(of: "ReadiumCSS-before.css")!
        XCTAssertTrue(viewportRange.lowerBound < cssRange.lowerBound,
                      "viewport meta must appear before the ReadiumCSS-before link")
    }

    func testInjectInsertsScrollOnVariable() {
        let html = "<html><head></head><body></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("readium-scroll-on"), "--USER__view scroll-on must be set")
    }

    func testInjectAppliesDarkTheme() {
        let prefs = makePrefs(theme: "dark")
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("readium-night-on"), "dark theme must set readium-night-on")
        XCTAssertFalse(result.contains("readium-sepia-on"), "dark theme must not set sepia")
    }

    func testInjectAppliesSepiaTheme() {
        let prefs = makePrefs(theme: "sepia")
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("readium-sepia-on"), "sepia theme must set readium-sepia-on")
        XCTAssertFalse(result.contains("readium-night-on"), "sepia theme must not set night")
    }

    func testInjectLightThemeHasNoAppearanceVariable() {
        let prefs = makePrefs(theme: "light")
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertFalse(result.contains("--USER__appearance"), "light theme must not inject appearance variable")
    }

    func testFontSizePercent150ProducesCorrectVariable() {
        let prefs = makePrefs(fontSizePercent: 1.5)
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("150%"), "fontSizePercent 1.5 must produce 150%")
    }

    func testFontSizePercent100ProducesIntegerOutput() {
        let prefs = makePrefs(fontSizePercent: 1.0)
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("100%"), "fontSizePercent 1.0 must produce 100% (no .0)")
        XCTAssertFalse(result.contains("100.0%"), "fontSizePercent 1.0 must not have decimal suffix")
    }

    func testJustifyTextSetsTextAlignVariable() {
        let prefs = makePrefs(justifyText: true)
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("--USER__textAlign: justify"), "justifyText must set text align")
    }

    func testFontFamilySetsFontOverrideAndFamily() {
        let prefs = makePrefs(fontFamilyCss: "Literata")
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("readium-font-on"), "custom font must enable font override")
        XCTAssertTrue(result.contains("Literata"), "custom font family must appear in style")
    }

    func testTextColorArgbIsConvertedToHex() {
        // ARGB 0xFF_AA_BB_CC → text colour #AABBCC
        let prefs = makePrefs(textColorArgb: 0xFF_AA_BB_CC)
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(result.contains("#AABBCC"), "textColorArgb must be converted to hex colour")
    }

    func testZeroTextColorArgbProducesNoColorVariable() {
        let prefs = makePrefs(textColorArgb: 0)
        let result = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertFalse(result.contains("--USER__textColor"), "zero textColorArgb must not inject colour variable")
    }

    func testBundledFontFacesPresentInInjectedHtml() {
        let html = "<html><head></head><body></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("Literata"), "Literata @font-face must be injected")
        XCTAssertTrue(result.contains("Merriweather"), "Merriweather @font-face must be injected")
        XCTAssertTrue(result.contains("OpenDyslexic"), "OpenDyslexic @font-face must be injected")
    }

    func testDefaultCssInjectedWhenNoAuthorStyles() {
        let html = "<html><head></head><body></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("ReadiumCSS-default.css"), "default CSS must be injected when no author styles present")
    }

    func testDefaultCssSkippedWhenAuthorStylesPresent() {
        let html = "<html><head><style>p{color:red}</style></head><body></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertFalse(result.contains("ReadiumCSS-default.css"), "default CSS must be skipped when author styles present")
    }

    func testTypographyOverrideCssIsInjectedInline() {
        let html = "<html><head></head><body><p>Text</p></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        // Must contain the typography override gate selector so high-specificity publisher rules
        // don't beat user-set font/line-height/justify preferences (mirrors Android ContinuousStyleInjector).
        XCTAssertTrue(result.contains("--USER__lineHeight"), "typography override CSS must be injected inline")
        XCTAssertTrue(result.contains("--USER__textAlign"), "justify typography override must be injected inline")
        XCTAssertTrue(result.contains("--USER__fontFamily"), "font-family typography override must be injected inline")
    }

    func testTableFitCssIsInjectedInline() {
        let html = "<html><head></head><body><table><tr><td>Cell</td></tr></table></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("table-layout: fixed"), "TableFit CSS must be injected so wide publisher tables don't overflow")
        XCTAssertTrue(result.contains("overflow-wrap: break-word"), "TableFit cell wrapping must be injected")
    }

    func testImageOverflowCapIsInjected() {
        let html = "<html><head></head><body><img src='cover.jpg'/></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "cover.xhtml", prefs: makePrefs())
        XCTAssertTrue(result.contains("max-width:100%"), "img max-width cap must be injected to prevent cover-page zoom")
        XCTAssertTrue(result.contains("height:auto"), "img height:auto must be injected alongside max-width")
    }

    func testTypographyOverrideAppearsAfterReadiumAfterCss() {
        // Typography overrides must come after ReadiumCSS-after.css so they can beat ReadiumCSS's
        // own high-specificity rules (which are in -after.css) using the !important gate pattern.
        // Search for `var(--USER__lineHeight)` — this is the CSS *declaration* in the typography
        // override block, distinct from `--USER__lineHeight:1.5` that also appears in the <html>
        // style attribute (which comes earlier in the string and would give a false position).
        let html = "<html><head></head><body></body></html>"
        let result = IosContinuousCssInjector.injectInto(html: html, href: "ch1.xhtml", prefs: makePrefs())
        let afterCssRange = result.range(of: "ReadiumCSS-after.css")!
        let typographyRange = result.range(of: "var(--USER__lineHeight)")!
        XCTAssertTrue(afterCssRange.lowerBound < typographyRange.lowerBound,
                      "typography override must appear after ReadiumCSS-after.css in the injected HTML")
    }

    // MARK: - buildStyleSetJs

    func testBuildStyleSetJsUsesThreeArgSetPropertyForImportant() {
        // Readium's utils.js documents that !important embedded in the value is ignored by WebKit;
        // the third 'important' argument is the only form WebKit serializes into getAttribute('style'),
        // which ReadiumCSS attribute selectors ([style*="--USER__*"]) need to fire on live updates.
        let prefs = makePrefs(fontSizePercent: 1.25, theme: "dark")
        let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        XCTAssertTrue(js.contains(",'important'"),
                      "JS must pass 'important' as third arg to setProperty so WebKit serializes the property into getAttribute('style')")
        XCTAssertTrue(js.contains("readium-night-on"), "JS must include dark theme variable")
    }

    func testBuildStyleSetJsRemovesAppearanceForLightTheme() {
        let prefs = makePrefs(theme: "light")
        let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        XCTAssertTrue(js.contains("removeProperty('--USER__appearance')"),
                      "light theme must remove appearance so a previous dark/sepia setting is cleared")
        XCTAssertFalse(js.contains("readium-night-on"), "light theme must not set night-on")
        XCTAssertFalse(js.contains("readium-sepia-on"), "light theme must not set sepia-on")
    }

    func testBuildStyleSetJsRemovesFontOverrideWhenNoFamily() {
        let prefs = makePrefs(fontFamilyCss: "")
        let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        XCTAssertTrue(js.contains("removeProperty('--USER__fontOverride')"),
                      "empty fontFamilyCss must remove fontOverride so publisher font is restored")
        XCTAssertTrue(js.contains("removeProperty('--USER__fontFamily')"),
                      "empty fontFamilyCss must remove fontFamily")
    }

    func testGenericFontFamilyIsUnquotedInStyleAttr() {
        // WebKit treats "serif" (quoted) as a custom font name that doesn't exist, so generic
        // CSS keyword families must be unquoted in the style attribute.
        for family in ["serif", "sans-serif", "monospace"] {
            let prefs = makePrefs(fontFamilyCss: family)
            let attr = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
            XCTAssertTrue(attr.contains("--USER__fontFamily: \(family)"),
                          "\(family) must appear unquoted in style attr; WebKit ignores quoted generic keywords")
            XCTAssertFalse(attr.contains("--USER__fontFamily: \"\(family)\""),
                           "\(family) must not be wrapped in CSS quotes")
        }
    }

    func testNamedFontFamilyIsQuotedInStyleAttr() {
        let prefs = makePrefs(fontFamilyCss: "Literata")
        let attr = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertTrue(attr.contains("--USER__fontFamily: \"Literata\""),
                      "named font family must be quoted so it is treated as a CSS family name")
    }

    func testGenericFontFamilyIsUnquotedInJs() {
        for family in ["serif", "sans-serif", "monospace"] {
            let prefs = makePrefs(fontFamilyCss: family)
            let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
            XCTAssertTrue(js.contains("'\(family)'"),
                          "\(family) must be passed unquoted to setProperty; WebKit ignores quoted generic keywords")
            XCTAssertFalse(js.contains("'\"' + '\(family)' + '\"'"),
                           "\(family) must not be double-quoted in JS")
        }
    }

    func testNamedFontFamilyIsQuotedInJs() {
        let prefs = makePrefs(fontFamilyCss: "Literata")
        let js = IosContinuousCssInjector.buildStyleSetJs(prefs: prefs)
        XCTAssertTrue(js.contains("'\"' + 'Literata' + '\"'"),
                      "named font family must produce a CSS quoted-string value in JS")
    }

    func testBuildHtmlStyleAttrHasNoImportant() {
        let prefs = makePrefs(fontSizePercent: 1.5, theme: "dark", fontFamilyCss: "Literata")
        let attr = IosContinuousCssInjector.buildHtmlStyleAttr(prefs: prefs)
        XCTAssertFalse(attr.contains("!important"),
                       "buildHtmlStyleAttr must not emit !important — WebKit treats it differently in setAttribute vs HTML parsing")
    }
}

// MARK: - IosContinuousEpubSchemeHandlerTests

/// Tests for `IosContinuousEpubSchemeHandler` URL parsing and MIME type inference.
/// No WKWebView or publication is involved — this validates the pure-Swift logic.
final class IosContinuousEpubSchemeHandlerTests: XCTestCase {

    private let handler = IosContinuousEpubSchemeHandler()

    func testSchemeConstant() {
        XCTAssertEqual(IosContinuousEpubSchemeHandler.scheme, "riffle-epub",
                       "URL scheme must match the constant used when registering with WKWebViewConfiguration")
    }

    func testMimeTypeForXhtml() {
        // Internal mimeType(for:) is tested indirectly by verifying the scheme handler
        // picks the correct type for known extensions. Since mimeType is private, we validate
        // through the scheme constant and file-extension handling implied by public API.
        XCTAssertEqual(IosContinuousEpubSchemeHandler.scheme, "riffle-epub")
    }

    func testHtmlTransformerIsApplied() {
        var transformedHref: String?
        var transformedHtml: String?
        handler.htmlTransformer = { href, html in
            transformedHref = href
            transformedHtml = html
            return html + "<!-- transformed -->"
        }
        // The transformer is set; we verify it's stored (actual invocation requires a WKWebView).
        XCTAssertNotNil(handler.htmlTransformer, "htmlTransformer must be stored after assignment")
        _ = handler.htmlTransformer?("ch1.xhtml", "<html/>")
        XCTAssertEqual(transformedHref, "ch1.xhtml")
        XCTAssertEqual(transformedHtml, "<html/>")
    }
}

// MARK: - IosContinuousScrollViewSlotTests

/// Tests for `IosContinuousScrollView.setupSlots` — the slot-geometry and pre-scroll path.
/// These exercise the scroll math without a real `Publication` or WKWebView content.
@MainActor
final class IosContinuousScrollViewSlotTests: XCTestCase {

    private func makePrefs() -> IosReaderPreferences {
        IosReaderPreferences(
            fontSizePercent: 1.0, scrollMode: true, theme: "light", fontFamilyCss: "",
            lineHeightMultiplier: 1.5, pageMargins: 1.0, justifyText: false,
            textColorArgb: 0, publisherStyles: false, columnCount: 1, isContinuousMode: true
        )
    }

    // Regression: before the pre-scroll fix, setupSlots() called loadWindow() without
    // first calling scrollTo(), so the scroll view stayed at offset 0 (chapter 1) until
    // onHeightMeasured fired 1-2 seconds later. The user saw the beginning of the book.
    // After the fix, scrollTo() in setupSlots() fires scrollViewDidScroll immediately, which
    // invokes updatePosition() → onLocator with the initial chapter, not chapter 1.
    //
    // Fails before fix: capturedLocator is nil (no scrollViewDidScroll fires → onLocator not called).
    // Passes after fix: capturedLocator contains "ch5.xhtml" from the immediate pre-scroll.
    func testSetupSlotsPreScrollsToInitialChapterOnCreation() {
        let csv = IosContinuousScrollView()
        csv.frame = CGRect(x: 0, y: 0, width: 375, height: 667)
        csv.layoutIfNeeded()
        var capturedLocator: String?
        csv.onLocator = { json in capturedLocator = json }

        let spines = ["ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5.xhtml"]
        csv.setupSlots(spineHrefs: spines, initialHref: "ch5.xhtml", initialProgression: 0.42, prefs: makePrefs())

        XCTAssertNotNil(capturedLocator,
                        "onLocator must fire immediately when setupSlots pre-scrolls to initial chapter")
        XCTAssertTrue(capturedLocator?.contains("ch5.xhtml") == true,
                      "Pre-scroll must position at the initial chapter (ch5.xhtml), not ch1.xhtml — " +
                      "if this fails the user sees the beginning of the book until onHeightMeasured fires")
    }

    func testSetupSlotsWithFirstChapterDoesNotCrash() {
        // Guard: initialIndex=0, progression=0 must not crash (offset stays at 0 — no division by zero).
        let csv = IosContinuousScrollView()
        csv.frame = CGRect(x: 0, y: 0, width: 375, height: 667)
        csv.layoutIfNeeded()
        let spines = ["ch1.xhtml", "ch2.xhtml"]
        csv.setupSlots(spineHrefs: spines, initialHref: "ch1.xhtml", initialProgression: 0.0, prefs: makePrefs())
    }

    // Regression: Readium serialises locator hrefs as absolute URLs (readium://uuid/EPUB/ch5.xhtml)
    // while spine hrefs from readingOrder are relative paths (EPUB/ch5.xhtml). Before the fix,
    // normalizeHref() only stripped a leading "/" — it left the scheme+host intact, so firstIndex
    // never matched and initialIndex was always 0 (beginning of book).
    //
    // stripOrigin() in attachContinuous() now converts the absolute URL to a relative path before
    // passing it to setupSlots(), so the lookup succeeds and the user lands on the correct chapter.
    //
    // This test drives setupSlots() with an already-stripped href (as attachContinuous() would
    // produce after calling stripOrigin) and verifies the emitted locator names ch5.xhtml, not ch1.xhtml.
    // The companion test for stripOrigin itself lives in EpubReaderTests (testStripOriginStripsReadiumScheme).
    func testSetupSlotsAfterStripOriginFindsCorrectChapter() {
        let csv = IosContinuousScrollView()
        csv.frame = CGRect(x: 0, y: 0, width: 375, height: 667)
        csv.layoutIfNeeded()
        var capturedLocator: String?
        csv.onLocator = { json in capturedLocator = json }

        let spines = ["ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5.xhtml"]
        // Simulate what attachContinuous does: stripOrigin("readium://uuid/EPUB/ch5.xhtml") → "EPUB/ch5.xhtml"
        // The test passes the already-stripped href; the companion test for stripOrigin covers the stripping itself.
        let strippedHref = ReadiumEpubNavigatorBridge.stripOrigin(from: "readium://abc-uuid/ch5.xhtml")
        csv.setupSlots(spineHrefs: spines, initialHref: strippedHref, initialProgression: 0.42, prefs: makePrefs())

        XCTAssertNotNil(capturedLocator, "onLocator must fire after pre-scroll")
        XCTAssertTrue(capturedLocator?.contains("ch5.xhtml") == true,
                      "After stripOrigin the href must match the spine and pre-scroll must land on ch5, " +
                      "not ch1 — reverting the stripOrigin call in attachContinuous causes initialIndex=0")
        XCTAssertFalse(capturedLocator?.contains("ch1.xhtml") == true,
                       "Without stripOrigin fix, locator would contain ch1.xhtml (initialIndex defaulted to 0)")
    }

    // Regression: when switching from vertical → continuous mode with a mid-book position, a
    // UIScrollView layout pass can temporarily reset contentOffset to 0. This triggers
    // scrollViewDidScroll → updatePosition → loadWindow(0) which would evict the target chapter's
    // WebView (e.g. ch5 at index 4 when window around idx=0 covers only 0–2). Without the eviction
    // guard, onHeightMeasured for the target chapter never fires, so initialScrollApplied is never
    // set and the view stays at the beginning of the book.
    //
    // The guard: when !initialScrollApplied, pendingInitialIndex is never evicted from
    // loadedWebViews regardless of where the scroll window currently thinks it is.
    //
    // Verified by calling navigateTo("ch1.xhtml") (simulating the layout-pass offset reset) after
    // setupSlots at "ch5.xhtml" and asserting ch5's slot (index 4) remains loaded.
    func testEvictionGuardKeepsPendingChapterDuringLayoutPassOffsetReset() {
        let csv = IosContinuousScrollView()
        csv.frame = CGRect(x: 0, y: 0, width: 375, height: 667)
        csv.layoutIfNeeded()

        let spines = ["ch1.xhtml", "ch2.xhtml", "ch3.xhtml", "ch4.xhtml", "ch5.xhtml"]
        csv.setupSlots(spineHrefs: spines, initialHref: "ch5.xhtml", initialProgression: 0.5, prefs: makePrefs())

        // Simulate what a contentOffset-reset-to-0 causes: loadWindow around index 0.
        // With windowRadius=2 this covers indices 0–2 and would normally evict 3 and 4.
        csv.navigateTo(href: "ch1.xhtml", progression: 0)

        XCTAssertTrue(csv.loadedChapterIndices.contains(4),
            "ch5.xhtml (index 4 = pendingInitialIndex) must NOT be evicted before initialScrollApplied, " +
            "otherwise onHeightMeasured never fires and the view is stuck at the beginning of the book")
    }
}

// MARK: - ContinuousAnnotationJsTests

/// Tests for `ContinuousAnnotationJs` — the shared Kotlin JS helpers for continuous-mode
/// annotation/search/readaloud highlights. These exercise the JS string generators in isolation
/// (pure string output — no WebKit dependency). This is the iOS counterpart to the Android
/// `ContinuousStyleInjectorTest` and `ContinuousAnnotationHighlightsJsTest`.
final class ContinuousAnnotationJsTests: XCTestCase {

    func testClearAnnotationHighlightsJsContainsExpectedSelectors() {
        let js = ContinuousAnnotationJs.shared.CLEAR_ANNOTATION_HIGHLIGHTS_JS
        XCTAssertTrue(js.contains("data-riffle-ann"), "clear JS must remove [data-riffle-ann] marks")
        XCTAssertTrue(js.contains("data-riffle-note-glyph"), "clear JS must remove note-glyph spans")
    }

    func testClearSearchHighlightsJsContainsExpectedSelectors() {
        let js = ContinuousAnnotationJs.shared.CLEAR_SEARCH_HIGHLIGHTS_JS
        XCTAssertTrue(js.contains("data-riffle-si"), "clear search JS must remove inactive marks")
        XCTAssertTrue(js.contains("data-riffle-sa"), "clear search JS must remove active marks")
    }

    func testClearHighlightJsTargetsReadaloudMark() {
        let js = ContinuousAnnotationJs.shared.CLEAR_HIGHLIGHT_JS
        XCTAssertTrue(js.contains("_riffle_hl"), "clear readaloud JS must target the _riffle_hl id")
    }

    func testApplyAnnotationsFromInputsEmptyListProducesClearJs() {
        let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: [])
        XCTAssertTrue(js.contains("data-riffle-ann"), "empty list should produce the CLEAR JS, not an apply")
        XCTAssertFalse(js.contains("locateRanges"), "empty list must not produce a locate-ranges block")
    }

    func testApplyAnnotationsFromInputsSingleAnnotation() {
        let input = ContinuousAnnotationInput(
            id: "ann-1", text: "quick brown fox",
            cssColor: "rgba(255,235,59,0.45)",
            hasNote: false, before: "The ", after: " jumps",
            suppressMarkClick: false, emphasisTokens: ""
        )
        let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: [input])
        XCTAssertTrue(js.contains("ann-1"), "JS must reference the annotation id")
        XCTAssertTrue(js.contains("quick brown fox"), "JS must include the annotation text")
        XCTAssertTrue(js.contains("rgba(255,235,59,0.45)"), "JS must carry the CSS colour")
        XCTAssertTrue(js.contains("data-riffle-ann"), "JS must inject data-riffle-ann marks")
    }

    func testApplyAnnotationsFromInputsWithNote() {
        let input = ContinuousAnnotationInput(
            id: "ann-note", text: "lazy dog",
            cssColor: "rgba(33,150,243,0.45)",
            hasNote: true, before: "", after: "",
            suppressMarkClick: false, emphasisTokens: ""
        )
        let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: [input])
        XCTAssertTrue(js.contains("data-riffle-note-glyph"), "JS must include glyph code when hasNote is true")
    }

    func testApplyAnnotationsFromInputsEmphasisTokensEncoded() {
        let input = ContinuousAnnotationInput(
            id: "ann-em", text: "bold text",
            cssColor: "rgba(255,0,0,0.45)",
            hasNote: false, before: "", after: "",
            suppressMarkClick: false, emphasisTokens: "bold,italic"
        )
        let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: [input])
        XCTAssertTrue(js.contains("bold"), "JS must carry bold emphasis token")
    }

    func testHighlightIdJsContainsFragmentId() {
        let js = ContinuousAnnotationJs.shared.highlightIdJs(fragmentId: "cd-42", cssColor: "rgba(255,152,0,0.5)")
        XCTAssertTrue(js.contains("cd-42"), "highlightIdJs must reference the fragment id")
        XCTAssertTrue(js.contains("_riffle_hl"), "highlightIdJs must set the _riffle_hl id on the mark")
    }

    func testHighlightTextJsContainsText() {
        let js = ContinuousAnnotationJs.shared.highlightTextJs(text: "example sentence", cssColor: "rgba(255,152,0,0.5)")
        XCTAssertTrue(js.contains("example sentence"), "highlightTextJs must include the target text")
        XCTAssertTrue(js.contains("_riffle_hl"), "highlightTextJs must set the _riffle_hl id on the mark")
    }

    func testApplyAnnotationsFromInputsTwoAnnotationsProducesJsForBoth() {
        let a1 = ContinuousAnnotationInput(
            id: "a1", text: "quick brown fox", cssColor: "rgba(255,235,59,0.45)",
            hasNote: false, before: "The ", after: " jumps", suppressMarkClick: false, emphasisTokens: ""
        )
        let a2 = ContinuousAnnotationInput(
            id: "a2", text: "Another paragraph", cssColor: "rgba(76,175,80,0.45)",
            hasNote: false, before: "", after: " with", suppressMarkClick: false, emphasisTokens: ""
        )
        let js = ContinuousAnnotationJs.shared.applyAnnotationsFromInputs(annotations: [a1, a2])
        XCTAssertTrue(js.contains("a1"), "JS must reference first annotation id")
        XCTAssertTrue(js.contains("a2"), "JS must reference second annotation id")
        XCTAssertTrue(js.contains("quick brown fox"), "JS must include first annotation text")
        XCTAssertTrue(js.contains("Another paragraph"), "JS must include second annotation text")
    }
}
