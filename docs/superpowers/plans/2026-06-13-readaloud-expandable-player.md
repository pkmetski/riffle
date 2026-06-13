# Readaloud Expandable Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user swipe the in-reader Readaloud mini player up into a full-screen player (the same surface the standalone Audiobook player uses) and pull it back down to the exact reading spot, as one playback session shown two ways.

**Architecture:** Extract the Audiobook player's visual body into a stateless `PlayerSurface`. Surface the readaloud session's missing data (duration, global position, chapter starts, arbitrary seek). Feed `PlayerSurface` from the reader view-model. Replace the fixed mini-player slot with an `AnchoredDraggable` sheet whose peek state is the mini player and whose expanded state is `PlayerSurface`; the reader stays mounted underneath so collapse returns to the exact spot.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `AnchoredDraggable`), Media3, Hilt, JUnit (pure-JVM unit tests in `core/domain`), Compose instrumented harness tests.

**Build/test commands:**
- JVM unit tests: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:domain:test`
- App JVM tests: `... ./gradlew :app:testDebugUnitTest`
- Full: `... ./gradlew test`
- Harness (phone): `make harness-test`

---

## File Structure

- **Create** `app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt` — stateless full-player body (cover, title/author, chapter label, scrubber, transport, speed). Owns `PlayerSurfaceState` + `PlayerSurfaceActions`.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt` — render `PlayerSurface` from `AudiobookPlayerUiState`; delete the moved private composables.
- **Modify** `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudTrack.kt` — expose `totalDurationSec`, `chapterStartsSec`, public `seekTarget(globalSec)`, public `globalPositionOf(audioSrc, positionSec)`.
- **Modify** `core/domain/src/test/.../ReadaloudTrackTest.kt` (create if absent) — unit-test the new accessors.
- **Modify** `app/.../feature/reader/readaloud/ReadaloudController.kt` — add `durationSec`, `positionGlobalSec`, `chapterStartsSec` to `PlaybackState`; add `seekTo(globalSec)`.
- **Modify** `app/.../feature/reader/readaloud/PlayerCoordinator.kt` — pass-through `seekTo`.
- **Modify** `app/.../feature/reader/EpubReaderViewModel.kt` — add `seekReadaloud(globalSec)`; add `readaloudPlayerState: StateFlow<PlayerSurfaceState>`.
- **Create** `app/.../feature/reader/readaloud/ReadaloudPlayerSheet.kt` — the `AnchoredDraggable` sheet (peek = mini player, expanded = `PlayerSurface`).
- **Modify** `app/.../feature/reader/EpubReaderScreen.kt` — swap the fixed mini-player block for `ReadaloudPlayerSheet`; collapse-on-back wiring.

---

## Task 1: Extract stateless `PlayerSurface` and refactor the Audiobook player

Pure refactor. The existing `AudiobookPlayerScreen` instrumented tests are the safety net; all current test tags must be preserved (`audiobook_*`, transport icons, etc.).

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt`

- [ ] **Step 1: Create `PlayerSurface.kt`** with the state holder, actions, and the body moved verbatim from `AudiobookPlayerScreen` (`PlayerBody`, `TransportRow`, `DualTime`, `ChapterSeekBar`, `formatHms`).

```kotlin
package com.riffle.app.feature.audio

// (imports: copy the foundation/material3/ui imports currently used by PlayerBody,
//  TransportRow, DualTime, ChapterSeekBar in AudiobookPlayerScreen.kt, plus coil AsyncImage/
//  ImageRequest and R)

/** Everything PlayerSurface renders. Both the standalone Audiobook player and the in-reader
 *  Readaloud expanded player map their own state into this. positionSec/durationSec/
 *  chapterStartsSec are on ONE global timeline. */
data class PlayerSurfaceState(
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val authToken: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapterStartsSec: List<Double> = emptyList(),
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
)

/** Callbacks the surface invokes. onSeek takes an absolute global position in seconds. */
data class PlayerSurfaceActions(
    val onSeek: (Double) -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onRewind: () -> Unit,
    val onForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSpeedChange: (Float) -> Unit,
)

/** The full-player body: square cover, title/author, chapter label, seekable chapter-map
 *  scrubber + dual time, centered transport, and the shared speed pill. */
