import XCTest
import Foundation
import Riffle

/// Verifies the NSUserDefaults round-trip that backs iOS language switching (#1151).
///
/// `AppLanguage` is a KMP type from the `feature:settings-ui` module; in Swift it is
/// exposed as `Settings_uiAppLanguage`.  Tests assert on the `.tag` property (the BCP-47
/// language tag stored to UserDefaults) rather than on enum identity, which makes them
/// independent of ObjC name-mangling and equally rigorous.
/// Each test saves and restores the `AppleLanguages` key so the suite is side-effect-free.
final class IosPlatformSettingsHooksTests: XCTestCase {

    private let defaults = UserDefaults.standard
    private let key = "AppleLanguages"
    private var snapshot: [String]?

    override func setUp() {
        super.setUp()
        snapshot = defaults.stringArray(forKey: key)
    }

    override func tearDown() {
        if let saved = snapshot {
            defaults.set(saved, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
        defaults.synchronize()
        super.tearDown()
    }

    // MARK: - onLanguageChanged writes the correct tag

    func testOnLanguageChangedSpanishWritesEsES() {
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.spanish)
        let stored = defaults.stringArray(forKey: key)
        XCTAssertEqual(stored, ["es-ES"], "Spanish must write 'es-ES' tag to AppleLanguages")
    }

    func testOnLanguageChangedBulgarianWritesBg() {
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.bulgarian)
        let stored = defaults.stringArray(forKey: key)
        XCTAssertEqual(stored, ["bg"], "Bulgarian must write 'bg' tag to AppleLanguages")
    }

    func testOnLanguageChangedEnglishWritesEn() {
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.english)
        let stored = defaults.stringArray(forKey: key)
        XCTAssertEqual(stored, ["en"], "English must write 'en' tag to AppleLanguages")
    }

    func testOnLanguageChangedSystemClearsAppOverride() {
        // Write Spanish, clear with System, write Bulgarian — verify the write layer responds to
        // Bulgarian (not Spanish), proving System actually removed the Spanish override.
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.spanish)
        XCTAssertEqual(IosPlatformSettingsHooks.shared.currentLanguage().tag, "es-ES",
                       "Pre-condition: Spanish must be active before clearing")
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.system)
        // The app's persistent domain must no longer contain the key. NSUserDefaults writes to
        // the main bundle's domain; read it back directly to avoid interference from system layers.
        let appBundle = Bundle.main.bundleIdentifier ?? ""
        let appDomain = defaults.persistentDomain(forName: appBundle)
        XCTAssertNil(appDomain?[key],
                     "onLanguageChanged(System) must remove AppleLanguages from the app domain; bundle=\(appBundle)")
    }

    // MARK: - currentLanguage reads the stored tag

    func testCurrentLanguageReadsSpanishTag() {
        defaults.set(["es-ES"], forKey: key)
        let lang = IosPlatformSettingsHooks.shared.currentLanguage()
        XCTAssertEqual(lang.tag, "es-ES", "currentLanguage must return Spanish (tag='es-ES')")
    }

    func testCurrentLanguageReadsBulgarianTag() {
        defaults.set(["bg"], forKey: key)
        let lang = IosPlatformSettingsHooks.shared.currentLanguage()
        XCTAssertEqual(lang.tag, "bg", "currentLanguage must return Bulgarian (tag='bg')")
    }

    // MARK: - round-trip

    func testRoundTripBulgarianWriteThenRead() {
        IosPlatformSettingsHooks.shared.onLanguageChanged(language: Settings_uiAppLanguage.bulgarian)
        let lang = IosPlatformSettingsHooks.shared.currentLanguage()
        XCTAssertEqual(lang.tag, "bg", "Read-back after write must return the same language tag")
    }
}
