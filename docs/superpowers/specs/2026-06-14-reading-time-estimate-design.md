# Reading Time Estimate — Design Spec

**Date:** 2026-06-14  
**Branch target:** main

---

## Overview

Show estimated (or exact, when read aloud is active) time remaining in the current chapter and in the whole book. The estimate adapts silently to the user's actual reading speed via a per-device EWMA over reading sessions. Togglable in the formatting panel with both a global default and a per-book override, following the same pattern as all other formatting toggles.

---

## 1. Adaptive Reading Speed Engine

### Unit of measure

Readium *positions* are used as the proxy for text length. A position is one screen-pageful of text (~250 words). Position counts are already computed per chapter as `RailSegment.weight`; their sum = `totalPositions`. This avoids needing actual word counts.

**Default rate:** 63 s/position (= 250 words ÷ 238 WPM, the average adult reading speed).

### Session tracking

`EpubReaderViewModel` already calls `onReaderResumed()` / `onReaderPaused()` via lifecycle hooks. Reading sessions are tracked there:

- **On `onReaderResumed()`:** snapshot `(totalProgression: Float, wallClockMs: Long)`.
- **On `onReaderPaused()`:** compute:

  ```
  progressDelta  = currentProgression - startProgression
  timeDeltaSec   = (nowMs - startMs) / 1000
  positionsDelta = progressDelta × totalPositions
  observedRate   = timeDeltaSec / positionsDelta   // seconds per position
  ```

