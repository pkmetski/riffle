# Audiobook Sleep Timer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a sleep timer to the audiobook player that fades out and pauses playback after a set duration or at the end of the current chapter.

**Architecture:** Timer countdown and fade live in `AudiobookController` (singleton, survives backgrounding); the ViewModel detects end-of-chapter transitions and delegates to the controller; the UI is a matched speed+sleep pill row with a bottom sheet grid picker.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 MediaController, kotlinx.coroutines StateFlow/combine

---

## File Map

| Action | Path |
|---|---|
| Create | `app/src/main/kotlin/com/riffle/app/feature/audiobook/SleepTimerMode.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt` |
| Create | `app/src/main/kotlin/com/riffle/app/feature/audio/SleepTimerControl.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt` |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt` |
| Create | `app/src/test/kotlin/com/riffle/app/feature/audiobook/SleepTimerTest.kt` |

---

### Task 1: SleepTimerMode domain model

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/audiobook/SleepTimerMode.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.audiobook

sealed interface SleepTimerMode {
    data object None : SleepTimerMode
    data class CountDown(val remainingMs: Long) : SleepTimerMode
    data object EndOfChapter : SleepTimerMode
}

fun SleepTimerMode.formatCountdown(): String {
    if (this !is SleepTimerMode.CountDown) return ""
    val totalSec = remainingMs / 1_000L
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/SleepTimerMode.kt
git commit -m "feat(player): add SleepTimerMode sealed interface"
```

---

### Task 2: AudiobookController — timer logic

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt`

The controller already has `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` and `private var pollJob: Job?`. The Media3 `controller` field (of type `MediaController?`) implements `Player`, which exposes `setVolume(Float)`.

- [ ] **Step 1: Add timer state fields after the existing `_state` declaration**

```kotlin
private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
open val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()
private var timerJob: Job? = null

companion object {
    // add alongside existing POLL_INTERVAL_MS, REWIND_SEC, FORWARD_SEC:
    private const val FADE_STEPS = 50
    private const val FADE_STEP_MS = 100L
}
```

- [ ] **Step 2: Add the public timer API and private fade helper**

Add these functions to the class body (after `setSpeed`):

```kotlin
open fun setSleepTimer(mode: SleepTimerMode) {
    timerJob?.cancel()
    _sleepTimer.value = mode
    if (mode is SleepTimerMode.CountDown) {
        timerJob = scope.launch {
            var remaining = mode.remainingMs
            while (remaining > 0L) {
                delay(1_000L)
                remaining -= 1_000L
                _sleepTimer.value = SleepTimerMode.CountDown(remaining.coerceAtLeast(0L))
            }
            fadeAndStop()
        }
    }
    // EndOfChapter: no countdown needed; ViewModel calls triggerSleepNow() on chapter change.
}

open fun cancelSleepTimer() {
    timerJob?.cancel()
    timerJob = null
    _sleepTimer.value = SleepTimerMode.None
}

// Called by ViewModel when a chapter boundary is crossed in EndOfChapter mode.
open fun triggerSleepNow() {
    timerJob?.cancel()
    timerJob = scope.launch { fadeAndStop() }
}

private suspend fun fadeAndStop() {
    repeat(FADE_STEPS) { i ->
        controller?.setVolume((1f - (i + 1f) / FADE_STEPS).coerceAtLeast(0f))
        delay(FADE_STEP_MS)
    }
    pollJob?.cancel()
    controller?.pause()
    controller?.setVolume(1f)
    _sleepTimer.value = SleepTimerMode.None
}
```

- [ ] **Step 3: Cancel the timer in `pause()` (manual pause)**

Find the existing `pause()` function and add two lines at the top of its body:

```kotlin
fun pause() {
    timerJob?.cancel()          // ← add
    timerJob = null             // ← add
    _sleepTimer.value = SleepTimerMode.None  // ← add
    // ... rest of existing pause() body unchanged ...
}
```

- [ ] **Step 4: Build to confirm no compilation errors**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt
git commit -m "feat(player): add sleep timer countdown and fade-to-pause in AudiobookController"
```

---

