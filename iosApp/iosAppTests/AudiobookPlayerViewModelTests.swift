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

    // MARK: formatCompactDuration (scenarios 5.3–5.4)

    func testFormatCompactDurationHoursAndMinutes() {
        // 2h 6m = 7560s
        let result = AudiobookProgressUtilsKt.formatCompactDuration(
            durationSec: 7560.0,
            templates: CompactDurationLabelTemplates(
                minutes: "%1$dm", hours: "%1$dh", hoursMinutes: "%1$dh %2$dm"
            ),
            roundToNearestMinute: false
        )
        XCTAssertEqual(result, "2h 6m")
    }

    func testFormatCompactDurationHoursOnly() {
        let result = AudiobookProgressUtilsKt.formatCompactDuration(
            durationSec: 3600.0,
            templates: CompactDurationLabelTemplates(
                minutes: "%1$dm", hours: "%1$dh", hoursMinutes: "%1$dh %2$dm"
            ),
            roundToNearestMinute: false
        )
        XCTAssertEqual(result, "1h")
    }

    func testFormatCompactDurationMinutesOnly() {
        let result = AudiobookProgressUtilsKt.formatCompactDuration(
            durationSec: 600.0,
            templates: CompactDurationLabelTemplates(
                minutes: "%1$dm", hours: "%1$dh", hoursMinutes: "%1$dh %2$dm"
            ),
            roundToNearestMinute: false
        )
        XCTAssertEqual(result, "10m")
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

    // MARK: audiobookProgressFraction (scenarios 5.5–5.6)

    func testProgressFractionZeroDuration() {
        XCTAssertEqual(
            AudiobookProgressUtilsKt.audiobookProgressFraction(positionSec: 100.0, durationSec: 0.0),
            0.0
        )
    }

    func testProgressFractionSnapsToOneNearEnd() {
        XCTAssertEqual(
            AudiobookProgressUtilsKt.audiobookProgressFraction(positionSec: 3599.5, durationSec: 3600.0),
            1.0
        )
    }

    // MARK: audiobookStartSec (scenario 5.7)

    func testStartSecRestartsFinishedBook() {
        XCTAssertEqual(
            AudiobookProgressUtilsKt.audiobookStartSec(resumeSec: 3599.5, durationSec: 3600.0),
            0.0
        )
    }

    func testStartSecRestartsExactlyAtEnd() {
        XCTAssertEqual(
            AudiobookProgressUtilsKt.audiobookStartSec(resumeSec: 3600.0, durationSec: 3600.0),
            0.0,
            "Position at end must reset to 0 so the player opens from the beginning"
        )
    }

    func testStartSecPreservesInProgressPosition() {
        XCTAssertEqual(
            AudiobookProgressUtilsKt.audiobookStartSec(resumeSec: 1800.0, durationSec: 3600.0),
            1800.0,
            "In-progress position must not be reset"
        )
    }

    // MARK: NowPlayingStore (scenario 5.8)

    func testNowPlayingStoreSetAndClear() {
        let store = NowPlayingStore()
        store.set(value: NowPlayingAudiobook(sourceId: "source-1", itemId: "book-1"))
        XCTAssertEqual(store.current?.itemId, "book-1")

        store.clearIf { KotlinBoolean(bool: $0.itemId == "book-1") }
        XCTAssertNil(store.current)
    }

    func testNowPlayingAudiobookExposesSourceId() {
        let nowPlaying = NowPlayingAudiobook(sourceId: "radio-es-source", itemId: "item-abc")
        XCTAssertEqual(nowPlaying.sourceId, "radio-es-source")
        XCTAssertEqual(nowPlaying.itemId, "item-abc")
    }

    func testPlaylistAdvanceExposesSourceId() {
        let event = AudiobookPlayerEventPlaylistAdvance(sourceId: "radio-es-source", nextItemId: "item-xyz")
        XCTAssertEqual(event.sourceId, "radio-es-source")
        XCTAssertEqual(event.nextItemId, "item-xyz")
    }

    // MARK: readaloudControlState (scenario 5.9)

    func testReadaloudControlStateStoryteller() {
        let state = AudiobookProgressUtilsKt.readaloudControlState(
            isStoryteller: true, isMatchedAbs: false, bundlePresent: false
        )
        XCTAssertTrue(state.visible)
        XCTAssertTrue(state.enabled)
    }

    func testReadaloudControlStateMatchedAbs() {
        let state = AudiobookProgressUtilsKt.readaloudControlState(
            isStoryteller: false, isMatchedAbs: true, bundlePresent: false
        )
        XCTAssertTrue(state.visible)
        XCTAssertTrue(state.enabled)
    }

    func testReadaloudControlStateUnmatched() {
        let state = AudiobookProgressUtilsKt.readaloudControlState(
            isStoryteller: false, isMatchedAbs: false, bundlePresent: true
        )
        XCTAssertFalse(state.visible)
        XCTAssertFalse(state.enabled)
    }
}
