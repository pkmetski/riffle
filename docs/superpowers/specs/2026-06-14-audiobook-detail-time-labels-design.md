# Audiobook Detail — Total & Remaining Time Labels

## Problem

The item detail screen shows total audiobook duration as `"Audiobook · 12h 4m"`. When the user has started listening, a percentage progress bar reads `"47% listened"` but gives no sense of absolute time left. It's unclear at a glance how much listening remains.

## Goal

Replace the opaque percentage with explicit labeled time, so users can immediately judge "do I have time to finish this?"

## Design

### String format by state

| State | Duration line | Progress indicator |
|---|---|---|
| Not started (`readingProgress == 0`) | `"Audiobook · 12h 4m"` (unchanged) | hidden (unchanged) |
| In-progress (`0 < progress < READ_THRESHOLD`) | `"12h 4m total · 6h 17m remaining"` | unchanged (progress bar + "47% listened") |
| Finished (`progress ≥ READ_THRESHOLD`) | `"12h 4m total"` | unchanged ("100% listened") |

The "Audiobook ·" prefix is dropped in the in-progress and finished states because the framing shifts from type-identification to time-accounting; context makes the content type obvious.

### Computation

```
remainingSec = (1f - readingProgress) * durationSec
```

`readingProgress` is a `Float` in `[0, 1]`. The existing `formatAudiobookDuration(durationSec)` utility is reused for both the total and remaining values.

### Component change

`AudiobookDurationLine(durationSec: Double)` gains a second parameter:

```kotlin
fun AudiobookDurationLine(durationSec: Double, readingProgress: Float = 0f)
```

The composable selects the string based on the thresholds above. All three call sites (`LibraryItemDetailContentPhone`, `LibraryItemDetailContentTablet`, and the landscape two-column layout) already have `item.readingProgress` in scope and pass it in.

`READ_PROGRESS_THRESHOLD` (the existing constant) is reused to define the finished boundary.

## Out of scope

- Changing how the progress bar or percentage label looks
- Any changes to the audiobook player screen's `DualTime` row
- Ebook reading progress (this change is audiobook-only, gated by `item.isListenable`)
