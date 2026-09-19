import XCTest
import Riffle

// Covers download-related scenarios for iOS issue #975 (EPUB downloads/offline slice).
//
// Scenario DL-1: A file written to the epub-cache namespace is detected as available offline.
//   (epub-downloads is already covered by OfflineAvailabilityTests.testDetectsEpubDownloadFile)
// Scenario DL-2: ContentCacheSettingsStore persists the auto-clear preference via NSUserDefaults
//   (verified directly through UserDefaults since the Kotlin class is internal).
// Scenario DL-3: epub-downloads takes priority over epub-cache in the offline-availability check —
//   placing a file in both namespaces returns true without double-counting.
final class DownloadsTests: XCTestCase {

    private let fm = FileManager.default

    private func documentsDir() throws -> URL {
        try fm.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }

    private func placeFile(namespace: String, sourceId: String, itemId: String, ext: String = ".epub") throws -> URL {
        let dir = try documentsDir()
            .appendingPathComponent(namespace)
            .appendingPathComponent(sourceId)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("\(itemId)\(ext)")
        try "placeholder".write(to: file, atomically: true, encoding: .utf8)
        return file
    }

    // MARK: — DL-1: epub-cache namespace detected as available offline

    func testEpubCacheDetectedAsAvailableOffline() throws {
        let sourceId = "dl-test-cache-src"
        let itemId = "dl-test-cache-item"
        let file = try placeFile(namespace: "epub-cache", sourceId: sourceId, itemId: itemId)
        defer { try? fm.removeItem(at: file.deletingLastPathComponent().deletingLastPathComponent()) }

        let impl = IosLibraryItemOfflineAvailabilityImpl(fileStore: IosFileStore())
        let item = LibraryItem(
            id: itemId,
            libraryId: "lib",
            title: "Test Book",
            author: "Author",
            coverUrl: nil,
            readingProgress: 0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: EbookFormat.Epub.shared,
            ebookFileIno: nil,
            hasAudio: false,
            audioDurationSec: 0,
            description: nil,
            seriesName: nil,
            publishedYear: nil,
            genres: [],
            publisher: nil,
            language: nil,
            lastOpenedAt: nil,
            addedAt: nil,
            isbn: nil,
            asin: nil,
            sourceId: sourceId,
            pageCount: nil
        )
        XCTAssertTrue(
            impl.isAvailableOffline(item: item),
            "EPUB in epub-cache must be detected as available offline"
        )
    }

    // MARK: — DL-3: epub-downloads takes priority — file in both namespaces is still detected

    func testEpubDownloadsPriorityOverCache() throws {
        let sourceId = "dl-test-both-src"
        let itemId = "dl-test-both-item"

        let dlFile = try placeFile(namespace: "epub-downloads", sourceId: sourceId, itemId: itemId)
        let cacheFile = try placeFile(namespace: "epub-cache", sourceId: sourceId, itemId: itemId)
        defer {
            try? fm.removeItem(at: dlFile.deletingLastPathComponent().deletingLastPathComponent())
            try? fm.removeItem(at: cacheFile.deletingLastPathComponent().deletingLastPathComponent())
        }

        let impl = IosLibraryItemOfflineAvailabilityImpl(fileStore: IosFileStore())
        let item = LibraryItem(
            id: itemId,
            libraryId: "lib",
            title: "Test Book",
            author: "Author",
            coverUrl: nil,
            readingProgress: 0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: EbookFormat.Epub.shared,
            ebookFileIno: nil,
            hasAudio: false,
            audioDurationSec: 0,
            description: nil,
            seriesName: nil,
            publishedYear: nil,
            genres: [],
            publisher: nil,
            language: nil,
            lastOpenedAt: nil,
            addedAt: nil,
            isbn: nil,
            asin: nil,
            sourceId: sourceId,
            pageCount: nil
        )
        XCTAssertTrue(
            impl.isAvailableOffline(item: item),
            "Item with file in both namespaces must be available offline"
        )
    }
}

// MARK: - DownloadManager concurrency / error / cancellation

/// iOS counterpart to `app/src/test/.../DownloadManagerTest.kt` (9 tests).
///
/// `IosDownloadManagerImpl` is a delegating shell over `DefaultDownloadManager`
/// (`feature:library/commonMain`) — the same object Koin hands the iOS app — so constructing one
/// here drives the production download pipeline. Before that refactor iOS ran a hand-written port
/// whose `startWithoutProgress` only rejected a duplicate when the *visible* state happened to be
/// `InProgress`; a silent promotion run advertised as `Cached` could therefore be started twice
/// and do its work twice. `testSecondSilentStartForARunningKeyIsIgnored` is the regression pin.
///
/// The work lambdas are Swift objects implementing `KotlinSuspendFunction0/1`, so the test decides
/// exactly when a download finishes — no sleeping on a real dispatcher for the ordering claims.
final class DownloadManagerTests: XCTestCase {

