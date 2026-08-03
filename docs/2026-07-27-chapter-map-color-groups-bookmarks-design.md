# Chapter Map — Color-Coded Parent Groups + Bookmark Markers

**Date:** 2026-07-27
**Branch:** pkmetski/chapter-map-colored-sections
**Status:** Approved and implemented

---

## Summary

Extend the chapter-map rail (`ChapterNavigationRail`) with two new capabilities:

1. **Parent-chapter color groups** — when a book's TOC has Chapter → Section hierarchy, each section becomes a rail segment and inherits a distinct muted color from its parent chapter. Books with a flat TOC keep the current single-color behavior unchanged.
2. **Bookmark markers** — thin vertical tick marks at each bookmark's rail position, drawn on top of the rail.

---

## Mode Detection: Group vs. Flat

A book enters **group mode** when the source TOC has hierarchy — at least one top-level
`TocEntry` has children — or when preprocessing creates a container that
`buildRailSegments` expands. Ordinary Chapter → same-file Section TOCs expose every direct
section as a rail segment, preserving fragment hrefs that the flat rail historically
deduplicated. If the entire TOC is flat — every top-level entry is a leaf — the rail stays in
**flat mode** (current behavior, no changes).

Once group mode is active, **all** top-level entries receive a group index, including standalone ones like a "Preface" that was not itself expanded. This keeps the coloring consistent across the whole rail.

---

## Rail Layout

### Flat mode (unchanged)
```
┌──────────────────────── 4dp ────────────────────────┐
│  foreground@30% track  │  primary@85% fill  │ cursor │
└─────────────────────────────────────────────────────┘
```

### Group mode (new)
```
┌──────────────────────── 4dp ────────────────────────┐
│ [chapter 1 sections] │ [chapter 2 sections] │ ... │
└─────────────────────────────────────────────────────┘
  ▲ bookmark ticks: 1.5dp wide, full 4dp height
```

Total rail height in group mode remains **4dp**, identical to the existing chapter map.

- **Section segments**: each section is filled full-height with its parent chapter's group color. The read portion is 100% opaque and the unread portion retains the same hue at 35% opacity, producing a strong whole-book progress boundary without adding a second strip. Existing inter-segment gaps apply as before (2.5dp punched between segments).
- **Cursor line**: full 4dp height with a 4dp page-background halo and a 2dp page-foreground core, so the exact position remains visible over every group color and reader theme.
- **Bookmark ticks**: drawn after section colors, before cursor. `primary@90%` alpha, 1.5dp wide, spanning full rail height.

The rail semantics also announce the rounded whole-book progress percentage alongside the active section title.

Color grouping is controlled by **Display → Chapter map → Colored chapter map**. It defaults on.
Turning it off restores the neutral chapter-map track and primary progress fill while preserving
section boundaries, bookmark ticks, and the high-contrast cursor. The color toggle is disabled and
visually muted whenever Chapter map itself is off.

---

## Color Palette

Eight vivid, high-contrast, color-blind-friendly colors cycle when there are more than 8 groups. They render at full opacity so chapter groups remain legible for low-vision readers instead of blending into the page:

| Index | Hex       | Name          |
|-------|-----------|---------------|
| 0     | `#E66100` | Vivid orange  |
| 1     | `#0072B2` | Strong blue   |
| 2     | `#009E73` | Bluish green  |
| 3     | `#CC3311` | Vermilion     |
| 4     | `#AA4499` | Purple        |
| 5     | `#00A6D6` | Cyan          |
| 6     | `#EE3377` | Magenta       |
| 7     | `#B8860B` | Dark amber    |

Group index → palette index: `groupIndex % GROUP_COLORS.size`.

---

## Data Model Changes

### `RailSegment` (new field)
```kotlin
data class RailSegment(
    val title: String,
    val href: String,
    val weight: Float = 1f,
    val groupIndex: Int? = null,   // null = flat mode; 0..N = parent group
)
```

`null` means flat mode (no source hierarchy and no top-level expansion occurred). `0..N` is the index of the top-level TocEntry this segment descended from.

### Group mode detection
After `buildRailSegments` runs, group mode is active when
`segments.any { it.groupIndex != null }`.

---

## Implementation Scope

### 1. `RailSegment.kt`
Add `groupIndex: Int? = null`.

### 2. `RailSegmentGenerator.kt` — `buildRailSegments`
Thread group index through the recursion:
- Iterate top-level `TocEntry` items with their index.
- When all direct children share the parent's spine resource, expose those same-file fragment
  sections as separate rail segments and preserve each full fragment href.
