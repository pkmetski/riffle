import XCTest
import Riffle

/// iOS counterpart to `app/src/androidTest/.../reader/EpubProgressionLocatorTest.kt` (5 tests).
///
/// Guards the **fallback** inbound-sync path (ADR-0013). The primary path is
/// server CFI → `EpubCfiTranslator` → within-chapter progression → Locator. When the server's
/// `ebookLocation` is absent or unparseable and all we have is a bare `ebookProgress` float,
/// Android falls back to `Publication.locateProgression(totalProgression)`.
///
/// iOS had **no** equivalent: a bare progression simply dead-ended and the book opened at page
/// one. `ReadiumPublicationInspector.locateProgression` is the new capability (Readium Swift's
/// `Publication.locate(progression:)`), reached from `IosEpubReaderScreen` when
/// `positionStore.load(...)` returns nothing. These tests drive that Swift implementation against
/// the bundled `test.epub`.
final class EpubProgressionLocatorTests: XCTestCase {

    private func bundledEpubPath(_ name: String) -> String {
        guard let url = Bundle(for: EpubProgressionLocatorTests.self).url(forResource: name, withExtension: nil) else {
            XCTFail("Missing test asset '\(name)' in test bundle")
            return ""
        }
        return url.path
    }

    /// One resolved Locator, decoded from the JSON the bridge hands back to Kotlin.
    private struct ResolvedLocator {
        let href: String
        /// `locations.progression` — how far into its own spine item the position sits.
        let withinChapter: Double?
    }

    private func locate(_ progression: Double, file: StaticString = #filePath, line: UInt = #line) -> ResolvedLocator? {
        let inspector = ReadiumPublicationInspector()
        let done = expectation(description: "locateProgression(\(progression))")
        var json: String?
        inspector.locateProgression(filePath: bundledEpubPath("test.epub"), totalProgression: progression) { result in
            json = result
            done.fulfill()
        }
        wait(for: [done], timeout: 20)

        guard let json else { return nil }
        guard
            let data = json.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let href = object["href"] as? String
        else {
            XCTFail("locateProgression(\(progression)) returned unparseable Locator JSON: \(json)", file: file, line: line)
            return nil
        }
        let locations = object["locations"] as? [String: Any]
        return ResolvedLocator(href: href, withinChapter: locations?["progression"] as? Double)
    }

    /// Turns a resolved Locator href into its spine index. Readium may report the href with or
    /// without a leading path component depending on the container, so match on either suffix.
    private func readingOrderIndex(of href: String) -> Int? {
        readingOrderHrefs.firstIndex { href.hasSuffix($0) || $0.hasSuffix(href) }
    }

    /// The spine, in reading order, as `inspectEpub` reports it.
    private lazy var readingOrderHrefs: [String] = {
        let inspector = ReadiumPublicationInspector()
        let done = expectation(description: "inspectEpub")
        var json: String?
        inspector.inspectEpub(filePath: bundledEpubPath("test.epub")) { result in
            json = result
            done.fulfill()
        }
        wait(for: [done], timeout: 20)

        guard
            let json,
            let data = json.data(using: .utf8),
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let readingOrder = object["readingOrder"] as? [String],
            !readingOrder.isEmpty
        else {
            XCTFail("Could not read the test EPUB's reading order")
            return []
        }
        return readingOrder
    }()

    // MARK: - 1. Book start

    /// 0.0 must land in the first spine item. A fallback that resolved the start of the book to a
    /// later chapter would teleport a barely-started reader forwards.
    func testProgressionZeroLandsInTheFirstSpineItem() throws {
        let locator = try XCTUnwrap(locate(0.0), "progression 0.0 must resolve to a Locator")
        let index = try XCTUnwrap(
            readingOrderIndex(of: locator.href),
            "Resolved href '\(locator.href)' must be part of the reading order \(readingOrderHrefs)"
        )
        XCTAssertEqual(index, 0, "progression 0.0 must land in the first spine item")
    }

    // MARK: - 2. Book end

