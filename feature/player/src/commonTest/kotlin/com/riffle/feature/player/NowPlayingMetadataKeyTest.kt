package com.riffle.feature.player

import com.riffle.core.domain.AudiobookChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the shared lock-screen / notification refresh cadence used by both
 * `AudiobookController` (Android) and `IosAudioPlayerController` (iOS).
 */
class NowPlayingMetadataKeyTest {

    private val chapterOne = AudiobookChapter(index = 0, startSec = 0.0, endSec = 550.0, title = "One")
    private val chapterTwo = AudiobookChapter(index = 1, startSec = 550.0, endSec = 1200.0, title = "Two")

    @Test
    fun sameMinuteAndChapterProducesTheSameKeySoNoRefreshHappens() {
        val a = NowPlayingMetadataKey.of(positionSec = 100.0, durationSec = 1200.0, chapter = chapterOne)
        val b = NowPlayingMetadataKey.of(positionSec = 110.0, durationSec = 1200.0, chapter = chapterOne)
        assertEquals(a, b, "positions inside the same remaining-minute bucket must not force a refresh")
    }

    @Test
    fun crossingAWholeMinuteOfRemainingTimeChangesTheKey() {
        val a = NowPlayingMetadataKey.of(positionSec = 100.0, durationSec = 1200.0, chapter = chapterOne)
        val b = NowPlayingMetadataKey.of(positionSec = 165.0, durationSec = 1200.0, chapter = chapterOne)
        assertNotEquals(a, b, "the remaining-time line must tick once per whole minute")
    }

    @Test
    fun crossingAChapterBoundaryChangesTheKeyWithinTheSameMinute() {
        // 549 s and 551 s both leave 10m-and-change, so only the chapter differs.
        val before = NowPlayingMetadataKey.of(positionSec = 549.0, durationSec = 1200.0, chapter = chapterOne)
        val after = NowPlayingMetadataKey.of(positionSec = 551.0, durationSec = 1200.0, chapter = chapterTwo)
        assertEquals(
            before.remainingMinuteBucket,
            after.remainingMinuteBucket,
            "fixture must isolate the chapter change from the minute tick",
        )
        assertNotEquals(before, after, "a chapter transition must refresh the metadata immediately")
    }

    @Test
    fun chapterlessBooksStillTickOnRemainingTime() {
        val a = NowPlayingMetadataKey.of(positionSec = 0.0, durationSec = 1200.0, chapter = null)
        val b = NowPlayingMetadataKey.of(positionSec = 61.0, durationSec = 1200.0, chapter = null)
        assertEquals(-1, a.chapterIndex)
        assertNotEquals(a, b)
    }

    @Test
    fun noRealPositionCollidesWithTheNothingPushedYetSentinel() {
        val atEnd = NowPlayingMetadataKey.of(positionSec = 1200.0, durationSec = 1200.0, chapter = null)
        assertNotEquals(NowPlayingMetadataKey.NONE, atEnd)
        assertEquals(0L, atEnd.remainingMinuteBucket)
    }

    @Test
    fun remainingTimeNeverGoesNegativePastTheEnd() {
        assertEquals(0.0, NowPlayingMetadataKey.remainingSec(positionSec = 1300.0, durationSec = 1200.0))
    }
}
