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

    // MARK: - The remaining CycleOutcome branches
    //
    // feature:settings' commonTest drives these indirectly through `SettingsViewModel
    // .annotationSyncRow`, which iOS already runs. What it does not cover — on either platform —
    // are the `Failed.Server` and `Failed.Unknown` variants, and no test anywhere pins the
    // function's own mapping for the Tls/Auth/Network branches. These do.

    private var config: AnnotationSyncConfig {
        AnnotationSyncConfig(baseUrl: "https://dav.example.com", username: "user", password: "pw")
    }

    /// Anything the user has to act on — bad credentials, an untrusted certificate, a broken
    /// server, an unclassified failure — is an Error badge. Reporting these as Pending would tell
    /// the user to wait for a sync that will never succeed on its own.
    func testEveryActionableFailureDerivesError() {
        let actionable: [(String, CycleOutcome)] = [
            ("auth", CycleOutcome.FailedAuth(atMs: 1000, code: 401)),
            ("tls", CycleOutcome.FailedTls(atMs: 1000, message: "cert untrusted")),
            ("server", CycleOutcome.FailedServer(atMs: 1000, code: 500)),
            ("unknown", CycleOutcome.FailedUnknown(atMs: 1000, message: "boom")),
        ]
        for (name, outcome) in actionable {
            XCTAssertEqual(
                AnnotationSyncKindKt.deriveAnnotationSyncKind(config: config, outcome: outcome, pendingBookCount: 0),
                AnnotationSyncKind.error,
                "\(name) failure must surface as Error, not Pending"
            )
        }
    }

    /// A network failure is transient: it stays Pending so the row reads as "will retry" rather
    /// than demanding the user fix something.
    func testNetworkFailureDerivesPendingNotError() {
        XCTAssertEqual(
            AnnotationSyncKindKt.deriveAnnotationSyncKind(
                config: config,
                outcome: CycleOutcome.FailedNetwork(atMs: 1000, message: "offline"),
                pendingBookCount: 0
            ),
            AnnotationSyncKind.pending
        )
    }

    /// A clean cycle with nothing queued is the only way to reach Synced.
    func testSuccessWithNothingPendingDerivesSynced() {
        XCTAssertEqual(
            AnnotationSyncKindKt.deriveAnnotationSyncKind(
                config: config,
                outcome: CycleOutcome.Success(atMs: 1000),
                pendingBookCount: 0
            ),
            AnnotationSyncKind.synced
        )
    }

    /// A successful cycle that still has queued books is not Synced — books written offline have
    /// not reached the server yet and claiming "Synced" would hide them.
    func testSuccessWithPendingBooksDerivesPending() {
        XCTAssertEqual(
            AnnotationSyncKindKt.deriveAnnotationSyncKind(
                config: config,
                outcome: CycleOutcome.Success(atMs: 1000),
                pendingBookCount: 3
            ),
            AnnotationSyncKind.pending
        )
    }

    /// An unconfigured WebDAV endpoint is Local whatever the last outcome or queue says — the
    /// config check short-circuits before the outcome is consulted.
    func testUnconfiguredStaysLocalRegardlessOfOutcomeOrQueue() {
        XCTAssertEqual(
            AnnotationSyncKindKt.deriveAnnotationSyncKind(
                config: nil,
                outcome: CycleOutcome.FailedAuth(atMs: 1000, code: 401),
                pendingBookCount: 7
            ),
            AnnotationSyncKind.local
        )
    }
}
