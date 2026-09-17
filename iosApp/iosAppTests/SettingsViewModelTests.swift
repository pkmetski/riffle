import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/6-settings-viewmodel.md
//
// Exercises pure Kotlin logic in feature:settings/commonMain that is shared with iOS via the
// KMP framework. Tests use the sealed-class subtitle variants and derive functions directly.
final class SettingsViewModelTests: XCTestCase {

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