### Task 3: AudiobookPlayerViewModel — expose timer state and EoC detection

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`

- [ ] **Step 1: Add `sleepTimer` to `AudiobookPlayerUiState`**

Find the `data class AudiobookPlayerUiState(` declaration and add one field:

```kotlin
val sleepTimer: SleepTimerMode = SleepTimerMode.None,
```

- [ ] **Step 2: Include `controller.sleepTimer` in the `uiState` combine**

The current combine is `combine(meta, controller.state) { m, playback -> ... }`. Change it to a 3-flow combine:

```kotlin
val uiState: StateFlow<AudiobookPlayerUiState> =
    combine(meta, controller.state, controller.sleepTimer) { m, playback, timer ->
        val pos = playback.positionSec
        val chapter = timeline.chapterAt(pos)
        m.copy(
            isPlaying = playback.isPlaying,
            speed = playback.speed,
            positionSec = pos,
            durationSec = playback.durationSec,
            currentChapterTitle = chapter?.title,
            chapterStartsSec = timeline.chapterStartsSec,
            chapters = timeline.chapters,
            currentChapterIndex = timeline.chapterIndexAt(pos),
            canPreviousChapter = timeline.hasPreviousChapter(pos),
            canNextChapter = timeline.hasNextChapter(pos),
            sleepTimer = timer,       // ← new
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AudiobookPlayerUiState(loading = true))
```

(Keep all existing fields — only the lambda signature changes from `{ m, playback ->` to `{ m, playback, timer ->` and `sleepTimer = timer` is appended to the `m.copy(...)` block.)

- [ ] **Step 3: Add EoC chapter-change detection in `init {}`**

Add this block inside `init {}` after any existing init logic:

```kotlin
// End-of-chapter sleep timer: fire when chapter index advances while EoC mode is active.
var eocPrevChapterIndex = -1
viewModelScope.launch {
    uiState.collect { state ->
        val idx = state.currentChapterIndex
        if (eocPrevChapterIndex >= 0
            && idx != eocPrevChapterIndex
            && controller.sleepTimer.value is SleepTimerMode.EndOfChapter
        ) {
            controller.triggerSleepNow()
        }
        eocPrevChapterIndex = idx
    }
}
```

- [ ] **Step 4: Add public timer delegation functions**

Add after the existing `setSpeed()` function:

```kotlin
fun setSleepTimer(mode: SleepTimerMode) = controller.setSleepTimer(mode)
fun cancelSleepTimer() = controller.cancelSleepTimer()
```

- [ ] **Step 5: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt
git commit -m "feat(player): wire sleep timer state and EoC detection in AudiobookPlayerViewModel"
```

---

### Task 4: SleepTimerControl composable

**Files:**
- Create: `app/src/main/kotlin/com/riffle/app/feature/audio/SleepTimerControl.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.riffle.app.feature.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.audiobook.SleepTimerMode
import com.riffle.app.feature.audiobook.formatCountdown

private val PRESETS_MINUTES = listOf(15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerControl(
    timerMode: SleepTimerMode,
    onSetTimer: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Sleep Timer",
                style = MaterialTheme.typography.titleMedium,
            )

            // Active timer banner — shown only when a timer is already running.
            if (timerMode !is SleepTimerMode.None) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bannerText = when (timerMode) {
                        is SleepTimerMode.CountDown ->
                            "Sleeping in ${timerMode.formatCountdown()}"
                        is SleepTimerMode.EndOfChapter ->
                            "Sleeping at end of chapter"
                        else -> ""
                    }
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = { onCancel(); onDismiss() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel timer")
                    }
                }
            }

            // End of chapter — full-width.
            FilledTonalButton(
                onClick = { onSetTimer(SleepTimerMode.EndOfChapter); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("End of chapter")
            }

            // 3-column preset grid: row 1 = 15/30/45, row 2 = 60/90/(blank).
            val rowOne = PRESETS_MINUTES.take(3)
            val rowTwo = PRESETS_MINUTES.drop(3)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowOne.forEach { minutes ->
                    PresetButton(
                        minutes = minutes,
                        onSetTimer = onSetTimer,
                        onDismiss = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTwo.forEach { minutes ->
                    PresetButton(
                        minutes = minutes,
                        onSetTimer = onSetTimer,
                        onDismiss = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Blank cell to keep alignment.
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetButton(
    minutes: Int,
    onSetTimer: (SleepTimerMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = {
            onSetTimer(SleepTimerMode.CountDown(minutes * 60 * 1_000L))
            onDismiss()
        },
        modifier = modifier,
        shape = RoundedCornerShape(50),
    ) {
        Text("$minutes min", style = MaterialTheme.typography.labelLarge)
    }
}
```

- [ ] **Step 2: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audio/SleepTimerControl.kt
git commit -m "feat(player): add SleepTimerControl bottom sheet with preset grid"
```

---

### Task 5: PlayerSurface — speed + sleep pill row

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt`

The existing standalone `PlaybackSpeedControl` wrapped in a `Column` (inside `PlayerControls`) becomes one half of a `Row` that also holds the new sleep pill.

- [ ] **Step 1: Add `sleepTimer` to `PlayerSurfaceState`**

Find the `data class PlayerSurfaceState(` declaration and add:

```kotlin
val sleepTimer: SleepTimerMode = SleepTimerMode.None,
```

Add the import at the top of the file:
```kotlin
import com.riffle.app.feature.audiobook.SleepTimerMode
import com.riffle.app.feature.audiobook.formatCountdown
```

- [ ] **Step 2: Add sleep timer actions to `PlayerSurfaceActions`**

Find the `data class PlayerSurfaceActions(` declaration and add:

```kotlin
val onSleepTimerSet: (SleepTimerMode) -> Unit,
val onSleepTimerCancel: () -> Unit,
```

- [ ] **Step 3: Replace the standalone speed `Column` in `PlayerControls` with a Row**

Find this block inside `PlayerControls` (the speed pill section):

```kotlin
Column(horizontalAlignment = Alignment.CenterHorizontally) {
    PlaybackSpeedControl(
        speed = state.speed,
        onSpeedChange = actions.onSpeedChange,
        tagPrefix = "audiobook",
    ) { onClick ->
        FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(50)) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(PlaybackSpeed.label(state.speed), style = MaterialTheme.typography.titleSmall)
        }
    }
    Text("Speed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
```

Replace it with:

```kotlin
var sleepSheetOpen by remember { mutableStateOf(false) }

Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    PlaybackSpeedControl(
        speed = state.speed,
        onSpeedChange = actions.onSpeedChange,
        tagPrefix = "audiobook",
    ) { onClick ->
        FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(50)) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(PlaybackSpeed.label(state.speed), style = MaterialTheme.typography.titleSmall)
        }
    }

    val timerActive = state.sleepTimer !is SleepTimerMode.None
    val timerLabel = when (val t = state.sleepTimer) {
        is SleepTimerMode.CountDown -> t.formatCountdown()
        is SleepTimerMode.EndOfChapter -> "End of ch."
        else -> "Sleep"
    }
    FilledTonalButton(
        onClick = { sleepSheetOpen = true },
        shape = RoundedCornerShape(50),
        colors = if (timerActive)
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        else ButtonDefaults.filledTonalButtonColors(),
    ) {
        Icon(Icons.Filled.Bedtime, contentDescription = "Sleep timer", modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(timerLabel, style = MaterialTheme.typography.titleSmall)
    }
}

if (sleepSheetOpen) {
    SleepTimerControl(
        timerMode = state.sleepTimer,
        onSetTimer = actions.onSleepTimerSet,
        onCancel = actions.onSleepTimerCancel,
        onDismiss = { sleepSheetOpen = false },
    )
}
```

Add any missing imports (Bedtime, ButtonDefaults, SleepTimerControl, etc.):

```kotlin
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.riffle.app.feature.audio.SleepTimerControl
```

- [ ] **Step 4: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audio/PlayerSurface.kt
git commit -m "feat(player): add sleep timer pill alongside speed control in PlayerSurface"
```

---

### Task 6: Wire state and actions in AudiobookPlayerScreen

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt`

- [ ] **Step 1: Add `sleepTimer` to the `PlayerSurfaceState(...)` construction**

Find the `PlayerSurfaceState(` call (around lines 196–225) and add:

```kotlin
sleepTimer = state.sleepTimer,
```

- [ ] **Step 2: Add sleep timer actions to the `PlayerSurfaceActions(...)` construction**

Find the `PlayerSurfaceActions(` call and add:

```kotlin
onSleepTimerSet = viewModel::setSleepTimer,
onSleepTimerCancel = viewModel::cancelSleepTimer,
```

- [ ] **Step 3: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerScreen.kt
git commit -m "feat(player): wire sleep timer state and actions in AudiobookPlayerScreen"
```

---

### Task 7: JVM tests

**Files:**
- Create: `app/src/test/kotlin/com/riffle/app/feature/audiobook/SleepTimerTest.kt`

The pattern follows `AudiobookPlayerViewModelBookmarkTest`: `StandardTestDispatcher`, `FakeController` subclassing `AudiobookController`, `runTest`, `runCurrent()`.

- [ ] **Step 1: Write the test file**

```kotlin
package com.riffle.app.feature.audiobook

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    // ── formatCountdown ─────────────────────────────────────────────────────────

    @Test
    fun `formatCountdown formats minutes and seconds`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 30 * 60 * 1_000L) // 30 min
        assertEquals("30:00", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown pads seconds with leading zero`() {
        val mode = SleepTimerMode.CountDown(remainingMs = 5 * 60 * 1_000L + 7_000L) // 5:07
        assertEquals("5:07", mode.formatCountdown())
    }

    @Test
    fun `formatCountdown returns empty string for non-CountDown mode`() {
        assertEquals("", SleepTimerMode.None.formatCountdown())
        assertEquals("", SleepTimerMode.EndOfChapter.formatCountdown())
    }

    // ── FakeController behaviour ─────────────────────────────────────────────────

    private class FakeController : AudiobookController() {
        private val _sleepTimer = MutableStateFlow<SleepTimerMode>(SleepTimerMode.None)
        override val sleepTimer: StateFlow<SleepTimerMode> = _sleepTimer.asStateFlow()

        val setSleepTimerCalls = mutableListOf<SleepTimerMode>()
        var cancelCalled = 0
        var triggerNowCalled = 0

        override fun setSleepTimer(mode: SleepTimerMode) {
            setSleepTimerCalls.add(mode)
            _sleepTimer.value = mode
        }

        override fun cancelSleepTimer() {
            cancelCalled++
            _sleepTimer.value = SleepTimerMode.None
        }

        override fun triggerSleepNow() {
            triggerNowCalled++
            _sleepTimer.value = SleepTimerMode.None
        }
    }

    // ── ViewModel delegation ─────────────────────────────────────────────────────

    @Test
    fun `setSleepTimer delegates to controller with CountDown mode`() = runTest {
        val controller = FakeController()
        // setSleepTimer is a direct delegation — no ViewModel needed for unit test.
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))

        assertEquals(1, controller.setSleepTimerCalls.size)
        assertTrue(controller.setSleepTimerCalls[0] is SleepTimerMode.CountDown)
        assertEquals(30 * 60_000L, (controller.setSleepTimerCalls[0] as SleepTimerMode.CountDown).remainingMs)
    }

    @Test
    fun `setSleepTimer delegates to controller with EndOfChapter mode`() = runTest {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.EndOfChapter)

        assertEquals(SleepTimerMode.EndOfChapter, controller.setSleepTimerCalls[0])
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
    }

    @Test
    fun `cancelSleepTimer resets timer to None`() = runTest {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        controller.cancelSleepTimer()

        assertEquals(1, controller.cancelCalled)
        assertEquals(SleepTimerMode.None, controller.sleepTimer.value)
    }

    @Test
    fun `setting a new timer replaces the previous one`() = runTest {
        val controller = FakeController()
        controller.setSleepTimer(SleepTimerMode.CountDown(30 * 60_000L))
        controller.setSleepTimer(SleepTimerMode.EndOfChapter)

        assertEquals(2, controller.setSleepTimerCalls.size)
        assertEquals(SleepTimerMode.EndOfChapter, controller.sleepTimer.value)
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.riffle.app.feature.audiobook.SleepTimerTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL` with all tests passing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/riffle/app/feature/audiobook/SleepTimerTest.kt
git commit -m "test(player): add JVM tests for SleepTimerMode formatting and controller delegation"
```

---

## Done

All tasks complete when:
- `./gradlew test` passes
- The player shows a `[🌙 Sleep]` pill beside the speed pill
- Tapping it opens a bottom sheet with "End of chapter" + a 3-column grid of 15/30/45/60/90 min
- Selecting a preset lights the pill purple with a live countdown
- When the countdown reaches zero, playback fades out over 5 seconds then pauses
- Manual pause cancels the timer
- The timer survives the screen being locked (audio continues in the background)
