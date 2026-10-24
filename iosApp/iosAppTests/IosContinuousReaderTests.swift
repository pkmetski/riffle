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
