package com.riffle.feature.player.ui

import androidx.compose.runtime.Composable
import com.riffle.feature.player.CompactDurationLabelTemplates
import com.riffle.feature.player.ui.generated.resources.Res
import com.riffle.feature.player.ui.generated.resources.ui_add_bookmark
import com.riffle.feature.player.ui.generated.resources.ui_audiobook
import com.riffle.feature.player.ui.generated.resources.ui_back
import com.riffle.feature.player.ui.generated.resources.ui_bookmark_count_one
import com.riffle.feature.player.ui.generated.resources.ui_bookmark_count_other
import com.riffle.feature.player.ui.generated.resources.ui_bookmark_options
import com.riffle.feature.player.ui.generated.resources.ui_bookmarks
import com.riffle.feature.player.ui.generated.resources.ui_cancel
import com.riffle.feature.player.ui.generated.resources.ui_cancel_timer
import com.riffle.feature.player.ui.generated.resources.ui_cannot_play
import com.riffle.feature.player.ui.generated.resources.ui_chapter_number
import com.riffle.feature.player.ui.generated.resources.ui_chapters
import com.riffle.feature.player.ui.generated.resources.ui_compact_duration_hours
import com.riffle.feature.player.ui.generated.resources.ui_compact_duration_hours_minutes
import com.riffle.feature.player.ui.generated.resources.ui_compact_duration_minutes
import com.riffle.feature.player.ui.generated.resources.ui_delete
import com.riffle.feature.player.ui.generated.resources.ui_end_of_chapter
import com.riffle.feature.player.ui.generated.resources.ui_minutes_short
import com.riffle.feature.player.ui.generated.resources.ui_new_bookmark
import com.riffle.feature.player.ui.generated.resources.ui_next_chapter
import com.riffle.feature.player.ui.generated.resources.ui_no_bookmarks_yet
import com.riffle.feature.player.ui.generated.resources.ui_now_playing
import com.riffle.feature.player.ui.generated.resources.ui_offline_bookmarks_will_sync
import com.riffle.feature.player.ui.generated.resources.ui_pause
import com.riffle.feature.player.ui.generated.resources.ui_play
import com.riffle.feature.player.ui.generated.resources.ui_playback_speed
import com.riffle.feature.player.ui.generated.resources.ui_previous_chapter
import com.riffle.feature.player.ui.generated.resources.ui_remove_bookmark
import com.riffle.feature.player.ui.generated.resources.ui_rename
import com.riffle.feature.player.ui.generated.resources.ui_rename_bookmark
import com.riffle.feature.player.ui.generated.resources.ui_save
import com.riffle.feature.player.ui.generated.resources.ui_sleep_pill_end_of_chapter
import com.riffle.feature.player.ui.generated.resources.ui_sleep_pill_idle
import com.riffle.feature.player.ui.generated.resources.ui_sleep_timer
import com.riffle.feature.player.ui.generated.resources.ui_sleep_timer_title
import com.riffle.feature.player.ui.generated.resources.ui_sleeping_at_end_of_chapter
import com.riffle.feature.player.ui.generated.resources.ui_sleeping_in
import org.jetbrains.compose.resources.stringResource

/** Builds [PlayerChromeLabels] from composeResources, picking up the active locale automatically. */
@Composable
fun playerChromeLabels(): PlayerChromeLabels = PlayerChromeLabels(
    back = stringResource(Res.string.ui_back),
    cannotPlay = stringResource(Res.string.ui_cannot_play),
    play = stringResource(Res.string.ui_play),
    pause = stringResource(Res.string.ui_pause),
    previousChapter = stringResource(Res.string.ui_previous_chapter),
    nextChapter = stringResource(Res.string.ui_next_chapter),
    chapters = stringResource(Res.string.ui_chapters),
    bookmarks = stringResource(Res.string.ui_bookmarks),
    bookmarkCountOne = stringResource(Res.string.ui_bookmark_count_one),
    bookmarkCountOther = stringResource(Res.string.ui_bookmark_count_other),
    addBookmark = stringResource(Res.string.ui_add_bookmark),
    removeBookmark = stringResource(Res.string.ui_remove_bookmark),
    newBookmark = stringResource(Res.string.ui_new_bookmark),
    renameBookmark = stringResource(Res.string.ui_rename_bookmark),
    bookmarkOptions = stringResource(Res.string.ui_bookmark_options),
    rename = stringResource(Res.string.ui_rename),
    delete = stringResource(Res.string.ui_delete),
    save = stringResource(Res.string.ui_save),
    cancel = stringResource(Res.string.ui_cancel),
    noBookmarksYet = stringResource(Res.string.ui_no_bookmarks_yet),
    offlineBookmarksWillSync = stringResource(Res.string.ui_offline_bookmarks_will_sync),
    nowPlaying = stringResource(Res.string.ui_now_playing),
    chapterNumber = stringResource(Res.string.ui_chapter_number),
    playbackSpeed = stringResource(Res.string.ui_playback_speed),
    sleepTimerSheetTitle = stringResource(Res.string.ui_sleep_timer_title),
    sleepTimer = stringResource(Res.string.ui_sleep_timer),
    sleepPillIdle = stringResource(Res.string.ui_sleep_pill_idle),
    sleepPillEndOfChapter = stringResource(Res.string.ui_sleep_pill_end_of_chapter),
    endOfChapter = stringResource(Res.string.ui_end_of_chapter),
    sleepingIn = stringResource(Res.string.ui_sleeping_in),
    sleepingAtEndOfChapter = stringResource(Res.string.ui_sleeping_at_end_of_chapter),
    cancelTimer = stringResource(Res.string.ui_cancel_timer),
    minutesShort = stringResource(Res.string.ui_minutes_short),
    audiobook = stringResource(Res.string.ui_audiobook),
    compactDuration = CompactDurationLabelTemplates(
        minutes = stringResource(Res.string.ui_compact_duration_minutes),
        hours = stringResource(Res.string.ui_compact_duration_hours),
        hoursMinutes = stringResource(Res.string.ui_compact_duration_hours_minutes),
    ),
)