- If `expandIfRedundant` expands the entry, pass `groupIndex = topLevelIndex` to all resulting segments.
- If `expandIfRedundant` does NOT expand (single parent result), pass
  `groupIndex = topLevelIndex` when the source TOC has any top-level hierarchy or a sibling
  was expanded; otherwise leave `null`.
- Implementation: change the return type of `expandIfRedundant` to carry the group index, or do a two-pass approach: first run current logic, then annotate group indices using source hierarchy and expansion results.

Two-pass is simpler and keeps `expandIfRedundant` signature unchanged:
  1. Run existing `buildRailSegments` to get the flat list (no group info yet).
  2. For each top-level TocEntry, expose direct same-file sections or run the existing
     redundancy-expansion rules.
  3. If the source TOC has top-level hierarchy, or any top-level entry was expanded, mark all
     top-level entries' segments with their group index.
  4. Only when the source TOC is flat and no entry was expanded, leave all
     `groupIndex = null`.

### 3. `ChapterNavigationRail.kt`
New parameters:
```kotlin
fun ChapterNavigationRail(
    segments: List<RailSegment>,
    activeIndex: Int,
    cursorPosition: Float,
    readerTheme: ReaderTheme,
    onSegmentClick: (RailSegment) -> Unit,
    bookmarkPositions: List<Float> = emptyList(),   // new: pre-computed 0..1 rail fractions
    modifier: Modifier = Modifier,
    railHeight: Dp = 4.dp,                          // unchanged in both modes
)
```

Drawing logic:
- Detect group mode: `val groupMode = segments.any { it.groupIndex != null }`.
- If `groupMode`: draw each section full-height with its parent group's color. Otherwise draw
  the existing flat track and progress fill.
- Draw bookmark ticks last before cursor.

### 4. `EpubReaderViewModel.kt`
Expose bookmark rail fractions as a `StateFlow`:
```kotlin
val bookmarkRailPositions: StateFlow<List<Float>>
```
Derived from existing `bookmarksController.bookmarkPositions`, `railSegments`, and `spineHrefs`:
```kotlin
bookmarkRailPositions = combine(
    bookmarksController.bookmarkPositions, railSegments, spineHrefs
) { bookmarks, segments, spineHrefs ->
    bookmarks.mapNotNull { bm ->
        val idx = findActiveSegmentIndex(
            segments, bm.chapterHref, spineHrefs, bm.progression.toFloat()
        )
        if (idx < 0) null
        else weightedRailCursorPosition(
            idx,
            segments,
            progressionWithinRailSegment(
                segments, bm.chapterHref, idx, bm.progression.toFloat()
            ),
        )
    }
}.stateIn(...)
```

### 5. `EpubReaderScreen.kt` — `EpubChapterRailOverlay`
Pass `bookmarkRailPositions` collected from the ViewModel into `ChapterNavigationRail`.

### 6. PDF path
No changes. `PdfTocEntry` is flat (no children field), so `buildPdfRailSegments` always produces flat-mode segments. No bookmark markers on the PDF rail (PDF bookmarks are a separate concern not in scope).

---

## Edge Cases

| Scenario | Behavior |
|---|---|
| Flat TOC (all top-level = leaves) | Flat mode: current 4dp single-strip, no colors |
| Chapter → same-file Sections | Every direct section is a distinct segment; all siblings share the chapter color |
| Single parent chapter | Its sections all get `groupIndex=0` and share one color at the normal 4dp height |
| >8 groups | Colors cycle: `groupIndex % 8`. |
| Bookmark in a spine resource with no TOC entry | `findActiveSegmentIndex` already handles this (falls back to last segment before the current spine position). Bookmark tick lands there. |
| No bookmarks | `bookmarkPositions` is empty list; no ticks drawn. |

---

## Files Touched

| File | Change |
|---|---|
| `app/.../reader/RailSegment.kt` | Add `groupIndex: Int?` |
| `app/.../reader/RailSegmentGenerator.kt` | Thread + assign group indices in `buildRailSegments` |
| `app/.../reader/ChapterNavigationRail.kt` | Full-height section colors + bookmark ticks |
| `app/.../reader/EpubReaderViewModel.kt` | Progression-aware active sections + `bookmarkRailPositions` |
| `app/.../reader/EpubReaderScreen.kt` | Pass bookmark positions to rail overlay |
| Tests: `RailSegmentGeneratorTest.kt` | Cases for group index assignment, flat fallback |
| Tests: `ChapterNavigationRailTest.kt` (if exists) or new | Verify group/flat draw path (Compose screenshot or unit) |

---

## Out of Scope

- PDF bookmark markers on rail
- User-configurable color palette
- Animated transitions between group colors
- Tooltip on bookmark tick showing bookmark title
