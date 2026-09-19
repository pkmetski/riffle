import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/05-audiobook-player-viewmodel.md
//
// Exercises the pure Kotlin helpers promoted to feature:player/commonMain so they compile
// and behave correctly on iOS without any JVM-specific APIs.
final class AudiobookPlayerViewModelTests: XCTestCase {

    // MARK: SleepTimerMode.formatCountdown (scenarios 5.1–5.2)

    func testFormatCountdownOneMinuteThirtySeconds() {
        let mode = SleepTimerModeCountDown(remainingMs: 90_000)
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(mode), "1:30")
    }

    func testFormatCountdownNoneReturnsEmpty() {
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeNone.shared), "")
    }

    func testFormatCountdownEndOfChapterReturnsEmpty() {
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeEndOfChapter.shared), "")
    }

    func testFormatCompactDurationCustomLocalizedTemplates() {
        let templates = CompactDurationLabelTemplates(
            minutes: "%1$d min",
            hours: "%1$d hr",
            hoursMinutes: "%1$d hr %2$d min"
        )
        // 5400s = 1h 30m
        let result = AudiobookProgressUtilsKt.formatCompactDuration(
            durationSec: 5400.0,
            templates: templates,
            roundToNearestMinute: false
        )
        XCTAssertEqual(result, "1 hr 30 min")
    }

    // MARK: NowPlayingStore (scenario 5.8)

    func testNowPlayingStoreSetAndClear() {
        let store = NowPlayingStore()
        store.set(value: NowPlayingAudiobook(sourceId: "source-1", itemId: "book-1"))
        XCTAssertEqual(store.current?.itemId, "book-1")

        store.clearIf { KotlinBoolean(bool: $0.itemId == "book-1") }
        XCTAssertNil(store.current)
    }

    // MARK: - Sleep timer countdown formatting (scenarios 5.1–5.2, remaining cases)

    /// Seconds are zero-padded and minutes are not. A naive `"\(min):\(sec)"` prints "1:5".
    func testFormatCountdownZeroPadsSecondsButNotMinutes() {
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 65_000)), "1:05")
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 5_000)), "0:05")
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 60_000)), "1:00")
    }

    /// The countdown keeps counting in minutes past the hour rather than rolling over to an
    /// hours field the label has no room for, and a spent timer reads 0:00 instead of going
    /// negative.
    func testFormatCountdownHandlesOverAnHourAndExhaustedTimers() {
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 3_600_000)), "60:00")
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 0)), "0:00")
    }

    /// Sub-second remainders truncate towards the lower second — the label must never show a
    /// second the user has not actually reached.
    func testFormatCountdownTruncatesPartialSeconds() {
        XCTAssertEqual(SleepTimerModeKt.formatCountdown(SleepTimerModeCountDown(remainingMs: 1_999)), "0:01")
    }

    // MARK: - NowPlayingStore guard (scenario 5.8, remaining cases)

    /// Each player screen clears its own session on teardown. The predicate guard is what stops
    /// the reader's teardown from wiping a full-screen audiobook session that replaced it — drop
    /// the guard and the media notification stops routing anywhere.
    func testClearIfLeavesAnotherScreensSessionAlone() {
        let store = NowPlayingStore()
        store.set(value: NowPlayingAudiobook(sourceId: "source-1", itemId: "book-1"))

        store.clearIf { KotlinBoolean(bool: $0.itemId == "some-other-book") }
        XCTAssertEqual(store.current?.itemId, "book-1", "a non-matching teardown must not clear the session")
    }

    /// Only one session exists at a time: setting a new one replaces the old, and the variants
    /// stay distinguishable so a notification tap can route to the right screen.
    func testSettingASessionReplacesThePreviousOneAndKeepsItsVariant() {
        let store = NowPlayingStore()
        store.set(value: NowPlayingAudiobook(sourceId: "source-1", itemId: "book-1"))
        store.set(value: NowPlayingReadaloud(itemId: "book-2"))

        XCTAssertEqual(store.current?.itemId, "book-2")
        XCTAssertTrue(store.current is NowPlayingReadaloud, "the readaloud variant must survive the round trip")
        XCTAssertFalse(store.current is NowPlayingAudiobook)

        store.clearIf { _ in KotlinBoolean(bool: true) }
        XCTAssertNil(store.current)
    }

    // MARK: - Skip / rewind intervals

    /// The transport's skip and rewind steps are asymmetric on purpose (forward 30s, back 15s),
    /// and rewind-on-resume is opt-in. iOS reads the same constants as Android, so a change on
    /// one platform must not silently leave the other on different steps.
    func testSkipAndRewindDefaultsAreTheSharedListeningDefaults() {
        let defaults = ListeningPreferencesStoreCompanion.shared
        XCTAssertEqual(defaults.DEFAULT_SKIP_INTERVAL_SECONDS, 30)
        XCTAssertEqual(defaults.DEFAULT_REWIND_INTERVAL_SECONDS, 15)
        XCTAssertEqual(defaults.DEFAULT_REWIND_ON_RESUME_SECONDS, 0, "rewind-on-resume must stay opt-in")
        XCTAssertNotEqual(
            defaults.DEFAULT_SKIP_INTERVAL_SECONDS,
            defaults.DEFAULT_REWIND_INTERVAL_SECONDS,
            "forward and back steps are deliberately different"
        )
    }
}
