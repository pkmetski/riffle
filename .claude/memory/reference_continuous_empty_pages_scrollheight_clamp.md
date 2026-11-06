---
name: reference_continuous_empty_pages_scrollheight_clamp
description: "Continuous-mode \"empty pages\" root cause = documentElement.scrollHeight viewport-clamping for short resources, NOT a GPU texture limit"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 83b90ce5-90d2-4d2e-9d04-931543631959
---

Continuous (endless-scroll) reader "empty pages" / large blank gaps between chapters = the height-measurement JS used `documentElement.scrollHeight`, which the spec clamps to `max(content, viewport)`. A resource shorter than the viewport (chapter-title divider page `partNNNN_split_000.html` with only a heading, ~146px content) measured the full viewport (~780px) → its ChapterWebView sized to 780px → ~580px blank tail after it in the stack.

Fix (branch pkmetski/endless-scroll-chapters, commit 96f9a544): in `ContinuousStyleInjector.HEIGHT_MEASUREMENT_JS` `currentHeight()`, measure true content extent = `body.getBoundingClientRect().bottom + pageYOffset + :root paddingBottom`, maxed with `de.offsetHeight`/`b.offsetHeight`/`b.scrollHeight` (none viewport-clamped). Drop `de.scrollHeight`. Tall chapters unaffected (content > viewport → old max was already right).

Verify via WebView DevTools `Runtime.evaluate`: compare `documentElement.scrollHeight` (clamped 780) vs `body.getBoundingClientRect().bottom` (real 146) vs `de.offsetHeight` (correct 201, includes root padding). After fix, `art0027_split_000` measured 202 not 780. KEY DIAGNOSTIC: docSH==innerH but lastChildBottom << docSH means viewport-clamp over-measure, not a paint gap.

SECOND, SEPARATE blank-pages cause (survives the measurement fix), CONFIRMED on `-gpu host` AVD via logcat: `E chromium: [ERROR:tile_manager.cc(826)] WARNING: tile memory limits exceeded, some content may not draw`. Fires ~55×/fast-fling-burst, 0× when still/slow. It is Chromium's TILE (raster) MEMORY budget — shared across the single WebView renderer — NOT a hardware max-texture-SIZE limit (that earlier framing was wrong). Each ChapterWebView is sized to its FULL content height; the un-split mega-chapters (Martian part0020=18235px, part0021=21328px) make one visible WebView's raster interest area huge, so fast-fling tile churn exceeds the budget → dropped tiles → blank (persists longer on slower device GPUs; the fast Mac GPU re-rasters within a frame so the blank is usually invisible in screenshots even while the warning fires). Flinging WITHIN one mega-chapter alone trips it → fix must bound PER-WEBVIEW raster height (slice tall chapters into height-capped stacked WebViews of the same resource, internally scroll-offset), not just shrink WINDOW_SIZE. AVD acceptance test for any fix = tile-exceeded warning count drops to ~0 during a hard fling burst. See [[reference_api25_avd_boot_incantation]].
