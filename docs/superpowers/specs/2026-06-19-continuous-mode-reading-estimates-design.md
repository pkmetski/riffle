# Continuous Mode: Reading Estimates & Chapter Map Fix

**Date:** 2026-06-19  
**Branch:** pkmetski/surat-v1

## Problem

Reading time estimates and the chapter map rail cursor are not updated in continuous scroll
mode. They work correctly in paginated and vertical modes.

**Root cause:** Paginated and vertical modes use Readium's `EpubNavigatorFragment`, which
emits `Locator` objects that include `totalProgression` (a book-wide 0.0–1.0 fraction).
Continuous mode uses a custom `ContinuousReaderView` whose `onPositionChanged` callback
only reports `(href, withinChapterProgression)` — `totalProgression` is never computed
or included in the `Locator` passed to the ViewModel.

`chapterTimeRemaining`, `bookTimeRemaining`, and `railCursorPosition` all return null /
degenerate when `totalProgression` is absent.

## Architecture context

Three reading modes:

| Mode       | Navigator                        | `totalProgression` source |
|------------|----------------------------------|---------------------------|
| Paginated  | Readium `EpubNavigatorFragment` (scroll=false) | Readium — automatic |
| Vertical   | Readium `EpubNavigatorFragment` (scroll=true)  | Readium — automatic |
| Continuous | Custom `ContinuousReaderView`    | **Missing — this fix**   |

The ViewModel (`EpubReaderViewModel`) is mode-agnostic: it receives a `Locator` and reads
`locator.locations.totalProgression`. It does not need to change.

The adaptation boundary is `EpubReaderScreen.kt`, which already branches per-mode to wire
up position callbacks.

## Section 1 — Fix: compute `totalProgression` for continuous mode

### Where

In `EpubReaderScreen.kt`, inside the continuous-mode `onPositionChanged` lambda
(currently around line 2132). This is the only place that constructs a `Locator` from raw
`(href, progression)` values before forwarding to the ViewModel.

### How

Extract a pure function:

```kotlin
fun computeTotalProgression(
    href: String,
    progression: Float,
    segments: List<RailSegment>,
): Float? {
    val idx = segments.indexOfFirst { it.href == href }
    if (idx < 0) return null
    val totalWeight = segments.sumOf { it.weight.toDouble() }.toFloat()
    if (totalWeight == 0f) return null
    val cumulativeWeight = segments.take(idx).sumOf { it.weight.toDouble() }.toFloat()
    val chapterWeight = segments[idx].weight
    return (cumulativeWeight + chapterWeight * progression) / totalWeight
}
```

Use it in the lambda:

```kotlin
view.onPositionChanged = { href, progression ->
    val totalProgression = computeTotalProgression(href, progression, railSegments)
    val locator = Locator.fromJSON(
        JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", JSONObject()
                .put("progression", progression.toDouble())
                .apply { totalProgression?.let { put("totalProgression", it.toDouble()) } })
    )
    if (locator != null) onPositionChanged(locator)
}
```

`railSegments` is a `StateFlow` whose values arrive asynchronously as Readium computes
positions after publication load. The `onPositionChanged` callback is set inside
`AndroidView`'s `factory` lambda, which runs once and captures values at creation time.
To avoid a stale capture, use `rememberUpdatedState(railSegments)` in the composable so
the lambda always reads the latest segment list:

```kotlin
val currentSegments by rememberUpdatedState(railSegments)
// ... inside AndroidView factory:
view.onPositionChanged = { href, progression ->
    val totalProgression = computeTotalProgression(href, progression, currentSegments)
    ...
}
```

`computeTotalProgression` is a pure function with no side effects, so it is unit
testable without any Android dependencies.

The ViewModel receives a complete `Locator` and needs no changes. Paginated and vertical
modes are unaffected (they never enter this lambda).

### Accuracy

`RailSegment.weight` values are derived from `publication.positionsByReadingOrder()` —
the same position-count data Readium uses internally for its own `totalProgression`
calculation. The continuous mode result will be consistent with how the chapter rail is
drawn.

## Section 2 — CLAUDE.md guideline

Add to `CLAUDE.md`:

```markdown
## Reader mode changes

The reader has three modes: paginated, vertical, and continuous.

Paginated and vertical both use Readium's EpubNavigatorFragment (scroll=false vs
scroll=true). Readium drives navigation, emits position updates, and populates Locator
fields automatically.

Continuous uses a custom ContinuousReaderView with a fully manual position pipeline.
Anything Readium provides for free to paginated/vertical must be explicitly computed
and threaded through the continuous onPositionChanged lambda in EpubReaderScreen.kt.

Any change that touches the reader — position tracking, navigation events, new ViewModel
state, UI driven by the current locator — must be verified to work in all three modes,
with particular attention to continuous: if paginated/vertical get something from Readium,
ask whether continuous needs to compute an equivalent.
```

## Section 3 — Code reuse

Three mechanical extractions, all in `EpubReaderScreen.kt`:

### 3a. `computeTotalProgression()` (new — part of the fix)

Pure function described above. Lives as a top-level function alongside other reader
utilities, or as a companion/object function where `RailSegment` is defined.

### 3b. `goToContinuousLocator(view, locator)` (extraction)

The following block appears verbatim in 5 separate `LaunchedEffect` blocks
(`onNavigationEvents`, `serverLocatorEvents`, `returnNavEvents`,
`searchNavigationEvents`, `annotationNavigationEvents`):

```kotlin
val view = continuousViewRef.value ?: return@collect
view.navigateTo(
    locator.href.toString(),
    locator.locations.progression?.toFloat() ?: 0f,
)
return@collect
```

Extract to a local helper; replace all 5 occurrences.

### 3c. `applyDecorationsWithClear(fragment, decorations, group)` (extraction)

The clear-then-reapply pattern for Readium decorations appears 4+ times:

```kotlin
withContext(Dispatchers.Main) {
    fragment.applyDecorations(emptyList(), group = "X")
    fragment.applyDecorations(decorations, group = "X")
}
```

Extract to a one-liner helper; replace all occurrences.

## Out of scope

- Unifying the Flow (paginated) vs callback (continuous) position-tracking idioms — higher
  risk, no behaviour change, separate refactor.
- JS boundary evaluation deduplication — touches volume key handling, separate concern.
- Pixel-based `totalProgression` from `ContinuousPositionTracker` scroll heights — the
  sliding window only holds ~3 chapters so accuracy would be worse than the weight-based
  approach for out-of-window chapters.

## Testing

- `computeTotalProgression()`: unit tests covering first chapter, last chapter, mid-chapter,
  href not found, empty segments, zero total weight.
- Manual verification in continuous mode: chapter map cursor moves as you scroll, time
  estimates update, switching between modes preserves the last known position.
- Paginated and vertical modes: regression check that estimates and rail are unchanged.
