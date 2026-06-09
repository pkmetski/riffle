# Readaloud Skip Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add rewind-15s, forward-30s, and previous/next-chapter controls to the Readaloud mini-player.

**Architecture:** All seek *math* lives in the pure-JVM domain class `ReadaloudTrack`, which already owns the Media-Overlay timeline. It gains a global cross-file timeline (each audio file's duration approximated by its last clip's end) so a relative skip and a chapter jump resolve to an exact `(audioSrc, positionSec)` with no dependency on Media3 runtime durations — making every behaviour unit-testable. `ReadaloudController` becomes a thin caller of that math plus the existing `seekToAudio`; `PlayerCoordinator` and `EpubReaderViewModel` add pass-throughs; `ReadaloudMiniPlayer` gains the buttons.

**Tech Stack:** Kotlin, Media3 (`MediaController`), Jetpack Compose (Material 3, `material-icons-extended`), JUnit4, Compose UI test (harness AVD).

---

## Design reference

Spec: `docs/superpowers/specs/2026-06-09-readaloud-skip-controls-design.md`
Prototype: `.context/readaloud-skip-prototype/index.html`

Final mini-bar layout (left → right): `[1×] …spacer… [⟲15] [⏮] [▶/⏸] [⏭] [30⟳] …spacer… [✕]`

Constants: rewind = 15 s, forward = 30 s, near-start threshold = 3 s.

## File structure

- **Modify** `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudTrack.kt` — chapter list + global-timeline skip/chapter math (new public API: `chapterCount`, `chapterIndexAt`, `firstClipOfChapter`, `resolveRelativeSkip`, `resolveChapterSkip`, nested `Position`).
- **Modify** `core/domain/src/test/kotlin/com/riffle/core/domain/ReadaloudTrackTest.kt` — unit tests for the new math.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt` — `PlaybackState` gains `currentChapterIndex`/`chapterCount`; new `rewind()`, `forward()`, `previousChapter()`, `nextChapter()`; `pushState` fills the new fields.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/PlayerCoordinator.kt` — thin pass-throughs.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` — intent methods.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudPlayerUi.kt` — `SkipIcon` composable + new buttons + callbacks on `ReadaloudMiniPlayer`.
- **Modify** `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` — wire callbacks.
- **Create** `app/src/androidTest/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudMiniPlayerTest.kt` — Compose harness test for the buttons.

## Pre-flight

Gradle needs the Android Studio JBR on every command in this plan:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

---

### Task 1: ReadaloudTrack — global timeline + chapter math

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudTrack.kt`
- Test: `core/domain/src/test/kotlin/com/riffle/core/domain/ReadaloudTrackTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `ReadaloudTrackTest.kt` (inside the class, after the last test). These fixtures use whole-second clips so the global offsets are easy to reason about. `c1.mp3` has clips ending at 9.0 (so its duration ≈ 9.0); `c2.mp3` starts the next chapter and ends at 4.0; global timeline is therefore `[0,9)` for c1 and `[9,13)` for c2.

