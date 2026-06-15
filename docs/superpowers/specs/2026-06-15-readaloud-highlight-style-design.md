# Readaloud Highlight Style Setting

## Problem

The readaloud synchronized-reading highlight color is hardcoded as `#FF7DD3FC` (sky blue) in
`EpubReaderScreen.kt:1296`. Users cannot change it even when that color clashes with their chosen
reader theme or personal preference.

## Solution

Introduce a global `ReadaloudPreferences` DataStore (following the same pattern as
`FormattingPreferences`) with a single `highlightColor` setting exposed as a preset color picker in
the Settings screen.

Scope: global-only, not per-book. Highlight color is a personal taste preference — unlike font
size or margins, it doesn't vary with individual books' publisher CSS.

---

## Data Layer

### `ReadaloudHighlightColor` enum — `core/domain`

```
BLUE   → #FF7DD3FC  (default, current hardcoded value)
YELLOW → #FFFDE68A
GREEN  → #FF86EFAC
PINK   → #FFFDA4AF
PURPLE → #FFC4B5FD
```

Each case carries an `argb: Int` property used directly in `Decoration.Style.Highlight(tint = …)`.

### `ReadaloudPreferences` data class — `core/domain`

```kotlin
data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE
)
```

### `ReadaloudPreferencesStore` interface — `core/domain`

```kotlin
interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}
```

### `ReadaloudPreferencesStoreImpl` — `core/data`

- Backed by `preferencesDataStore(name = "readaloud_preferences")` (new extension file
  `ReadaloudPreferencesDataStoreExt.kt`, mirroring `FormattingPreferencesDataStoreExt.kt`)
- Single DataStore key: `KEY_HIGHLIGHT_COLOR` (stringPreferencesKey), stores the enum name
- Reads with `map { … }`, writes via `edit { … }`, defaulting to `BLUE`

### DI — `DataModule.kt`

Bind `ReadaloudPreferencesStoreImpl` to `ReadaloudPreferencesStore` (singleton), following the
`FormattingPreferencesStore` binding.

---

## Reader Wiring

`EpubReaderViewModel` injects `ReadaloudPreferencesStore` and exposes:

```kotlin
val readaloudHighlightColor: StateFlow<ReadaloudHighlightColor>
```

`EpubReaderScreen.kt` collects this as state and passes it to the highlight `LaunchedEffect` at
line 1296, replacing the hardcoded `Color.parseColor("#FF7DD3FC")` with
`readaloudHighlightColor.argb`.

---

## Settings UI

`SettingsScreen` gains a new **"Readaloud"** section below the existing "Reading" section:

- Section header: "Readaloud"
- List item label: "Sentence highlight"
- Trailing content: a row of five color-chip circles (one per preset), the active one shown with a
  contrasting border ring
- Tapping a chip calls `settingsViewModel.updateReadaloudHighlightColor(color)`

`SettingsViewModel` injects `ReadaloudPreferencesStore` and exposes:
- `readaloudPreferences: StateFlow<ReadaloudPreferences>`
- `fun updateReadaloudHighlightColor(color: ReadaloudHighlightColor)`

No separate panel or navigation needed — a single inline chip row is right-sized for one setting.

---

## Testing

- Unit test `ReadaloudPreferencesStoreImpl`: round-trip write/read for each color, default value
  when key absent.
- Unit test `SettingsViewModel`: `updateReadaloudHighlightColor` persists to store.
- No new harness (UI) tests required — the chip row is simple enough and there are no existing
  readaloud harness tests to extend.
