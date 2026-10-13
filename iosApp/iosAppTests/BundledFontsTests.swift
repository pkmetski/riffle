import XCTest
import Riffle

// Unit tests for the bundled font family declarations registered with the Readium navigator.
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
}
