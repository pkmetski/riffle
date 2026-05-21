# ADR 0006 — Detect Immersive Mode Taps via Readium Navigator Listener

**Status:** Accepted

## Context

Immersive Mode is toggled by tapping the reading content area. Both the EPUB and PDF readers render their content inside a Readium navigator fragment hosted in an `AndroidView`. There are two ways to detect the tap:

1. **Readium `VisualNavigator.Listener.onTap`** — implement the navigator's own listener interface; the fragment calls `onTap` only for taps it does not consume itself (e.g. page turns, text selection, link navigation).
2. **Compose transparent overlay** — place a full-screen transparent `Box` with a `pointerInput` tap detector on top of the `AndroidView` and forward unconsumed events to the fragment.

## Decision

Use the Readium `VisualNavigator.Listener` interface (`onTap` callback) to detect taps for toggling Immersive Mode.

## Consequences

- Taps consumed by the navigator for page turns, text selection, and link navigation do not fire `onTap`, so they do not accidentally toggle immersive mode.
- A Compose overlay would intercept all touches before the fragment receives them, requiring manual hit-testing and pass-through logic that is fragile across Readium versions.
- Both `EpubNavigatorFragment` and `PdfiumNavigatorFragment` implement `VisualNavigator`, so the same listener interface works for both formats without format-specific branching.
