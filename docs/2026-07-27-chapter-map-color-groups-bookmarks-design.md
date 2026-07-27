# Chapter Map — Color-Coded Parent Groups + Bookmark Markers

**Date:** 2026-07-27
**Branch:** pkmetski/chapter-map-colored-sections
**Status:** Approved, ready for implementation

---

## Summary

Extend the chapter-map rail (`ChapterNavigationRail`) with two new capabilities:

1. **Parent-chapter color groups** — when a book's TOC has hierarchy (e.g. Part I → chapters, Part II → chapters), each top-level group's rail segments are painted a distinct muted color. Books with a flat TOC keep the current single-color behavior unchanged.
2. **Bookmark markers** — thin vertical tick marks at each bookmark's rail position, drawn on top of the fill band.

---

## Mode Detection: Group vs. Flat

A book enters **group mode** when at least one top-level `TocEntry` was expanded into its children by `buildRailSegments` (i.e. `shouldReplaceWithChildren` returned `true` for it). If the entire TOC is flat — every top-level entry is a leaf — the rail stays in **flat mode** (current behavior, no changes).

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
┌─────── 3dp ───────┐  top strip — group color band
│  [Part I color]   │  [Part II color]  │  ...
├─────── 3dp ───────┤  bottom strip — progress fill
│  primary@85% fill │  foreground@30% track
└───────────────────┘
  ▲ bookmark ticks: 1.5dp wide, full 6dp height
```

Total rail height in group mode: **6dp** (up from 4dp).

- **Top strip** (3dp): filled with the group's color at 70% alpha across the full segment width. Existing inter-segment gaps apply as before (2.5dp punched between segments).
- **Bottom strip** (3dp): progress fill (`primary@85%`) up to the cursor x; unread portion (`foreground@30%`). This is the current strip's coloring, confined to the lower 3dp.
- **Cursor line**: full 6dp height, `primary@100%`, 2dp wide — unchanged.
- **Bookmark ticks**: drawn after fill, before cursor. `primary@90%` alpha, 1.5dp wide, spanning full rail height.

---

## Color Palette

Eight muted, mid-lightness colors cycling when there are more than 8 groups. All work visually at 70% alpha on both white (Light/Sepia) and black (Dark) reader backgrounds:

| Index | Hex       | Name         |
|-------|-----------|--------------|
| 0     | `#B4634A` | Terracotta   |
| 1     | `#5B7FA6` | Slate blue   |
| 2     | `#5A8A6A` | Sage green   |
| 3     | `#9B6B9B` | Dusty purple |
| 4     | `#8B7355` | Warm brown   |
| 5     | `#4A8B8B` | Teal         |
| 6     | `#A67C52` | Amber        |
| 7     | `#7B6B8B` | Mauve        |

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

`null` means flat mode (no top-level expansion occurred). `0..N` is the index of the top-level TocEntry this segment descended from.

### Group mode detection
After `buildRailSegments` runs, group mode is active when `segments.any { it.groupIndex != null }`.

---

## Implementation Scope

### 1. `RailSegment.kt`
Add `groupIndex: Int? = null`.

### 2. `RailSegmentGenerator.kt` — `buildRailSegments`
Thread group index through the recursion:
- Iterate top-level `TocEntry` items with their index.
- If `expandIfRedundant` expands the entry (returns multiple children), pass `groupIndex = topLevelIndex` to all resulting segments.
- If `expandIfRedundant` does NOT expand (single leaf result), pass `groupIndex = topLevelIndex` only when at least one sibling top-level entry was expanded; otherwise leave `null`.
- Implementation: change the return type of `expandIfRedundant` to carry the group index, or do a two-pass approach: first run current logic, then annotate group indices based on which top-level entries had >1 resulting segment OR had any of their children expanded.

Two-pass is simpler and keeps `expandIfRedundant` signature unchanged:
  1. Run existing `buildRailSegments` to get the flat list (no group info yet).
  2. For each top-level TocEntry, find which resulting segments' hrefs are descendants of it.
  3. If any top-level entry contributed >1 segment (was expanded), mark all top-level entries' segments with their group index.
  4. If every top-level entry contributed exactly 1 segment, leave all `groupIndex = null`.

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
    railHeight: Dp = 4.dp,                          // unchanged default; group mode overrides internally
)
```

Drawing logic:
- Detect group mode: `val groupMode = segments.any { it.groupIndex != null }`.
- If `groupMode`: `effectiveHeight = 6.dp`; draw top strip (group colors) + bottom strip (progress). Otherwise draw single strip as today.
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
        val idx = findActiveSegmentIndex(segments, bm.chapterHref, spineHrefs)
        if (idx < 0) null
        else weightedRailCursorPosition(idx, segments, bm.progression.toFloat())
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
| 3-level book (Part → Chapter → Section) | Group = top-level (Part). Sections from the same Part share its color, regardless of how deep expansion goes. |
| Single top-level entry (entire book is one Part) | Expanded children all get groupIndex=0 (one color). Visually a single-color rail, identical to current behavior effectively, but in group mode shape (6dp). |
| >8 groups | Colors cycle: `groupIndex % 8`. |
| Bookmark in a spine resource with no TOC entry | `findActiveSegmentIndex` already handles this (falls back to last segment before the current spine position). Bookmark tick lands there. |
| No bookmarks | `bookmarkPositions` is empty list; no ticks drawn. |

---

## Files Touched

| File | Change |
|---|---|
| `app/.../reader/RailSegment.kt` | Add `groupIndex: Int?` |
| `app/.../reader/RailSegmentGenerator.kt` | Thread + assign group indices in `buildRailSegments` |
| `app/.../reader/ChapterNavigationRail.kt` | Group-mode two-strip layout + bookmark ticks |
| `app/.../reader/EpubReaderViewModel.kt` | Expose `bookmarkRailPositions: StateFlow<List<Float>>` |
| `app/.../reader/EpubReaderScreen.kt` | Pass bookmark positions to rail overlay |
| Tests: `RailSegmentGeneratorTest.kt` | Cases for group index assignment, flat fallback |
| Tests: `ChapterNavigationRailTest.kt` (if exists) or new | Verify group/flat draw path (Compose screenshot or unit) |

---

## Out of Scope

- PDF bookmark markers on rail
- User-configurable color palette
- Animated transitions between group colors
- Tooltip on bookmark tick showing bookmark title