@Composable
fun PlayerSurface(
    state: PlayerSurfaceState,
    actions: PlayerSurfaceActions,
    modifier: Modifier = Modifier,
) {
    // BODY: paste the current AudiobookPlayerScreen.PlayerBody Column verbatim, replacing:
    //   state.* reads -> stay the same (field names already match)
    //   viewModel::seekTo        -> actions.onSeek
    //   viewModel::togglePlayPause -> actions.onTogglePlayPause
    //   viewModel::rewind        -> actions.onRewind
    //   viewModel::forward       -> actions.onForward
    //   viewModel::previousChapter -> actions.onPreviousChapter
    //   viewModel::nextChapter   -> actions.onNextChapter
    //   viewModel::setSpeed      -> actions.onSpeedChange
    // Wrap in `Column(modifier = modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally)`.
    // Move TransportRow/DualTime/ChapterSeekBar/formatHms into this file as private, swapping their
    // `viewModel` params for the equivalent action lambdas (TransportRow takes PlayerSurfaceActions;
    // ChapterSeekBar already takes onSeek).
}
```

- [ ] **Step 2: Refactor `AudiobookPlayerScreen`** to map its UI state into `PlayerSurfaceState`/`PlayerSurfaceActions` and call `PlayerSurface`. Keep the outer `Surface` + gradient + collapse row + loading/failed branches exactly as-is. Delete `PlayerBody`, `TransportRow`, `DualTime`, `ChapterSeekBar`, `formatHms` from this file (now in `PlayerSurface.kt`).

```kotlin
// inside AudiobookPlayerScreen, replacing `else -> PlayerBody(state, viewModel)`:
else -> PlayerSurface(
    state = PlayerSurfaceState(
        title = state.title,
        author = state.author,
        coverUrl = state.coverUrl,
        authToken = state.authToken,
        isPlaying = state.isPlaying,
        speed = state.speed,
        positionSec = state.positionSec,
        durationSec = state.durationSec,
        currentChapterTitle = state.currentChapterTitle,
        chapterStartsSec = state.chapterStartsSec,
        canPreviousChapter = state.canPreviousChapter,
        canNextChapter = state.canNextChapter,
    ),
    actions = PlayerSurfaceActions(
        onSeek = viewModel::seekTo,
        onTogglePlayPause = viewModel::togglePlayPause,
        onRewind = viewModel::rewind,
        onForward = viewModel::forward,
        onPreviousChapter = viewModel::previousChapter,
        onNextChapter = viewModel::nextChapter,
        onSpeedChange = viewModel::setSpeed,
    ),
)
```

- [ ] **Step 3: Build** `... ./gradlew :app:compileDebugKotlin`. Expected: success, no unresolved refs.

- [ ] **Step 4: Run** the existing audiobook harness test to confirm the refactor is behaviour-preserving: `make harness-test` (or the specific `AudiobookPlayerScreenTest` class if present). Expected: PASS.

- [ ] **Step 5: Commit** `refactor(audiobook): extract stateless PlayerSurface for reuse`.

---

## Task 2: Surface readaloud duration, global position, chapter starts, and seek target on `ReadaloudTrack`

Pure-JVM, fully unit-testable. `positionAt` already does global→Position; we expose it and add the static accessors.

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudTrack.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/ReadaloudTrackTest.kt`

- [ ] **Step 1: Write failing tests.** Build a track from a few `MediaOverlayClip`s across two audio files (e.g. file "a" clips at [0,5),[5,10) for chapter `text/c1`; file "b" clip [0,8) for chapter `text/c2`) and assert:

```kotlin
// totalDurationSec == 18.0
// chapterStartsSec == listOf(0.0, 10.0)   // c1 starts at global 0, c2 at global 10 (file b start)
// globalPositionOf("b", 3.0) == 13.0
// seekTarget(13.0) == ReadaloudTrack.Position("b", 3.0)
// seekTarget(100.0) == ReadaloudTrack.Position("b", 8.0)   // clamped to end
```

- [ ] **Step 2: Run** `... ./gradlew :core:domain:test --tests '*ReadaloudTrackTest*'`. Expected: FAIL (unresolved `totalDurationSec` etc.).

- [ ] **Step 3: Implement.** In `ReadaloudTrack`:
  - Change `private val totalDuration` to expose it: add `val totalDurationSec: Double get() = totalDuration` (keep the private field).
  - Make `positionAt` public, renamed for the public API: add `fun seekTarget(globalSec: Double): Position = positionAt(globalSec)`.
  - Add a public `fun globalPositionOf(audioSrc: String?, positionSec: Double): Double = globalOf(audioSrc, positionSec) ?: 0.0`.
  - Add `val chapterStartsSec: List<Double>` computed from each chapter's first clip:

```kotlin
val chapterStartsSec: List<Double> = chapterHrefs.mapNotNull { href ->
    val first = clips.firstOrNull { chapterHrefOf(it) == href } ?: return@mapNotNull null
    globalOf(first.audioSrc, first.clipBeginSec)
}
```

