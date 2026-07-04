# Remember last-used highlight color (annotation default)

## Problem

New user-created highlights are always born yellow — `AnnotationStore.createHighlight` defaults `color = DEFAULT_COLOR = "yellow"` and `EpubReaderViewModel.createHighlight()` never overrides it. A user who prefers blue must recolor every single highlight after creation. The color-picker popup does show the current color as "selected", but "current" is always yellow at birth, so the affordance to keep a preferred color doesn't exist.

Scope: user-created highlights only. Readaloud highlight color is a separate, already-persisted preference (`ReadaloudPreferencesStore`) and stays independent.

## Behavior

- The first time a user picks a color on a highlight (either at creation or a later recolor), that color becomes the app-wide default for subsequent new highlights.
- Next time the user creates a highlight, it's born in that color — and the picker's swatch row shows it already selected (falls out of the existing `selected = current.color` logic in `HighlightActionsPopup`).
- Preference is global (not per-server, not per-book). First-run default is `HighlightColor.YELLOW`, preserving today's behavior.

## Design

### 1. Preference store

New `HighlightColorPreferencesStore` interface in `core/domain` and factory in `core/data/PreferenceStoreFactories.kt`. Single-key `PrefCodecs.enum` over `HighlightColor.entries`, key `last_used_highlight_color`, default `HighlightColor.YELLOW`. Modeled directly on the existing `ReadaloudPreferencesStore` factory a few lines above in the same file — same shape, same fallback semantics for unknown legacy names.

```kotlin
interface HighlightColorPreferencesStore {
    val lastUsedColor: Flow<HighlightColor>
    suspend fun setLastUsedColor(value: HighlightColor)
}
```

Bound in the same Hilt module that binds `ReadaloudPreferencesStore` (`DataModule` — same `DataStore<Preferences>` provider).

### 2. Wire into highlight creation

`EpubReaderViewModel` gains a constructor-injected `HighlightColorPreferencesStore`. Inside `createHighlight()` (currently at line 947), read the current value once (`.first()`) and pass it into `annotationStore.createHighlight(..., color = lastUsed.token, ...)`. No default-argument change on the interface — the caller is now explicit.

### 3. Wire into recolor

The popup's `onPick = { color -> onRecolorHighlight(editTarget.id, color) }` lambda in `EpubReaderScreen.kt:2475` calls into a VM method that today just calls `annotationStore.recolor(...)`. Extend that VM method to also `store.setLastUsedColor(color)` (fire-and-forget in `viewModelScope`). "Last-used" tracks both the initial pick at creation and any later change.

### 4. UI

No changes. `HighlightActionsPopup` already shows the current highlight's color as the selected swatch. Because new highlights are now born in the last-used color, the popup opens with the correct swatch pre-selected without touching the composable.

### 5. Tests (JVM only — logic doesn't touch Readium/WebView)

- **`HighlightColorPreferencesStoreTest`** (mirrors `ReadaloudPreferencesStoreTest`) — first read is `YELLOW`; write-then-read round-trips; unknown legacy string falls back to `YELLOW`.
- **`EpubReaderViewModelTest`** — two regression assertions that flip red if the wiring is reverted:
  1. Given the pref store has `BLUE`, `createHighlight(...)` results in a call to `annotationStore.createHighlight(...)` with `color = "blue"` (not `"yellow"`).
  2. Given `recolorHighlight(id, GREEN)` is called, the pref store's `lastUsedColor` becomes `GREEN`.

These use a fake `AnnotationStore` + an in-memory `HighlightColorPreferencesStore` — no device required.

## Non-goals

- No Settings UI to view/set the default separately. The last-picked value is the default; a separate control would double the surface area without new capability.
- No per-server or per-book scoping.
- No touching the readaloud highlight color — that remains its own store.
- No migration for existing highlights — recoloring old highlights is already possible via the popup.