```kotlin
    // ── skip + chapter math (rewind/forward, prev/next chapter) ──

    // Two chapters across two files. Global timeline: c1 -> [0,9), c2 -> [9,13).
    private val skipClips = listOf(
        MediaOverlayClip("text/c1.html#s1", "c1.mp3", 0.0, 2.0),
        MediaOverlayClip("text/c1.html#s2", "c1.mp3", 2.0, 5.0),
        MediaOverlayClip("text/c1.html#s3", "c1.mp3", 5.0, 9.0),
        MediaOverlayClip("text/c2.html#s1", "c2.mp3", 0.0, 4.0),
    )
    private val skipTrack = ReadaloudTrack(skipClips)

    @Test
    fun `chapterCount counts distinct chapter hrefs in order`() {
        assertEquals(2, skipTrack.chapterCount)
    }

    @Test
    fun `chapterIndexAt maps an in-file position to its chapter`() {
        assertEquals(0, skipTrack.chapterIndexAt("c1.mp3", 3.0))
        assertEquals(1, skipTrack.chapterIndexAt("c2.mp3", 1.0))
    }

    @Test
    fun `chapterIndexAt returns -1 when nothing is playing`() {
        assertEquals(-1, skipTrack.chapterIndexAt(null, 0.0))
    }

    @Test
    fun `firstClipOfChapter returns the chapter's opening clip`() {
        assertEquals(skipClips[0], skipTrack.firstClipOfChapter(0))
        assertEquals(skipClips[3], skipTrack.firstClipOfChapter(1))
        assertNull(skipTrack.firstClipOfChapter(2))
    }

    @Test
    fun `resolveRelativeSkip seeks within the current file`() {
        // at c1 3.0s, +4s -> c1 7.0s
        assertEquals(ReadaloudTrack.Position("c1.mp3", 7.0), skipTrack.resolveRelativeSkip("c1.mp3", 3.0, 4.0))
        // at c1 7.0s, -4s -> c1 3.0s
        assertEquals(ReadaloudTrack.Position("c1.mp3", 3.0), skipTrack.resolveRelativeSkip("c1.mp3", 7.0, -4.0))
    }

    @Test
    fun `resolveRelativeSkip rolls forward across a file boundary`() {
        // at c1 8.0s, +3s -> global 11.0 -> c2 2.0s
        assertEquals(ReadaloudTrack.Position("c2.mp3", 2.0), skipTrack.resolveRelativeSkip("c1.mp3", 8.0, 3.0))
    }

    @Test
    fun `resolveRelativeSkip rolls backward across a file boundary`() {
        // at c2 1.0s (global 10.0), -3s -> global 7.0 -> c1 7.0s
        assertEquals(ReadaloudTrack.Position("c1.mp3", 7.0), skipTrack.resolveRelativeSkip("c2.mp3", 1.0, -3.0))
    }

    @Test
    fun `resolveRelativeSkip clamps at the start and end of the readaloud`() {
        // rewind before zero
        assertEquals(ReadaloudTrack.Position("c1.mp3", 0.0), skipTrack.resolveRelativeSkip("c1.mp3", 1.0, -30.0))
        // forward past the end (total 13.0) clamps to the last file's end
        assertEquals(ReadaloudTrack.Position("c2.mp3", 4.0), skipTrack.resolveRelativeSkip("c2.mp3", 1.0, 30.0))
    }

    @Test
    fun `resolveRelativeSkip returns null when nothing is playing`() {
        assertNull(skipTrack.resolveRelativeSkip(null, 0.0, 30.0))
    }

    @Test
    fun `next chapter jumps to the following chapter's first clip`() {
        assertEquals(skipClips[3], skipTrack.resolveChapterSkip("c1.mp3", 3.0, forward = true, nearStartSec = 3.0))
    }

    @Test
    fun `next chapter on the last chapter returns null`() {
        assertNull(skipTrack.resolveChapterSkip("c2.mp3", 1.0, forward = true, nearStartSec = 3.0))
    }

    @Test
    fun `previous chapter restarts the current chapter when past the near-start window`() {
        // c1 4.0s is > 3s into chapter 0 -> restart chapter 0
        assertEquals(skipClips[0], skipTrack.resolveChapterSkip("c1.mp3", 4.0, forward = false, nearStartSec = 3.0))
    }

    @Test
    fun `previous chapter jumps to the prior chapter when within the near-start window`() {
        // c2 1.0s is within 3s of chapter 1's start -> go to chapter 0
        assertEquals(skipClips[0], skipTrack.resolveChapterSkip("c2.mp3", 1.0, forward = false, nearStartSec = 3.0))
    }

    @Test
    fun `previous chapter at the first chapter near its start restarts the first chapter`() {
        // chapter 0 near start: no prior chapter, so restart chapter 0 (effective no-op seek to 0)
        assertEquals(skipClips[0], skipTrack.resolveChapterSkip("c1.mp3", 0.5, forward = false, nearStartSec = 3.0))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.ReadaloudTrackTest"`
Expected: FAIL — unresolved references `chapterCount`, `chapterIndexAt`, `firstClipOfChapter`, `resolveRelativeSkip`, `resolveChapterSkip`, `ReadaloudTrack.Position`.

- [ ] **Step 3: Implement the math**

In `ReadaloudTrack.kt`, add inside the class body (after `clipForFragment`, before `resolveStartClip` is fine). First add a shared chapter-href helper and the global-timeline scaffolding, then the public API:

```kotlin
    /** A resolved seek target: which queued audio file, and the offset within it. */
    data class Position(val audioSrc: String, val positionSec: Double)

    private fun chapterHrefOf(clip: MediaOverlayClip): String =
        clip.textFragmentRef.substringBefore('#').trimStart('/')

    /** Distinct chapter hrefs in reading order (clips are already in reading order). */
    private val chapterHrefs: List<String> = clips.map(::chapterHrefOf).distinct()

    /** Distinct audio files in playlist order — the same order [ReadaloudController] queues them. */
    private val fileOrder: List<String> = clips.map { it.audioSrc }.distinct()

    /** Each file's duration, approximated by the end of its last clip. */
    private val fileDuration: Map<String, Double> =
        clips.groupBy { it.audioSrc }.mapValues { (_, cs) -> cs.maxOf { it.clipEndSec } }

    /** Global start offset of each file on the concatenated timeline. */
    private val fileStart: Map<String, Double> = buildMap {
        var acc = 0.0
        for (src in fileOrder) {
            put(src, acc)
            acc += fileDuration[src] ?: 0.0
        }
    }

    private val totalDuration: Double = fileOrder.sumOf { fileDuration[it] ?: 0.0 }

    /** Number of chapters in the readaloud. */
    val chapterCount: Int get() = chapterHrefs.size

    /** Maps a live playback position to a global timeline offset, or null if [audioSrc] is unknown. */
    private fun globalOf(audioSrc: String?, positionSec: Double): Double? {
        val start = audioSrc?.let { fileStart[it] } ?: return null
        return start + positionSec
    }

    /** Inverse of [globalOf]: maps a clamped global offset back to a [Position]. */
    private fun positionAt(global: Double): Position {
        val g = global.coerceIn(0.0, totalDuration)
        // Last file whose start is <= g; offset is the remainder, clamped to that file's duration.
        val src = fileOrder.lastOrNull { (fileStart[it] ?: 0.0) <= g } ?: fileOrder.first()
        val within = (g - (fileStart[src] ?: 0.0)).coerceIn(0.0, fileDuration[src] ?: 0.0)
        return Position(src, within)
    }

    /**
     * The chapter index containing the live position, or -1 when nothing is playing. Uses the active
     * clip when the position sits inside one; otherwise the chapter of the latest clip at or before
     * the global position (covers inter-clip gaps).
     */
    fun chapterIndexAt(audioSrc: String?, positionSec: Double): Int {
        if (audioSrc == null) return -1
        activeClipAt(audioSrc, positionSec)?.let { return chapterHrefs.indexOf(chapterHrefOf(it)) }
        val global = globalOf(audioSrc, positionSec) ?: return -1
        val clip = clips.lastOrNull { (globalOf(it.audioSrc, it.clipBeginSec) ?: Double.MAX_VALUE) <= global }
            ?: return -1
        return chapterHrefs.indexOf(chapterHrefOf(clip))
    }

    /** The first clip of chapter [index], or null when [index] is out of range. */
    fun firstClipOfChapter(index: Int): MediaOverlayClip? {
        val href = chapterHrefs.getOrNull(index) ?: return null
        return clips.firstOrNull { chapterHrefOf(it) == href }
    }

    /** Resolves a relative skip of [deltaSec] from the live position, clamped to the whole readaloud. */
    fun resolveRelativeSkip(audioSrc: String?, positionSec: Double, deltaSec: Double): Position? {
        val global = globalOf(audioSrc, positionSec) ?: return null
        return positionAt(global + deltaSec)
    }

    /**
     * Resolves a chapter jump. [forward] true -> the next chapter's first clip (null at the last
     * chapter). [forward] false -> restart the current chapter, unless the live position is within
     * [nearStartSec] of the current chapter's start, in which case go to the previous chapter (or
     * restart the first chapter when there is none).
     */
    fun resolveChapterSkip(audioSrc: String?, positionSec: Double, forward: Boolean, nearStartSec: Double): MediaOverlayClip? {
        val index = chapterIndexAt(audioSrc, positionSec)
        if (index < 0) return null
        if (forward) return firstClipOfChapter(index + 1)
        val currentFirst = firstClipOfChapter(index) ?: return null
        val global = globalOf(audioSrc, positionSec) ?: return currentFirst
        val chapterStart = globalOf(currentFirst.audioSrc, currentFirst.clipBeginSec) ?: 0.0
        val nearStart = (global - chapterStart) <= nearStartSec
        return if (nearStart && index > 0) firstClipOfChapter(index - 1) else currentFirst
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:domain:test --tests "com.riffle.core.domain.ReadaloudTrackTest"`
Expected: PASS (all existing + new tests).

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudTrack.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/ReadaloudTrackTest.kt
git commit -m "feat(readaloud): global-timeline skip + chapter math on ReadaloudTrack"
```

---

### Task 2: ReadaloudController — skip + chapter actions, chapter state

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt`

This is the Media3 boundary (no unit test — it needs a live `MediaController`; behaviour is covered by Task 1's pure tests and the harness/manual check). Keep it a thin caller of Task 1's math.

- [ ] **Step 1: Add chapter fields to `PlaybackState`**

In the `data class PlaybackState`, add two fields after `positionSec`:

```kotlin
        val positionSec: Double = 0.0,
        val currentChapterIndex: Int = -1,
        val chapterCount: Int = 0,
```

- [ ] **Step 2: Add the skip/chapter actions**

After `setSpeed(...)` add:

```kotlin
    /** Rewinds/forwards along the continuous timeline (negative = rewind), clamped to the readaloud. */
    fun skipBy(deltaSec: Double) {
        val s = _state.value
        val target = track?.resolveRelativeSkip(s.currentAudioSrc, s.positionSec, deltaSec) ?: return
        seekToAudio(target.audioSrc, target.positionSec)
        pushState()
    }

    /** Jumps to the first clip of an adjacent chapter (see [ReadaloudTrack.resolveChapterSkip]). */
    fun skipChapter(forward: Boolean) {
        val s = _state.value
        val clip = track?.resolveChapterSkip(
            s.currentAudioSrc, s.positionSec, forward, NEAR_START_SEC,
        ) ?: return
        seekToAudio(clip.audioSrc, clip.clipBeginSec)
        pushState()
    }
```

- [ ] **Step 3: Fill the chapter fields in `pushState`**

Replace the body of `pushState()` so the new fields are populated from the track:

```kotlin
    private fun pushState() {
        val c = controller
        val t = track
        val audioSrc = c?.currentMediaItem?.mediaId
        val positionSec = (c?.currentPosition ?: 0L) / 1000.0
        _state.value = PlaybackState(
            connected = c != null,
            isPlaying = c?.isPlaying == true,
            speed = c?.playbackParameters?.speed ?: 1f,
            currentAudioSrc = audioSrc,
            positionSec = positionSec,
            currentChapterIndex = t?.chapterIndexAt(audioSrc, positionSec) ?: -1,
            chapterCount = t?.chapterCount ?: 0,
        )
    }
```

- [ ] **Step 4: Add the constants**

In the `companion object`, add the skip durations next to `SPEEDS`:

```kotlin
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        const val REWIND_SEC = 15.0
        const val FORWARD_SEC = 30.0
        private const val NEAR_START_SEC = 3.0
```

- [ ] **Step 5: Add the public rewind/forward/chapter helpers**

After `skipChapter(...)` add the named entry points the coordinator calls:

```kotlin
    fun rewind() = skipBy(-REWIND_SEC)
    fun forward() = skipBy(FORWARD_SEC)
    fun previousChapter() = skipChapter(forward = false)
    fun nextChapter() = skipChapter(forward = true)
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt
git commit -m "feat(readaloud): controller skip/forward + chapter skip + chapter state"
```

---

### Task 3: PlayerCoordinator — pass-throughs

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/PlayerCoordinator.kt`

- [ ] **Step 1: Add the pass-throughs**

After `fun setSpeed(speed: Float) = controller.setSpeed(speed)` add:

```kotlin
    fun rewind() = controller.rewind()

    fun forward() = controller.forward()

    fun previousChapter() = controller.previousChapter()

    fun nextChapter() = controller.nextChapter()
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/PlayerCoordinator.kt
git commit -m "feat(readaloud): coordinator pass-throughs for skip + chapter controls"
```

---

### Task 4: EpubReaderViewModel — intent methods

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Add the intent methods**

After `fun setSpeed(speed: Float) = playerCoordinator.setSpeed(speed)` (around line 1122) add:

```kotlin
    fun rewind() = playerCoordinator.rewind()

    fun forward() = playerCoordinator.forward()

    fun previousChapter() = playerCoordinator.previousChapter()

    fun nextChapter() = playerCoordinator.nextChapter()
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(readaloud): view-model intents for skip + chapter controls"
```

---

### Task 5: ReadaloudMiniPlayer — buttons + SkipIcon

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudPlayerUi.kt`
- Test: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudMiniPlayerTest.kt`

Material `material-icons-extended` ships `Forward30`, `SkipNext`, `SkipPrevious` but **no `Replay15`**. So the two fine-skip glyphs are drawn by a small `SkipIcon` composable: a circular-arrow `Icons.Filled.Replay` (mirrored horizontally for forward) with the seconds count overlaid — giving a matched `⟲15` / `30⟳` pair with no custom drawable.

- [ ] **Step 1: Write the failing harness test**

Create `app/src/androidTest/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudMiniPlayerTest.kt`:

```kotlin
package com.riffle.app.feature.reader.readaloud

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadaloudMiniPlayerTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun setPlayer(
        canPreviousChapter: Boolean = true,
        canNextChapter: Boolean = true,
        onRewind: () -> Unit = {},
        onForward: () -> Unit = {},
        onPreviousChapter: () -> Unit = {},
        onNextChapter: () -> Unit = {},
    ) {
        rule.setContent {
            ReadaloudMiniPlayer(
                isPlaying = false,
                speed = 1f,
                offlineMessage = false,
                downloadProgress = null,
                canPreviousChapter = canPreviousChapter,
                canNextChapter = canNextChapter,
                containerColor = Color.LightGray,
                contentColor = Color.Black,
                onPlayPause = {},
                onCycleSpeed = {},
                onRewind = onRewind,
                onForward = onForward,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onClose = {},
                onExpand = {},
            )
        }
    }

    @Test
    fun skipButtons_areDisplayedInThePlayableState() {
        setPlayer()
        rule.onNodeWithTag("readaloud_rewind").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_prev_chapter").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_next_chapter").assertIsDisplayed()
        rule.onNodeWithTag("readaloud_forward").assertIsDisplayed()
    }

    @Test
    fun skipButtons_invokeTheirCallbacks() {
        var rewinds = 0
        var forwards = 0
        var prevs = 0
        var nexts = 0
        setPlayer(
            onRewind = { rewinds++ },
            onForward = { forwards++ },
            onPreviousChapter = { prevs++ },
            onNextChapter = { nexts++ },
        )
        rule.onNodeWithTag("readaloud_rewind").performClick()
        rule.onNodeWithTag("readaloud_forward").performClick()
        rule.onNodeWithTag("readaloud_prev_chapter").performClick()
        rule.onNodeWithTag("readaloud_next_chapter").performClick()
        assertEquals(1, rewinds)
        assertEquals(1, forwards)
        assertEquals(1, prevs)
        assertEquals(1, nexts)
    }

    @Test
    fun nextChapter_isDisabledAtTheLastChapter() {
        setPlayer(canNextChapter = false)
        rule.onNodeWithTag("readaloud_next_chapter").assertIsNotEnabled()
    }

    @Test
    fun skipButtons_areAbsentWhileOffline() {
        rule.setContent {
            ReadaloudMiniPlayer(
                isPlaying = false,
                speed = 1f,
                offlineMessage = true,
                downloadProgress = null,
                canPreviousChapter = true,
                canNextChapter = true,
                containerColor = Color.LightGray,
                contentColor = Color.Black,
                onPlayPause = {},
                onCycleSpeed = {},
                onRewind = {},
                onForward = {},
                onPreviousChapter = {},
                onNextChapter = {},
                onClose = {},
                onExpand = {},
            )
        }
        rule.onNodeWithTag("readaloud_rewind").assertDoesNotExist()
        rule.onNodeWithTag("readaloud_next_chapter").assertDoesNotExist()
    }
}
```

Add the missing import for the last test:

```kotlin
import androidx.compose.ui.test.assertDoesNotExist
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `make harness-test` (boots the Harness Medium Phone AVD; never call `connectedDebugAndroidTest` directly).
Expected: FAIL — `ReadaloudMiniPlayer` has no `canPreviousChapter`/`onRewind`/… parameters; compile error.

