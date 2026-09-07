import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/6-settings-viewmodel.md
//
// Exercises pure Kotlin logic in feature:settings/commonMain that is shared with iOS via the
// KMP framework. Tests use the sealed-class subtitle variants and derive functions directly.
final class SettingsViewModelTests: XCTestCase {

    // MARK: AnnotationSyncSubtitle sealed class (scenarios 6.3)

    func testAnnotationSyncSubtitleSyncedCarriesIdentity() {
        let subtitle = AnnotationSyncSubtitle.Synced(identity: "alice@example.com")
        guard let synced = subtitle as? AnnotationSyncSubtitle.Synced else {
            XCTFail("Expected Synced but got \(subtitle)")
            return
        }
        XCTAssertEqual(synced.identity, "alice@example.com")
    }

    func testAnnotationSyncSubtitleBooksPendingOfflineCarriesCount() {
        let subtitle = AnnotationSyncSubtitle.BooksPendingOffline(count: 3)
        guard let pending = subtitle as? AnnotationSyncSubtitle.BooksPendingOffline else {
            XCTFail("Expected BooksPendingOffline but got \(subtitle)")
            return
        }
        XCTAssertEqual(pending.count, 3)
    }

    func testAnnotationSyncSubtitleHttpErrorCarriesCode() {
        let subtitle = AnnotationSyncSubtitle.HttpError(code: 503)
        guard let httpError = subtitle as? AnnotationSyncSubtitle.HttpError else {
            XCTFail("Expected HttpError but got \(subtitle)")
            return
        }
        XCTAssertEqual(httpError.code, 503)
    }

    // MARK: AnnotationSyncKind derivation (scenarios 6.3)

    func testDeriveAnnotationSyncKindLocalWhenUnconfigured() {
        let kind = AnnotationSyncKindKt.deriveAnnotationSyncKind(
            config: nil,
            outcome: CycleOutcome.NeverRun(),
            pendingBookCount: 0
        )
        XCTAssertEqual(kind, AnnotationSyncKind.local)
    }

    func testDeriveAnnotationSyncKindPendingWhenNeverRunAndConfigured() {
        let config = AnnotationSyncConfig(
            baseUrl: "https://dav.example.com",
            username: "user",
            password: "pw"
        )
        let kind = AnnotationSyncKindKt.deriveAnnotationSyncKind(
            config: config,
            outcome: CycleOutcome.NeverRun(),
            pendingBookCount: 0
        )
        XCTAssertEqual(kind, AnnotationSyncKind.pending)
    }
}
