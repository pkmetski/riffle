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

}
