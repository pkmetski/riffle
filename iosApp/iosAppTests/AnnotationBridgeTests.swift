import XCTest
import UIKit
import ReadiumNavigator
import ReadiumShared
@testable import Riffle

/// The part of the annotation pipeline Kotlin cannot cover: what Readium actually puts in the
/// document.
///
/// Everything above this seam — the merge policy, the anchor arithmetic, the decoration mapper,
/// the colour resolution — runs in `commonTest` on `iosSimulatorArm64`. All of it stops at
/// `ReadiumSwiftNavigator`, which hands a decoration payload to this bridge. If the locator it
/// builds cannot be resolved to a DOM range, `DecorationGroup.add` logs "Can't locate DOM range"
/// and drops the decoration — every Kotlin test stays green and the reader shows nothing. That
/// is exactly the state iOS was in, so these tests assert on the *document*.
///
/// In the `iosAppUnitTests` target (no `XCUIApplication`), alongside `CadenceBridgeTests`.
final class AnnotationBridgeTests: XCTestCase {

    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    // MARK: - Fixture

    /// A phrase that exists exactly once in the fixture's first chapter.
    private let anchorText = "This is the opening of the first chapter."
    private let anchorAfter = " The words here serve to fill"

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: AnnotationBridgeTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    private func preferences(scroll: Bool) -> IosReaderPreferences {
        IosReaderPreferences(
            fontSizePercent: 1.0,
            scrollMode: scroll,
            theme: "light",
            fontFamilyCss: "",
            lineHeightMultiplier: 0.0,
            pageMargins: 1.0,
            justifyText: false,
            textColorArgb: 0,
            publisherStyles: true,
            columnCount: 1
        )
    }

    private func openFixture(scroll: Bool = true) -> ReadiumEpubNavigatorBridge? {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyReaderPreferences(preferences: preferences(scroll: scroll))
        // Registered BEFORE open so `attach` re-registers it on the fresh navigator — the path
        // production takes, since the coordinator asks for its groups once per reader.
        for group in ReaderDecorationGroups.shared.activable {
            bridge.observeDecorationGroup(group: group as? String ?? "")
        }

        let loaded = expectation(description: "first chapter loaded")
        loaded.assertForOverFulfill = false
        bridge.setPageLoadCallback { loaded.fulfill() }

        let hostWindow = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        hostWindow.rootViewController = bridge.viewController()
        hostWindow.makeKeyAndVisible()
        window = hostWindow

        bridge.openEpub(filePath: bundledEpubPath("test.epub"), locatorJson: nil)

        guard XCTWaiter().wait(for: [loaded], timeout: 30) == .completed else {
            XCTFail("the fixture EPUB never reported a page load, so the annotation seam cannot be exercised")
            return nil
        }
        settle(seconds: 1.0)
        return bridge
    }

    private func settle(seconds: TimeInterval) {
        RunLoop.current.run(until: Date(timeIntervalSinceNow: seconds))
    }