- [ ] **Step 4: Run** the test. Expected: PASS.

- [ ] **Step 5: Commit** `feat(domain): expose readaloud duration, chapter starts, and seek target`.

---

## Task 3: Add duration/global-position/chapter-starts and `seekTo` to `ReadaloudController` + `PlayerCoordinator`

**Files:**
- Modify: `app/.../feature/reader/readaloud/ReadaloudController.kt`
- Modify: `app/.../feature/reader/readaloud/PlayerCoordinator.kt`

- [ ] **Step 1: Extend `PlaybackState`** with the fields the scrubber needs:

```kotlin
data class PlaybackState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val currentAudioSrc: String? = null,
    val positionSec: Double = 0.0,          // within-file (unchanged; used by skip logic)
    val positionGlobalSec: Double = 0.0,    // NEW: position on the concatenated timeline
    val durationSec: Double = 0.0,          // NEW: whole-readaloud duration
    val chapterStartsSec: List<Double> = emptyList(), // NEW: chapter tick positions (global)
    val currentChapterIndex: Int = -1,
    val chapterCount: Int = 0,
)
```

- [ ] **Step 2: Populate them in `pushState()`:**

```kotlin
_state.value = PlaybackState(
    connected = c != null,
    isPlaying = c?.isPlaying == true,
    speed = c?.playbackParameters?.speed ?: 1f,
    currentAudioSrc = audioSrc,
    positionSec = positionSec,
    positionGlobalSec = t?.globalPositionOf(audioSrc, positionSec) ?: 0.0,
    durationSec = t?.totalDurationSec ?: 0.0,
    chapterStartsSec = t?.chapterStartsSec ?: emptyList(),
    currentChapterIndex = t?.chapterIndexAt(audioSrc, positionSec) ?: -1,
    chapterCount = t?.chapterCount ?: 0,
)
```

- [ ] **Step 3: Add `seekTo`:**

```kotlin
/** Seeks to an absolute position on the concatenated readaloud timeline (scrubber). */
fun seekTo(globalSec: Double) {
    val target = track?.seekTarget(globalSec) ?: return
    seekToAudio(target.audioSrc, target.positionSec)
    pushState()
}
```

- [ ] **Step 4: Add the `PlayerCoordinator` pass-through** mirroring the existing `rewind()/forward()` style:

```kotlin
fun seekTo(globalSec: Double) = controller.seekTo(globalSec)
```

