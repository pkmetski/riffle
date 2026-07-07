# Annotations View — Separate Reading Preferences

## Problem

The annotations reading view (`EpubReaderScreen` in `ReaderSource.Highlights` mode) currently shares its reading preferences — font size, theme, margins, line spacing, everything — with the full-book view of the same book. Changing font size while skimming highlights mutates the book's reading experience, and vice versa. These are distinct reading contexts; the user wants them tuned independently.

## Goals

- Reading preferences applied in the annotations view are stored and read independently from the full-book view.
- Applies at both layers today: **global defaults** (DataStore) and **per-book overrides** (Room).
- No cross-talk: writing a pref in one mode never changes what the other mode reads back.

## Non-goals

- No import/copy button ("copy my book prefs to annotations view"). Add later if requested.
- No annotations-mode-only extra settings — same schema, separate bucket.
- No visual indicator ("these are annotations-view prefs") in the formatting sheet for v1. The user already knows which view they're in.
- No change to per-book override UX beyond scoping — "Reset to global" behaves the same, just against the annotations-mode global defaults when in that mode.

## Design

Add a `source: ReaderSource` dimension to both preference layers. Full-book keeps its existing storage untouched; annotations mode gets a parallel, empty-by-default chain.

Initial state on first open in annotations mode: **app defaults** — same starting point as a fresh install of the full-book view. No snapshot, no implicit fallback to the full-book prefs.

### 1. Global prefs — two DataStore files

Add a second DataStore file alongside the existing one:

- `formatting_preferences.preferences_pb` (existing, unchanged) → full-book prefs.
- `formatting_preferences_highlights.preferences_pb` (new) → annotations-mode prefs.

Rationale over key-namespacing inside one file:

- Zero migration on the existing file.
- No risk of a bug reading the wrong bucket via a missing prefix.
- Cleaner qualifier-based DI.

Wiring: `FormattingPreferencesStore` gains a `source` parameter (or split into two injected instances behind a `ReaderSource`-keyed factory). `DataModule` exposes both DataStore instances via distinct `@FormattingPreferencesDataStore(source)` qualifiers.

### 2. Per-book overrides — add `source` PK column

`BookFormattingPreferencesEntity` today is keyed by `(serverId, itemId)`. Add `source: String` as a third PK column.

- New PK: `(serverId, itemId, source)`
- Column values: `"FullBook"` and `"Highlights"` (matches `ReaderSource` enum names).
- Room migration bumps the DB schema version and backfills all existing rows with `source = "FullBook"`.

Store API updates:

- `BookFormattingPreferencesStore.load(itemId, source)`
- `BookFormattingPreferencesStore.save(itemId, source, prefs)`
- `BookFormattingPreferencesStore.clear(itemId, source)`

Follow the AGENTS.md migration checklist: bump `@Database.version`, write `MIGRATION_N_(N+1)`, register in `DataModule`, generate schema JSON, add `MigrationTest.migrationNToN1()` + append to `migrateFullChain`.

### 3. Effective prefs plumbing

`FormattingSession.bindToBook(itemId)` becomes `bindToBook(itemId, source)`. It:

1. Picks the global store instance for that source.
2. Loads the per-book override for `(serverId, itemId, source)`.
3. Merges (per-book overrides win) exactly as today.

`EpubReaderViewModel` already knows `source` from the nav route; thread it into the session bind call.

### 4. UI

No new screens. The existing formatting sheet inside the reader binds to `FormattingSession.effectiveFormattingPreferences`; it automatically writes to the annotations chain when `source == Highlights`. "Reset to global" resets to the annotations-mode global defaults when in that mode.

## Testing

Regression tests must exist for each of the following (per AGENTS.md — the assertion must flip red if the fix is reverted):

- **`FormattingPreferencesStoreImpl` isolation.** Writing a value in the `Highlights` store leaves the `FullBook` store's value unchanged, and vice versa.
- **`BookFormattingPreferencesStoreImpl` isolation.** `save(itemId, Highlights, prefs)` followed by `load(itemId, FullBook)` returns whatever `FullBook` had (or null), not the `Highlights` value.
- **`FormattingSession` correct chain.** Binding with `source = Highlights` yields effective prefs derived from the highlights global store + `(serverId, itemId, Highlights)` override — not the `FullBook` chain.
- **Room `MigrationTest.migrationNToN1()`.** Pre-existing rows land with `source = "FullBook"`; a subsequent insert of `(serverId, itemId, "Highlights")` doesn't collide with the pre-existing row.
- **End-to-end JVM regression.** Simulate: user changes font size in annotations view → assert full-book effective prefs for the same book unchanged.

Every test above pins a specific behavior; reverting the split would flip at least one red.

## Rollout

Single PR. Migration is additive (new column, new DataStore file) — no data loss, no user-visible change to existing full-book prefs. Users opening the annotations view after the update see app defaults there, which matches the design intent.
