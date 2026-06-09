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

## Why NOT text-anchoring (a flaw caught during implementation)

The first design draft proposed bringing the hit into view by **text-anchoring
on `locator.text.highlight`**, the way readaloud follows a narrated sentence.
This is wrong for search:

- Readaloud's follow primitive (`autoFollowSnapJs`) locates text by walking the
  DOM and matching the **first** occurrence of a 12-char prefix in document
  order. That is unambiguous for a long, near-unique sentence.
- A search term is short and **repeats** — the reporting screenshot shows
  "watney", result **4 of 224**. Text-anchoring on "watney" would always snap to
  the *first* "watney" in the chapter, never the 4th hit the user navigated to.
- Readaloud is *forced* to text-anchor because Readium strips its sentence spans.
  **Search is not**: Readium's `SearchService` gives each result locator an
  **occurrence-specific `progression`** (position within the resource). Text-
  anchoring would throw that precision away and regress to the wrong occurrence.

So search must use its locator's precise position, not a text search.

## Design (Option X — minimal, occurrence-correct)

`go(locator)` already scrolls to the **correct occurrence** via the locator's
progression. The only bug is the snap that runs afterwards.

Search currently calls `ColumnSnap.goAndSnap(fragment, locator)` with the default
`landAtStartWhenNoTarget = true`. Because search locators have no `#fragment`,
the post-`go()` snap runs `scrollLeft = 0` and yanks to the chapter top, undoing
`go()`'s correct within-chapter scroll.

**The fix:** pass `landAtStartWhenNoTarget = false` — the *exact route* the
resume/peer background-sync already uses (`EpubReaderScreen.kt:1070`). With no
DOM target it rounds the page `go()` landed on to the column grid (flush — fixes
the misalignment) instead of yanking to the chapter top (fixes the navigation).
It is occurrence-correct because `go()` used the progression.

```
        ┌──────────────────────────────────────────────┐
        │  ColumnSnap.goAndSnap(locator, landAtStart…)   │  ← the shared nav route
        └──────────────────────────────────────────────┘
              ▲              ▲                 ▲
            TOC          resume / peer       search
        (=true,         (=false, round    (NOW =false, round
         chapter top)    to grid)          to grid)
```

This is the real consolidation: search joins the **existing shared `goAndSnap`
nav route** alongside TOC/resume/peer. It introduces **no coupling to readaloud**
and **no new primitive** — the opposite of the discarded text-anchor draft.

### Into-view behavior: keep-visible (Option B / "Option 1")

It is enough that the matched word is **visible** on the page; it need not be
centered. `go(progression)` lands on the occurrence's page (Readium snaps to its
own page boundary); the no-target round-to-grid then floors that to the column
grid — a no-op when already flush, the Option-1 drift-correction when resting a
few px off-grid. Hits already on the current page do not produce a disruptive
jump.

### Changes

1. **Search navigation** (`EpubReaderScreen` `searchNavigationEvents`,
   `:1074–1087`): change the single call
   `ColumnSnap.goAndSnap(fragment, locator)` →
   `ColumnSnap.goAndSnap(fragment, locator, landAtStartWhenNoTarget = false)`.
   Nothing else in the effect changes (cover detection, cover delay unchanged).

2. **Decoration painting is unchanged.** `searchResults` →
   `applyDecorations(group = "search")` (`EpubReaderScreen.kt:1113–1141`) already
   works; only the *into-view* step was broken.

No rename, no new `ColumnSnap` primitive, no readaloud changes.

## Caveat (verify, don't assume)

The expected drift is the **page**, not the decoration rect. If a residual
Readium decoration-positioning bug remains — the highlight box drawn offset from
the word even when the page is snapped flush — this change will not fix it. That
is treated as a separate follow-up. We will **not** claim the misalignment is
fixed without observing it on a device.

Cross-chapter reflow tracking is whatever resume/peer already get from this route
(the no-target branch rounds the current scroll to the grid through the reflow-
settle loop, not anchored to the occurrence). If cross-chapter hits drift after
the new chapter's typography reflow, escalate to **Option Y**: a new `ColumnSnap`
primitive that snaps to the hit's progression and tracks it through reflow.

## Testing

- **Characterization unit test** (`ReaderWebViewScriptsTest`, pure JVM): the
  existing test covers `snapToTargetColumnJs(null)` (default `true` → `scrollLeft
  = 0`). Add the `landAtStartWhenNoTarget = false` no-target case search now
  depends on: it rounds the current scroll to the grid
  (`Math.round(se.scrollLeft/iw)*iw`) rather than snapping to column 0. (This
  passes against existing production code — the `false` branch already exists; it
  locks the contract search now relies on, it is not red-green TDD, because
  Option X adds no new production logic, only routes search through an existing
  tested path.)
- **Device verification** (harness, paginated mode): search a frequent word
  ("watney"), step Next across pages and across a chapter boundary; confirm each
  hit lands on its own page (not the first occurrence, not the chapter top),
  visible, with the highlight on the word. This is where the rect-offset caveat
  above is checked.

## Out of scope

- Any change to readaloud's text source, the Storyteller bundle, or media-overlay
  handling.
- Persisted-highlight (annotations) rendering.
- Scroll/vertical-mode behavior beyond what the shared primitive already does
  (it centers in scroll mode; search inherits that unchanged).
