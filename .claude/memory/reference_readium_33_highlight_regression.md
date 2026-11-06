---
name: reference-readium-33-highlight-regression
description: Readium 3.2.0+ mispositions decorations ONLY in multi-column; now back on 3.3.0 (latest) with the single-column pin + text-anchored highlight ([[reference_readaloud_highlight_text_anchored]])
metadata: 
  node_type: memory
  type: reference
  originSessionId: 70e80f6c-ef7b-40d9-839c-4c495a0bbaf3
---

Readium **3.3.0** regresses the readaloud synced highlight on reflowable books (e.g. The Martian). 3.3 changed the reflowable default so a phone-width viewport renders **TWO columns**, and its decoration renderer **mispositions the highlight in a multi-column layout** — the decoration is applied to the DOM (`r2-decoration-*` elements exist) but isn't visibly rendered. The audio→sentence mapping and `applyDecorations` are both correct; only the visible multi-column render breaks.

Verified on API 25: 3.0.0 single-column → highlight shows; 3.3.0 two-column → no highlight; 3.3.0 **forced** single-column → highlight shows; 3.0.0 **forced** two-column → highlight shows (so 3.0.0's decorator handles multi-column, 3.3.0's doesn't). This also made "Play from here" *look* broken (no visible cue), though resolution was correct.

The regression actually entered in **3.2.0** (its changelog: "new experimental positioning of EPUB decorations that places highlights behind text"); 3.3.0 is only deprecations + bug fixes. The 3.0.0→3.3.0 bump crossed it. No public bug report found.

**UPDATE (now on 3.3.0):** the "highlight breaks on 3.3.0" framing was incomplete. The *root* cause of the vanished highlight was the reader rendering the ABS ebook whose media-overlay spans Readium strips — fixed independently by anchoring the highlight to sentence TEXT ([[reference_readaloud_highlight_text_anchored]]). With that fix the highlight is engine-independent, so we bumped back to **3.3.0 (latest)** and kept the **single-column** reflowable pin (`ColCount.ONE` in `FormattingPreferencesMapper`). The 3.2.0+ multi-column decoration misposition is still real, so it's avoided by staying single-column; landscape double-page (intentional two-column) remains a known edge case where the highlight may be off. Layout API is the 3.3.0 form (`metadata.layout` / `Layout`).

**Guard:** `ReadiumVersionPinTest` now asserts `BuildConfig.READIUM_VERSION == "3.3.0"`; on a future bump it goes red until a human re-verifies (highlight renders + single column) and raises the pin. No faithful automated *render* test is viable (bare WebView can't reproduce Readium's serving/stripping; full-navigator render SIGSEGVs on API 25) — but `ReadaloudLocatorTest` + `ReadaloudTextQuotesTest` guard the text-anchoring contract. See [[reference-api25-avd-boot-incantation]] for the WebView/DevTools trace.
