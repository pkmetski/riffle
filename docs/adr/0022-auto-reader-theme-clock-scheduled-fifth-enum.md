# ADR 0022 — Auto reader theme: clock-scheduled, live-switching, fifth enum value

**Status:** Accepted

## Context

[Formatting Preferences] already exposes a `theme` field on `ReaderTheme = { Light, Dark, DarkDim, Sepia }`, set globally and overridable per-book. Users want the reader to automatically switch between a day-appropriate and a night-appropriate look without manually flipping a chip every evening.

The feature has to compose with three things already true of the theme system:

- The same `ReaderTheme` enum drives both the EPUB navigator (Readium preferences) and the PDF reader (colour filter), the chapter rail backdrop, the formatting-panel chip swatch, and the per-book override store (`book_formatting_preferences`, intentionally not scoped per-server).
- The formatting panel is reused as the in-reader half-height overlay and as the full-screen Settings panel.
- Per-book theme is a single enum pick — a book is pinned to one rendered look, or it isn't.

Several axes were live: (a) is "auto" a peer enum value or an orthogonal scheduling field; (b) is the schedule driven by clock-times, sunrise/sunset, or Android's system dark mode; (c) does the palette switch live mid-session or only on reader open/resume. Each of these has lower-friction alternatives that we rejected for specific reasons.

## Decision

Three load-bearing choices:

### 1. Auto is a fifth value of `ReaderTheme`, not an orthogonal `themeSchedule` field

`ReaderTheme` grows to `{ Light, Dark, DarkDim, Sepia, Auto }`. Auto is selectable anywhere any other theme is selectable: as the global default, as a per-book override, in either format's reader. The four schedule parameters (day-start, night-start, day-theme, night-theme) live on global `FormattingPreferences` and are only meaningful when some surface is rendering `Auto`. The two theme picks inside the schedule are restricted to the four concrete values — Auto cannot nest inside Auto.

At render-time, every consumer of `ReaderTheme` first calls a resolver `resolveTheme(prefs, clock): ReaderTheme` that returns one of the four concretes; downstream code (Readium mapper, `ReaderThemePalette`, chapter-rail backdrop, swatch rendering) is unchanged. The formatting panel's chip selection still tracks the user's actual pick — so the Auto chip stays highlighted even when the resolved palette is Dark.

### 2. Schedule is clock-based, not system dark mode and not sunrise/sunset

Two user-configured local-clock times define the boundary. The schedule is interpreted as two arcs on a 24-hour circle, with the night arc allowed to cross midnight. Degenerate equal-time input is treated as always-day. No location, no permissions, no system-mode tracking.

### 3. Boundary crossings repaint live during a reading session

A timer fires at the next scheduled boundary; the resolved theme changes; the reader VM republishes preferences and Readium reapplies them. The user sees the page repaint without closing the book. The timer also resets when the user edits the schedule or when the app returns from background.

## Consequences

**Per-book override semantics stay coherent.** One enum pick fully describes a book's theme intent: "this book renders as Sepia" or "this book follows the schedule". The `book_formatting_preferences` store needs no new columns. Future per-book overrides remain a single-field decision.

**Schedule editing lives only in Settings.** The in-reader formatting panel shows the Auto chip as a fifth selectable theme but does not surface day-start, night-start, day-theme, or night-theme. Per the global-only scope, those four fields are edited on the full-screen Settings variant of the panel. A user opting a single book into Auto inherits the global schedule silently.

**Both readers behave identically.** Because resolution happens at the `ReaderTheme` boundary, EPUB and PDF both see only concrete values and need no auto-awareness. A PDF book set to Auto goes through the same colour-filter mapping as any concrete pick.

**The `Auto` enum value is a mild type-smell.** It mixes a "scheduled meta" with concrete palettes in one enum, and code that lists `ReaderTheme.entries` for swatches has to special-case Auto's chip (split day/night background instead of one filled circle, no `ReaderThemePalette` entry). This is local and named; the alternative — a separate `themeSchedule: Manual | Auto(day, night)` field — pushed the cost into the per-book override store and the UI's "is this book Auto?" reasoning, where it was worse.

**A timer ticks inside the reader.** Live switching requires a coroutine waiting until the next boundary in local time, cancellable on schedule edits, on app background, and on reader exit. Tests need a virtual clock injectable into the resolver. The cost is bounded and one-place; the alternative (resample only on resume) was rejected because a long evening session crossing the boundary in one sitting is exactly the case the feature exists to serve.

**Schema migration is local to global `FormattingPreferences`.** The DataStore adds four fields with defaults (07:00, 20:00, Light, Dark). No Room migration is required — the per-book overrides store is unchanged. A book whose serialised override is the previous `Light/Dark/DarkDim/Sepia` set still decodes correctly; only the global store grows.

## Alternatives considered

- **Auto as an orthogonal `themeSchedule: Manual | Auto(day, night)` field.** Cleaner-typed (the four concrete themes stay pure), but per-book override semantics get muddier — a book is either "pinned to a concrete theme" or "inherits global, which may or may not be Auto", and the UI has to surface that distinction. Rejected because the override model's single-pick simplicity is more valuable than the enum's purity.
- **Follow Android's system dark mode (`isSystemInDarkTheme()`).** Zero config, no permission, no timer, composes with the OS's own sunset/sunrise scheduling. Rejected because the user wants Riffle's reading schedule controllable independently of the system — a user with system always-dark for the OS may want their book on Light during the day.
- **Sunrise / sunset.** Most "true" to user intent but introduces a location permission for a feature whose mental model is trivially expressible as clock times. Rejected for the permission cost.
- **Resample only on reader open and on resume.** Avoids the in-reader timer and the mid-session Readium re-apply. Rejected because a marathon evening reader crossing the boundary in one session would stay in the day theme until backgrounding — defeating the feature for the case it exists to serve.

[Formatting Preferences]: ../../CONTEXT.md#formatting-preferences
