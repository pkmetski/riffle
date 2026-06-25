package com.riffle.app.feature.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riffle.app.feature.audio.PlayerSurface
import com.riffle.app.feature.audio.PlayerSurfaceActions
import com.riffle.app.feature.audio.PlayerSurfaceState
import com.riffle.app.feature.audio.formatHms
import com.riffle.app.feature.reader.CornerBookmarkIndicator
import com.riffle.core.domain.AudiobookBookmark

/**
 * Full-screen [Audiobook Player] (ADR 0029): square cover, title/author, current-chapter label, a
 * seekable chapter-map scrubber (vertical playhead + chapter ticks + current-chapter band) with dual
 * chapter/book time, and a centered transport cluster — rewind 15s · prev chapter · play/pause · next
 * chapter · forward 30s — with the speed control in a separate utility row. Chapter controls disable
 * when the book has no chapter markers.
 */
// Minimum downward drag (px) on the player to trigger the switch to the readaloud reader — a
// deliberate swipe, not an accidental nudge.
private const val SWITCH_TO_READALOUD_THRESHOLD_PX = 160f
private const val BOOKMARK_WINDOW_SEC = 3.0

/** Which list the shared [PlayerListSheet] is showing (no tabs — one kind at a time). */
private enum class SheetKind { Chapters, Bookmarks }

/**
 * A snapshot taken when the New-bookmark dialog opens. Pinning the position (and the title/label
 * derived from it) keeps the dialog stable while playback continues — otherwise the live playhead
 * would rewrite the default title every second and wipe the user's edits.
 */
private data class BookmarkDraft(
    val positionSec: Double,
    val defaultTitle: String,
    val chapterTitle: String,
)

/**
 * Small drag handle hinting that dragging the player down switches to the read-along reader. Mirrors
 * the handle on the in-reader mini player (see ReadaloudPeek) so the swipe-down ↔ swipe-up gesture
 * pair reads as one continuous affordance across both surfaces.
 */
@Composable
private fun ReadAlongDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                .size(width = 32.dp, height = 4.dp),
        )
    }
}

