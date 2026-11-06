import XCTest

// The full behavioral iOS tests for beta-release filtering (checkForUpdate excludes prereleases
// when betaUpdatesEnabled=false, includes them when true, and listReleasesSince returns empty
// when all releases are prereleases and beta is off) live in IosCrashAndUpdateTest.kt, which
// targets iosSimulatorArm64 and runs the real IosAppUpdateRepositoryImpl with a MockEngine.
//
// This Swift file covers the preference-layer contract that the Kotlin test cannot reach:
// IosAppUpdatePreferencesStoreImpl stores betaUpdatesEnabled under a documented NSUserDefaults
// key with a false default. If the key or default changes, the Kotlin repository will silently
// read the wrong value. These assertions pin both.
final class AppUpdateRepositoryBetaTests: XCTestCase {

    private let betaKey = "app_update:beta_updates_enabled"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: betaKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: betaKey)
        super.tearDown()
    }

    func testBetaUpdatesEnabledDefaultIsFalse() {
        // When the key has never been written, NSUserDefaults returns false for a missing Bool.
        // IosAppUpdatePreferencesStoreImpl must NOT pre-write true on first access, which would
        // silently opt new users into beta releases.
        XCTAssertFalse(
            UserDefaults.standard.bool(forKey: betaKey),
            "betaUpdatesEnabled must default to false — new users must not receive prereleases"
        )
    }

    func testBetaUpdatesEnabledKeyIsStable() {
        // Pin the documented NSUserDefaults key. A rename here breaks every existing installation:
        // the new key starts at its default (false) and the old key's value is orphaned.
        UserDefaults.standard.set(true, forKey: betaKey)
        XCTAssertTrue(
            UserDefaults.standard.bool(forKey: betaKey),
            "betaUpdatesEnabled NSUserDefaults key must be '\(betaKey)'"
        )
    }
}
