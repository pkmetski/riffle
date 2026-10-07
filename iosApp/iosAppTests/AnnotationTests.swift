import XCTest
import Riffle
import ReadiumNavigator


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

    func testNullJsonParsesToZeroDecorations() {
        let bridge = ReadiumEpubNavigatorBridge()
        XCTAssertTrue(bridge.parseDecorations("null").isEmpty,
                      "JSON null must yield zero decorations")
    }

    // MARK: - Helpers

    private func validLocatorJson(href: String, cfi: String, progression: Double) -> String {
        return "{\"href\":\"\(href)\",\"type\":\"application/xhtml+xml\",\"locations\":{\"cfi\":\"\(cfi)\",\"progression\":\(progression)}}"
    }
}
