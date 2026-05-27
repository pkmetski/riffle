# ADR 0015 — Immersive mode: full-screen content and selective bar restore on tap

**Status:** Accepted

## Context

In immersive mode the reader calls `controller.hide(WindowInsetsCompat.Type.systemBars())`, but on physical devices the status bar inset remains non-zero — its space is reserved and blank rather than reclaimed by the reader. Content starts below the status bar area instead of filling the full screen.

When the user taps to exit immersive mode, the natural fix is to restore all system bars. However, showing the Android navigation bar (bottom) changes the WebView's measured height, causing paginated EPUB content to reflow: pages are re-laid out and the current reading position can jump. The status bar (top) does not affect the WebView's measured height — with edge-to-edge enabled the content already starts at y=0 and the status bar merely overlays its top edge — so showing it is safe.

## Decision

Two changes together address the full problem:

1. **Immersive mode — fill the status bar area.** `ViewCompat.setOnApplyWindowInsetsListener` is set on each reader's `AndroidView` root (the `ScrollBoundaryNavigationContainer` in the EPUB reader; the `FragmentContainerView` in the PDF reader) returning `WindowInsetsCompat.CONSUMED`. This stops the native View inset-dispatch chain before it reaches Readium's fragments and WebViews, which on physical devices would otherwise apply the non-zero status-bar inset as top padding even after `controller.hide()`. Compose reads insets from the window holder — not from the View dispatch chain — so `navigationBarsPadding()` and `TopAppBarDefaults.windowInsets` are unaffected.

2. **Tap to exit immersive — restore status bar only.** `toggle()` calls `controller.show(WindowInsetsCompat.Type.statusBars())` when exiting immersive mode, restoring the status bar alongside the TopAppBar. The navigation bar is not restored — it remains hidden until the user performs an edge-swipe (Android `BEHAVIOR_DEFAULT` permanently restores all bars) or exits the reader (which calls `controller.show(systemBars())` via `DisposableEffect`).

## Consequences

- In immersive mode, reader content fills the full screen including the status bar area.
- In non-immersive mode (after tap), the status bar (clock, battery, signal) is visible alongside the TopAppBar.
- No WebView reflow occurs on tap — paginated EPUB position is stable.
- The navigation bar is never restored by a tap; only an edge-swipe or exiting the reader restores it.
- Auto-dismiss of the TopAppBar on page turns is disabled in non-immersive mode (`systemBarsHidden` is false once the status bar is shown). The user must tap explicitly to re-enter immersive.
