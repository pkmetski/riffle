import XCTest
import ReadiumNavigator
import ReadiumShared
import UIKit
@testable import Riffle

// Unit tests for the Swift side of highlight rendering: ReadiumEpubNavigatorBridge's
// decoration-JSON parser. In the iosAppUnitTests target so both the bridge class and the
// Riffle KMP framework are available.
//
// Scope note (issue #1066). The highlight *decision* logic — merge, adjacency, true-range
// overlap, figure enclosure, ARGB→CSS conversion — lives in `feature:reader`'s commonMain and
// its ~84 tests already execute on iOS via `feature:reader:iosSimulatorArm64Test`. Re-asserting
// those here would be the "Swift test that re-asserts a Kotlin commonTest" AGENTS.md rejects.
// What is genuinely iOS-only is the last hop: turning the decoration JSON Kotlin emits into
// Readium-Swift `Decoration`s, resolving each type to a style and tint. A defect there renders
// the wrong colour, or nothing at all, with every Kotlin test still green — so that is what
// this file covers.

final class ReaderHighlightsTests: XCTestCase {

    private func bridge() -> ReadiumEpubNavigatorBridge { ReadiumEpubNavigatorBridge() }

    /// A minimal but valid Readium locator, which `parseDecoration` requires before it will
    /// produce a `Decoration` at all.
    private func locatorJson(href: String = "chapter1.xhtml", progression: Double = 0.25) -> String {
        """
        {"href":"\(href)","type":"application/xhtml+xml","locations":{"progression":\(progression)}}
        """
    }

    private func decorationJson(
        id: String = "d1",
        type: String = "highlight",
        extra: String = ""
    ) -> String {
        "[{\"id\":\"\(id)\",\"type\":\"\(type)\",\"locator\":\(locatorJson())\(extra)}]"
    }

    /// The tint a `.highlight` style carries, or nil for any other style.
    ///
    /// `Decoration.Style` is a struct with an `AnyHashable` config rather than an enum, so the
    /// tint is read by downcasting the config, not by pattern matching.
    private func tint(of decoration: Decoration) -> UIColor? {
        guard decoration.style.id == .highlight,
              let config = decoration.style.config as? Decoration.Style.HighlightConfig
        else { return nil }
        return config.tint
    }

    /// A colour's four channels, named so the assertions below read as claims about colour.
    private struct Channels: Equatable {
        let red: CGFloat
        let green: CGFloat
        let blue: CGFloat
        let alpha: CGFloat
    }

    private func rgba(_ color: UIColor) -> Channels {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return Channels(red: red, green: green, blue: blue, alpha: alpha)
    }

    // MARK: - Well-formed input

    func testHighlightDecorationKeepsItsIdAndLocator() {
        let decorations = bridge().parseDecorations(decorationJson(id: "annotation-42"))

        XCTAssertEqual(decorations.count, 1)
        XCTAssertEqual(decorations.first?.id, "annotation-42")
        XCTAssertEqual(decorations.first?.locator.href.string, "chapter1.xhtml")
        XCTAssertEqual(decorations.first?.locator.locations.progression, 0.25)
    }

    func testHighlightUsesTheSuppliedColourAndAlpha() throws {
        let json = decorationJson(extra: ",\"color\":\"#FF0000\",\"alpha\":0.5")

        let decoration = try XCTUnwrap(bridge().parseDecorations(json).first)
        let channels = rgba(try XCTUnwrap(tint(of: decoration)))

        XCTAssertEqual(channels.red, 1.0, accuracy: 0.01, "red channel must come from the supplied hex")
        XCTAssertEqual(channels.green, 0.0, accuracy: 0.01)
        XCTAssertEqual(channels.blue, 0.0, accuracy: 0.01)
        XCTAssertEqual(channels.alpha, 0.5, accuracy: 0.01, "alpha must come from the supplied value, not the default")
    }