/** The two quiet affordances under the scrubber: Chapters and the bookmark count. */
@Composable
private fun PlayerListPills(
    bookmarkCount: Int,
    onOpenChapters: () -> Unit,
    onOpenBookmarks: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        AssistChip(
            onClick = onOpenChapters,
            label = { Text("Chapters") },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
        val bookmarkLabel = if (bookmarkCount == 1) "1 bookmark" else "$bookmarkCount bookmarks"
        AssistChip(
            onClick = onOpenBookmarks,
            label = { Text(bookmarkLabel) },
            leadingIcon = {
                Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
    }
}

@Composable
fun AudiobookPlayerScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onSwitchToReadaloud: (ebookItemId: String, atSec: Double) -> Unit = { _, _ -> },
    viewModel: AudiobookPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Any short (Compact-height) window — i.e. a phone in landscape — is too short for the vertical
    // layout (the square cover pushes the controls off-screen), so split into cover+details / controls.
    val twoColumn = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    // Read fresh inside the gesture (it's keyed on Unit, so it must not capture a stale position).
    val latestState = rememberUpdatedState(state)

    // Local UI state for the sheets and dialogs (variant B).
    var openSheet by remember { mutableStateOf<SheetKind?>(null) }
    // Non-null while the New-bookmark dialog is open; carries the position/title pinned at open time so
    // they don't drift with the still-running playhead while the user edits (see [BookmarkDraft]).
    var createDraft by remember { mutableStateOf<BookmarkDraft?>(null) }
    var renaming by remember { mutableStateOf<AudiobookBookmark?>(null) }

    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
        ),
    )
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient)
                    // Swipe DOWN anywhere → switch to the readaloud reader (only when this title has a
                    // linked readaloud ebook; otherwise the drag does nothing). Down = toward reading.
                    // The scrubber's own horizontal drag is unaffected; taps still reach the transport.
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            // Pre-warm readaloud the moment a downward drag starts (ADR 0032):
                            // resolves the SMIL seek target while the user is still dragging so
                            // playFromSecond() fires instantly when the threshold is reached.
                            onDragStart = { total = 0f; viewModel.hintReadaloudHandoff() },
                            onVerticalDrag = { change, dragAmount -> total += dragAmount; change.consume() },
                            onDragEnd = {
                                val s = latestState.value
                                val ebookId = s.readaloudEbookItemId
                                if (total > SWITCH_TO_READALOUD_THRESHOLD_PX && ebookId != null) {
                                    // Release the shared player to readaloud (without stopping it) before
                                    // navigating, so readaloud keeps playing through the handoff.
                                    viewModel.prepareReadaloudHandoff()
                                    onSwitchToReadaloud(ebookId, s.positionSec)
                                } else {
                                    viewModel.cancelHandoffHint()
                                }
                            },
                        )
                    }
                    .padding(horizontal = 24.dp),
            ) {
                // Leave room for the back button overlaid above, plus the read-along handle when present.
                Spacer(Modifier.size(48.dp))
                if (state.readaloudEbookItemId != null) {
                    ReadAlongDragHandle()
                }

                when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.failed -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("This audiobook can't be played right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // navigationBarsPadding so the pills row at the bottom clears the system nav bar —
                // otherwise PlayerListPills renders behind it and the chapters/bookmarks entry point
                // is invisible.
                else -> Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    PlayerSurface(
                        modifier = Modifier.weight(1f),
                        state = PlayerSurfaceState(
                            title = state.title,
                            author = state.author,
                            publishedYear = state.publishedYear,
                            coverUrl = state.coverUrl,
                            authToken = state.authToken,
                            isPlaying = state.isPlaying,
                            speed = state.speed,
                            positionSec = state.positionSec,
                            durationSec = state.durationSec,
                            currentChapterTitle = state.currentChapterTitle,
                            chapterStartsSec = state.chapterStartsSec,
                            bookmarkPositionsSec = state.bookmarks.map { it.positionSec },
                            canPreviousChapter = state.canPreviousChapter,
                            canNextChapter = state.canNextChapter,
                            facts = state.facts,
                            description = state.description,
                            sleepTimer = state.sleepTimer,
                            skipIntervalSeconds = state.skipIntervalSeconds,
                            rewindIntervalSeconds = state.rewindIntervalSeconds,
                        ),
                        twoColumn = twoColumn,
                        actions = PlayerSurfaceActions(
                            onSeek = viewModel::seekTo,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onRewind = viewModel::rewind,
                            onForward = viewModel::forward,
                            onPreviousChapter = viewModel::previousChapter,
                            onNextChapter = viewModel::nextChapter,
                            onSpeedChange = viewModel::setSpeed,
                            onSleepTimerSet = viewModel::setSleepTimer,
                            onSleepTimerCancel = viewModel::cancelSleepTimer,
                        ),
                    )
                    PlayerListPills(
                        bookmarkCount = state.bookmarks.size,
                        onOpenChapters = { openSheet = SheetKind.Chapters },
                        onOpenBookmarks = { openSheet = SheetKind.Bookmarks },
                    )
                    Spacer(Modifier.size(12.dp))
                }
                }
            }
            // Exit affordance, overlaid OUTSIDE the swipe Column so its taps are never captured by the
            // swipe-down gesture. A plain back arrow (not a down-chevron) — distinct from swipe=read.
            IconButton(
                // safeDrawingPadding (not just statusBarsPadding) so the arrow clears the status bar,
                // nav bar AND any display cutout — in landscape the cutout/short status bar otherwise
                // sits right under it.
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(4.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            // Corner bookmark ribbon — same shape/placement as the ebook reader. Filled when the
            // playhead is within ±3 s of an existing bookmark, or while the add-bookmark dialog is open.
            val nearBookmark = state.bookmarks.any { bm ->
                kotlin.math.abs(bm.positionSec - state.positionSec) <= BOOKMARK_WINDOW_SEC
            }
            CornerBookmarkIndicator(
                isBookmarked = createDraft != null || nearBookmark,
                isVisible = true,
                onToggle = {
                    val positionSec = viewModel.currentPositionSec()
                    val nearby = state.bookmarks
                        .filter { kotlin.math.abs(it.positionSec - positionSec) <= BOOKMARK_WINDOW_SEC }
                        .minByOrNull { kotlin.math.abs(it.positionSec - positionSec) }
                    if (nearby != null) {
                        viewModel.deleteBookmark(nearby.id)
                    } else {
                        createDraft = BookmarkDraft(
                            positionSec = positionSec,
                            defaultTitle = viewModel.defaultBookmarkTitle(positionSec),
                            chapterTitle = state.currentChapterTitle?.trim().orEmpty(),
                        )
                    }
                },
                contentDescription = if (nearBookmark && createDraft == null) "Remove bookmark" else "Add bookmark",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(end = 12.dp),
            )
        }
    }

    // Create dialog: pre-fill the default title and offer quick suggestions, all from the pinned draft.
    createDraft?.let { draft ->
        val absolute = formatHms(draft.positionSec)
        val positionLabel = if (draft.chapterTitle.isNotEmpty()) "$absolute · ${draft.chapterTitle}" else absolute
        val suggestions = listOf(draft.defaultTitle, draft.chapterTitle, absolute).filter { it.isNotBlank() }.distinct()
        BookmarkCreateDialog(
            initialTitle = draft.defaultTitle,
            positionLabel = positionLabel,
            suggestions = suggestions,
            onConfirm = { title ->
                viewModel.addBookmark(title, draft.positionSec)
                createDraft = null
            },
            onDismiss = { createDraft = null },
        )
    }

    // Rename reuses the create dialog with a different title and no suggestions.
    renaming?.let { bookmark ->
        BookmarkCreateDialog(
            initialTitle = bookmark.title,
            positionLabel = formatHms(bookmark.positionSec),
            suggestions = emptyList(),
            title = "Rename bookmark",
            onConfirm = { title ->
                viewModel.renameBookmark(bookmark.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    when (openSheet) {
        SheetKind.Chapters -> PlayerListSheet(
            content = PlayerListContent.Chapters(
                items = state.chapters,
                currentIndex = state.currentChapterIndex,
                // A chapter start is genuine user navigation — seekTo moves the resume baseline.
                onSeek = { chapter -> viewModel.seekTo(chapter.startSec) },
            ),
            onDismiss = { openSheet = null },
        )
        SheetKind.Bookmarks -> PlayerListSheet(
            content = PlayerListContent.Bookmarks(
                items = state.bookmarks,
                onSeek = { bm -> viewModel.seekToBookmark(bm.positionSec) },
                onRename = { renaming = it },
                onDelete = { viewModel.deleteBookmark(it.id) },
                offlineNote = state.bookmarksOffline,
            ),
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }
}
