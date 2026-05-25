# Volume Key Scroll Animation & Chapter Transition Design

## Summary

Improve the volume key scroll experience in continuous (vertical) scroll mode with:
1. Animated smooth scroll instead of instant jump
2. Sticky chapter-boundary detection — press again at the boundary to advance chapters
3. Volume key path routed through `ScrollBoundaryNavigationContainer`

## Previous Behaviour

In `EpubReaderScreen.kt`, volume key events in scroll mode called:

```js
window.scrollBy(0, ±window.innerHeight * 0.8)
```

This was an instant jump with no animation and no awareness of chapter boundaries.
The `ScrollBoundaryNavigationContainer` already owned `onNavigateForward`/`onNavigateBackward`,
but the volume key path bypassed it entirely.

Fling-based chapter navigation also existed via `GestureDetector` / `handleFling`, which
triggered on fast upward/downward swipes at chapter boundaries (75%/25% thresholds).

## Implemented Behaviour

**Volume Down (forward):**
- JS queries whether the WebView is at the bottom: `window.scrollY + window.innerHeight >= document.body.scrollHeight - 4`
- If at bottom (`atBoundary = true`): navigate to next chapter
- Otherwise: smooth-scroll down ~80% of viewport height; WebView stops naturally at chapter end

**Volume Up (backward):**
- JS queries whether the WebView is at the top: `window.scrollY <= 4`
- If at top (`atBoundary = true`): navigate to previous chapter
- Otherwise: smooth-scroll up ~80% of viewport height

**Stickiness:** After a smooth scroll reaches the chapter boundary, the WebView can't scroll further.
The next key press detects `atBoundary = true` via JS and navigates. No polling or timers needed.

**Fling navigation removed:** Fling-based chapter navigation (`handleFling`, `GestureDetector`)
was removed. Chapter boundaries are now crossed only by deliberate drag (drag-past-boundary) or
volume key presses. This prevents accidental chapter skips from fast scrolling gestures.

**Drag threshold made density-independent:** `DRAG_THRESHOLD_PX = 80f` (fixed pixels) was
replaced with `DRAG_THRESHOLD_DP = 120f` converted to pixels at construction time, so the
required pull distance is consistent across screen densities.

## Architecture

### `ScrollBoundaryNavigationContainer` — new method

```kotlin
internal fun handleVolumeScroll(forward: Boolean, atBoundary: Boolean, evaluateJs: (String) -> Unit) {
    if (!isScrollMode) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastVolumeNavMs < VOLUME_NAV_COOLDOWN_MS) return
    if (forward) {
        if (atBoundary) {
            lastVolumeNavMs = now
            onNavigateForward?.invoke()
        } else {
            evaluateJs("window.scrollBy({top: window.innerHeight * 0.8, behavior: 'smooth'})")
        }
    } else {
        if (atBoundary) {
            lastVolumeNavMs = now
            onNavigateBackward?.invoke()
        } else {
            evaluateJs("window.scrollBy({top: -(window.innerHeight * 0.8), behavior: 'smooth'})")
        }
    }
}
```

Uses a separate `lastVolumeNavMs` / `VOLUME_NAV_COOLDOWN_MS = 300L` so volume key cooldown
is independent of the drag navigation cooldown (`NAVIGATION_COOLDOWN_MS = 1500L`). This
prevents a preceding drag or chapter transition from swallowing an intentional button press.

The `atBoundary` value is computed by the caller via JS before calling this method, so it
reflects the WebView's actual scroll position rather than Readium's `currentProgression` value
(which can lag behind by up to one animation frame).

### `EpubReaderScreen.kt` — wire up container ref

A `containerRef` (analogous to `fragmentRef`) captures the `ScrollBoundaryNavigationContainer`
from the `AndroidView` factory via `.also { containerRef.value = it }`.

In `LaunchedEffect(volumeNavEvents)`, the vertical scroll branch queries JS for boundary state
then delegates to `container.handleVolumeScroll`:

```kotlin
VolumeNavEvent.Forward -> {
    val atBottom = fragment.evaluateJavascript(
        "(window.scrollY + window.innerHeight >= document.body.scrollHeight - 4).toString()"
    )?.trim('"') == "true"
    container.handleVolumeScroll(forward = true, atBoundary = atBottom) { js ->
        launch { fragment.evaluateJavascript(js) }
    }
}
```

## What Does Not Change

- Drag-past-boundary detection in `dispatchTouchEvent`
- Paginated (horizontal) mode volume key behaviour (`fragment.goForward/goBackward`)
- Chapter navigation callbacks (`onNavigateForward`, `onNavigateBackward`) in `EpubReaderScreen`

## Testing

`ScrollBoundaryNavigationContainerTest` covers `handleVolumeScroll`:
- Mid-chapter forward → JS smooth scroll fired, no navigation
- Mid-chapter backward → JS smooth scroll fired, no navigation
- `atBoundary = true` forward → `onNavigateForward` invoked
- `atBoundary = true` backward → `onNavigateBackward` invoked
- Rapid presses within `VOLUME_NAV_COOLDOWN_MS` → second press is a no-op
- After cooldown, next press scrolls (doesn't re-navigate)
- `isScrollMode = false` → no-op