- **Discard** the session if any of:
  - `timeDeltaSec < 30` (too short — user just glanced at the book)
  - `positionsDelta < 0.5` (less than half a position moved — probably didn't read)
  - Implied WPM outside `[20, 1000]` (sanity bounds on `250 / observedRate × 60`)

- **EWMA update** (on valid sessions only):
  ```
  adaptedRate = 0.2 × observedRate + 0.8 × priorRate
  ```
  α = 0.2 gives slow, stable adaptation: roughly 5 sessions to shift meaningfully.

### Storage

A new **`ReadingSpeedStore`** backed by its own `@ReadingSpeedDataStore`-qualified `DataStore<Preferences>` created in `DataModule` — device-wide, not per-book. Reading speed is a property of the reader, not the book; pooling sessions across all books converges 5–10× faster than per-book tracking and avoids a cold-start problem on every new book. Giving it a dedicated DataStore keeps `FormattingPreferencesDataStore` scoped to formatting only.

```kotlin
val KEY_READING_SPEED_SECS_PER_POSITION = doublePreferencesKey("reading_speed_secs_per_position")
// DataStore file name: "reading_speed_preferences"
```

`ReadingSpeedStore` is a new domain interface + `ReadingSpeedStoreImpl` in `core/data`, bound in `DataModule` alongside the other store bindings. Injected into `EpubReaderViewModel`.

### Time calculations (estimated mode)

```
totalPositions        = railSegments.sumOf { it.weight }
bookRemainingSec      = (1 - totalProgression) × totalPositions × secsPerPosition
chapterRemainingSec   = (1 - chapterProgression) × chapterWeight × secsPerPosition
```

`chapterWeight` = `railSegments[activeRailSegmentIndex].weight`.

---

## 2. Readaloud Exact Mode

When readaloud is connected (`playbackState.connected == true` and `readaloudTrack != null`), use the audio timeline directly — no inference needed.

```
chapterEndSec       = chapterStartsSec[chapterIndex + 1]  // totalDurationSec for last chapter
chapterRemainingSec = (chapterEndSec - positionGlobalSec) / speed
bookRemainingSec    = (totalDurationSec - positionGlobalSec) / speed
```

Both values update live as `positionGlobalSec` ticks in `PlaybackState`.

If readaloud is connected but `readaloudTrack` is not yet loaded, fall back to estimated mode.

---

## 3. ViewModel Surface

### New sealed class

```kotlin
sealed class TimeRemaining {
    data class Estimated(val sec: Long) : TimeRemaining()
    data class Exact(val sec: Long) : TimeRemaining()
}
```

### New `StateFlow`s on `EpubReaderViewModel`

```kotlin
val chapterTimeRemaining: StateFlow<TimeRemaining?>
val bookTimeRemaining: StateFlow<TimeRemaining?>
```

Both emit `null` when `totalProgression` is unknown (Readium hasn't computed positions yet) or when `totalPositions == 0`.

These are `combine()` expressions over `currentLocatorTotalProgression`, `currentLocatorProgression`, `railSegments`, `activeRailSegmentIndex`, `playbackState`, `readaloudTrack`, and `readingSpeed` from `ReadingSpeedStore`.

### New classes

| Class | Location | Purpose |
|---|---|---|
| `ReadingSpeedStore` | `core/domain` | Interface: `flow: Flow<Double>`, `update(Double)` |
| `ReadingSpeedStoreImpl` | `core/data` | DataStore-backed implementation |
| `ReadingSpeedTracker` | `core/domain` | Pure: `recordSession(progressDelta, timeDeltaSec, totalPositions, priorRate): Double?` — returns new EWMA rate or null if session is discarded |

`ReadingSpeedTracker` is a pure function object with no Android dependencies — JVM unit-testable.

---

## 4. Toggle — `showReadingTimeEstimate`

Follows the exact same 4-file pattern as `showReadingProgressLabels` and `showCurrentChapterLabel`.

### `FormattingPreferences.kt`
```kotlin
val showReadingTimeEstimate: Boolean = DEFAULT_SHOW_READING_TIME_ESTIMATE,

// in companion:
const val DEFAULT_SHOW_READING_TIME_ESTIMATE: Boolean = true
```

Default is **true** — on by default so users discover the feature without needing to opt in.

### `BookFormattingOverrides.kt`
```kotlin
val showReadingTimeEstimate: Boolean? = null
```

Add to `isEmpty`, `applyTo()`, and `withChanges()` following the identical pattern of the other Boolean? fields.

### `BookFormattingPreferencesEntity.kt`
```kotlin
val showReadingTimeEstimate: Boolean? = null
```

Requires **Room migration 35 → 36**:
```sql
ALTER TABLE book_formatting_preferences ADD COLUMN showReadingTimeEstimate INTEGER;
```

Add `MIGRATION_35_36` to `RiffleDatabase`, register in `DataModule.addMigrations(...)`, and add a migration test in `MigrationTest`.

### `FormattingPreferencesStoreImpl.kt`
```kotlin
showReadingTimeEstimate = prefs[KEY_SHOW_READING_TIME_ESTIMATE] ?: true,
// in update():
prefs[KEY_SHOW_READING_TIME_ESTIMATE] = preferences.showReadingTimeEstimate
// companion:
val KEY_SHOW_READING_TIME_ESTIMATE = booleanPreferencesKey("show_reading_time_estimate")
```

### `FormattingPanel.kt`
Add a toggle row after the `showCurrentChapterLabel` row, same chip/switch pattern:

```
"Time remaining"   [toggle]
```

---

## 5. UI — The Pill

### Placement

In `ReadingProgressLabels`, the centre slot becomes a `Column` when either the chapter title or the time pill is visible:

```
[Ch X of N]  [  Chapter Title (italic)  ]  [XX.X%]
             [ ~8 min · ~2h 14m left    ]
```

If only `showReadingTimeEstimate` is on (chapter title off), the pill alone occupies the centre:

```
[Ch X of N]  [ ~8 min · ~2h 14m left   ]  [XX.X%]
```

The pill is a `Text` with `labelSmall`, `background(shape = RoundedCornerShape(8.dp))` at low alpha, padded `2dp` vertical / `6dp` horizontal.

### Format

| Mode | Chapter remaining | Book remaining |
|---|---|---|
| Estimated | `~8 min in chapter` | `~2h 14m left` |
| Estimated < 1 min | `< 1 min in chapter` | `< 1 min left` |
| Exact (readaloud) | `8:32 in chapter` | `2:14:07 left` |
| Exact < 1 hr | `8:32 in chapter` | `14:07 left` |

Combined pill text: `"~8 min in chapter · ~2h 14m left"` (bullet separator).

Colour: same `readerThemeLabelColor()` as other labels for estimated; **`MaterialTheme.colorScheme.tertiary`** for exact to distinguish audio-derived precision (follows the active theme rather than a hardcoded hex).

### Visibility guard

`EpubChapterRailOverlay` gate — add `|| formattingPrefs.showReadingTimeEstimate` to the existing `showReadingProgressLabels || showCurrentChapterLabel` condition at line 372.

### `ReadingProgressLabels` signature change

```kotlin
private fun ReadingProgressLabels(
    // existing params...
    chapterTimeRemaining: TimeRemaining?,   // new
    bookTimeRemaining: TimeRemaining?,      // new
    showReadingTimeEstimate: Boolean,       // new
)
```

---

## 6. Files Changed

| File | Change |
|---|---|
| `core/domain/…/FormattingPreferences.kt` | Add `showReadingTimeEstimate` field + constant |
| `core/domain/…/BookFormattingOverrides.kt` | Add nullable field, wire into `isEmpty`, `applyTo`, `withChanges` |
| `core/domain/…/ReadingSpeedStore.kt` | **New** — interface |
| `core/domain/…/ReadingSpeedTracker.kt` | **New** — pure session EWMA logic |
| `core/domain/…/TimeRemaining.kt` | **New** — sealed class |
| `core/data/…/FormattingPreferencesStoreImpl.kt` | Add key + read/write for `showReadingTimeEstimate` |
| `core/data/…/ReadingSpeedStoreImpl.kt` | **New** — DataStore impl |
| `core/data/…/di/DataModule.kt` | Bind `ReadingSpeedStore` |
| `core/database/…/BookFormattingPreferencesEntity.kt` | Add `showReadingTimeEstimate: Boolean?` |
| `core/database/…/RiffleDatabase.kt` | Bump to v36, add `MIGRATION_35_36` |
| `core/database/…/MigrationTest.kt` | Add `migration35To36()` + chain test |
| `app/…/reader/EpubReaderViewModel.kt` | Inject `ReadingSpeedStore`, session tracking, expose `chapterTimeRemaining` / `bookTimeRemaining` |
| `app/…/reader/FormattingPanel.kt` | Add `showReadingTimeEstimate` toggle row |
| `app/…/reader/EpubReaderScreen.kt` | Collect time flows, pass to `ReadingProgressLabels`, update visibility guard |

---

## 7. Tests

- **`ReadingSpeedTrackerTest`** (JVM): valid session updates EWMA; sessions below 30 s / < 0.5 pos / outside WPM bounds are discarded; EWMA converges correctly over N sessions.
- **`ReadingTimeRemainingTest`** (JVM, on ViewModel or a pure helper): correct chapter/book seconds for estimated mode; correct values in exact mode with speed != 1.
- **`MigrationTest.migration35To36()`**: column added with NULL default; prior rows unaffected.
- **`BookFormattingOverridesTest`**: extend existing tests for new `showReadingTimeEstimate` field.
