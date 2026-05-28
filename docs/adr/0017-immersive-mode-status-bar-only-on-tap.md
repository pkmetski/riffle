# ADR 0017 — Immersive mode: full-screen content and bar restore on tap without reflow

**Status:** Accepted (supersedes the original "status bar only" decision)

## Context

In immersive mode the reader calls `controller.hide(WindowInsetsCompat.Type.systemBars())`, but on physical devices the status bar inset remains non-zero — its space is reserved and blank rather than reclaimed by the reader. Content starts below the status bar area instead of filling the full screen.

When the user taps to exit immersive mode, the natural behavior is to restore all system bars. The original concern was that showing the Android navigation bar (bottom) changes the WebView's measured height when the content area is constrained by `navigationBarsPadding()`, causing paginated EPUB content to reflow and reading position to jump. An earlier version of this ADR worked around that by restoring only the status bar on tap, which left the nav bar hidden until an edge-swipe — a confusing and inconsistent state.

## Decision

Three changes together address the full problem:

1. **Immersive mode — fill the status bar area.** `ViewCompat.setOnApplyWindowInsetsListener` is set on each reader's `AndroidView` root (the `ScrollBoundaryNavigationContainer` in the EPUB reader; the `FragmentContainerView` in the PDF reader) returning `WindowInsetsCompat.CONSUMED`. This stops the native View inset-dispatch chain before it reaches Readium's fragments and WebViews, which on physical devices would otherwise apply the non-zero status-bar inset as top padding even after `controller.hide()`.

2. **Reader content is edge-to-edge — no `navigationBarsPadding`.** The reader's content `Box` no longer applies `navigationBarsPadding()`. The WebView (EPUB) / PDF view fills the full screen height in both immersive and non-immersive states. When the nav bar appears on tap-exit, it overlays the bottom of the reader content rather than resizing it — so no reflow occurs and the reading position is stable.

3. **Tap to exit immersive — restore both system bars.** `toggle()` calls `controller.show(WindowInsetsCompat.Type.systemBars())` when exiting immersive mode, restoring the status bar and the navigation bar alongside the floating `TopAppBar`. Because of change #2 this no longer reflows the WebView.

## Consequences

- In immersive mode, reader content fills the full screen including both system-bar areas.
- In non-immersive mode (after tap), both system bars are visible. The status bar (top) sits above the floating `TopAppBar`. The navigation bar (bottom) overlays the bottom edge of the reader content (including a sliver of the EPUB chapter rail when it is enabled — the rail is intentionally anchored to the absolute screen bottom rather than padded above the nav bar).
- No WebView/PDF-view reflow occurs on tap — paginated EPUB position is stable.
- Auto-dismiss of the `TopAppBar` on page turns is disabled in non-immersive mode (`systemBarsHidden` is cleared once the user taps to reveal). The user must tap explicitly to re-enter immersive.
- Compose still reads window insets from the window holder, so the floating `TopAppBar` (via `TopAppBarDefaults.windowInsets`) is positioned correctly below the status bar when visible.
