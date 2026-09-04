# iOS Scenario: Audiobook Player ViewModel KMP Module (Issue #868)

Covers the `feature:player` KMP module — `AudioPlayerInterface`, `PlaybackState`,
`SleepTimerMode`, `NowPlaying`, and the utility functions in `AudiobookProgressUtils`
that are shared between Android and iOS via `commonMain`.

## Preconditions

- `feature:player` builds for iOS targets (`iosArm64`, `iosSimulatorArm64`).
- `AudiobookProgressUtils.kt`, `SleepTimerMode.kt`, and `NowPlaying.kt` contain no
  JVM-only APIs (`String.format`, `@Volatile`, etc.).

## Scenarios

### 5.1 — `formatCountdown` formats sleep-timer remaining time correctly

**Steps:**
1. Create a `SleepTimerMode.CountDown(remainingMs = 90_000L)` (90 s).
2. Call `SleepTimerMode.formatCountdown()` on it.

**Expected:** Returns `"1:30"` (m:ss).

### 5.2 — `formatCountdown` returns empty string for non-countdown modes

**Steps:**
1. Call `SleepTimerMode.formatCountdown()` on `SleepTimerMode.None`.
2. Call it on `SleepTimerMode.EndOfChapter`.

**Expected:** Both return `""`.

### 5.3 — `formatCompactDuration` applies positional templates

**Steps:**
1. Call `formatCompactDuration(7560.0)` (2 h 6 m) with default templates.
2. Call `formatCompactDuration(3600.0)` (1 h) with default templates.
3. Call `formatCompactDuration(600.0)` (10 m) with default templates.

**Expected:**
- Scenario 1: `"2h 6m"`
- Scenario 2: `"1h"`
- Scenario 3: `"10m"`

### 5.4 — `formatCompactDuration` respects custom localized templates

**Steps:**
1. Create `CompactDurationLabelTemplates(minutes = "%1\$d min", hours = "%1\$d hr", hoursMinutes = "%1\$d hr %2\$d min")`.
2. Call `formatCompactDuration(5400.0, templates)` (1 h 30 m).

**Expected:** Returns `"1 hr 30 min"`.

### 5.5 — `audiobookProgressFraction` returns 0 for zero duration

**Steps:**
1. Call `audiobookProgressFraction(100.0, 0.0)`.

**Expected:** Returns `0f`.

### 5.6 — `audiobookProgressFraction` snaps to 1.0 near the end

**Steps:**
1. Call `audiobookProgressFraction(3599.5, 3600.0)` (0.5 s from end, within `AUDIOBOOK_FINISHED_EPS_SEC`).

**Expected:** Returns `1f`.

### 5.7 — `audiobookStartSec` restarts a finished book from zero

**Steps:**
1. Call `audiobookStartSec(resumeSec = 3599.5, durationSec = 3600.0)`.

**Expected:** Returns `0.0`.

### 5.8 — `NowPlayingStore` stores and clears the current session

**Steps:**
1. Create a `NowPlayingStore`.
2. Call `set(NowPlaying.Audiobook("book-1"))`.
3. Call `clearIf { it.itemId == "book-1" }`.

**Expected:**
- After `set`: `current?.itemId == "book-1"`.
- After `clearIf`: `current == null`.

### 5.9 — `readaloudControlState` returns visible+enabled for Storyteller and matched ABS

**Steps:**
1. Call `readaloudControlState(isStoryteller = true, isMatchedAbs = false, bundlePresent = false)`.
2. Call `readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = false)`.
3. Call `readaloudControlState(isStoryteller = false, isMatchedAbs = false, bundlePresent = true)`.

**Expected:**
- Scenarios 1 and 2: `visible = true`, `enabled = true`.
- Scenario 3: `visible = false`, `enabled = false`.
