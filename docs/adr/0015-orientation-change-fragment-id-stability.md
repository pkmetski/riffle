# ADR 0015 — Fragment container ID stability for orientation change support

**Status:** Accepted

## Context

Riffle supports both portrait and landscape orientations in the reader. The OS controls orientation — the user can rotate the device at any time and the Activity recreates via the standard Android configuration-change lifecycle.

Both `EpubReaderScreen` and `PdfReaderScreen` embed a Readium navigator fragment (`EpubNavigatorFragment` / `PdfiumNavigatorFragment`) inside a Compose `AndroidView`. The `AndroidView`'s `factory` creates a `FragmentContainerView` whose ID is assigned via `View.generateViewId()`. On every Activity recreation (including rotation), Compose tears down and rebuilds the composition, calling `factory` again. `View.generateViewId()` produces a different ID each time.

This causes two problems:

1. `supportFragmentManager.findFragmentById(newId)` returns `null`, so the `update` callback adds a second navigator fragment while the original one (restored by the FragmentManager from its saved state) is orphaned — its container no longer exists.
2. The `currentLocator` collection coroutine is tied to the old Activity's `lifecycleScope` and is cancelled when the Activity is destroyed; it must be re-launched on the new Activity's scope against the recovered fragment.

The result is that the reader either loses its content view (blank screen) or crashes and navigates back to the Library Item Detail Screen on rotation.

## Decision

Stabilise the `FragmentContainerView` ID with `rememberSaveable`:

```kotlin
val containerId = rememberSaveable { View.generateViewId() }
```

`rememberSaveable` persists the generated ID in the saved-state bundle across Activity recreation. After rotation, `findFragmentById(containerId)` finds the FragmentManager-restored fragment. The `AndroidView` `update` callback gains an `else` branch: when a fragment is already present, reconnect `fragmentRef` to it, re-attach the input listener, and re-launch the `currentLocator` collection on the new Activity's `lifecycleScope`.

Immersive mode state is preserved across rotation via `rememberSaveable` as well. The `LaunchedEffect` that enters immersive mode on reader open only calls `state.hide()` when the saved value is `null` (first open) or `true` (was already immersive); if the user had revealed chrome before rotating, chrome stays visible after rotation.

## Alternatives considered

**`android:configChanges="orientation|screenSize|keyboardHidden"` on `MainActivity`** — prevents Activity recreation entirely, making Fragment ID stability moot. Rejected because it hands orientation-change handling to the app rather than the OS. Since the entire UI is Jetpack Compose, Compose recomposes naturally on window-size changes; however, suppressing recreation app-wide is a global override that future non-Compose surfaces (dialogs, system UI interactions) might not respect correctly. The OS-controlled recreation model is the Android-intended path.

**Stable resource ID via `ids.xml`** — define `@+id/epub_reader_fragment_container` and use it directly. Semantically equivalent to `rememberSaveable { View.generateViewId() }` but requires a new resource file and a hardcoded ID that cannot be reused if two reader instances ever exist simultaneously. `rememberSaveable` scopes the ID to the composable instance naturally.

**Fragment reconnect (rejected after device testing)** — The initial plan added an `else` branch that, when the FM already had a restored fragment, would reconnect `fragmentRef` to it without recreating. In practice, the FM restores the Readium navigator fragment using the DEFAULT `FragmentFactory` (before any Compose code runs), which cannot initialise the fragment's WebView streaming session. The reconnected fragment's WebView stays on an indefinite loading spinner. The rejected approach is replaced by proactively removing the FM-restored fragment before the creation path, so the factory-backed creation always runs.

## Consequences

- Device rotation preserves reading position in both EPUB and PDF readers. After rotation, the fragment is always recreated (brief WebView reload) but lands at `latestLocator()` rather than the DB-stored initial position.
- The `AndroidView` `update` callback has a single creation path: if `fragmentRef.value == null`, remove any FM-restored stale fragment, then create fresh with the factory. No reconnect branch.
- Immersive mode state survives rotation: on → stays on, off → stays off. Closing and reopening a book always starts in immersive mode (unchanged).
- `showFormattingPanel` (a plain `remember` in `EpubReaderScreen`) resets to `false` on rotation; this inconsistency with `tocVisible` (which survives in the ViewModel) is accepted as a low-impact papercut.
