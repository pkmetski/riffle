# ADR 0014 — Scroll-mode chapter boundary navigation via gesture interception

**Status:** Accepted

## Context

In vertical (continuous scroll) reading orientation, each EPUB spine item is rendered as a separate scrollable WebView inside a horizontal `ViewPager` managed by `EpubNavigatorFragment`. Scrolling within a chapter works vertically, but advancing to the next or previous chapter requires swiping the `ViewPager` horizontally — a gesture that is inconsistent with the scroll orientation the user chose.

The natural fix is to detect when the user performs a downward fling at the end of a chapter (or an upward fling at the beginning) and programmatically advance the navigator via `OverflowableNavigator.goForward(false)` or a constructed backward `Locator`.

`EpubNavigatorFragment` implements `OverflowableNavigator` and exposes `goForward(animated: Boolean)` and `goBackward(animated: Boolean)`. However, the public listener chain — `EpubNavigatorFragment.Listener → OverflowableNavigator.Listener → VisualNavigator.Listener` — contains no callback for overscroll or chapter-boundary overflow events. There is no SDK extension point that fires when the user tries to scroll past the end of a spine item.

The `R2BasicWebView` inside the fragment does have an internal `OnOverScrolledCallback`, but it is not exposed publicly.

## Decision

Wrap the `FragmentContainerView` that hosts `EpubNavigatorFragment` in a thin custom `FrameLayout` (`ScrollBoundaryNavigationContainer`). This container overrides `dispatchTouchEvent` to feed all touch events — without consuming them — to a `GestureDetector`. The WebView receives every event unchanged. When the `GestureDetector` fires `onFling` and the following conditions all hold, the container triggers cross-chapter navigation:

- Reading orientation is `ReaderOrientation.Vertical` (scroll mode)
- Fling direction is downward and `currentProgression ≥ 1.0f` → `fragment.goForward(false)`
- Fling direction is upward and `currentProgression ≤ 0.0f` → construct a `Locator` at `progression = 1.0` for the previous spine item and call `fragment.go(locator)`, landing at the bottom of the previous chapter

The backward case uses a constructed `Locator` rather than `goBackward()` because `goBackward()` positions at the start of the previous resource, which is disorienting when the user was at the beginning of the current chapter and swiped up.

## Alternatives considered

**`OverflowableNavigator.Listener` overflow callback** — does not exist in Readium 3.0.0. The listener hierarchy only contains `shouldJumpToLink` (deprecated).

**Compose `pointerInput` overlay** — a transparent `Box` with `pointerInput` placed above the `AndroidView`. Rejected for the same reason as ADR 0006: it intercepts touches before the WebView, requiring fragile manual pass-through logic to avoid breaking text selection, link taps, and scroll.

**`NestedScrollingParent` wrapper** — relies on the WebView implementing `NestedScrollingChild` and propagating unconsumed scroll deltas upward. Android's `WebView` does not implement `NestedScrollingChild`, so no unconsumed deltas are emitted.

**Monitoring `currentLocator.locations.progression` alone** — can detect arrival at a boundary but cannot detect user intent to scroll further; auto-advancing on `progression == 1.0` would navigate while the user is still reading the last line.

## Consequences

- `dispatchTouchEvent` observation is non-consuming: the WebView's scroll, text selection, link navigation, and tap-to-toggle-immersive-mode all continue to work unchanged.
- The container must be kept up to date with `isScrollMode` and `currentProgression` from the composable layer; stale state could trigger spurious navigation or suppress valid gestures.
- Forward navigation lands at the top of the next chapter (Readium default for `goForward`). Backward navigation lands at the bottom of the previous chapter (constructed `Locator`). This asymmetry is intentional and produces a consistent directional mental model for the user.
- Only applies when `orientation == ReaderOrientation.Vertical`; paginated mode is unaffected.
