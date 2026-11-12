---
name: reference-readiumcss-night-nukes-background
description: Anything painted via background-color inside reader WebViews is erased on Dark/DarkDim/Sepia unless declared INLINE with !important — note-glyph invisibility root cause
metadata: 
  node_type: memory
  type: reference
  originSessionId: 960ee867-93ad-4e5e-af6d-7aae3df688c8
  modified: 2026-08-14T08:53:30.454Z
---

ReadiumCSS-after.css night and sepia modes apply `background-color: transparent !important` to every `:not(a)` element (`:root[style*=readium-night-on] :not(a)`, specificity (0,2,1)). This applies in ALL THREE reader modes — continuous mode links the same ReadiumCSS-after.css and sets `readium-night-on` for Dark/DarkDim.

**Consequence:** any reader-content element that paints via `background-color` (including CSS-mask icons, which composite the mask over the background) renders fully transparent on Dark/DarkDim/Sepia unless the declaration is **inline on the element with `!important`**. A stylesheet rule with `!important` still loses (class selector (0,1,0) < (0,2,1)); only inline importance beats it.

Known victims:
- Highlight tint — fixed in PR #383 via `HIGHLIGHT_INLINE_STYLE_SUFFIX` (HighlightDecoration.kt).
- Note glyph (mask over `currentColor` background) — invisible on dark themes in all modes; fixed 2026-08-14 by inlining `background-color:currentColor !important` in `NOTE_GLYPH_ELEMENT_HTML` and the continuous `makeGlyph` cssText. This was the real cause behind the "note glyph not rendered" reports that PR #646 (ordering) and #668 (clamping) failed to fix — those repros/harness runs all used Light theme.

**How to apply:** when adding any new decoration/injected element that paints via background, use inline style + `!important`, and give `NoteGlyphRenderHarnessTest`-style coverage a Dark-theme variant (its `evalGlyphVisible` checks computed `background-color != rgba(0,0,0,0)` — that check only bites if a test actually runs under Dark). DevTools computed-style inspection (`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`) shows the nuked value instantly.