    /// 1.0 must land in the final spine item — the "finished the book" position.
    func testProgressionOneLandsInTheFinalSpineItem() throws {
        let locator = try XCTUnwrap(locate(1.0), "progression 1.0 must resolve to a Locator")
        let index = try XCTUnwrap(
            readingOrderIndex(of: locator.href),
            "Resolved href '\(locator.href)' must be part of the reading order \(readingOrderHrefs)"
        )
        XCTAssertEqual(
            index, readingOrderHrefs.count - 1,
            "progression 1.0 must land in the last spine item, got index \(index) of \(readingOrderHrefs.count)"
        )
    }

    // MARK: - 3. Mid-book keeps the within-chapter offset

    /// The original Android bug: the fallback silently landed at the START of whichever chapter
    /// contains the progression. The Locator must carry a non-zero `locations.progression`.
    func testMidBookProgressionCarriesNonZeroWithinChapterProgression() throws {
        let locator = try XCTUnwrap(locate(0.5), "progression 0.5 must resolve to a Locator")
        let withinChapter = try XCTUnwrap(
            locator.withinChapter,
            "The Locator must carry a within-chapter progression, not just an href"
        )
        XCTAssertGreaterThan(
            withinChapter, 0.0,
            "0.5 must yield a non-zero within-chapter progression; got \(withinChapter) in \(locator.href)"
        )
    }

    // MARK: - 4. Monotonic across the spine

    /// Increasing progressions must never resolve to an earlier spine item. This is what stops the
    /// fallback from returning arbitrary chapters.
    func testResolvedSpineIndexIsMonotonicAcrossTheBook() throws {
        var lastIndex = -1
        for progression in [0.0, 0.25, 0.5, 0.75, 1.0] {
            let locator = try XCTUnwrap(locate(progression), "locateProgression(\(progression)) returned nil")
            let index = try XCTUnwrap(
                readingOrderIndex(of: locator.href),
                "locateProgression(\(progression)) href '\(locator.href)' is not in the reading order"
            )
            XCTAssertGreaterThanOrEqual(
                index, lastIndex,
                "Spine index for \(progression) (=\(index)) must not regress below \(lastIndex)"
            )
            lastIndex = index
        }
    }

    // MARK: - 5. Two positions in the same chapter stay distinct

    /// Two different total progressions inside one chapter must produce different within-chapter
    /// progressions — otherwise inbound sync collapses every position to the chapter start
    /// regardless of how far in the server said the reader was.
    func testTwoProgressionsInTheSameChapterKeepDistinctOffsets() throws {
        let first = try XCTUnwrap(locate(0.05))
        let second = try XCTUnwrap(locate(0.20))

        guard first.href == second.href else {
            // Different chapters already encode the difference; the claim is trivially satisfied.
            XCTAssertNotEqual(first.href, second.href)
            return
        }
        let firstWithin = try XCTUnwrap(first.withinChapter)
        let secondWithin = try XCTUnwrap(second.withinChapter)
        XCTAssertGreaterThan(
            abs(firstWithin - secondWithin), 0.001,
            "Two distinct total progressions inside \(first.href) must differ; " +
                "got \(firstWithin) and \(secondWithin)"
        )
    }

    // MARK: - Guard rails

    /// Out-of-range and unreadable inputs resolve to nil rather than throwing or returning a
    /// bogus locator that would open the reader somewhere arbitrary.
    func testOutOfRangeProgressionAndMissingFileResolveToNil() {
        XCTAssertNil(locate(1.5), "a progression above 1.0 must not resolve")
        XCTAssertNil(locate(-0.2), "a negative progression must not resolve")

        let inspector = ReadiumPublicationInspector()
        let done = expectation(description: "missing file")
        var json: String? = "not-yet-called"
        inspector.locateProgression(filePath: "/nonexistent/does-not-exist.epub", totalProgression: 0.5) { result in
            json = result
            done.fulfill()
        }
        wait(for: [done], timeout: 20)
        XCTAssertNil(json, "an unopenable EPUB must resolve to nil")
    }
}
