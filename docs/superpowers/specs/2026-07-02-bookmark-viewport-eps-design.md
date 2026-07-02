# Live viewport-fraction for bookmark eps

**Issue:** #399
**Status:** Design
**Date:** 2026-07-02

## Problem

`BookmarksController.bookmarkEpsFor` currently derives the ±progression window used by
`isCurrentPageBookmarked` from Readium's `positionsByReadingOrder()`:

```kotlin
if (positions > 0) return 0.5 / positions
```

Readium's positions are ~1024-char slices, not viewport-pages. In all three reader modes
(paginated / vertical / continuous) the true geometric bound for a viewport-midpoint locator
is `viewportSize / chapterSize / 2`. The current formula lights the indicator for the right
page on typical chapters but drifts for short chapters, wide viewports, or non-average
character density.

## Goal

Feed a live per-chapter `viewportFraction` (viewport size / chapter size) into
`bookmarkEpsFor` so the ±eps window is geometrically correct. Fall back to `0.5 / positions`
when the live measurement hasn't landed yet, and to the flat `BOOKMARK_PAGE_EPS` /
`BOOKMARK_VIEWPORT_EPS` when neither is available.

## Non-goals

- Changing bookmark storage / progression semantics.
- Changing anything in `isCurrentPageBookmarked`'s reactive shape beyond adding one flow.
- Reworking Readium's own positions computation.

## Constraints (from issue)

The prior attempt at this plumbing flaked `HighlightRepaintOrientationHarnessTest`. On-scroll
emission churn caused Compose recomposition pressure that prevented the chrome-reveal
`LaunchedEffect` from idling inside `waitUntil`. The fix must:

1. Emit **only** when viewport size or chapter size changes — never on scroll.
2. Apply `distinctUntilChanged` at the emission boundary.
3. Re-run `HighlightRepaintOrientationHarnessTest` before merge.

## Design

### The signal

Add a single new signal owned by `EpubReaderViewModel`:

```kotlin
val viewportFractionByHref: StateFlow<Map<String, Double>>
```

- Key: normalized chapter href (same normalization as `spinePositionCounts`).
- Value: `viewportSize / chapterSize` — the fraction of the chapter visible in one viewport.
- Missing entry means "not measured yet" → `bookmarkEpsFor` falls through to `positions`.

The VM's flow is a simple `MutableStateFlow<Map<String, Double>>` populated by each mode's
producer. Producers write via a single VM-level `putViewportFraction(href, fraction)` that
short-circuits when the incoming value equals the current stored value (built-in
distinct-until-changed on the per-entry level, so the map identity only changes when a value
actually changes).

### Producers per mode

**Continuous.** `ContinuousWindowController` already tracks `measuredHeights[i]` per window
slot and `port.viewportHeightPx`. Emit at three specific hooks — none of them scroll:

- After a chapter's height first measures (`measuredHeights[i]` transition from placeholder
  to a real px value — lines ~528 / ~535 in `ContinuousWindowController.kt`).
- After a remeasure (`measuredHeights[i]` changed to a different real value — line ~583).
- On viewport-height change (attach to `onSizeChanged` in `ContinuousReaderView` — rotation).

Value: `port.viewportHeightPx.toDouble() / measuredHeight`. When either side is 0 or the
chapter has no href yet, skip.

**Paginated.** A new `RendererCapability` (`ViewportFractionProbe`) whose install script runs
on `onPageLoaded` and re-runs after every reflow (typography change, orientation change,
readaloud reserve change). Measurement: `viewportWidth / totalScrollWidth`. Reports back
through a `RendererBridge.readViewportFraction()` typed call, or via a `@JavascriptInterface`
callback that pushes the value to the presenter, which forwards to the VM. Consistent with
the bridge's existing typed shape, a suspend `readViewportFraction(): Double?` invoked from
`ReaderPresenter.onPageLoaded` and again from the same site as `applyTypographyOverride` is
cleanest.

**Vertical.** Same `ViewportFractionProbe` capability. Measurement:
`viewportHeight / scrollHeight`. Called from the same hooks as paginated.

### Consumer

`BookmarksController` gains a fourth bound flow:

```kotlin
fun bind(
    serverId: String,
    itemId: String,
    currentLocator: StateFlow<Locator?>,
    spinePositionCounts: StateFlow<Pair<List<String>, List<Int>>>,
    viewportFractionByHref: StateFlow<Map<String, Double>>,
)
```

`isCurrentPageBookmarked`'s combine grows to 4 arguments. New priority in `bookmarkEpsFor`:

```kotlin
internal fun bookmarkEpsFor(
    orientation: ReaderOrientation,
    spineCounts: Pair<List<String>, List<Int>>,
    viewportFractionByHref: Map<String, Double>,
    chapterHref: String,
): Double {
    viewportFractionByHref[chapterHref]?.let { vf ->
        if (vf > 0.0) return vf / 2.0
    }
    val positions = /* existing lookup */
    if (positions > 0) return 0.5 / positions
    return if (orientation == ReaderOrientation.Continuous) BOOKMARK_VIEWPORT_EPS
    else BOOKMARK_PAGE_EPS
}
```

The public `bookmarkEpsFor(chapterHref)` (used by the toggle call site) reads the current
`.value` of the new flow so toggle and indicator stay in sync.

### Flake-avoidance discipline

- **No scroll emissions.** `ContinuousWindowController.onScrollChanged` and paginated
  column-position callbacks MUST NOT feed the fraction. Enforced by grep test + code review.
- **Distinct-until-changed.** The VM's `putViewportFraction` no-ops when the incoming value
  equals the stored one, so `viewportFractionByHref` emits a new map only on real change.
- **Scope discipline.** `isCurrentPageBookmarked` stays on `OrchestratorScope`, still
  `SharingStarted.Eagerly`. The added flow is one more argument to the same combine — no
  extra viewModelScope subscriptions.
- **Harness gate.** `HighlightRepaintOrientationHarnessTest` runs on the AVD before PR.

## Testing

New JVM tests in `BookmarksControllerTest` (or a companion pure-function test):

1. `live viewport fraction takes precedence over positions` — set fraction to 0.20, positions
   to 30. Expect eps == 0.10.
2. `live viewport fraction falls back to positions when unset` — fraction map empty. Expect
   eps == `0.5 / positions`.
3. `both fall back to flat eps when nothing measured` — expect `BOOKMARK_PAGE_EPS` /
   `BOOKMARK_VIEWPORT_EPS` per orientation.
4. `live viewport fraction is chapter-scoped` — set fraction for chapter A; asking for B
   falls back to positions.

Existing regression tests remain green (they exercise the fallback path).

Additionally: a `ContinuousWindowController` unit test asserting the fraction publishes on
measurement transitions but NOT on scroll ticks (uses a recording emitter fake).

Harness: `HighlightRepaintOrientationHarnessTest` on `Harness_Medium_Phone` after the wire
lands.

## Rollout

Single PR. `Closes #399`. Regression tests + one new pinning test + harness re-run.
