# Search highlight: reliable navigation & alignment

**Date:** 2026-06-09
**Status:** Approved — ready for implementation plan

## Problem

In-book search highlights are unreliable in two ways:

1. **Navigation doesn't land on the match.** Stepping Next/Prev (or the initial
   jump to result 1) often does not bring the reader to the page that holds the
   highlight.
2. **Misalignment.** When a hit is shown, the highlighted word frequently sits
   at a ragged page offset rather than cleanly on the page.

Readaloud highlights never have these problems: they always bring the narrated
sentence into view and land it flush on the column grid.

## Root cause

Search navigation calls `ColumnSnap.goAndSnap(fragment, locator)` with the
default `landAtStartWhenNoTarget = true` (`EpubReaderScreen.kt:1081`). Readium's
`SearchService` locators carry a `cssSelector` + text quote but **no href
`#fragment`**, so `navTargetFragmentId` returns null and the post-`go()` snap
runs `scrollLeft = 0` — yanking the reader to the **chapter top** and undoing
the within-chapter scroll `go()` had performed. The background position-sync
path already works around this by passing `landAtStartWhenNoTarget = false`
(`EpubReaderScreen.kt:1070`); search was never given the equivalent treatment.

The misalignment is the same defect seen visually: with the page resting at the
chapter top (or otherwise off the column grid) the highlighted word lands at an
arbitrary offset — half-clipped at a ragged page edge — instead of flush in a
column.

## Constraints (from the requester)

- **No dependency from search to readaloud.** They must not be coupled. Not
  every book has readaloud; readaloud is a proprietary (Storyteller) format, and
  another read-along format may appear later.
- Search must work fully on books with no readaloud.

## Design

### Dependency shape

Three independent highlight clients, one shared **format-neutral** primitive.
No client imports another.

```
        ┌─────────────────────────────────────────┐
        │  ColumnSnap (existing, format-neutral)    │
        │  • applyDecorations on an isolated group  │
        │  • bringTextIntoView(text): keep-visible  │  ← the shared rule
        └─────────────────────────────────────────┘
              ▲              ▲                 ▲
       readaloud         search          persisted highlights
   (text from           (text from       (paint only, no follow)
    Storyteller         Readium
    bundle)             SearchService)
```

The into-view primitive already lives in `ColumnSnap` and already takes a plain
`String` — it knows nothing about Storyteller, media overlays, or the readaloud
bundle. `ColumnSnap`'s own doc names it the single owner of column-snapping for
"TOC / search / resume / read-aloud follow". Search becomes a **peer** of
readaloud, sourcing its text from Readium's own search result
(`locator.text.highlight`, which already carries before/highlight/after context).

If a third read-along format appears, it is simply a third client of the same
neutral primitive.

### Into-view behavior: keep-visible (Option B / "Option 1")

It is enough that the matched word is **visible** on the page; it does not need
to be centered.

Reuse the existing readaloud follow primitive **unchanged**:

- Match already on the current page → no movement, **except** the existing
  drift-correction nudge that floors a visibly-off-grid page flush (a fix, not a
  jump).
- Match off the current page → floor `scrollLeft` to the matched word's column
  so it lands visible.

No new branch is added; multiple hits sharing a page do not produce disruptive
jumps because the primitive already no-ops when the matched column is the current
page.

### Changes

1. **`ColumnSnap`** — neutralize the readaloud-specific naming (pure rename, no
   behavior change):
   - `followNarratedSentence(fragment, text)` → `bringTextIntoView(fragment, text)`
   - internal `autoFollowSnapJs(text)` → `snapTextColumnJs(text)`
   - Readaloud's call site (`EpubReaderScreen.kt:1251`) updates to the new name.
   This makes "search calls a readaloud-named function" structurally impossible —
   it is now a neutral primitive both call.

2. **Search navigation** (`EpubReaderScreen` `searchNavigationEvents`, currently
   `:1074–1087`) becomes the structural twin of readaloud's auto-follow effect:
   - **Same chapter** (hit is in the loaded resource): skip `go()` entirely; call
     `ColumnSnap.bringTextIntoView(fragment, locator.text.highlight)`. Avoids
     `go(cssSelector)`'s flush-to-element sliver.
   - **Different chapter** (cover jump): `go(locator)` to load the chapter, then a
     follow effect keyed on `pageLoadGeneration` (and the current result) re-runs
     `bringTextIntoView` once the new chapter paints — exactly how readaloud
     re-snaps after a chapter load. The `scrollLeft = 0` chapter-top yank is gone.
   - `bringTextIntoView` returning `"off"` (text not yet painted) is not an error:
     the `pageLoadGeneration` re-key re-runs it after load, mirroring readaloud.

3. **Decoration painting is unchanged.** `searchResults` →
   `applyDecorations(group = "search")` (`EpubReaderScreen.kt:1113–1141`) already
   works; only the *into-view* step was broken.

## Caveat (verify, don't assume)

The expected drift is the **page**, not the decoration rect. If a residual
Readium decoration-positioning bug remains — the highlight box drawn offset from
the word even when the page is snapped flush — this change will not fix it. That
is treated as a separate follow-up. We will **not** claim the misalignment is
fixed without observing it on a device.

## Testing

- **JS unit tests** (harness script tests) for `snapTextColumnJs` already exist
  from the readaloud follow; they now cover the shared primitive directly. Add a
  search-flavored case: multiple hits on one page → no page move; a hit off-page
  → snaps flush to the matched word's column.
- **Device verification** (harness, paginated mode): search a frequent word, step
  Next across pages and across a chapter boundary; confirm each hit lands visible
  and the highlight sits on the word. This is where the rect-offset caveat above
  is checked.

## Out of scope

- Any change to readaloud's text source, the Storyteller bundle, or media-overlay
  handling.
- Persisted-highlight (annotations) rendering.
- Scroll/vertical-mode behavior beyond what the shared primitive already does
  (it centers in scroll mode; search inherits that unchanged).