(Match the coordinator's existing delegation pattern; if it wraps via a field name other than `controller`, use that.)

- [ ] **Step 5: Build** `... ./gradlew :app:compileDebugKotlin`. Expected: success.

- [ ] **Step 6: Commit** `feat(readaloud): expose timeline duration/position + arbitrary seek`.

---

## Task 4: Build `readaloudPlayerState` in `EpubReaderViewModel`

Combine the polled `playbackState` with the loaded `LibraryItem` (title/author/coverUrl) and auth token into a `PlayerSurfaceState`, and expose `seekReadaloud`.

**Files:**
- Modify: `app/.../feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Locate** the loaded `LibraryItem` and auth-token plumbing in `EpubReaderViewModel` (the same token source the cover uses elsewhere; mirror `AudiobookPlayerViewModel`'s `tokenStorage.getToken(serverId)`). Confirm the field names before writing.

- [ ] **Step 2: Add `seekReadaloud`** next to the existing `rewind()/forward()` delegates:

```kotlin
fun seekReadaloud(globalSec: Double) = playerCoordinator.seekTo(globalSec)
```

- [ ] **Step 3: Expose `readaloudPlayerState`.** Combine `playbackState` with item metadata + token. Chapter title is a simple "Chapter N of M" (readaloud hrefs aren't human-readable):

```kotlin
val readaloudPlayerState: StateFlow<PlayerSurfaceState> =
    combine(playbackState, itemFlow, tokenFlow) { pb, item, token ->
        PlayerSurfaceState(
            title = item?.title.orEmpty(),
            author = item?.author.orEmpty(),
            coverUrl = item?.coverUrl,
            authToken = token.orEmpty(),
            isPlaying = pb.isPlaying,
            speed = pb.speed,
            positionSec = pb.positionGlobalSec,
            durationSec = pb.durationSec,
            currentChapterTitle = if (pb.chapterCount > 0 && pb.currentChapterIndex >= 0)
                "Chapter ${pb.currentChapterIndex + 1} of ${pb.chapterCount}" else null,
            chapterStartsSec = pb.chapterStartsSec,
            canPreviousChapter = pb.currentChapterIndex > 0,
            canNextChapter = pb.currentChapterIndex in 0 until (pb.chapterCount - 1),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerSurfaceState())
```

(Use the actual flow/field names found in Step 1 — `itemFlow`/`tokenFlow` are placeholders for whatever holds the `LibraryItem` and token. If the item is a one-shot loaded value, wrap it in a `MutableStateFlow` set at open time.)

- [ ] **Step 4: Build** `... ./gradlew :app:compileDebugKotlin`. Expected: success.

- [ ] **Step 5: Commit** `feat(reader): expose readaloud player state for the expanded surface`.

---

## Task 5: The `AnchoredDraggable` sheet (peek = mini player, expanded = PlayerSurface)

**Files:**
- Create: `app/.../feature/reader/readaloud/ReadaloudPlayerSheet.kt`

The sheet fills the reader area and is positioned by a vertical offset between two anchors. Peek shows only the mini-player-height strip at the bottom; Expanded fills the screen. Drag progress cross-fades mini↔full and raises a scrim.

- [ ] **Step 1: Implement the sheet.** Uses Material3 `AnchoredDraggableState`.

```kotlin
package com.riffle.app.feature.reader.readaloud

// imports: androidx.compose.foundation.gestures.AnchoredDraggableState, DraggableAnchors,
//   anchoredDraggable, animateTo; layout (Box, fillMaxSize, offset, height); animation; graphics;
//   com.riffle.app.feature.audio.{PlayerSurface, PlayerSurfaceState, PlayerSurfaceActions};
//   androidx.compose.ui.graphics.Color

enum class PlayerSheetAnchor { Peek, Expanded }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadaloudPlayerSheet(
    playerState: PlayerSurfaceState,
    actions: PlayerSurfaceActions,
    peekContent: @Composable () -> Unit,     // the existing ReadaloudMiniPlayer
    sheetState: AnchoredDraggableState<PlayerSheetAnchor>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fullPx = with(density) { maxHeight.toPx() }
        val peekPx = with(density) { PEEK_HEIGHT.toPx() }
        // anchors: Expanded => offset 0 (fills); Peek => offset (full - peek) (only strip shows)
        SideEffect {
            sheetState.updateAnchors(
                DraggableAnchors {
                    PlayerSheetAnchor.Expanded at 0f
                    PlayerSheetAnchor.Peek at (fullPx - peekPx)
                },
            )
        }
        val offsetY = sheetState.requireOffset()
        // progress 1f at Expanded (offset 0) -> 0f at Peek (offset full-peek)
        val progress = (1f - (offsetY / (fullPx - peekPx))).coerceIn(0f, 1f)

        // Scrim over the reader, fades in with expansion. Sits BEHIND the sheet.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = progress * 0.7f)),
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.toInt()) }
                .anchoredDraggable(sheetState, Orientation.Vertical),
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = (26 * (1 - progress)).dp, topEnd = (26 * (1 - progress)).dp),
        ) {
            Box(Modifier.fillMaxSize().testTag("readaloud_player_sheet")) {
                // Peek (mini player) pinned to the top strip of the sheet; fades out as it expands.
                Box(Modifier.align(Alignment.TopStart).alpha((1f - progress * 1.8f).coerceIn(0f, 1f))) {
                    peekContent()
                }
                // Expanded full surface; fades in. Grabber + "pull down" hint at top.
                if (progress > 0.02f) {
                    Column(Modifier.fillMaxSize().alpha(((progress - 0.15f) / 0.85f).coerceIn(0f, 1f))) {
                        ExpandedHeader(onCollapse = { /* set by caller via actions or a lambda */ })
                        PlayerSurface(state = playerState, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedHeader(onCollapse: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse, modifier = Modifier.testTag("readaloud_collapse")) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
        }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(48.dp))
    }
}

private val PEEK_HEIGHT = 64.dp   // matches the current mini-player bar height
```

(`onCollapse` should call `scope.launch { sheetState.animateTo(PlayerSheetAnchor.Peek) }`; thread a lambda from the caller in `EpubReaderScreen` that owns the scope, or hoist the scope into the sheet.)

- [ ] **Step 2: Build** `... ./gradlew :app:compileDebugKotlin`. Expected: success.

- [ ] **Step 3: Commit** `feat(reader): draggable readaloud player sheet (peek/expanded)`.

---

## Task 6: Wire the sheet into `EpubReaderScreen`, collapse-on-back, and a harness test

**Files:**
- Modify: `app/.../feature/reader/EpubReaderScreen.kt`
- Test: `app/src/androidTest/.../reader/ReadaloudPlayerSheetTest.kt` (or extend an existing reader harness test)

- [ ] **Step 1: Replace the fixed mini-player block** (EpubReaderScreen.kt ~371-402, inside the `if (readaloudOpen)` branch) so the mini player becomes the peek content of `ReadaloudPlayerSheet`. Hoist an `AnchoredDraggableState<PlayerSheetAnchor>` (remembered, default `Peek`) and a `rememberCoroutineScope()`. Keep the existing `ReadaloudMiniPlayer(...)` call as `peekContent`. Collect `readaloudPlayerState`:

```kotlin
val readaloudPlayer by viewModel.readaloudPlayerState.collectAsStateWithLifecycle()
val sheetState = remember {
    AnchoredDraggableState(initialValue = PlayerSheetAnchor.Peek, /* positionalThreshold, velocityThreshold, animationSpec per the project's Material3 version */)
}
val scope = rememberCoroutineScope()
// ...
if (readaloudOpen) {
    ReadaloudPlayerSheet(
        playerState = readaloudPlayer,
        actions = PlayerSurfaceActions(
            onSeek = viewModel::seekReadaloud,
            onTogglePlayPause = viewModel::togglePlayPause,
            onRewind = viewModel::rewind,
            onForward = viewModel::forward,
            onPreviousChapter = viewModel::previousChapter,
            onNextChapter = viewModel::nextChapter,
            onSpeedChange = viewModel::setSpeed,
        ),
        peekContent = { ReadaloudMiniPlayer(/* the exact existing args */) },
        sheetState = sheetState,
    )
}
```

Note: the chapter rail overlay (`showRailOverlay`) currently shares the bottom Column with the mini player. When the sheet is at Peek it should still sit above the strip; render the rail behind/below the sheet as today (the sheet only occupies its offset region visually via the scrim+offset). Keep the rail in its existing bottom-anchored Column, drawn before the sheet so the peek strip overlaps as before.

- [ ] **Step 2: Collapse on back.** Add a `BackHandler(enabled = readaloudOpen && sheetState.currentValue == PlayerSheetAnchor.Expanded) { scope.launch { sheetState.animateTo(PlayerSheetAnchor.Peek) } }` so predictive/system back collapses the expanded sheet before the reader's own back behaviour.

- [ ] **Step 3: Build** `... ./gradlew :app:compileDebugKotlin`. Expected: success.

- [ ] **Step 4: Write the harness test** `ReadaloudPlayerSheetTest`: open a reader with readaloud active (follow the setup in the existing readaloud harness tests — see `reference_readaloud_e2e_test_setup`), assert `readaloud_mini_player` is displayed, perform a swipe-up on `readaloud_player_sheet` (or call the sheet's expand), assert the full surface (e.g. `audiobook`-tagged transport via `PlayerSurface`, or add a `readaloud_collapse` tag check) is displayed, swipe down / click `readaloud_collapse`, assert `readaloud_mini_player` is shown again. Guard the test with `@TabletLayout` only if appropriate; otherwise it runs under `make harness-test`.

- [ ] **Step 5: Run** `make harness-test`. Expected: PASS. (Per project notes, headless AVDs can stall reader nav and audio HAL; if the swipe-gesture assertion flakes, assert via direct `sheetState` expand in a composition test instead, and verify the live drag on a real device.)

- [ ] **Step 6: Commit** `feat(reader): swipe the readaloud mini player up into a full-screen player`.

---

## Self-Review notes

- **Spec coverage:** §Architecture 1 → Task 1; §2 (missing data) → Tasks 2–4; §3 (sheet) → Tasks 5–6; testing §→ Tasks 1/2/6. Covered.
- **Type consistency:** `PlayerSurfaceState`/`PlayerSurfaceActions` defined in Task 1 are used unchanged in Tasks 4–6. `PlaybackState` new fields (`positionGlobalSec`, `durationSec`, `chapterStartsSec`) defined in Task 3, consumed in Task 4. `seekTarget`/`totalDurationSec`/`chapterStartsSec`/`globalPositionOf` defined in Task 2, consumed in Task 3.
- **Known unknowns to resolve at implementation time (flagged, not placeholders):** exact `LibraryItem`/token field names in `EpubReaderViewModel` (Task 4 Step 1); the `PlayerCoordinator` delegation field name (Task 3 Step 4); the `AnchoredDraggableState` constructor signature for the repo's Material3 version (Task 5/6). Each task says to confirm the real name before writing.
- **Risk order:** lowest-risk, fully-tested domain/refactor work first (Tasks 1–4); the WebView-layered sheet last (Tasks 5–6), per the design's risk note.