    func testHighlightWithoutColourFallsBackToYellowAtDefaultAlpha() throws {
        let decoration = try XCTUnwrap(bridge().parseDecorations(decorationJson()).first)
        let channels = rgba(try XCTUnwrap(tint(of: decoration)))

        XCTAssertEqual(channels.red, 1.0, accuracy: 0.01, "documented fallback is #FFFF00")
        XCTAssertEqual(channels.green, 1.0, accuracy: 0.01)
        XCTAssertEqual(channels.blue, 0.0, accuracy: 0.01)
        XCTAssertEqual(channels.alpha, 0.4, accuracy: 0.01, "documented fallback alpha is 0.4")
    }

    func testEveryDecorationTypeResolvesToADistinctTint() throws {
        var tints: [String: UIColor] = [:]
        for type in ["highlight", "bookmark", "noteGlyph", "searchMark"] {
            let decoration = try XCTUnwrap(
                bridge().parseDecorations(decorationJson(type: type)).first,
                "\(type) must produce a decoration"
            )
            tints[type] = try XCTUnwrap(tint(of: decoration), "\(type) must resolve to a highlight style")
        }

        // Bookmarks, note glyphs and search marks must not be indistinguishable on the page.
        let distinct = Set(tints.values.map { tint -> String in
            let ch = rgba(tint)
            return "\(ch.red),\(ch.green),\(ch.blue)"
        })
        XCTAssertEqual(distinct.count, tints.count, "each decoration type must render a different colour")
    }

    func testCurrentSearchMarkIsTintedDifferentlyFromTheRest() throws {
        let current = try XCTUnwrap(
            bridge().parseDecorations(decorationJson(type: "searchMark", extra: ",\"isCurrent\":true")).first
        )
        let other = try XCTUnwrap(
            bridge().parseDecorations(decorationJson(type: "searchMark", extra: ",\"isCurrent\":false")).first
        )

        XCTAssertNotEqual(
            rgba(try XCTUnwrap(tint(of: current))),
            rgba(try XCTUnwrap(tint(of: other))),
            "the active search hit must be distinguishable from the other matches"
        )
    }

    func testAllDecorationsInAnArrayAreParsed() {
        let json = """
        [{"id":"a","type":"highlight","locator":\(locatorJson(href: "c1.xhtml"))},
         {"id":"b","type":"bookmark","locator":\(locatorJson(href: "c2.xhtml"))},
         {"id":"c","type":"noteGlyph","locator":\(locatorJson(href: "c3.xhtml"))}]
        """

        XCTAssertEqual(bridge().parseDecorations(json).map(\.id), ["a", "b", "c"])
    }

    // MARK: - Malformed input

    func testMalformedJsonParsesToZeroDecorations() {
        XCTAssertEqual(bridge().parseDecorations("not json").count, 0)
        XCTAssertEqual(bridge().parseDecorations("").count, 0)
        XCTAssertEqual(bridge().parseDecorations("{}").count, 0, "a JSON object is not a decoration array")
    }

    func testUnknownDecorationTypeIsDroppedRatherThanRenderedWrong() {
        XCTAssertEqual(bridge().parseDecorations(decorationJson(type: "somethingElse")).count, 0)
    }

    func testDecorationMissingItsLocatorIsDropped() {
        XCTAssertEqual(bridge().parseDecorations("[{\"id\":\"d1\",\"type\":\"highlight\"}]").count, 0)
    }

    func testOneBadEntryDoesNotDiscardTheGoodOnesAroundIt() {
        let json = """
        [{"id":"good1","type":"highlight","locator":\(locatorJson())},
         {"id":"bad","type":"highlight"},
         {"id":"good2","type":"bookmark","locator":\(locatorJson())}]
        """

        XCTAssertEqual(
            bridge().parseDecorations(json).map(\.id), ["good1", "good2"],
            "a single unparseable decoration must not take the whole page's highlights with it"
        )
    }

    // MARK: - applyDecorations bookkeeping

    func testApplyDecorationsRecordsWhatWasLastApplied() {
        let navigatorBridge = bridge()
        let json = decorationJson(id: "annotation-7")

        navigatorBridge.applyDecorations(decorationsJson: json, group: "highlights")

        XCTAssertEqual(navigatorBridge.lastAppliedDecorationsJson, json)
        XCTAssertEqual(navigatorBridge.lastAppliedGroup, "highlights")
    }
}
