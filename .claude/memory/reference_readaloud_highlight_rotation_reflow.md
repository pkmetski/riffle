---
name: reference_readaloud_highlight_rotation_reflow
description: "Why the readaloud highlight landed on the wrong sentence after a device rotation, and the pageLoadGeneration fix"
metadata: 
  node_type: memory
  type: reference
  originSessionId: e538a5ba-9c9f-47ff-94dd-cb87cce813db
---

Rotating the device while readaloud was active left the synced highlight on the **wrong sentence** (shifted a line, or scattered across a different paragraph). Distinct from the text-anchored-locator issue ([[reference_readaloud_highlight_text_anchored]]).

**Root cause (proven on the API-25 AVD with tagged logs across a rotation):** `MainActivity` has no `android:configChanges`, so a rotation **recreates the Activity and the whole Compose tree**. In `EpubReaderScreen`/`EpubNavigatorView`:
- `rememberReflowReapplyGeneration(formattingPrefs)` resets to 0 and its first-composition skip means it **never bumps on rotation** (and rotation doesn't change `formattingPrefs` anyway). It was built for *in-place* reflow (font/margins via `submitPreferences`, which fires no page load), not recreation.
- The freshly-created `EpubNavigatorFragment` applies its initial readaloud decoration **before `onPageLoaded` fires** (log order: CREATED → readaloud APPLIED → onPageLoaded ×3), so the decoration is positioned against an unsettled layout.
- The readaloud decoration `LaunchedEffect` was keyed on `(activeFragmentRef, reflowGeneration, sentenceQuotes)` — none change after the page settles → **no re-apply**. During playback it self-heals on the next sentence (~2 s glitch); when **paused** it stays wrong permanently.

**Fix part 1 — repositioning (EpubReaderScreen.kt):** added `val pageLoadGeneration = remember { mutableStateOf(0) }`, bumped in `paginationListener.onPageLoaded()`, and added `pageLoadGeneration.value` to the keys of the readaloud-highlight and persisted-highlight decoration effects. `onPageLoaded` is the precise "layout settled" signal, so the decorations re-apply after the rotated page actually loads.

**Fix part 2 — accumulation (the part-1 follow-up bug).** After part 1, on rotation *during playback* the PREVIOUS sentence stayed lit while the current played: decoration elements **accumulated**. Cause: after the fragment is recreated, Readium's decoration controller for the new WebView no longer removes the prior element on `applyDecorations([new])` (its `update()` = remove-by-id + add desyncs / leaves orphan elements), so each sentence adds a rect without dropping the last. Proven with a WebView DOM probe (counted decoration rects by y-band: buggy build showed disjoint clusters = stale + current; the in-place font reflow never accumulated). Readium's reflowable JS decoration group has a `clear()` that removes the **whole container element** (`n.remove()`), so the fix is **clear-then-apply**: call `applyDecorations(emptyList(), "readaloud")` immediately before `applyDecorations([deco], "readaloud")`. The two run back-to-back on the main thread → exactly one highlight, no flicker.

Orientation question (asked explicitly): accumulation is specific to fragment **recreation** (device rotation always recreates; the Scroll↔Paginated *setting* recreates only when it toggles double-page in landscape). The in-place reflows (font/margins, and Scroll↔Paginated in portrait) reflow cleanly and never accumulated. clear-then-apply is trigger-agnostic so it covers all of them.

No clean regression-test seam: `OrientationChangeTest` only asserts the screen survives `recreate()` and is `@SdkSuppress`-ed on API 25 (WebView SIGSEGV on recreate); positioning/accumulation need a full Readium+WebView+Storyteller-track harness with DOM inspection. Verified manually on device with a DOM y-band probe: device rotation (both directions) + Scroll↔Paginated setting, single clustered highlight across many advances.