- [ ] **Step 3: Add the `SkipIcon` composable**

In `ReadaloudPlayerUi.kt`, add near the top (after `speedLabel`):

```kotlin
/**
 * A circular-arrow skip glyph with the seconds count overlaid. Material ships Forward30 but no
 * Replay15, so both fine-skip buttons share this drawing for a matched look: a counter-clockwise
 * [Icons.Filled.Replay] for rewind, mirrored horizontally for forward.
 */
@Composable
private fun SkipIcon(seconds: Int, forward: Boolean, tint: Color) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Filled.Replay,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.then(if (forward) Modifier.scale(scaleX = -1f, scaleY = 1f) else Modifier),
        )
        Text(
            text = seconds.toString(),
            color = tint,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
```

Add these imports to the file's import block (keep alphabetical grouping with the existing ones):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.sp
```

(`Forward30` is imported for parity even though `SkipIcon` draws the mirrored Replay; it is used by the chapter-vs-fine distinction nowhere else — remove this import if the compiler flags it as unused.)

- [ ] **Step 4: Add the new parameters and buttons to `ReadaloudMiniPlayer`**

Change the signature to add the four callbacks and two enable flags (after `onCycleSpeed`, before `onClose`):

```kotlin
fun ReadaloudMiniPlayer(
    isPlaying: Boolean,
    speed: Float,
    offlineMessage: Boolean,
    downloadProgress: Float?,
    canPreviousChapter: Boolean,
    canNextChapter: Boolean,
    containerColor: Color,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace the `else` branch (the `play_pause` + `speed` + trailing `Spacer`) with the speed-left, centered-transport layout. The current block is:

```kotlin
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag("readaloud_play_pause")) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                TextButton(onClick = onCycleSpeed, modifier = Modifier.testTag("readaloud_speed")) {
                    Text(
                        speedLabel(speed),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
```

Replace it with:

```kotlin
            } else {
                TextButton(onClick = onCycleSpeed, modifier = Modifier.testTag("readaloud_speed")) {
                    Text(
                        speedLabel(speed),
                        color = contentColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onRewind, modifier = Modifier.testTag("readaloud_rewind")) {
                    SkipIcon(seconds = 15, forward = false, tint = contentColor)
                }
                IconButton(
                    onClick = onPreviousChapter,
                    enabled = canPreviousChapter,
                    modifier = Modifier.testTag("readaloud_prev_chapter"),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous chapter")
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag("readaloud_play_pause")) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = canNextChapter,
                    modifier = Modifier.testTag("readaloud_next_chapter"),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next chapter")
                }
                IconButton(onClick = onForward, modifier = Modifier.testTag("readaloud_forward")) {
                    SkipIcon(seconds = 30, forward = true, tint = contentColor)
                }
                Spacer(modifier = Modifier.weight(1f))
            }
```

This yields: `[1×] …spacer… [⟲15] [⏮] [▶/⏸] [⏭] [30⟳] …spacer… [✕]` (the trailing `✕` is the existing close `IconButton` after the `if/else`).

- [ ] **Step 5: Run the harness test to verify it passes**

Run: `make harness-test`
Expected: PASS — `ReadaloudMiniPlayerTest` green (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudPlayerUi.kt \
        app/src/androidTest/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudMiniPlayerTest.kt
git commit -m "feat(readaloud): mini-player rewind/forward + prev/next chapter buttons"
```

---

### Task 6: Wire the callbacks in EpubReaderScreen

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt:364-380`

- [ ] **Step 1: Pass the new arguments at the call site**

In the `ReadaloudMiniPlayer(...)` call, add the chapter-enable flags (derived from `playbackState`) and the four callbacks. Insert `canPreviousChapter`/`canNextChapter` after `downloadProgress`, and the four callbacks after `onCycleSpeed`:

```kotlin
                    ReadaloudMiniPlayer(
                        isPlaying = playbackState.isPlaying,
                        speed = playbackState.speed,
                        offlineMessage = readaloudOfflineMessage,
                        downloadProgress = downloadProgress,
                        canPreviousChapter = playbackState.currentChapterIndex > 0,
                        canNextChapter = playbackState.currentChapterIndex >= 0 &&
                            playbackState.currentChapterIndex < playbackState.chapterCount - 1,
                        containerColor = readerPalette.background.copy(alpha = 0.65f),
                        contentColor = readerPalette.foreground,
                        onPlayPause = viewModel::togglePlayPause,
                        onCycleSpeed = {
                            // Cycle 0.75× → 1× → 1.25× → 1.5× → 2× → 0.75×.
                            val speeds = com.riffle.app.feature.reader.readaloud.ReadaloudController.SPEEDS
                            val idx = speeds.indexOfFirst { kotlin.math.abs(it - playbackState.speed) < 0.001f }
                            viewModel.setSpeed(if (idx < 0) 1f else speeds[(idx + 1) % speeds.size])
                        },
                        onRewind = viewModel::rewind,
                        onForward = viewModel::forward,
                        onPreviousChapter = viewModel::previousChapter,
                        onNextChapter = viewModel::nextChapter,
                        onClose = viewModel::closeReadaloud,
                        onExpand = viewModel::expandPlayer,
                    )
```

> Note: `canPreviousChapter` stays `true` even at chapter 0 (the button *restarts* chapter 0 — an effective no-op at the very start); it is only the dead `next` direction that disables. `currentChapterIndex > 0` would disable restart-of-chapter-0; that is acceptable per the spec ("no-op ⏮ at the very first chapter"). Use `playbackState.currentChapterIndex > 0` as written so the first chapter's ⏮ is disabled.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(readaloud): wire mini-player skip + chapter callbacks"
```

---

### Task 7: Full verification

- [ ] **Step 1: Run the full JVM test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL (CI parity — catches pure-JVM modules `:test` misses with module-specific tasks).

- [ ] **Step 2: Run the phone harness suite**

Run: `make harness-test`
Expected: BUILD SUCCESSFUL — including `ReadaloudMiniPlayerTest`.

- [ ] **Step 3: Manual smoke (optional but recommended)**

On a real device or the AVD with a prepared readaloud book: play, tap ⟲15 (jumps back ~15s, highlight follows), tap 30⟳ (jumps forward ~30s), tap ⏭ (next chapter, page follows), tap ⏮ mid-chapter (restarts chapter), tap ⏮ near a chapter start (previous chapter). Confirm ⏭ is disabled on the last chapter.

---

## Self-review notes

- **Spec coverage:** layout (Task 5/6), rewind 15 / forward 30 continuous + clamp (Task 1 `resolveRelativeSkip` + Task 2 `skipBy`), prev/next chapter driving audio (Task 1 `resolveChapterSkip` + Task 2 `skipChapter`), ⏮ restart-vs-previous near-start threshold (Task 1), first/last-chapter clamp (Task 1 returns null for next-past-last; Task 6 disables `next`; `prev` disabled at chapter 0 via `> 0`), mini-bar-only / sheet untouched (no sheet edits), gating to the playable state (Task 5 keeps buttons inside the existing `else` branch). Covered.
- **Type consistency:** `Position(audioSrc, positionSec)` used identically in Task 1 tests, impl, and Task 2 caller. `currentChapterIndex`/`chapterCount` named identically in `PlaybackState` (Task 2) and the screen (Task 6). `REWIND_SEC`/`FORWARD_SEC` constants defined in Task 2.
- **Icon caveat:** no `Replay15` in Material; `SkipIcon` draws it. `Forward30` import may be unused — remove if flagged.
