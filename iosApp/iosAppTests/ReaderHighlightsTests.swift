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

    /// The claim is unchanged — a highlight whose payload omits the colour still renders, rather
    /// than vanishing off the page. What changed is *which* colour: the fallback used to be a
    /// hardcoded `#FFFF00` at alpha 0.4, which matched nothing in the palette. It now comes from
    /// `HighlightColor.DEFAULT` via Kotlin, so this asserts against that instead of a literal and
    /// cannot drift again.
    func testHighlightWithoutColourFallsBackToThePaletteDefault() throws {
        let decoration = try XCTUnwrap(bridge().parseDecorations(decorationJson()).first)
        let channels = rgba(try XCTUnwrap(tint(of: decoration)))
        let expected = rgba(
            UIColor(hex: ReaderHighlightDefaults.shared.highlightHex)
                .withAlphaComponent(CGFloat(ReaderHighlightDefaults.shared.highlightAlpha))
        )

        XCTAssertEqual(channels.red, expected.red, accuracy: 0.01, "fallback must be HighlightColor.DEFAULT")
        XCTAssertEqual(channels.green, expected.green, accuracy: 0.01)
        XCTAssertEqual(channels.blue, expected.blue, accuracy: 0.01)
        XCTAssertEqual(channels.alpha, expected.alpha, accuracy: 0.01)

        // Pin the value too, so a change to the palette is a deliberate edit here rather than a
        // silent one: #FBBF24 with the baked 0x80 alpha.
        XCTAssertEqual(channels.red, 0.984, accuracy: 0.01)
        XCTAssertEqual(channels.green, 0.749, accuracy: 0.01)
        XCTAssertEqual(channels.blue, 0.141, accuracy: 0.01)
        XCTAssertEqual(channels.alpha, 0.502, accuracy: 0.01)
    }

    /// The claim is unchanged — bookmarks, note glyphs and search marks must not be
    /// indistinguishable on the page. What changed is *how* they differ.
    ///
    /// When this was written every type was a `.highlight` style and could only differ by tint,
    /// so the assertion compared tints. A bookmark now renders as a gutter sidemark and a note
    /// as a margin glyph — each has its own style id and its own HTML template — so "a different
    /// colour" is no longer the right, or the strongest, way to say it. The rendering identity
    /// is now the (style id, tint) pair, and this asserts all four are distinct in that.
    func testEveryDecorationTypeResolvesToADistinctRendering() throws {
        var renderings: [String: String] = [:]
        for type in ["highlight", "bookmark", "noteGlyph", "searchMark"] {
            let decoration = try XCTUnwrap(
                bridge().parseDecorations(decorationJson(type: type)).first,
                "\(type) must produce a decoration"
            )
            let colour = tint(of: decoration).map { paint -> String in
                let channels = rgba(paint)
                return "\(channels.red),\(channels.green),\(channels.blue)"
            } ?? "no-tint"
            renderings[type] = "\(decoration.style.id.rawValue)/\(colour)"
        }

        XCTAssertEqual(
            Set(renderings.values).count,
            renderings.count,
            "each decoration type must be visually distinguishable: \(renderings)"
        )
        // And each must have a template registered, or Readium drops it and nothing renders.
        let templates = RiffleDecorationTemplates.all()
        for (type, rendering) in renderings {
            let styleId = Decoration.Style.Id(rawValue: String(rendering.split(separator: "/")[0]))
            XCTAssertNotNil(templates[styleId], "\(type) has no registered template")
        }
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