    @discardableResult
    private func eval(_ bridge: ReadiumEpubNavigatorBridge, _ script: String) -> String? {
        let done = expectation(description: "evaluateJavaScript")
        var result: String?
        bridge.evaluateJavaScript(script: script) { value in
            result = value
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 20), .completed,
                       "evaluateJavaScript must always invoke its callback")
        return result
    }

    private func evalInt(_ bridge: ReadiumEpubNavigatorBridge, _ script: String) -> Int {
        Int(Double(eval(bridge, script) ?? "") ?? -1)
    }

    /// Number of laid-out decoration items in `group`, read from the document Readium built.
    ///
    /// Readium appends one container div per group (`data-group="…"`) to `<body>` and one child
    /// per placed decoration. A decoration whose locator could not be resolved never produces a
    /// child, which is the failure mode this whole file exists to catch. Retries because the
    /// decoration JS runs on `requestAnimationFrame`.
    private func decorationCount(_ bridge: ReadiumEpubNavigatorBridge, group: String) -> Int {
        var last = 0
        for _ in 0..<40 {
            last = evalInt(bridge, """
            (function(){var c=document.querySelector('[data-group="\(group)"]');\
            return c?c.children.length:0;})()
            """)
            if last > 0 { return last }
            settle(seconds: 0.05)
        }
        return last
    }

    private func highlightsGroup() -> String { ReaderDecorationGroups.shared.highlights }
    private func noteGlyphGroup() -> String { ReaderDecorationGroups.shared.noteGlyphs }
    private func emphasisGroup() -> String { ReaderDecorationGroups.shared.emphasis }

    private func textAnchoredLocator() -> String {
        let escapedAnchor = anchorText
        return """
        {"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml",\
        "text":{"highlight":"\(escapedAnchor)","after":"\(anchorAfter)"},\
        "locations":{"cfi":"/4/4,/1:0,/1:40","progression":0.0}}
        """
    }

    private func cfiOnlyLocator() -> String {
        """
        {"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml",\
        "locations":{"cfi":"/4/4,/1:0,/1:40","progression":0.0}}
        """
    }

    // MARK: - The defect the whole feature rested on

    func testACfiOnlyLocatorPlacesNothingAndATextAnchoredOnePlacesTheHighlight() {
        guard let bridge = openFixture() else { return }

        // What `annotationLocatorJson` produced before this change. Readium's `rangeFromLocator`
        // reads text.highlight / cssSelector / fragments and never locations.cfi, so the range
        // is nil and the decoration is dropped — silently, with the right colour.
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"cfi-only\",\"type\":\"highlight\",\"locator\":\(cfiOnlyLocator())}]",
            group: highlightsGroup()
        )
        settle(seconds: 1.0)
        XCTAssertEqual(
            0, decorationCount(bridge, group: highlightsGroup()),
            "a cfi-only locator cannot be resolved by Readium; if this ever becomes non-zero the "
                + "text anchor is no longer load-bearing and this file should be revisited"
        )

        // What `annotationDecorationLocatorJson` produces now.
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"anchored\",\"type\":\"highlight\",\"locator\":\(textAnchoredLocator()),"
                + "\"color\":\"#FBBF24\",\"alpha\":0.502}]",
            group: highlightsGroup()
        )
        XCTAssertGreaterThan(
            decorationCount(bridge, group: highlightsGroup()), 0,
            "the text-anchored locator must place the highlight in the document — this is the "
                + "difference between a visible annotation and none at all"
        )
        bridge.disposeNavigator()
    }

    func testTheHighlightPaintsThePalettesOwnAlphaNotTheTemplateDefault() {
        // Readium's default template renders `tint.cssValue(alpha: 0.3)`, and `cssValue(alpha:)`
        // SUBSTITUTES that for the colour's own alpha. Riffle's palette bakes 0x80 (≈0.502) into
        // HighlightColor.argb, so the stock template would paint every highlight at a different
        // opacity from Android's while every Kotlin colour test stayed green.
        guard let bridge = openFixture() else { return }
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"a\",\"type\":\"highlight\",\"locator\":\(textAnchoredLocator()),"
                + "\"color\":\"#FBBF24\",\"alpha\":0.502}]",
            group: highlightsGroup()
        )
        XCTAssertGreaterThan(decorationCount(bridge, group: highlightsGroup()), 0)

        // Every styled node under the group container: Readium wraps each decoration in an item
        // container and puts one box per line inside it, and it sets `pointer-events` on the
        // boxes — so picking "the first div with a style attribute" finds the wrapper, not the
        // painted box.
        let css = eval(bridge, """
        (function(){var c=document.querySelector('[data-group="\(highlightsGroup())"]');\
        if(!c)return '';var out=[];var n=c.querySelectorAll('*');\
        for(var i=0;i<n.length;i++){var s=n[i].getAttribute('style');if(s)out.push(s);}\
        return out.join(' | ');})()
        """) ?? ""
        XCTAssertTrue(css.contains("251, 191, 36"), "expected HighlightColor.YELLOW's rgb, got: \(css)")

        // Compare the rendered alpha numerically: UIColor quantises the component to 8 bits, so
        // the string is "0.5" rather than the "0.502" that went in.
        let alpha = Self.renderedAlpha(in: css)
        XCTAssertNotNil(alpha, "no rgba() alpha in: \(css)")
        XCTAssertEqual(
            alpha ?? 0,
            Double(ReaderHighlightDefaults.shared.highlightAlpha),
            accuracy: 0.02,
            "expected the palette's baked 0x80 alpha; the stock template would render 0.3"
        )
        bridge.disposeNavigator()
    }

    /// The alpha of the first `rgba(r, g, b, a)` in `css`.
    static func renderedAlpha(in css: String) -> Double? {
        guard let open = css.range(of: "rgba("),
              let close = css.range(of: ")", range: open.upperBound..<css.endIndex)
        else { return nil }
        let parts = css[open.upperBound..<close.lowerBound].split(separator: ",")
        guard parts.count == 4 else { return nil }
        return Double(parts[3].trimmingCharacters(in: .whitespaces))
    }

    // MARK: - The surfaces that had no producer

    func testANoteGlyphRendersAMarkerInTheMargin() {
        guard let bridge = openFixture() else { return }
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"n1\",\"type\":\"noteGlyph\",\"locator\":\(textAnchoredLocator())}]",
            group: noteGlyphGroup()
        )
        XCTAssertGreaterThan(
            decorationCount(bridge, group: noteGlyphGroup()), 0,
            "a highlight-with-note was indistinguishable from a plain one because nothing produced this"
        )
        XCTAssertGreaterThan(
            evalInt(bridge, "document.querySelectorAll('.riffle-note-glyph-icon').length"), 0,
            "the glyph element itself must be in the document, not just a bounds box"
        )
        // data-activable is what makes Readium hit-test the small gutter icon rather than the
        // text-covering bounds div; without it the glyph is decorative and untappable.
        XCTAssertGreaterThan(
            evalInt(bridge, "document.querySelectorAll('.riffle-note-glyph-icon[data-activable=\"1\"]').length"),
            0
        )
        bridge.disposeNavigator()
    }

    func testAnEmphasisRowPaintsOneDecorationPerDrawableStyle() {
        guard let bridge = openFixture() else { return }
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"e1\",\"type\":\"emphasis\",\"locator\":\(textAnchoredLocator()),"
                + "\"styles\":\"underline,strike\"}]",
            group: emphasisGroup()
        )
        XCTAssertEqual(
            2, decorationCount(bridge, group: emphasisGroup()),
            "underline and strike are separate Readium styles; one decoration can carry only one"
        )
        XCTAssertGreaterThan(
            evalInt(bridge, "document.querySelectorAll('.riffle-emphasis-strike').length"), 0,
            "strike has no built-in Readium template — without ours the decoration renders nothing"
        )
        bridge.disposeNavigator()
    }

    func testABookmarkRendersAGutterBarRatherThanAWashOverTheText() {
        guard let bridge = openFixture() else { return }
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"b1\",\"type\":\"bookmark\",\"locator\":\(textAnchoredLocator())}]",
            group: ReaderDecorationGroups.shared.bookmarks
        )
        XCTAssertGreaterThan(decorationCount(bridge, group: ReaderDecorationGroups.shared.bookmarks), 0)
        XCTAssertGreaterThan(
            evalInt(bridge, "document.querySelectorAll('.riffle-sidemark').length"), 0,
            "the bookmark must be a sidemark; the blue background wash it replaced sat on the text"
        )
        bridge.disposeNavigator()
    }

    func testABookmarksFragmentAnchorLandsOnItsElement() {
        // PR #671's element anchoring: `locations.fragments` is the only field of the three
        // Readium resolves that points at the exact paragraph. The fixture's `<h2 id="s2">` is
        // far enough into the chapter that a progression-only locator would not find it.
        guard let bridge = openFixture() else { return }
        let locator = """
        {"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml",\
        "locations":{"cfi":"","fragments":["s2"],"progression":0.0}}
        """
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"b2\",\"type\":\"bookmark\",\"locator\":\(locator)}]",
            group: ReaderDecorationGroups.shared.bookmarks
        )
        XCTAssertGreaterThan(
            decorationCount(bridge, group: ReaderDecorationGroups.shared.bookmarks), 0,
            "a fragment-anchored bookmark must resolve; dropping fragmentAnchor from the locator "
                + "leaves only a progression the reader cannot place"
        )
        bridge.disposeNavigator()
    }

    // MARK: - Tap dispatch

    func testDecorationGroupsAreRegisteredWithReadiumSoTapsReachTheReader() {
        // Readium's `findDecorationTarget` skips every group that is not activable, so an
        // unregistered highlight renders and swallows nothing — the tap falls through to
        // `didTapAt` and toggles the chrome instead of opening the actions sheet.
        guard let bridge = openFixture() else { return }
        bridge.applyDecorations(
            decorationsJson: "[{\"id\":\"a\",\"type\":\"highlight\",\"locator\":\(textAnchoredLocator())}]",
            group: highlightsGroup()
        )
        XCTAssertGreaterThan(decorationCount(bridge, group: highlightsGroup()), 0)

        let activable = eval(bridge, """
        (function(){try{return readium.getDecorations('\(highlightsGroup())').isActivable()?'1':'0';}\
        catch(e){return 'err';}})()
        """)
        XCTAssertEqual("1", activable, "the highlights group must be activable for taps to dispatch")

        XCTAssertTrue(bridge.observedDecorationGroups.contains(highlightsGroup()))
        XCTAssertTrue(bridge.observedDecorationGroups.contains(noteGlyphGroup()))
        bridge.disposeNavigator()
    }

    // MARK: - Chapter source

    func testReadResourceReturnsThePublicationSourceNotTheLiveDom() {
        // The shared merge and CFI arithmetic is computed against these exact bytes. The live
        // DOM carries Readium's injected scripts and, after Cadence runs, a span per sentence;
        // its character offsets would not match the ones Android derives for the same book.
        guard let bridge = openFixture() else { return }
        let done = expectation(description: "readResource")
        var html: String?
        bridge.readResource(href: "OEBPS/chapter1.xhtml") { value in
            html = value
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 20), .completed)

        let source = try? XCTUnwrap(html)
        XCTAssertNotNil(source)
        XCTAssertTrue(source?.contains(anchorText) == true, "the chapter's own text must be there")
        XCTAssertFalse(
            source?.contains("readium") == true,
            "the source must not be the live document — Readium's injected scripts would be in it"
        )
        bridge.disposeNavigator()
    }

    func testReadResourceReportsNilForAnHrefThatIsNotInThePublication() {
        guard let bridge = openFixture() else { return }
        let done = expectation(description: "readResource")
        var called = false
        bridge.readResource(href: "OEBPS/nope.xhtml") { value in
            called = true
            XCTAssertNil(value)
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 20), .completed)
        XCTAssertTrue(called, "the callback must always fire, or the Kotlin coroutine hangs the reader")
        bridge.disposeNavigator()
    }

    func testReadResourceStillAnswersWhenThereIsNoPublication() {
        let bridge = ReadiumEpubNavigatorBridge()
        let done = expectation(description: "readResource")
        bridge.readResource(href: "anything.xhtml") { value in
            XCTAssertNil(value)
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter().wait(for: [done], timeout: 10), .completed)
    }
}
