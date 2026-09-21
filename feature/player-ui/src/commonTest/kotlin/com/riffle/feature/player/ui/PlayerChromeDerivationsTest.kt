package com.riffle.feature.player.ui

import com.riffle.core.models.AudiobookBookmark
import com.riffle.feature.player.SleepTimerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audiobook player's pure derivations, shared by `:app` and `:shared`.
 *
 * Runs on the JVM and — via `:feature:player-ui:iosSimulatorArm64Test` — on the iOS simulator, so
 * one assertion covers both platforms. That is the point: every item pinned here is something iOS
 * either did differently or did not do at all before #1072.
 */
class PlayerChromeDerivationsTest {

    private val labels = PlayerChromeLabels.English

    // ── time labels ─────────────────────────────────────────────────────────────

    /**
     * iOS printed the book's *total* duration on the right of the scrubber — a number that never
     * moves. Both platforms now count down.
     */
    @Test
    fun theRightHandTimeCountsDownRatherThanShowingTheTotal() {
        assertEquals("-1:00:00", remainingTimeLabel(positionSec = 0.0, durationSec = 3_600.0))
        assertEquals("-30:00", remainingTimeLabel(positionSec = 1_800.0, durationSec = 3_600.0))
        assertEquals("-0:00", remainingTimeLabel(positionSec = 3_600.0, durationSec = 3_600.0))
    }

    @Test
    fun theRemainingTimeNeverGoesPastZero() {
        assertEquals(
            "-0:00",
            remainingTimeLabel(positionSec = 4_000.0, durationSec = 3_600.0),
            "an over-run position must not print a positive remainder after the minus sign",
        )
    }

    // ── layout ──────────────────────────────────────────────────────────────────

    @Test
    fun theTwoColumnBreakpointIsMaterialsCompactHeight() {
        assertTrue(isCompactPlayerHeight(479f), "a phone in landscape gets the two-column player")
        assertFalse(isCompactPlayerHeight(480f), "480dp is the first non-compact height")
        assertFalse(isCompactPlayerHeight(844f), "a phone in portrait keeps the vertical player")
    }

    // ── bookmarks ───────────────────────────────────────────────────────────────

    private fun bookmark(id: String, positionSec: Double) = AudiobookBookmark(
        id = id,
        sourceId = "s",
        itemId = "i",
        positionSec = positionSec,
        title = id,
        createdAt = 0L,
    )

    @Test
    fun theCornerRibbonPicksTheNearestBookmarkInsideTheWindow() {
        val bookmarks = listOf(bookmark("far", 100.0), bookmark("near", 101.5), bookmark("mid", 103.0))
        assertEquals("near", bookmarkNear(bookmarks, 101.0)?.id)
    }

    @Test
    fun aBookmarkOutsideTheWindowDoesNotFillTheRibbon() {
        val bookmarks = listOf(bookmark("a", 100.0))
        assertNull(bookmarkNear(bookmarks, 103.5), "beyond ±${BOOKMARK_WINDOW_SEC}s is not 'on' it")
        assertEquals("a", bookmarkNear(bookmarks, 103.0)?.id, "exactly at the window edge counts")
    }

    @Test
    fun theBookmarksPillIsSingularForExactlyOne() {
        assertEquals("0 bookmarks", bookmarkCountLabel(0, labels))
        assertEquals("1 bookmark", bookmarkCountLabel(1, labels))
        assertEquals("4 bookmarks", bookmarkCountLabel(4, labels))
    }

    @Test
    fun theBookmarkDialogAppendsTheChapterOnlyWhenThereIsOne() {
        assertEquals("1:02:11 · The Conversation", bookmarkPositionLabel("1:02:11", "The Conversation"))
        assertEquals("1:02:11", bookmarkPositionLabel("1:02:11", ""))
    }

    // ── chapters ────────────────────────────────────────────────────────────────

    @Test
    fun anUntitledChapterFallsBackToItsNumber() {
        assertEquals("Prologue", chapterDisplayTitle("Prologue", index = 0, labels = labels))
        assertEquals("Chapter 7", chapterDisplayTitle("   ", index = 6, labels = labels))
    }

    // ── sleep timer ─────────────────────────────────────────────────────────────

    @Test
    fun theSleepPillReadsTheLiveCountdown() {
        assertEquals("Sleep", sleepPillLabel(SleepTimerMode.None, labels))
        assertEquals("5:00", sleepPillLabel(SleepTimerMode.CountDown(300_000L), labels))
        assertEquals("End of ch.", sleepPillLabel(SleepTimerMode.EndOfChapter, labels))
    }

    @Test
    fun theSleepSheetBannerNamesTheArmedTimer() {
        assertEquals("", sleepBannerLabel(SleepTimerMode.None, labels))
        assertEquals("Sleeping in 5:00", sleepBannerLabel(SleepTimerMode.CountDown(300_000L), labels))
        assertEquals("Sleeping at end of chapter", sleepBannerLabel(SleepTimerMode.EndOfChapter, labels))
    }

    @Test
    fun aSleepPresetArmsThatManyMinutes() {
        assertEquals(SleepTimerMode.CountDown(5 * 60_000L), sleepPresetMode(5))
        assertEquals(SleepTimerMode.CountDown(90 * 60_000L), sleepPresetMode(90))
        assertEquals(listOf(5, 15, 30, 45, 60, 90), SLEEP_PRESETS_MINUTES)
        assertEquals("15 min", sleepPresetLabel(15, labels))
    }

    // ── speed ───────────────────────────────────────────────────────────────────

    @Test
    fun theCurrentSpeedPresetIsMarkedSelectedDespiteFloatDrift() {
        assertTrue(isSelectedSpeedPreset(1.25f, 1.2500001f))
        assertFalse(isSelectedSpeedPreset(1.25f, 1.3f))
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f), SPEED_SHEET_PRESETS)
    }

    // ── description blurb ───────────────────────────────────────────────────────

    @Test
    fun theBlurbStripsMarkupAndDecodesEntities() {
        assertEquals("Hello world", stripHtmlToText("<p>Hello <b>world</b></p>"))
        assertEquals("a b", stripHtmlToText("a<br/>b"), "a removed tag is a word boundary")
        assertEquals("Tom & Jerry", stripHtmlToText("Tom &amp; Jerry"))
        assertEquals("non breaking", stripHtmlToText("non&nbsp;breaking"))
        assertEquals("café", stripHtmlToText("caf&#233;"))
        assertEquals("café", stripHtmlToText("caf&#xE9;"))
        assertEquals("5 < 6", stripHtmlToText("5 &lt; 6"))
    }

    @Test
    fun anUnterminatedEntityIsLeftAlone() {
        assertEquals("Q&A now", stripHtmlToText("Q&A now"))
    }
}
