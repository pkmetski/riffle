# ADR 0009 — Screen Wake Lock as a Global-Only Preference with Its Own Store

**Status:** Accepted

## Context

The Screen Wake Lock prevents the device screen from sleeping while a book is open. The preference needed to be surfaced in the UI and persisted. Two design questions arose:

1. **Global vs per-book:** `FormattingPreferences` already supports per-book overrides (global defaults + book-level overrides with a reset path). Should wake lock follow the same pattern?
2. **Own store vs shared store:** Should `keepScreenOn` be a field on the `FormattingPreferences` domain model (and share its DataStore), or live in its own store?

## Decision

Wake lock is a **global-only preference** stored in its own `WakeLockPreferencesStore` (`keepScreenOn: Flow<Boolean>`, `setKeepScreenOn(Boolean)`), separate from `FormattingPreferences`.

## Consequences

- Wake lock is not a visual formatting concern — it is a device-behaviour preference. Merging it into `FormattingPreferences` would blur the domain boundary that CONTEXT.md already draws between the two concepts.
- Per-book wake lock override has no meaningful use case: a book does not inherently want the screen on, the user does. The per-book override machinery (global default + book-level delta + reset UI) would add complexity with no benefit.
- A dedicated store keeps `FormattingPreferences` reads and writes free of unrelated keys, and isolates the wake lock preference behind its own interface, making it independently testable.
- The toggle is placed in the "Reading defaults" section of the global Settings screen, not in the in-reader `FormattingPanel`.
