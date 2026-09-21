import XCTest
import ReadiumNavigator
import ReadiumShared
@testable import Riffle

/// The bridge's JSON wire shapes, which need no navigator.
///
/// Split from `AnnotationBridgeTests` so neither class outgrows swiftlint's type-body limit,
/// and because these run in milliseconds while the live-navigator tests take minutes.
final class AnnotationWireFormatTests: XCTestCase {

    private func textAnchoredLocator() -> String {
        """
        {"href":"OEBPS/chapter1.xhtml","type":"application/xhtml+xml",\
        "text":{"highlight":"anything"},\
        "locations":{"progression":0.0}}
        """
    }

    func testSelectionJsonCarriesTheTextTripleTheAnnotationDomainAnchorsOn() throws {
        let locator = Locator(
            href: try XCTUnwrap(AnyURL(string: "OEBPS/chapter1.xhtml")),
            mediaType: .xhtml,
            locations: Locator.Locations(progression: 0.42),
            text: Locator.Text(after: " after", before: "context ", highlight: "chosen words")
        )
        let json = try XCTUnwrap(ReadiumEpubNavigatorBridge.selectionJson(
            locator: locator,
            frame: CGRect(x: 10, y: 320, width: 180, height: 22)
        ))

        XCTAssertTrue(json.contains("\"text\":\"chosen words\""), json)
        XCTAssertTrue(json.contains("\"before\":\"context \""), json)
        XCTAssertTrue(json.contains("\"after\":\" after\""), json)
        XCTAssertTrue(json.contains("\"progression\":0.42"), json)
        XCTAssertTrue(json.contains("\"width\":180"), json)
        // And it must parse — the Kotlin side reads it with NSJSONSerialization.
        let data = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertNotNil(try? JSONSerialization.jsonObject(with: data))
    }

    func testSelectionJsonEscapesASelectionContainingQuotesAndNewlines() throws {
        let locator = Locator(
            href: try XCTUnwrap(AnyURL(string: "c.xhtml")),
            mediaType: .xhtml,
            text: Locator.Text(highlight: "she said \"hello\"\nand left")
        )
        let json = try XCTUnwrap(ReadiumEpubNavigatorBridge.selectionJson(locator: locator, frame: nil))
        let data = try XCTUnwrap(json.data(using: .utf8))
        let parsed = try XCTUnwrap(try? JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual("she said \"hello\"\nand left", parsed["text"] as? String,
                       "a selection with a quote or a paragraph break must survive the bridge")
    }

    func testActivationJsonCarriesTheIdGroupAndRect() throws {
        let json = ReadiumEpubNavigatorBridge.activationJson(
            id: "ann-7",
            group: "highlights",
            rect: CGRect(x: 4, y: 120, width: 200, height: 18)
        )

        XCTAssertTrue(json.contains("\"id\":\"ann-7\""), json)
        XCTAssertTrue(json.contains("\"group\":\"highlights\""), json)
        XCTAssertTrue(json.contains("\"y\":120"), json)
        let data = try XCTUnwrap(json.data(using: .utf8))
        XCTAssertNotNil(try? JSONSerialization.jsonObject(with: data))
    }

    func testActivationJsonOmitsTheRectRatherThanInventingOne() {
        let json = ReadiumEpubNavigatorBridge.activationJson(id: "a", group: "g", rect: nil)
        XCTAssertFalse(json.contains("\"x\":"), "a fabricated (0,0) rect would anchor the sheet wrongly: \(json)")
    }

    // MARK: - Decoration parsing for the new types

    func testEmphasisPayloadSplitsIntoOneDecorationPerStyle() {
        let bridge = ReadiumEpubNavigatorBridge()
        let json = "[{\"id\":\"e\",\"type\":\"emphasis\",\"locator\":\(textAnchoredLocator()),"
            + "\"styles\":\"underline,strike\"}]"

        let decorations = bridge.parseDecorations(json)

        XCTAssertEqual(["e#underline", "e#strike"], decorations.map(\.id))
        XCTAssertEqual(
            [Decoration.Style.Id.underline, RiffleDecorationTemplates.strikeStyleId],
            decorations.map(\.style.id)
        )
    }

    func testBoldAndItalicNeverBecomeDecorations() {
        // They reflow text; an overlay cannot. A decoration for them is an invisible box that
        // still swallows taps meant for the highlight underneath.
        let bridge = ReadiumEpubNavigatorBridge()
        let json = "[{\"id\":\"e\",\"type\":\"emphasis\",\"locator\":\(textAnchoredLocator()),"
            + "\"styles\":\"bold,italic\"}]"
        XCTAssertEqual(0, bridge.parseDecorations(json).count)
    }

    func testEveryRiffleStyleHasARegisteredTemplate() {
        // Readium drops a decoration whose style id has no template, so a missing entry here is
        // an annotation type that silently renders nothing.
        let templates = RiffleDecorationTemplates.all()
        for id in [
            Decoration.Style.Id.highlight,
            Decoration.Style.Id.underline,
            RiffleDecorationTemplates.strikeStyleId,
            RiffleDecorationTemplates.noteGlyphStyleId,
            RiffleDecorationTemplates.sidemarkStyleId
        ] {
            XCTAssertNotNil(templates[id], "no template registered for \(id.rawValue)")
        }
    }
}
