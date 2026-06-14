# Continue Series — Home Tab Section

## Overview

A new horizontal section in the Library home tab that surfaces the next unread book in each series the user has been reading.

## Trigger

A series appears in "Continue Series" when:
- At least one book in the series has `readingProgress = 1.0` (finished)
- At least one book in the series has `readingProgress < 1.0` (not yet finished — the next book)

## Card Design

Each card shows the **next book's cover** with:
- A semi-transparent dark pill badge overlaid at the top of the cover showing the **series name**
- The **book title** below the cover (same as existing home tab cards)
- No progress bar (the next book hasn't been started yet, or is partially read)

`LibraryItem.seriesName` (already populated) drives the badge — no model changes needed.

## Placement

Home tab order:
1. In Progress
2. **Continue Series** ← new, between In Progress and Recently Added
3. Recently Added
4. Completed

The section is hidden when empty (same as other sections).

## Navigation

Tapping a card calls the existing `onBookClick` handler — opens the book directly.

## Data Layer

### SeriesDao — new method

```sql
-- observeContinueSeriesItems(libraryId)
-- For each series with ≥1 finished book, return the next unread book
-- (min sequenceOrder where readingProgress < 1.0)
-- Ordered by most recently finished sibling (MAX lastOpenedAt DESC)
SELECT li.*
FROM library_items li
JOIN series_items si ON si.item_id = li.id AND si.server_id = li.server_id
WHERE li.library_id = :libraryId
  AND li.reading_progress < 1.0
  AND si.series_id IN (
      SELECT DISTINCT si2.series_id
      FROM series_items si2
      JOIN library_items li2 ON li2.id = si2.item_id AND li2.server_id = si2.server_id
      WHERE li2.library_id = :libraryId AND li2.reading_progress = 1.0
  )
  AND si.sequence_order = (
      SELECT MIN(si3.sequence_order)
      FROM series_items si3
      JOIN library_items li3 ON li3.id = si3.item_id AND li3.server_id = si3.server_id
      WHERE si3.series_id = si.series_id
        AND li3.library_id = :libraryId
        AND li3.reading_progress < 1.0
  )
ORDER BY (
    SELECT MAX(li4.last_opened_at)
    FROM series_items si4
    JOIN library_items li4 ON li4.id = si4.item_id AND li4.server_id = si4.server_id
    WHERE si4.series_id = si.series_id AND li4.reading_progress = 1.0
) DESC
```

Returns `List<LibraryItemEntity>` mapped to `List<LibraryItem>`.

### LibraryRepository interface

```kotlin
fun observeContinueSeriesItems(libraryId: String): Flow<List<LibraryItem>>
```

### LibraryRepositoryImpl

Delegates to `SeriesDao.observeContinueSeriesItems(libraryId)`, maps entities to domain.

## ViewModel

`LibraryItemsViewModel` adds:

```kotlin
val continueSeriesItems: StateFlow<List<LibraryItem>>
```

Constructed the same way as `filteredInProgress` — combines the raw flow with the offline filter.

## UI

### Cover tile composable

Accepts optional series name; when non-null, renders a dark semi-transparent pill badge (`#000000B3` background, white text, 10sp, rounded) positioned at the top-centre of the cover image.

### HomeTabContent

A new `BookSectionGrid` block for "Continue Series" inserted between "In Progress" and "Recently Added", only rendered when `continueSeriesItems.isNotEmpty()`.
