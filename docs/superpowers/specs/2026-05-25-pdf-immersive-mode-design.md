# PDF Immersive Mode

**Date:** 2026-05-25
**Status:** Approved

## Goal

Add immersive mode to the PDF reader so it behaves identically to the EPUB reader: system bars and the TopAppBar overlay are hidden when reading begins, toggled by a tap, and auto-hidden after page turns.

## Approach

Use `ImmersiveModeState` and `rememberImmersiveModeState()` directly in `PdfReaderScreen.kt` with no changes to `ImmersiveModeState.kt`. All changes are confined to the PDF reader screen.

## Layout

Replace the `Scaffold` in `PdfReaderScreen` with a `Box(Modifier.fillMaxSize())`. The `PdfNavigatorView` fills the entire box. The `TopAppBar` is rendered on top inside an `AnimatedVisibility` block — a floating overlay pattern identical to `EpubReaderScreen`. This avoids PDF view reflow when bars appear/disappear. `navigationBarsPadding()` is applied to the content; status bar padding is omitted because it is hidden in immersive mode.

## Tap Detection

`PdfiumNavigatorFragment` supports the same Readium `addInputListener` API as `EpubNavigatorFragment`. A tap listener is registered via `DisposableEffect` (mirroring the EPUB pattern) and calls `immersiveState::toggle` on unconsumed taps.

## Page-Change Wiring

In the existing `onPageChanged` callback in `PdfReaderScreen`, add `immersiveState.dismissOverlay()`. This auto-hides the overlay after a brief cooldown following a page turn, matching EPUB scroll behaviour.

## Entry / Exit

`rememberImmersiveModeState()` enters immersive mode immediately on composition and restores system bars on disposal — no special handling needed beyond calling the existing function.

## Scope

- `app/src/main/kotlin/com/riffle/app/feature/reader/PdfReaderScreen.kt` — all changes live here
- `ImmersiveModeState.kt` — no changes
- `PdfReaderViewModel.kt` — no changes
- No new files required
