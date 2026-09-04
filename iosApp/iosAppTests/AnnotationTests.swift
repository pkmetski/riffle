import XCTest
@testable import iosApp

// Covers scenarios from docs/testing/ios-scenarios/07-annotations.md

final class AnnotationTests: XCTestCase {

    // MARK: - Scenario 07-A: applyDecorations reaches the bridge

    func testSimulateApplyDecorationsRecordsJsonAndGroup() {
        let bridge = ReadiumEpubNavigatorBridge()
        let json = "[{\"id\":\"h1\",\"type\":\"highlight\"}]"
        bridge.simulateApplyDecorations(json, group: "highlights")
        XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
        XCTAssertEqual(bridge.lastAppliedDecorationsJson, json)
    }

    // MARK: - Scenario 07-B: applyDecorations records all decoration types

    func testApplyHighlightDecorationsRecordsJson() {
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.5)
        let json = "[{\"id\":\"h1\",\"type\":\"highlight\",\"locator\":\(locator),\"color\":\"#FFFF00\",\"alpha\":0.4}]"
        bridge.applyDecorations(decorationsJson: json, group: "highlights")
        XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
        XCTAssertEqual(bridge.lastAppliedDecorationsJson, json)
    }

    func testApplyBookmarkDecorationsRecordsJson() {
        let bridge = ReadiumEpubNavigatorBridge()
        let locator = validLocatorJson(href: "ch1.xhtml", cfi: "/4/2", progression: 0.3)
        let json = "[{\"id\":\"b1\",\"type\":\"bookmark\",\"locator\":\(locator)}]"
        bridge.applyDecorations(decorationsJson: json, group: "bookmarks")
        XCTAssertEqual(bridge.lastAppliedGroup, "bookmarks")
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
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        XCTAssertEqual(r, 1.0, accuracy: 0.01)
        XCTAssertEqual(g, 0.0, accuracy: 0.01)
        XCTAssertEqual(b, 0.0, accuracy: 0.01)
    }

    func testHexColorGreen() {
        let color = UIColor(hex: "#00FF00")
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        XCTAssertEqual(r, 0.0, accuracy: 0.01)
        XCTAssertEqual(g, 1.0, accuracy: 0.01)
        XCTAssertEqual(b, 0.0, accuracy: 0.01)
    }

    func testHexColorBlue() {
        let color = UIColor(hex: "#0000FF")
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        XCTAssertEqual(r, 0.0, accuracy: 0.01)
        XCTAssertEqual(g, 0.0, accuracy: 0.01)
        XCTAssertEqual(b, 1.0, accuracy: 0.01)
    }

    // MARK: - Scenario 07-D: Malformed JSON does not crash

    func testMalformedJsonDoesNotCrash() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyDecorations(decorationsJson: "not valid json", group: "highlights")
        XCTAssertNotNil(bridge.lastAppliedDecorationsJson)
    }

    func testNullJsonDoesNotCrash() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.applyDecorations(decorationsJson: "null", group: "bookmarks")
        XCTAssertNotNil(bridge.lastAppliedDecorationsJson)
    }

    // MARK: - Helpers

    private func validLocatorJson(href: String, cfi: String, progression: Double) -> String {
        return "{\"href\":\"\(href)\",\"type\":\"application/xhtml+xml\",\"locations\":{\"cfi\":\"\(cfi)\",\"progression\":\(progression)}}"
    }
}
