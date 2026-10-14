import XCTest

// Regression for issue #1220: on a Bulgarian device the TOC row showed "9 sections" in English
// instead of the translated "9 раздела". Root cause: Info.plist lacked CFBundleLocalizations so
// NSBundle.main.preferredLocalizations always returned "en"; Compose Multiplatform then loaded
// the default (English) strings regardless of the system language.
//
// Fix: add CFBundleLocalizations to Info.plist. These tests verify the fix directly by reading
// the source Info.plist and asserting the expected locales are declared. No simulator
// locale-switching is needed — the test pins the static source declaration that governs CMP's
// language selection. The iOS simulator has full macOS filesystem access, so #filePath is
// reachable at runtime regardless of whether the test runs in iosAppUnitTests or iosAppTests.
final class AppLocalizationUnitTests: XCTestCase {

    private var infoPlistLocalizations: [String] {
        // This file lives at iosApp/iosAppTests/AppLocalizationUnitTests.swift.
        // Navigate up two directories to reach iosApp/, then into iosApp/Info.plist.
        let sourceFile = URL(fileURLWithPath: #filePath)
        let infoPlistUrl = sourceFile
            .deletingLastPathComponent()  // iosApp/iosAppTests/
            .deletingLastPathComponent()  // iosApp/
            .appendingPathComponent("iosApp")
            .appendingPathComponent("Info.plist")
        guard let plist = NSDictionary(contentsOf: infoPlistUrl),
              let localizations = plist["CFBundleLocalizations"] as? [String] else {
            return []
        }
        return localizations
    }

    func testInfoPlistDeclaresIosBulgarianLocalization() {
        let localizations = infoPlistLocalizations
        XCTAssertFalse(localizations.isEmpty, "Could not read CFBundleLocalizations from Info.plist")
        XCTAssertTrue(localizations.contains("bg"),
            "Regression for #1220: CFBundleLocalizations must include 'bg' so CMP loads values-bg/strings.xml on Bulgarian devices instead of falling back to English")
    }

    func testInfoPlistDeclaresIosSpanishLocalization() {
        let localizations = infoPlistLocalizations
        XCTAssertFalse(localizations.isEmpty, "Could not read CFBundleLocalizations from Info.plist")
        XCTAssertTrue(localizations.contains("es"),
            "CFBundleLocalizations must include 'es' so CMP loads Spanish strings on Spanish-locale devices")
    }
}