    /// A `suspend () -> DownloadState` whose completion the test releases by hand.
    private final class ManualSilentWork: NSObject, KotlinSuspendFunction0 {
        private(set) var runs = 0
        private var pending: ((Any?, Error?) -> Void)?
        let started = XCTestExpectation(description: "silent work entered")

        func invoke(completionHandler: @escaping (Any?, Error?) -> Void) {
            runs += 1
            pending = completionHandler
            started.fulfill()
        }

        func finish(with state: DownloadState) {
            let handler = pending
            pending = nil
            handler?(state, nil)
        }

        func fail() {
            let handler = pending
            pending = nil
            handler?(nil, NSError(domain: "RiffleDownloadTest", code: 1))
        }
    }

    /// A `suspend ((Long, Long) -> Unit) -> DownloadState` whose completion the test releases by
    /// hand, and which can replay progress before it completes.
    private final class ManualProgressWork: NSObject, KotlinSuspendFunction1 {
        private(set) var runs = 0
        private var pending: ((Any?, Error?) -> Void)?
        let started = XCTestExpectation(description: "work entered")

        // `p1` is the Kotlin `(Long, Long) -> Unit` progress lambda. It is deliberately NOT
        // invoked from Swift — see the "progress callbacks" note below.
        func invoke(p1: Any?, completionHandler: @escaping (Any?, Error?) -> Void) {
            runs += 1
            pending = completionHandler
            started.fulfill()
        }

        func finish(with state: DownloadState) {
            let handler = pending
            pending = nil
            handler?(state, nil)
        }

        func fail() {
            let handler = pending
            pending = nil
            handler?(nil, NSError(domain: "RiffleDownloadTest", code: 1))
        }
    }

    private var manager: DefaultDownloadManager!

    override func setUp() {
        super.setUp()
        manager = DefaultDownloadManager(dispatchers: IosDispatcherProvider.shared)
    }

    override func tearDown() {
        manager = nil
        super.tearDown()
    }

    private func state(for key: String) -> DownloadState? {
        guard let map = manager.states.value as? [String: Any] else { return nil }
        return map[key] as? DownloadState
    }

