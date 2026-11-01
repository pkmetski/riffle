---
name: reference-readium-search-decoration-positioning
description: "How Readium 3.3.0 positions search-highlight decorations, and why search highlights can land on the wrong word"
metadata: 
  node_type: memory
  type: reference
  originSessionId: a2028e96-90af-4605-8efd-8e2ae8f06666
---

Verified on device (Riffle harness, real Martian EPUB, emulator) while chasing the "search highlight on the wrong word" bug. Facts about Readium 3.3.0's decoration positioning (`readium-reflowable.js`, extractable from the navigator AAR `assets/readium/scripts/`):

- **Search-result locators carry NO cssSelector/domRange** — only `progression` + ~200 chars of `before`/`after` context (confirmed in Readium `StringSearchService.kt`). So a search decoration can't be anchored to a precise DOM node.
- **Decoration resolver `M(locator)`**: if `locator.text.highlight` is present, it roots a **fuzzy `TextQuoteAnchor`** at `cssSelector ? querySelector(...) : document.body` and matches the highlight term with up to `exact.length/2` errors, scoring candidates 50% term + 20% prefix + 20% suffix. For a short, repeated term (e.g. "Watney" ×224) over the whole body, this is the fragile path.
- **The box is a `<div data-style>` (`id=r2-decoration-<group>-<n>`) appended to `document.body`**, positioned from `range.getBoundingClientRect()` + scroll offset — **raw CSS px, NOT dpr-scaled** (the `V()` dpr-scaler is used for selection-rect reporting, not decoration layout). So dpr is NOT a decoration-misposition cause.
- `M` **re-resolves live on every `applyDecorations`**, but the box element is **created once** and not repositioned when the layout later reflows.

What was DISPROVEN as the cause (so don't chase these): stale-pre-reflow rect (it re-resolves live), dpr rect-scaling (raw rects), and foreign-chapter decoration leak (Readium DOES filter decorations by resource href).

What I PROVED on device: in a clean EPUB (and even at large font) search highlights are **pixel-exact** — every box sits on its term. readaloud stays aligned because it **re-applies its decoration every ~250ms audio tick**; search applies once per navigation.

**CONFIRMED root cause + fix (reproduced & measured on device, 2026-06-09):** the box is placed once at applyDecorations time; on a *navigated* result the landing page is still settling (go() + column-snap rAF loop ~1.2s + async typography reflow land AFTER the first apply), so the box sticks at the pre-settle position and the text shifts out from under it — a **constant ~152px vertical offset, same column & width** (DevTools: on-screen box top=179/217 vs same-column "watney" at 331/369). NOT fuzzy mis-resolution, NOT dpr.

**THE FIX that actually worked — clear+re-apply, not just re-apply.** Re-applying the IDENTICAL decoration list is a no-op in Readium's diff (it re-positions a box only when the set changes), so plain re-apply over a settle window did NOTHING (the box kept its pre-settle spot — this fooled me twice). You must `applyDecorations(emptyList(), group)` then `applyDecorations(list, group)` back-to-back (no visible gap — the same trick the readaloud group already uses) to force Readium to re-run its locator→range resolver against the settled DOM. Do this on a SPACED schedule (~400/1000/1700/2600ms) to cover the settle; do NOT tight-poll (a 100ms loop floods the still-navigating WebView and blanks the page, ADR 0015). readaloud is immune because it clears+re-applies every audio tick. Verified on device (real Martian over ABS, deep navigated results 11/224 & 23/224): box-to-"watney" vertical delta = **0px** vs ~152px before. Commit `e77d1bed`. (The earlier `pageLoadGeneration`/`reflowGeneration` re-key and the plain settle-window re-apply did NOT fix it.)

**Reproduction recipe (the headless harness CANNOT do this — drive the REAL app):** the instrumented Compose test stalls Readium page navigation headless (same reason `NavigationSnapHarnessTest.manualPageFlips` is `@Ignore`d), and mid-session `FormattingPreferencesStore.update` doesn't reflow the open reader. What worked: boot a **windowed** ephemeral AVD (omit `-no-window`), install the debug APK, bridge to ABS via [[reference_emulator_tailscale_bridge]] (the app's persisted server already pointed at `127.0.0.1:13378`), open The Martian, and drive search by `adb shell input tap` + `uiautomator dump` for coords + `exec-out screencap`. Inspect geometry live via WebView DevTools (`adb forward tcp:PORT localabstract:webview_devtools_remote_$(pidof)`, then CDP `Runtime.evaluate` with a stdlib WS client at `/tmp/cdp.py`): probe `[data-style] *` box rects + `caretRangeFromPoint` text vs a treewalk of the term. Gotcha: Readium pre-renders ~3 adjacent resources as separate DevTools page targets; re-fetch `/json` after each navigation (targets go stale) and pick the one whose boxes are on-screen.
