import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/6-settings-viewmodel.md
//
// Exercises pure Kotlin logic in feature:settings/commonMain that is shared with iOS via the
// KMP framework. Tests use the sealed-class subtitle variants and derive functions directly.
final class SettingsViewModelTests: XCTestCase {

    // MARK: AnnotationSyncSubtitle sealed class (scenarios 6.3)

    func testAnnotationSyncSubtitleNotConfiguredIsDistinctType() {
        let subtitle = AnnotationSyncSubtitle.NotConfigured()
        XCTAssertTrue(subtitle is AnnotationSyncSubtitle.NotConfigured)
        XCTAssertFalse(subtitle is AnnotationSyncSubtitle.WaitingForFirstSync)
    }

    func testAnnotationSyncSubtitleWaitingForFirstSyncIsDistinctType() {
        let subtitle = AnnotationSyncSubtitle.WaitingForFirstSync()
        XCTAssertTrue(subtitle is AnnotationSyncSubtitle.WaitingForFirstSync)
    }

    func testAnnotationSyncSubtitleSyncedCarriesIdentity() {
        let subtitle = AnnotationSyncSubtitle.Synced(identity: "alice@example.com")
        XCTAssertTrue(subtitle is AnnotationSyncSubtitle.Synced)
        let synced = subtitle as! AnnotationSyncSubtitle.Synced
        XCTAssertEqual(synced.identity, "alice@example.com")
    }

    func testAnnotationSyncSubtitleBooksPendingOfflineCarriesCount() {
        let subtitle = AnnotationSyncSubtitle.BooksPendingOffline(count: 3)
        XCTAssertTrue(subtitle is AnnotationSyncSubtitle.BooksPendingOffline)
        let pending = subtitle as! AnnotationSyncSubtitle.BooksPendingOffline
        XCTAssertEqual(pending.count, 3)
    }

    func testAnnotationSyncSubtitleServerErrorCarriesCode() {
        let subtitle = AnnotationSyncSubtitle.ServerError(code: 503)
        XCTAssertTrue(subtitle is AnnotationSyncSubtitle.ServerError)
        let error = subtitle as! AnnotationSyncSubtitle.ServerError
        XCTAssertEqual(error.code, 503)
    }

    // MARK: AnnotationSyncKind derivation (scenarios 6.3)

    func testDeriveAnnotationSyncKindLocalWhenUnconfigured() {
        let kind = AnnotationSyncKindKt.deriveAnnotationSyncKind(
            config: nil,
            outcome: CycleOutcome.neverRun,
            pendingBookCount: 0
        )
        XCTAssertEqual(kind, AnnotationSyncKind.local)
    }

    func testDeriveAnnotationSyncKindPendingWhenNeverRunAndConfigured() {
        let config = AnnotationSyncConfig(
            url: "https://dav.example.com",
            username: "user",
            encryptedPassword: "pw",
            deviceId: "d1",
            deviceName: "iPhone"
        )
        let kind = AnnotationSyncKindKt.deriveAnnotationSyncKind(
            config: config,
            outcome: CycleOutcome.neverRun,
            pendingBookCount: 0
        )
        XCTAssertEqual(kind, AnnotationSyncKind.pending)
    }

    // MARK: AppVersion data class (scenario 6.1)

    func testAppVersionHoldsNameAndCode() {
        let version = AppVersion(name: "1.2.3", code: 42)
        XCTAssertEqual(version.name, "1.2.3")
        XCTAssertEqual(version.code, 42)
    }
}