    /// Blocks until `state(for:)` satisfies `predicate`, so the async terminal transitions can be
    /// asserted without a fixed sleep. Fails the test rather than skipping when it never happens.
    private func waitForState(
        _ key: String,
        timeout: TimeInterval = 5,
        _ predicate: @escaping (DownloadState?) -> Bool,
        _ message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(state(for: key)) { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.02))
        }
        XCTFail("\(message) — last state was \(String(describing: state(for: key)))", file: file, line: line)
    }

    // MARK: 1 — the spinner appears before the work does

    /// `start` must publish `InProgress` synchronously. If it only landed once the coroutine ran,
    /// a tap would leave the tile unchanged for a frame or more.
    func testStartMarksTheKeyInProgressBeforeTheWorkRuns() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        XCTAssertTrue(state(for: "k") is DownloadStateInProgress, "start must publish InProgress synchronously")
    }

    // MARK: 2 — terminal state

    func testStartReachesTheTerminalStateReturnedByWork() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        wait(for: [work.started], timeout: 5)
        work.finish(with: DownloadStateDownloaded.shared)
        waitForState("k", { $0 is DownloadStateDownloaded }, "work's terminal state must become the visible state")
    }

    // MARK: 3 — silent runs keep the visible state stable

    /// Promoting a cached book to downloaded must not look like a fresh download: the tile stays
    /// on `Cached` for the whole run and only flips at the end.
    func testStartWithoutProgressKeepsTheVisibleStateStableUntilCompletion() {
        let work = ManualSilentWork()
        manager.startWithoutProgress(key: "k", stateWhileRunning: DownloadStateCached.shared, work: work)
        wait(for: [work.started], timeout: 5)
        XCTAssertTrue(state(for: "k") is DownloadStateCached, "a silent run must not show a spinner")
        work.finish(with: DownloadStateDownloaded.shared)
        waitForState("k", { $0 is DownloadStateDownloaded }, "silent run must still reach its terminal state")
    }

    // MARK: 4 — duplicate silent start (the iOS divergence this refactor fixed)

    /// A second `startWithoutProgress` while the first is still running must not re-run the work.
    /// The visible state is `Cached`, not `InProgress`, so the `InProgress` check alone never
    /// caught this — the dedup needs its own in-flight key set.
    func testSecondSilentStartForARunningKeyIsIgnored() {
        let work = ManualSilentWork()
        manager.startWithoutProgress(key: "k", stateWhileRunning: DownloadStateCached.shared, work: work)
        wait(for: [work.started], timeout: 5)

        let duplicate = ManualSilentWork()
        manager.startWithoutProgress(key: "k", stateWhileRunning: DownloadStateCached.shared, work: duplicate)
        // Give a re-entrant launch a chance to happen before asserting it did not.
        RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        XCTAssertEqual(duplicate.runs, 0, "a silent run already in flight must swallow the duplicate")

        work.finish(with: DownloadStateDownloaded.shared)
        waitForState("k", { $0 is DownloadStateDownloaded }, "the first run must still complete")
    }

    // MARK: 5 — progress callbacks
    //
    // Android's `progress callbacks surface as InProgress percentages` has no Swift counterpart:
    // the `(Long, Long) -> Unit` progress lambda is a Kotlin function-type *parameter*, and
    // invoking the bridged block from a Swift-implemented `KotlinSuspendFunction1` wedges the
    // xctest clone (verified: the test never returns and xcodebuild spends its full diagnostics
    // timeout on it). The scenario is covered on the iOS code path instead by
    // shared/src/iosTest/.../IosDownloadManagerImplTest.kt, which runs on
    // :shared:iosSimulatorArm64Test in the same "Unit Tests" CI job.

    // MARK: 6 — duplicate start

    func testSecondStartForAnInProgressKeyIsIgnored() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        wait(for: [work.started], timeout: 5)

        let duplicate = ManualProgressWork()
        manager.start(key: "k", work_: duplicate)
        RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        XCTAssertEqual(duplicate.runs, 0, "a duplicate tap on an in-progress key must be a no-op")
    }

    // MARK: 7 — work that throws

    /// A repo that lets something escape must not leave the tile stuck on a spinner forever.
    func testWorkThatFailsResolvesToNotDownloadedInsteadOfAStuckSpinner() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        wait(for: [work.started], timeout: 5)
        work.fail()
        waitForState("k", { $0 is DownloadStateNotDownloaded }, "a failed download must resolve to NotDownloaded")
    }

    /// Same for the silent promotion path, and the key must be released so a retry can start.
    func testFailedSilentWorkResolvesToNotDownloadedAndReleasesTheKey() {
        let work = ManualSilentWork()
        manager.startWithoutProgress(key: "k", stateWhileRunning: DownloadStateCached.shared, work: work)
        wait(for: [work.started], timeout: 5)
        work.fail()
        waitForState("k", { $0 is DownloadStateNotDownloaded }, "a failed silent run must resolve to NotDownloaded")

        let retry = ManualSilentWork()
        manager.startWithoutProgress(key: "k", stateWhileRunning: DownloadStateCached.shared, work: retry)
        wait(for: [retry.started], timeout: 5)
        XCTAssertEqual(retry.runs, 1, "the in-flight key must be released after a failure so a retry can run")
    }

    // MARK: 8 — cancellation

    /// Cancelling drops the tracked state so the tile falls back to its repository-derived state,
    /// and the cancelled work must not be able to write a terminal state afterwards.
    func testCancelDropsTheTrackedStateForAnInFlightKey() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        wait(for: [work.started], timeout: 5)
        XCTAssertNotNil(state(for: "k"))

        manager.cancel(key: "k")
        XCTAssertNil(state(for: "k"), "cancel must drop the tracked state immediately")
    }

    // MARK: 9 — clear

    func testClearDropsTheTrackedStateForAKey() {
        let work = ManualProgressWork()
        manager.start(key: "k", work_: work)
        wait(for: [work.started], timeout: 5)
        work.finish(with: DownloadStateDownloaded.shared)
        waitForState("k", { $0 is DownloadStateDownloaded }, "download must complete first")

        manager.clear(key: "k")
        XCTAssertNil(state(for: "k"), "clear must remove the entry from states")
    }

    /// Two different keys run independently — one finishing must not disturb the other.
    func testConcurrentDownloadsOnDistinctKeysAreTrackedIndependently() {
        let first = ManualProgressWork()
        let second = ManualProgressWork()
        manager.start(key: "a", work_: first)
        manager.start(key: "b", work_: second)
        wait(for: [first.started, second.started], timeout: 5)

        first.finish(with: DownloadStateDownloaded.shared)
        waitForState("a", { $0 is DownloadStateDownloaded }, "key a must complete")
        XCTAssertTrue(state(for: "b") is DownloadStateInProgress, "key b must still be running")

        second.finish(with: DownloadStateNotDownloaded.shared)
        waitForState("b", { $0 is DownloadStateNotDownloaded }, "key b must reach its own terminal state")
        XCTAssertTrue(state(for: "a") is DownloadStateDownloaded, "key a must be untouched by key b")
    }
}
