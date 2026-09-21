package com.riffle.feature.player.ui

import com.riffle.feature.player.CompactDurationLabelTemplates
import com.riffle.feature.player.SleepTimerMode
import com.riffle.feature.player.formatCountdown
import com.riffle.feature.reader.ui.formatTemplate

/**
 * Every user-visible string the audiobook player chrome draws, supplied by the host.
 *
 * Same arrangement as `com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates`, and for the
 * same reason: `:app` fills this from its `res/values*` `stringResource`s (so Bulgarian and
 * Spanish keep working with no resource migration and no APK asset bridging), and `:shared` fills
 * it from [English] because the iOS app has no i18n mechanism wired up yet (#1072 §5). Only the
 * *strings* differ per host — every derivation lives in this module, so the two platforms cannot
 * drift on how the player reads.
 *
 * Fields carrying `%1$s` / `%1$d` are Android positional templates; expand them with
 * [formatTemplate], never with `String.format` (JVM-only).
 */
data class PlayerChromeLabels(
    /** Back-arrow content description. */
    val back: String,
    /** Shown instead of the transport when the session could not be opened. */
    val cannotPlay: String,
    val play: String,
    val pause: String,
    val previousChapter: String,
    val nextChapter: String,
    // The rewind/forward buttons' content descriptions are NOT here: they already exist as
    // com.riffle.feature.player.skipBackwardLabel / skipForwardLabel, which iOS's lock-screen
    // transport also uses. Forking them into a second string would let the in-app button and the
    // lock-screen button describe the same jump differently.
    val chapters: String,
    val bookmarks: String,
    /** `"1 bookmark"` */
    val bookmarkCountOne: String,
    /** `"%1$d bookmarks"` */
    val bookmarkCountOther: String,
    val addBookmark: String,
    val removeBookmark: String,
    val newBookmark: String,
    val renameBookmark: String,
    val bookmarkOptions: String,
    val rename: String,
    val delete: String,
    val save: String,
    val cancel: String,
    val noBookmarksYet: String,
    val offlineBookmarksWillSync: String,
    val nowPlaying: String,
    /** `"Chapter %1$d"` — fallback for a chapter the source gave no title. */
    val chapterNumber: String,
    val playbackSpeed: String,
    /** Title case, as it appears on the sleep-timer sheet header. */
    val sleepTimerSheetTitle: String,
    /** Sentence case, used as the sleep pill's content description. */
    val sleepTimer: String,
    /** The sleep pill's label while no timer is running. */
    val sleepPillIdle: String,
    /** The sleep pill's label while an end-of-chapter timer is armed. */
    val sleepPillEndOfChapter: String,
    val endOfChapter: String,
    /** `"Sleeping in %1$s"` */
    val sleepingIn: String,
    val sleepingAtEndOfChapter: String,
    val cancelTimer: String,
    /** `"%1$d min"` */
    val minutesShort: String,
    /** The medium word that opens the landscape facts line — `"Audiobook · 10h 53m · Sci-Fi"`. */
    val audiobook: String,
    /** The compact `%1$dh %2$dm` templates that facts line's duration segment is built from. */
    val compactDuration: CompactDurationLabelTemplates,
) {
    companion object {
        /**
         * The English catalogue, verbatim from `app/src/main/res/values/strings.xml` (and, where
         * Android still inlines a literal, from the literal). Used by the iOS host, which has no
         * string-resource mechanism yet. Keep the two in step: these are the same keys, not a
         * second wording.
         */
        val English = PlayerChromeLabels(
            back = "Back",
            cannotPlay = "This audiobook can't be played right now.",
            play = "Play",
            pause = "Pause",
            previousChapter = "Previous chapter",
            nextChapter = "Next chapter",
            chapters = "Chapters",
            bookmarks = "Bookmarks",
            bookmarkCountOne = "1 bookmark",
            bookmarkCountOther = "%1\$d bookmarks",
            addBookmark = "Add bookmark",
            removeBookmark = "Remove bookmark",
            newBookmark = "New bookmark",
            renameBookmark = "Rename bookmark",
            bookmarkOptions = "Bookmark options",
            rename = "Rename",
            delete = "Delete",
            save = "Save",
            cancel = "Cancel",
            noBookmarksYet = "No bookmarks yet.",
            offlineBookmarksWillSync = "Offline — bookmarks will sync",
            nowPlaying = "Now playing",
            chapterNumber = "Chapter %1\$d",
            playbackSpeed = "Playback Speed",
            sleepTimerSheetTitle = "Sleep Timer",
            sleepTimer = "Sleep timer",
            sleepPillIdle = "Sleep",
            sleepPillEndOfChapter = "End of ch.",
            endOfChapter = "End of chapter",
            sleepingIn = "Sleeping in %1\$s",
            sleepingAtEndOfChapter = "Sleeping at end of chapter",
            cancelTimer = "Cancel timer",
            minutesShort = "%1\$d min",
            audiobook = "Audiobook",
            compactDuration = CompactDurationLabelTemplates(
                minutes = "%1\$dmin",
                hours = "%1\$dh",
                hoursMinutes = "%1\$dh %2\$dmin",
            ),
        )
    }
}

/** `"1 bookmark"` / `"4 bookmarks"`. */
fun bookmarkCountLabel(count: Int, labels: PlayerChromeLabels): String =
    if (count == 1) labels.bookmarkCountOne else formatTemplate(labels.bookmarkCountOther, count)

/** The chapter's own title, or `"Chapter 7"` when the source supplied none. */
fun chapterDisplayTitle(title: String, index: Int, labels: PlayerChromeLabels): String =
    title.ifBlank { formatTemplate(labels.chapterNumber, index + 1) }

/** `"5 min"`, `"90 min"` — the sleep-timer preset buttons. */
fun sleepPresetLabel(minutes: Int, labels: PlayerChromeLabels): String =
    formatTemplate(labels.minutesShort, minutes)

/**
 * What the sleep pill next to the speed control reads: the idle word, a live `m:ss` countdown, or
 * the abbreviated end-of-chapter marker.
 */
fun sleepPillLabel(mode: SleepTimerMode, labels: PlayerChromeLabels): String = when (mode) {
    SleepTimerMode.None -> labels.sleepPillIdle
    is SleepTimerMode.CountDown -> mode.formatCountdown()
    SleepTimerMode.EndOfChapter -> labels.sleepPillEndOfChapter
}

/** The banner at the top of the sleep sheet while a timer is armed; empty when none is. */
fun sleepBannerLabel(mode: SleepTimerMode, labels: PlayerChromeLabels): String = when (mode) {
    SleepTimerMode.None -> ""
    is SleepTimerMode.CountDown -> formatTemplate(labels.sleepingIn, mode.formatCountdown())
    SleepTimerMode.EndOfChapter -> labels.sleepingAtEndOfChapter
}
