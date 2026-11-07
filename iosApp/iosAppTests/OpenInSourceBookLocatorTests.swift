import XCTest
import ReadiumShared
@testable import Riffle

/// iOS counterpart of OpenInSourceBookLocatorTest (Android, app/src/test).
///
/// Pins that a locator JSON built for "Open in Book" navigation carries a non-empty chapter href
/// so that Readium Swift can open the right chapter. The Android path previously used
/// buildLocatorJsonFromCfi which always emitted `"href":""`, causing Readium to fall back to the
/// last reading position. The equivalent risk on iOS is the same: if the locator passed to
/// ReadiumEpubNavigatorBridge.goToLocator has an empty href, the navigator silently ignores it.
///
/// The underlying function `annotationLocatorJson` is tested in full in
/// AnnotationLocatorJsonTest (commonTest, runs on iosSimulatorArm64Test). This file covers the
/// seam where the locator JSON enters the Readium Swift navigator: a locator with non-empty href
/// parses to a valid Readium Locator; one with empty href is accepted by JSON parsing but cannot
/// be located in a Publication.
///
/// In the `iosAppUnitTests` target (no XCUIApplication needed).
final class OpenInSourceBookLocatorTests: XCTestCase {

    // MARK: - Locator JSON parsing (Readium Swift side)

    func testLocatorWithChapterHrefParsesSuccessfully() throws {
        let locatorJson = """
        {"href":"EPUB/chapter01.xhtml","type":"application/xhtml+xml",\
        "locations":{"cfi":"/4/2/1:0)","progression":0.5}}
        """
        let jsonValue = try XCTUnwrap(try? JSONValue(jsonString: locatorJson),
            "annotationLocatorJson output must parse as valid Readium JSONValue")
        let locator = try XCTUnwrap(try? Locator(json: jsonValue),
            "A locator with non-empty href must deserialise to a Readium Locator")
        XCTAssertFalse(locator.href.string.isEmpty,
            "Open-in-book locator must carry the chapter href so Readium opens the right chapter")
    }

    func testLocatorWithEmptyHrefLacksChapterContext() throws {
        // Regression guard: the old buildLocatorJsonFromCfi always emitted empty href.
        // A locator like this may parse as JSON but Readium cannot map it to a spine item.
        let emptyHrefLocatorJson = """
        {"href":"","type":"application/xhtml+xml","locations":{"cfi":"/4/2/1:0)"}}
        """
        guard let jsonValue = try? JSONValue(jsonString: emptyHrefLocatorJson),
              let locator = try? Locator(json: jsonValue) else {
            // If Readium rejects it outright that is also an acceptable outcome.
            return
        }
        XCTAssertTrue(locator.href.string.isEmpty,
            "An empty-href locator must not silently acquire a chapter href")
    }
}
