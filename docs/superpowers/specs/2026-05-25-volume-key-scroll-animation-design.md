# Volume Key Scroll Animation & Chapter Transition Design

## Summary

Improve the volume key scroll experience in continuous (vertical) scroll mode with:
1. Animated smooth scroll instead of instant jump
2. Sticky chapter-boundary detection — press again at the boundary to advance chapters
3. Unified boundary state shared with existing fling/drag logic

## Current Behaviour

In `EpubReaderScreen.kt` (lines 314–315), volume key events in scroll mode call:

```js
window.scrollBy(0, ±window.innerHeight * 0.8)
```

This is an instant jump with no animation and no awareness of chapter boundaries.
The `ScrollBoundaryNavigationContainer` already tracks `currentProgression` and owns
`onNavigateForward`/`onNavigateBackward`, but the volume key path bypasses it entirely.

## Desired Behaviour

**Volume Down (forward):**
- If `currentProgression >= VOLUME_FORWARD_THRESHOLD`: navigate to next chapter
- Otherwise: smooth-scroll down ~80% of viewport height; WebView stops naturally at chapter end

**Volume Up (backward):**
- If `currentProgression <= VOLUME_BACKWARD_THRESHOLD`: navigate to previous chapter
- Otherwise: smooth-scroll up ~80% of viewport height

**Stickiness:** after a smooth scroll reaches the chapter boundary, `currentProgression` sits at
≈ 1.0 (or ≈ 0.0). The next key press detects the boundary and navigates. No polling or timers needed.

Fling and drag-past-boundary behaviour are **unchanged**.

## Architecture

### `ScrollBoundaryNavigationContainer` — new method

```kotlin
fun handleVolumeScroll(forward: Boolean, evaluateJs: (String) -> Unit) {
    if (!isScrollMode) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastNavigationMs < NAVIGATION_COOLDOWN_MS) return

    if (forward) {
        if (currentProgression >= VOLUME_FORWARD_THRESHOLD) {
            lastNavigationMs = now
            onNavigateForward?.invoke()
        } else {
            evaluateJs("window.scrollBy({top: window.innerHeight * 0.8, behavior: 'smooth'})")
        }
    } else {
        if (currentProgression <= VOLUME_BACKWARD_THRESHOLD) {
            lastNavigationMs = now
            onNavigateBackward?.invoke()
        } else {
            evaluateJs("window.scrollBy({top: -(window.innerHeight * 0.8), behavior: 'smooth'})")
        }
    }
}
```

Shared state reused from fling: `currentProgression`, `lastNavigationMs`,
`NAVIGATION_COOLDOWN_MS`, `onNavigateForward`, `onNavigateBackward`.

New constants (added to companion object):
- `VOLUME_FORWARD_THRESHOLD = 0.98f`
- `VOLUME_BACKWARD_THRESHOLD = 0.02f`

Tight thresholds ensure only a true end-of-chapter triggers navigation, not mid-chapter scrolls.

### `EpubReaderScreen.kt` — wire up container ref

Add a `containerRef` (analogous to `fragmentRef`) that captures the `ScrollBoundaryNavigationContainer`
from the `AndroidView` factory.

Replace the vertical scroll branch in `LaunchedEffect(volumeNavEvents)`:

```kotlin
// Before
VolumeNavEvent.Forward -> fragment.evaluateJavascript("window.scrollBy(...)")

// After
VolumeNavEvent.Forward -> container.handleVolumeScroll(true, fragment::evaluateJavascript)
VolumeNavEvent.Backward -> container.handleVolumeScroll(false, fragment::evaluateJavascript)
```

## What Does Not Change

- Fling detection (`handleFling`) and its thresholds (`FLING_FORWARD_THRESHOLD = 0.75f`)
- Drag-past-boundary detection in `dispatchTouchEvent`
- Paginated (horizontal) mode volume key behaviour (`fragment.goForward/goBackward`)
- Chapter navigation callbacks (`onNavigateForward`, `onNavigateBackward`) in `EpubReaderScreen`

## Testing

Update `ScrollBoundaryNavigationContainerTest` with new unit tests for `handleVolumeScroll`:
- Mid-chapter forward → JS smooth scroll fired, no navigation
- Mid-chapter backward → JS smooth scroll fired, no navigation
- At forward boundary (`progression = 0.99f`) → `onNavigateForward` invoked
- At backward boundary (`progression = 0.01f`) → `onNavigateBackward` invoked
- Rapid presses within cooldown → second press is a no-op
- Called when `isScrollMode = false` → no-op
