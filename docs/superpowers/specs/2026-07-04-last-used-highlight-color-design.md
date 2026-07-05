# Remember last-used highlight color (annotation default)

## Problem

New user-created highlights are always born yellow — `AnnotationStore.createHighlight` defaults `color = DEFAULT_COLOR = "yellow"` and `EpubReaderViewModel.createHighlight()` never overrides it. A user who prefers blue must recolor every single highlight after creation. The color-picker popup does show the current color as "selected", but "current" is always yellow at birth, so the affordance to keep a preferred color doesn't exist.

Scope: user-created highlights only. Readaloud highlight color is a separate, already-persisted preference (`ReadaloudPreferencesStore`) and stays independent.

## Behavior

- The first time a user picks a color on a highlight (either at creation or a later recolor), that color becomes the default for subsequent new highlights **in the same book**.
- Next time the user creates a highlight in that book, it's born in that color — and the picker's swatch row shows it already selected (falls out of the existing `selected = current.color` logic in `HighlightActionsPopup`).
- Preference is per-book (keyed by `serverId:itemId`), not global. A book the user has never picked a color on falls back to `HighlightColor.DEFAULT` — the first entry in the palette. There is no cross-book fallback: a pick in book A does not seed book B's default.

## Design

### 1. Preference store

`HighlightColorPreferencesStore` in `core/domain` (per-book API) and a hand-written factory in `core/data/PreferenceStoreFactories.kt` that keys DataStore entries by `"last_used_highlight_color:$serverId:$itemId"`. Absent/unknown values fall back to `HighlightColor.DEFAULT` (the first palette entry).

```kotlin
interface HighlightColorPreferencesStore {
    fun lastUsedColor(serverId: String, itemId: String): Flow<HighlightColor>
    suspend fun setLastUsedColor(serverId: String, itemId: String, value: HighlightColor)
}
```

Bound in the same Hilt module that binds `ReadaloudPreferencesStore` (`DataModule` — same `DataStore<Preferences>` provider).

### 2. Wire into highlight creation

`AnnotationSession` observes the per-book flow on `bind(serverId, itemId, …)` and exposes the current colour as a `StateFlow<HighlightColor>` (`lastUsedHighlightColor`). The VM's `createHighlight()` reads `annotationSession.lastUsedHighlightColor.value` synchronously and passes `color = lastUsed.token` to `annotationStore.createHighlight(...)`. A book that has never had a colour picked emits `HighlightColor.DEFAULT` — the first palette entry.

### 3. Wire into recolor

`AnnotationSession.recolorHighlight(id, color)` calls `annotationStore.recolor(...)` AND `highlightColorPreferencesStore.setLastUsedColor(boundServerId, boundItemId, color)` — writing the pick under the currently-bound book only. Picks on book A never mutate book B's default.

### 4. UI

No changes. `HighlightActionsPopup` already shows the current highlight's color as the selected swatch. Because new highlights are now born in the last-used color, the popup opens with the correct swatch pre-selected without touching the composable.

### 5. Tests (JVM only — logic doesn't touch Readium/WebView)

- **`HighlightColorPreferencesStoreTest`** — first read for an untouched book is `HighlightColor.DEFAULT`; write-then-read round-trips per book; two books' picks are independent; same `itemId` on different servers is independent; unknown legacy string falls back to `DEFAULT`.
- **`AnnotationSessionTest`** — regression assertions:
  1. `recolorHighlight` writes the picked colour under the currently-bound `(serverId, itemId)` and leaves other books' entries untouched.
  2. `lastUsedHighlightColor` StateFlow reflects the bound book's stored value after `bind`.
  3. Rebinding to a different book swaps `lastUsedHighlightColor` to that book's value (no leak from the previous book).

These use a fake `AnnotationStore` + an in-memory `HighlightColorPreferencesStore` — no device required.

## Non-goals

- No Settings UI to view/set the default separately. The last-picked value is the default; a separate control would double the surface area without new capability.
- No cross-book fallback (i.e. new books never inherit a last-used pick from another book). Every book without a stored pick shows the palette default (`HighlightColor.DEFAULT`).
- No touching the readaloud highlight color — that remains its own store.
- No migration for existing highlights — recoloring old highlights is already possible via the popup.
