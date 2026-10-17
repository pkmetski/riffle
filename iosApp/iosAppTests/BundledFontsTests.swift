import XCTest
import Riffle

// Unit tests for the bundled font family declarations registered with the Readium navigator,
// and for the UIAppFonts plist entry that makes them loadable by the OS.
//
// The declarations are consumed by EPUBNavigatorViewController.Configuration to make Literata,
// Merriweather, and OpenDyslexic available as reader typefaces. Removing a font file from the
// Xcode target, changing UIAppFonts, or changing a family name in the declaration would each
// flip at least one test here red.
final class BundledFontsTests: XCTestCase {

    func testThreeFamiliesAreRegistered() {
        let declarations = ReadiumEpubNavigatorBridge.bundledFontFamilyDeclarations
        XCTAssertEqual(declarations.count, 3,
                       "Literata, Merriweather, and OpenDyslexic must each produce one declaration")
    }

    func testExpectedFamilyNamesPresent() {
        let names = ReadiumEpubNavigatorBridge.bundledFontFamilyDeclarations
            .map { $0.fontFamily.rawValue }
        XCTAssertTrue(names.contains("Literata"),
                      "Literata family must be declared")
        XCTAssertTrue(names.contains("Merriweather"),
                      "Merriweather family must be declared")
        XCTAssertTrue(names.contains("OpenDyslexic"),
                      "OpenDyslexic family must be declared")
    }

    // Regression: a stale squash-merge (#1220) silently dropped the UIAppFonts array from
    // Info.plist, making the fonts unloadable by UIFont and therefore invisible in the reader.
    // This test catches any future plist rewrite that omits one or more of the entries.
    func testUIAppFontsListsAllThreeBundledFonts() {
        guard let bundlePath = Bundle.main.path(forResource: "Info", ofType: "plist"),
              let plist = NSDictionary(contentsOfFile: bundlePath),
              let uiAppFonts = plist["UIAppFonts"] as? [String] else {
            XCTFail("Could not read UIAppFonts from Info.plist")
            return
        }
        XCTAssertTrue(uiAppFonts.contains("Literata-Regular.ttf"),
                      "UIAppFonts must list Literata-Regular.ttf so UIFont can load it")
        XCTAssertTrue(uiAppFonts.contains("Merriweather-Regular.ttf"),
                      "UIAppFonts must list Merriweather-Regular.ttf so UIFont can load it")
        XCTAssertTrue(uiAppFonts.contains("OpenDyslexic-Regular.otf"),
                      "UIAppFonts must list OpenDyslexic-Regular.otf so UIFont can load it")
    }
}
