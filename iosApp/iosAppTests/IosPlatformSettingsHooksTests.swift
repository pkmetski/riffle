import XCTest
import Foundation
@testable import Riffle

/// Verifies the NSUserDefaults round-trip that backs iOS language switching (#1151).
///
/// The tests write and clear the `AppleLanguages` key in `UserDefaults.standard`, then call
/// `IosPlatformSettingsHooks` to confirm it reads and writes that key correctly.
/// Each test restores the original state so it is side-effect-free.
final class IosPlatformSettingsHooksTests: XCTestCase {

    private let defaults = UserDefaults.standard
    private let key = "AppleLanguages"
    private var originalLanguages: [String]?

    override func setUp() {
        super.setUp()
        originalLanguages = defaults.stringArray(forKey: key)
    }

    override func tearDown() {
        if let original = originalLanguages {
            defaults.set(original, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
        defaults.synchronize()
        super.tearDown()
    }

    // MARK: - currentLanguage()

    func testCurrentLanguageReturnsSystemWhenKeyAbsent() {
        defaults.removeObject(forKey: key)
        let hooks = IosPlatformSettingsHooks.shared
        XCTAssertEqual(hooks.currentLanguage(), AppLanguage.system)
    }

    func testCurrentLanguageReturnsSpanishWhenKeyIsEs() {
        defaults.set(["es-ES"], forKey: key)
        let hooks = IosPlatformSettingsHooks.shared
        XCTAssertEqual(hooks.currentLanguage(), AppLanguage.spanish)
    }

    func testCurrentLanguageReturnsBulgarianWhenKeyIsBg() {
        defaults.set(["bg"], forKey: key)
        let hooks = IosPlatformSettingsHooks.shared
        XCTAssertEqual(hooks.currentLanguage(), AppLanguage.bulgarian)
    }

    func testCurrentLanguageReturnsEnglishWhenKeyIsEnGb() {
        defaults.set(["en-GB"], forKey: key)
        let hooks = IosPlatformSettingsHooks.shared
        XCTAssertEqual(hooks.currentLanguage(), AppLanguage.english)
    }

    // MARK: - onLanguageChanged()

    func testOnLanguageChangedWritesTagToDefaults() {
        let hooks = IosPlatformSettingsHooks.shared
        hooks.onLanguageChanged(language: AppLanguage.spanish)
        let stored = defaults.stringArray(forKey: key)
        XCTAssertEqual(stored, ["es-ES"])
    }

    func testOnLanguageChangedSystemRemovesKey() {
        defaults.set(["es-ES"], forKey: key)
        let hooks = IosPlatformSettingsHooks.shared
        hooks.onLanguageChanged(language: AppLanguage.system)
        XCTAssertNil(defaults.stringArray(forKey: key))
    }

    func testRoundTripBulgarianWriteThenRead() {
        let hooks = IosPlatformSettingsHooks.shared
        hooks.onLanguageChanged(language: AppLanguage.bulgarian)
        XCTAssertEqual(hooks.currentLanguage(), AppLanguage.bulgarian)
    }
}
