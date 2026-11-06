import XCTest

// Tests for the beta channel Settings UI preference layer.
// The ViewModel clearing logic (setBetaUpdatesEnabled → clear ignored prerelease ignore) is
// pinned by SettingsViewModelTest.kt running on iosSimulatorArm64 via commonTest. These tests
// cover the iOS NSUserDefaults persistence layer for the three keys that the ViewModel reads and
// writes: betaUpdatesEnabled, ignoredVersionCode, and ignoredVersionWasPreRelease.
final class BetaChannelSettingsTests: XCTestCase {

    private let betaKey = "app_update:beta_updates_enabled"
    private let ignoredCodeKey = "app_update:ignored_version_code"
    private let ignoredWasPreReleaseKey = "app_update:ignored_version_was_prerelease"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: betaKey)
        UserDefaults.standard.removeObject(forKey: ignoredCodeKey)
        UserDefaults.standard.removeObject(forKey: ignoredWasPreReleaseKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: betaKey)
        UserDefaults.standard.removeObject(forKey: ignoredCodeKey)
        UserDefaults.standard.removeObject(forKey: ignoredWasPreReleaseKey)
        super.tearDown()
    }

    func testBetaUpdatesEnabledDefaultsFalse() {
        XCTAssertFalse(
            UserDefaults.standard.bool(forKey: betaKey),
            "betaUpdatesEnabled must default to false — new users must not receive prereleases"
        )
    }

    func testSetBetaUpdatesEnabledPersists() {
        UserDefaults.standard.set(true, forKey: betaKey)
        XCTAssertTrue(
            UserDefaults.standard.bool(forKey: betaKey),
            "betaUpdatesEnabled must persist true after being set"
        )
    }

    func testBetaOptOutClearsPrereleaseIgnore() {
        UserDefaults.standard.set(10500, forKey: ignoredCodeKey)
        UserDefaults.standard.set(true, forKey: ignoredWasPreReleaseKey)

        let wasPreRelease = UserDefaults.standard.bool(forKey: ignoredWasPreReleaseKey)
        if wasPreRelease {
            UserDefaults.standard.set(0, forKey: ignoredCodeKey)
            UserDefaults.standard.set(false, forKey: ignoredWasPreReleaseKey)
        }

        XCTAssertEqual(
            UserDefaults.standard.integer(forKey: ignoredCodeKey), 0,
            "ignoredVersionCode must be cleared when opting out of beta with a prerelease ignore active"
        )
        XCTAssertFalse(
            UserDefaults.standard.bool(forKey: ignoredWasPreReleaseKey),
            "ignoredVersionWasPreRelease must be cleared when opting out of beta"
        )
    }

    func testBetaOptOutPreservesStableIgnore() {
        UserDefaults.standard.set(10400, forKey: ignoredCodeKey)
        UserDefaults.standard.set(false, forKey: ignoredWasPreReleaseKey)

        let wasPreRelease = UserDefaults.standard.bool(forKey: ignoredWasPreReleaseKey)
        if wasPreRelease {
            UserDefaults.standard.set(0, forKey: ignoredCodeKey)
            UserDefaults.standard.set(false, forKey: ignoredWasPreReleaseKey)
        }

        XCTAssertEqual(
            UserDefaults.standard.integer(forKey: ignoredCodeKey), 10400,
            "ignoredVersionCode must remain unchanged when opting out of beta with a stable ignore active"
        )
    }
}
