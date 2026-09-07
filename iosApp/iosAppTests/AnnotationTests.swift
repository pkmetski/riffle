import XCTest
import Riffle
import ReadiumNavigator

// Covers scenarios from docs/testing/ios-scenarios/07-annotations.md

final class AnnotationTests: XCTestCase {

    // MARK: - Scenario 07-A: applyDecorations reaches the bridge

    func testApplyHighlightDecorationsRecordsJson() {
        // applyDecorations records the JSON/group it was handed before it hops to the main actor
        // to hand the parsed decorations to Readium. The coordinator reads that record back to
        // decide whether a group needs re-applying, so a call that silently fails to record
        // makes the reader re-apply (or skip) decorations for the wrong group.
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.5)
        let json = "[{\"id\":\"h1\",\"type\":\"highlight\",\"locator\":\(locator),\"color\":\"#FFFF00\",\"alpha\":0.4}]"
        bridge.applyDecorations(decorationsJson: json, group: "highlights")
        XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
        XCTAssertEqual(bridge.lastAppliedDecorationsJson, json)
    }

    // MARK: - Scenario 07-B: parseDecorations handles all four decoration types

    func testAllFourDecorationTypesParse() {
        // Every type the AnnotationDecorationCoordinator emits must survive parsing. A type that
        // falls through to `default` is dropped silently: the annotation stays in the database and
        // in the annotations list but never renders in the reader.
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.5)
        let cases: [(type: String, extras: String)] = [
            ("highlight", ",\"color\":\"#FFFF00\",\"alpha\":0.4"),
            ("bookmark", ""),
            ("noteGlyph", ""),
            ("searchMark", ",\"isCurrent\":true"),
        ]
        for (index, testCase) in cases.enumerated() {
            let id = "d\(index)"
            let json = "[{\"id\":\"\(id)\",\"type\":\"\(testCase.type)\",\"locator\":\(locator)\(testCase.extras)}]"
            let decorations = bridge.parseDecorations(json)
            XCTAssertEqual(decorations.count, 1,
                           "decoration type '\(testCase.type)' must parse to exactly one Decoration")
            XCTAssertEqual(decorations.first?.id, id,
                           "decoration type '\(testCase.type)' must keep its id so Readium can replace it")
        }
    }

    func testDecorationWithoutLocatorIsDropped() {
        // The locator is what positions the decoration; a decoration entry missing it must be
        // dropped rather than rendered at an arbitrary position.
        let bridge = ReadiumEpubNavigatorBridge()
        let json = "[{\"id\":\"h1\",\"type\":\"highlight\"}]"
        XCTAssertTrue(bridge.parseDecorations(json).isEmpty,
                      "a decoration with no locator must be dropped")
    }

    func testApplyEmptyListRecordsEmptyJson() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyDecorations(decorationsJson: "[]", group: "highlights")
        XCTAssertEqual(bridge.lastAppliedDecorationsJson, "[]")
        XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
    }

    // MARK: - Scenario 07-C: UIColor hex extension

    func testHexColorRed() {
        let color = UIColor(hex: "#FF0000")
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        XCTAssertEqual(red, 1.0, accuracy: 0.01)
        XCTAssertEqual(green, 0.0, accuracy: 0.01)
        XCTAssertEqual(blue, 0.0, accuracy: 0.01)
    }

    func testHexColorGreen() {
        let color = UIColor(hex: "#00FF00")
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        XCTAssertEqual(red, 0.0, accuracy: 0.01)
        XCTAssertEqual(green, 1.0, accuracy: 0.01)
        XCTAssertEqual(blue, 0.0, accuracy: 0.01)
    }

    func testHexColorBlue() {
        let color = UIColor(hex: "#0000FF")
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        XCTAssertEqual(red, 0.0, accuracy: 0.01)
        XCTAssertEqual(green, 0.0, accuracy: 0.01)
        XCTAssertEqual(blue, 1.0, accuracy: 0.01)
    }

    // MARK: - Scenario 07-D: Malformed JSON parses to zero decorations (and does not crash)

    func testValidJsonParsesToDecorations() {
        // Positive control for the malformed-JSON cases below: a well-formed decoration
        // list parses to exactly one Decoration, proving the parser is not just always-empty.
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.5)
        let json = "[{\"id\":\"h1\",\"type\":\"highlight\",\"locator\":\(locator),\"color\":\"#FFFF00\",\"alpha\":0.4}]"
        XCTAssertEqual(bridge.parseDecorations(json).count, 1,
                       "a valid highlight decoration must parse to exactly one Decoration")
    }

    func testMalformedJsonParsesToZeroDecorations() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertTrue(bridge.parseDecorations("not valid json").isEmpty,
                      "malformed JSON must yield zero decorations, not crash or apply garbage")
    }

    func testNullJsonParsesToZeroDecorations() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertTrue(bridge.parseDecorations("null").isEmpty,
                      "JSON null must yield zero decorations")
    }

    func testDecorationWithUnknownTypeIsDropped() {
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.5)
        let json = "[{\"id\":\"x1\",\"type\":\"sparkles\",\"locator\":\(locator)}]"
        XCTAssertTrue(bridge.parseDecorations(json).isEmpty,
                      "an unknown decoration type must be dropped rather than crash")
    }

    // MARK: - Helpers

    private func validLocatorJson(href: String, cfi: String, progression: Double) -> String {
        return "{\"href\":\"\(href)\",\"type\":\"application/xhtml+xml\",\"locations\":{\"cfi\":\"\(cfi)\",\"progression\":\(progression)}}"
    }
}
